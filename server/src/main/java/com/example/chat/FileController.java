package com.example.chat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/files")
public class FileController {
    static final long MAX_BYTES = 10L * 1024 * 1024 + 16;
    private static final long SENDER_QUOTA = 100L * 1024 * 1024;
    private static final long GLOBAL_QUOTA = 1024L * 1024 * 1024;
    private static final long SENDER_FILE_QUOTA = 100;
    private static final long GLOBAL_FILE_QUOTA = 10_000;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private final JdbcTemplate db;
    private final Path root;
    private final Semaphore transfers = new Semaphore(2, true);
    private final Set<Path> activeTemps = ConcurrentHashMap.newKeySet();
    private final Object changes = new Object(); // ponytail: one server lock; use database quota reservations if multi-node uploads are needed.

    record FileRow(String id, String sender, String senderAccountId, String recipient,
                   String recipientAccountId, long size, String sha256, Instant expiresAt) {}
    record UploadResult(String id, String expiresAt) {}

    public FileController(JdbcTemplate db, @Value("${chat.attachments-directory}") String directory) {
        this.db = db;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @PutMapping("/{id}")
    public UploadResult upload(@PathVariable String id, @RequestParam String to, @RequestParam String toAccountId,
                               @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        requireId(id);
        if (!"application/octet-stream".equalsIgnoreCase(request.getContentType()))
            throw bad("文件类型无效");
        if (!Username.valid(to) || to.equals(jwt.getSubject()) || !validUuid(toAccountId)) throw bad("接收方无效");
        long announced = request.getContentLengthLong();
        if (announced > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大");
        if (announced >= 0 && announced < 16) throw bad("文件内容不完整");
        if (!transfers.tryAcquire()) throw busy();
        Path temp = root.resolve(UUID.randomUUID().toString());
        try {
            if (!allowed(jwt.getSubject(), jwt.getClaimAsString("account_id"), to, toAccountId))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "联系人或设备身份不匹配");
            ensureRoot();
            activeTemps.add(temp);
            MessageDigest digest = sha256();
            long size = 0;
            try (var input = request.getInputStream();
                 var output = FileChannel.open(temp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    size += n;
                    if (size > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件过大");
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                    while (bytes.hasRemaining()) output.write(bytes);
                    digest.update(buffer, 0, n);
                }
                output.force(true);
            }
            if (size < 16 || announced >= 0 && size != announced) throw bad("文件内容不完整");
            String hash = HexFormat.of().formatHex(digest.digest());
            synchronized (changes) {
                if (!allowed(jwt.getSubject(), jwt.getClaimAsString("account_id"), to, toAccountId))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "联系人或设备身份不匹配");
                FileRow old = find(id);
                if (old != null) {
                    if (!old.expiresAt().isAfter(Instant.now()))
                        throw new ResponseStatusException(HttpStatus.GONE, "文件已过期，请使用新 ID");
                    if (!old.sender().equals(jwt.getSubject()) || !old.senderAccountId().equals(jwt.getClaimAsString("account_id"))
                            || !old.recipient().equals(to) || !old.recipientAccountId().equals(toAccountId)
                            || old.size() != size || !old.sha256().equals(hash))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "文件 ID 已被使用");
                    Path target = root.resolve(id);
                    boolean intact = false;
                    if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        try (FileChannel stored = FileChannel.open(target,
                                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                            intact = stored.size() == size && hash(stored).equals(hash);
                        }
                    }
                    if (!intact) Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    return new UploadResult(id, old.expiresAt().toString());
                }
                if (used("WHERE sender=?", jwt.getSubject()) + size > SENDER_QUOTA
                        || used("", new Object[0]) + size > GLOBAL_QUOTA
                        || count("WHERE sender=?", jwt.getSubject()) >= SENDER_FILE_QUOTA
                        || count("", new Object[0]) >= GLOBAL_FILE_QUOTA)
                    throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "文件存储空间不足");
                Path target = root.resolve(id);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "文件 ID 已被使用");
                Instant expires = Instant.now().plusSeconds(7 * 86400L).truncatedTo(ChronoUnit.SECONDS);
                Files.move(temp, target);
                try {
                    db.update("INSERT INTO attachments(id,sender,sender_account_id,recipient,recipient_account_id,size_bytes,sha256,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                        id, jwt.getSubject(), jwt.getClaimAsString("account_id"), to, toAccountId, size, hash, Timestamp.from(expires));
                } catch (RuntimeException e) {
                    Files.deleteIfExists(target);
                    throw e;
                }
                return new UploadResult(id, expires.toString());
            }
        } finally {
            activeTemps.remove(temp);
            try { Files.deleteIfExists(temp); }
            finally { transfers.release(); }
        }
    }

    @GetMapping("/{id}")
    public void download(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) throws IOException {
        requireId(id);
        FileRow row = visible(id, jwt);
        if (row == null) throw missing();
        if (!transfers.tryAcquire()) throw busy();
        try {
            ensureRoot();
            Path path = root.resolve(id);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
            try (FileChannel file = FileChannel.open(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (file.size() != row.size() || !hash(file).equals(row.sha256())) throw missing();
                file.position(0);
                response.setContentType("application/octet-stream");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setContentLengthLong(row.size());
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                var output = response.getOutputStream();
                while (file.read(buffer) != -1) {
                    buffer.flip();
                    output.write(buffer.array(), 0, buffer.remaining());
                    buffer.clear();
                }
            }
        } finally {
            transfers.release();
        }
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) throws IOException {
        requireId(id);
        synchronized (changes) {
            FileRow row = find(id);
            if (row == null || !row.sender().equals(jwt.getSubject())
                    || !row.senderAccountId().equals(jwt.getClaimAsString("account_id"))) throw missing();
            db.update("DELETE FROM attachments WHERE id=?", id);
            ensureRoot();
            Files.deleteIfExists(root.resolve(id));
        }
        response.setStatus(204);
    }

    private FileRow visible(String id, Jwt jwt) {
        return db.query("SELECT a.* FROM attachments a JOIN app_users s ON s.username=a.sender AND s.account_id=a.sender_account_id "
                + "JOIN app_users r ON r.username=a.recipient AND r.account_id=a.recipient_account_id WHERE a.id=? AND a.expires_at>CURRENT_TIMESTAMP",
            (rs, n) -> new FileRow(rs.getString("id"), rs.getString("sender"), rs.getString("sender_account_id"),
                rs.getString("recipient"), rs.getString("recipient_account_id"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getTimestamp("expires_at").toInstant()), id).stream().filter(row ->
                row.sender().equals(jwt.getSubject()) && row.senderAccountId().equals(jwt.getClaimAsString("account_id"))
                || row.recipient().equals(jwt.getSubject()) && row.recipientAccountId().equals(jwt.getClaimAsString("account_id")))
            .findFirst().orElse(null);
    }

    private FileRow find(String id) {
        return db.query("SELECT * FROM attachments WHERE id=?", (rs, n) -> new FileRow(rs.getString("id"),
            rs.getString("sender"), rs.getString("sender_account_id"), rs.getString("recipient"),
            rs.getString("recipient_account_id"), rs.getLong("size_bytes"), rs.getString("sha256"),
            rs.getTimestamp("expires_at").toInstant()), id).stream().findFirst().orElse(null);
    }

    private boolean allowed(String sender, String senderId, String recipient, String recipientId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM app_users s JOIN app_users r ON r.username=? AND r.account_id=? "
            + "JOIN contacts c ON ((c.user_a=s.username AND c.user_b=r.username) OR (c.user_b=s.username AND c.user_a=r.username)) "
            + "WHERE s.username=? AND s.account_id=? AND c.accepted=TRUE", Boolean.class,
            recipient, recipientId, sender, senderId));
    }

    private long used(String condition, Object... args) {
        return db.queryForObject("SELECT COALESCE(SUM(size_bytes),0) FROM attachments " + condition, Long.class, args);
    }
    private long count(String condition, Object... args) {
        return db.queryForObject("SELECT COUNT(*) FROM attachments " + condition, Long.class, args);
    }

    private void ensureRoot() throws IOException {
        // Reject configured symlink components and never follow a symlink inside the attachment directory.
        for (Path part = root; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Attachment directory is a symlink");
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Attachment directory unavailable");
    }

    @Scheduled(fixedDelayString = "${chat.retention-check-ms:3600000}", initialDelay = 60000)
    public void cleanExpired() {
        synchronized (changes) {
            try {
                db.update("DELETE FROM attachments WHERE expires_at<=CURRENT_TIMESTAMP OR NOT EXISTS "
                    + "(SELECT 1 FROM app_users s WHERE s.username=attachments.sender AND s.account_id=attachments.sender_account_id) "
                    + "OR NOT EXISTS (SELECT 1 FROM app_users r WHERE r.username=attachments.recipient AND r.account_id=attachments.recipient_account_id)");
                ensureRoot();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(root)) {
                    for (Path path : files) {
                        if (!ID.matcher(path.getFileName().toString()).matches() || activeTemps.contains(path)) continue;
                        if (find(path.getFileName().toString()) == null) Files.deleteIfExists(path);
                    }
                }
            } catch (Exception e) {
                log.warn("Attachment cleanup failed; exceptionType={}", e.getClass().getSimpleName());
            }
        }
    }

    private static String hash(FileChannel file) throws IOException {
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (file.read(buffer) != -1) { buffer.flip(); digest.update(buffer); buffer.clear(); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void requireId(String id) { if (!ID.matcher(id).matches()) throw bad("文件 ID 无效"); }
    private static boolean validUuid(String id) {
        if (id == null || !ID.matcher(id).matches()) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException e) { return false; }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"); }
    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "文件传输繁忙，请稍后重试"); }
}
