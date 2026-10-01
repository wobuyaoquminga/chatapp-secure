package com.example.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only delivered ciphertext expires; client history and pending messages are untouched. */
@Component
public class MessageRetention {
    private static final Logger log=LoggerFactory.getLogger(MessageRetention.class);
    private final JdbcTemplate db;
    private final RefreshTokenStore refresh;
    private final Path storage;
    public MessageRetention(JdbcTemplate db,RefreshTokenStore refresh,@Value("${chat.storage-monitor-path:data}") String path) {
        this.db=db;this.refresh=refresh;this.storage=Path.of(path).toAbsolutePath();
    }
    public int removeDeliveredBefore(Instant cutoff) {
        return db.update("DELETE FROM messages WHERE id IN (SELECT id FROM messages WHERE acknowledged=TRUE AND acknowledged_at IS NOT NULL AND acknowledged_at<=? ORDER BY id LIMIT 1000)",Timestamp.from(cutoff));
    }
    @Scheduled(fixedDelayString="${chat.retention-check-ms:3600000}",initialDelay=60000)
    public void maintain() {
        try {
            Instant cutoff=Instant.now().minusSeconds(7*86400L);int count=0;
            for(int batch=0;batch<100;batch++) {int removed=removeDeliveredBefore(cutoff);count+=removed;if(removed<1000)break;}
            if(count>0)log.info("Removed {} delivered ciphertext records older than seven days",count);
            refresh.removeExpired();
            if(Files.exists(storage)) {
                var disk=Files.getFileStore(storage);long available=disk.getUsableSpace(),total=disk.getTotalSpace();
                if(available<512L*1024*1024 || total>0 && available<total/10)
                    log.warn("Chat storage disk has low free capacity; freeBytes={} totalBytes={}",available,total);
            }
        } catch(Exception error) {log.warn("Storage maintenance failed; exceptionType={}",error.getClass().getSimpleName());}
    }
}
