package com.example.chat;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class HistoryController {
    private final MessageStore store;
    public HistoryController(MessageStore store) { this.store = store; }
    @GetMapping("/api/messages")
    public List<MessageStore.Message> history(@AuthenticationPrincipal Jwt jwt, @RequestParam String peer,
            @RequestParam(defaultValue="9223372036854775807") long beforeId) {
        return store.history(jwt.getSubject(), peer, beforeId);
    }
}
