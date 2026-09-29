package com.example.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountCleanup {
    private static final Logger log = LoggerFactory.getLogger(AccountCleanup.class);
    private final MessageStore store;
    private final ChatSocket socket;

    public AccountCleanup(MessageStore store, ChatSocket socket) {
        this.store = store;
        this.socket = socket;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void removeInactiveAccounts() {
        for (String user : store.expiredAccounts()) {
            try {
                store.deleteIfInactive(user).ifPresent(deleted -> {
                    socket.disconnectDeletedAccount(user, deleted.accountId());
                    socket.notifyAccountDeleted(user, deleted.accountId(), deleted.peers());
                    for (String peer : deleted.peers()) socket.notifyContactChanged(user, peer);
                });
            } catch (RuntimeException e) {
                log.warn("Inactive account cleanup or notification failed for {}; continuing", user, e);
            }
        }
    }
}
