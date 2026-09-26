package com.example.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/keys")
public class KeyController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public KeyController(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private String bytes(JsonNode node, String field, int size) {
        if (!node.path(field).isTextual()) throw bad("无效密钥字段: " + field);
        String value=node.path(field).asText();
        try { if (Base64.getDecoder().decode(value).length != size) throw bad("密钥长度无效"); }
        catch (IllegalArgumentException e) { throw bad("密钥 Base64 无效"); }
        return value;
    }
    private int id(JsonNode node, String field) {
        if (!node.path(field).isInt() || node.path(field).asInt() < 1) throw bad("密钥编号无效");
        return node.path(field).asInt();
    }
    @PutMapping
    @Transactional
    public Map<String,Object> publish(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input) throws Exception {
        String user=jwt.getSubject();
        db.queryForObject("SELECT username FROM app_users WHERE username=? FOR UPDATE", String.class, user);
        String identity=bytes(input,"identityKey",33);
        int registration=id(input,"registrationId");
        if (registration>16383) throw bad("注册编号无效");
        JsonNode sp=input.path("signedPreKey");
        int signedId=id(sp,"id");
        String publicKey=bytes(sp,"publicKey",33), signature=bytes(sp,"signature",64);
        String signed=json.writeValueAsString(Map.of("id",signedId,"publicKey",publicKey,"signature",signature));
        var old=db.queryForList("SELECT identity_key,registration_id,signed_pre_key FROM device_keys WHERE username=?",user);
        if (old.isEmpty()) db.update("INSERT INTO device_keys VALUES (?,?,?,?)",user,identity,registration,signed);
        else if (!identity.equals(old.get(0).get("identity_key")) || registration!=((Number)old.get(0).get("registration_id")).intValue() || !json.readTree(signed).equals(json.readTree((String)old.get(0).get("signed_pre_key"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"账号已绑定另一份本地密钥；本版不支持换设备或密钥重置");
        JsonNode batch=input.path("preKeys");
        if (!batch.isArray() || batch.size()>50) throw bad("每批最多 50 组预密钥");
        Integer total=db.queryForObject("SELECT COUNT(*) FROM one_time_keys WHERE username=? AND claimed=FALSE",Integer.class,user);
        if (total + batch.size() > 100) throw bad("未使用预密钥数量已达上限");
        for (JsonNode key:batch) {
            int keyId=id(key,"id");
            String ec=bytes(key,"publicKey",33), kyber=bytes(key,"kyberPublicKey",1569), sig=bytes(key,"kyberSignature",64);
            String bundle=json.writeValueAsString(Map.of("id",keyId,"publicKey",ec,"kyberPublicKey",kyber,"kyberSignature",sig));
            var existing=db.queryForList("SELECT bundle FROM one_time_keys WHERE username=? AND key_id=?",String.class,user,keyId);
            if (existing.isEmpty()) db.update("INSERT INTO one_time_keys(username,key_id,bundle) VALUES (?,?,?)",user,keyId,bundle);
            else if (!json.readTree(existing.get(0)).equals(json.readTree(bundle))) throw new ResponseStatusException(HttpStatus.CONFLICT,"预密钥编号不可覆盖");
        }
        return Map.of("ok",true);
    }
    @GetMapping("/me")
    public Map<String,Object> own(@AuthenticationPrincipal Jwt jwt) {
        var rows=db.queryForList("SELECT identity_key FROM device_keys WHERE username=?",String.class,jwt.getSubject());
        int remaining=db.queryForObject("SELECT COUNT(*) FROM one_time_keys WHERE username=? AND claimed=FALSE",Integer.class,jwt.getSubject());
        return Map.of("identityKey",rows.isEmpty()?"":rows.get(0),"remaining",remaining);
    }
    @GetMapping("/{user}")
    public Map<String,Object> identity(@PathVariable String user) throws Exception {
        if (!Username.valid(user)) throw bad("用户名无效");
        var rows=db.queryForList("SELECT k.*,u.account_id FROM device_keys k JOIN app_users u ON u.username=k.username WHERE k.username=?",user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"对方尚未初始化加密客户端");
        var row=rows.get(0);
        return Map.of("username",user,"accountId",row.get("account_id"),"identityKey",row.get("identity_key"),"registrationId",row.get("registration_id"),"signedPreKey",json.readTree((String)row.get("signed_pre_key")));
    }
    @PostMapping("/{user}/claim")
    @Transactional
    public Map<String,Object> claim(@AuthenticationPrincipal Jwt jwt,@PathVariable String user) throws Exception {
        if (!Username.valid(user)) throw bad("用户名无效");
        if (user.equals(jwt.getSubject())) throw bad("不能请求自己的会话预密钥");
        if (db.queryForList("SELECT username FROM app_users WHERE username=? FOR UPDATE",String.class,user).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"用户不存在");
        Map<String,Object> result=new HashMap<>(identity(user));
        var rows=db.queryForList("SELECT key_id,bundle FROM one_time_keys WHERE username=? AND claimed=FALSE ORDER BY key_id LIMIT 1",user);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"对方预密钥不足，请让对方登录客户端补充");
        var row=rows.get(0);
        db.update("UPDATE one_time_keys SET claimed=TRUE WHERE username=? AND key_id=?",user,row.get("key_id"));
        result.put("preKey",json.readTree((String)row.get("bundle")));
        return result;
    }
}
