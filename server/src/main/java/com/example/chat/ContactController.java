package com.example.chat;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {
    private final MessageStore store;
    private final ChatSocket socket;
    public ContactController(MessageStore store, ChatSocket socket) { this.store = store; this.socket = socket; }
    public record AcceptRequest(String peer) {}

    @GetMapping
    public List<MessageStore.Contact> list(@AuthenticationPrincipal Jwt jwt) {
        return store.contacts(jwt.getSubject()).stream()
            .map(c -> new MessageStore.Contact(c.username(), c.status(), socket.isOnline(c.username())))
            .toList();
    }

    @PostMapping("/accept")
    public MessageStore.Contact accept(@AuthenticationPrincipal Jwt jwt, @RequestBody AcceptRequest request) {
        if (request == null || !Username.valid(request.peer()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "peer 必须为 2–32 位有效用户名");
        String user = jwt.getSubject();
        store.accept(user, request.peer());
        socket.notifyContactChanged(user, request.peer());
        return new MessageStore.Contact(request.peer(), "accepted", socket.isOnline(request.peer()));
    }

    @PostMapping("/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @RequestBody AcceptRequest request) {
        if (request == null || !Username.valid(request.peer()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "peer 必须为 2–32 位有效用户名");
        String user = jwt.getSubject();
        if (store.remove(user, request.peer())) socket.notifyContactChanged(user, request.peer());
    }
}
