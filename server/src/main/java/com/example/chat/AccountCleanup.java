package com.example.chat;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountCleanup {
    private final MessageStore store;
    private final ChatSocket socket;

    public AccountCleanup(MessageStore store, ChatSocket socket) {
        this.store = store;
        this.socket = socket;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void removeInactiveAccounts() {
        for (String user : store.expiredAccounts()) {
            store.deleteIfInactive(user).ifPresent(deleted -> {
                socket.disconnectDeletedAccount(user, deleted.accountId());
                socket.notifyAccountDeleted(user, deleted.accountId(), deleted.peers());
                for (String peer : deleted.peers()) socket.notifyContactChanged(user, peer);
            });
        }
    }
}
