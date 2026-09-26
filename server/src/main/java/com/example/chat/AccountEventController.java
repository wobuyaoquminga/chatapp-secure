package com.example.chat;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/account-events")
public class AccountEventController {
    private final MessageStore store;
    public AccountEventController(MessageStore store) { this.store = store; }
    public record AckRequest(List<String> ids) {}

    @GetMapping
    public List<MessageStore.AccountDeletionEvent> list(@AuthenticationPrincipal Jwt jwt) {
        return store.accountEvents(jwt.getSubject(), jwt.getClaimAsString("account_id"));
    }

    @PostMapping("/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@AuthenticationPrincipal Jwt jwt, @RequestBody AckRequest request) {
        if (request == null || request.ids() == null || request.ids().size() > 1000
            || request.ids().stream().anyMatch(id -> id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 必须为 UUID 字符串数组，每批最多 1000 项");
        store.acknowledgeAccountEvents(jwt.getSubject(), jwt.getClaimAsString("account_id"), request.ids());
    }
}
