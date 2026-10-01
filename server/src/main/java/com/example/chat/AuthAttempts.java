package com.example.chat;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounded in-memory admission control, before costly password verification. */
@Component
public class AuthAttempts {
    private static final int MAX_ENTRIES=8192;
    private static final long IDLE_MILLIS=600_000;
    private static class Entry { double credits=150;long updated=System.currentTimeMillis();int failures;long blockedUntil; }
    private final Map<String,Entry> entries=new HashMap<>();
    private Entry entry(String key,long now) {
        if (entries.size()>=MAX_ENTRIES) entries.entrySet().removeIf(e->now-e.getValue().updated>IDLE_MILLIS);
        Entry item=entries.get(key);
        if (item==null) {
            if (entries.size()>=MAX_ENTRIES) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"登录请求较多，请稍后重试");
            item=new Entry();entries.put(key,item);
        }
        return item;
    }
    public synchronized void admit(String ip,String user) {
        long now=System.currentTimeMillis();
        Entry network=entry("ip:"+ip,now);
        network.credits=Math.min(150,network.credits+(now-network.updated)*0.0025);network.updated=now;
        if (network.credits<1) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"操作过于频繁，请稍后重试");
        network.credits--;
        if (user!=null) {
            Entry login=entry("login:"+ip+":"+user,now);login.updated=now;
            if (now<login.blockedUntil) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"密码尝试过多，请稍后重试");
        }
    }
    public synchronized void failure(String ip,String user) {
        long now=System.currentTimeMillis();Entry item=entry("login:"+ip+":"+user,now);item.updated=now;
        if (++item.failures>=5) item.blockedUntil=now+Math.min(300_000L,30_000L << Math.min(item.failures-5,3));
    }
    public synchronized void success(String ip,String user) { entries.remove("login:"+ip+":"+user); }
}
