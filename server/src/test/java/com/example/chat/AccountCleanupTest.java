package com.example.chat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AccountCleanupTest {
    @Test void oneFailedDeletionDoesNotDelayOtherExpiredAccounts() {
        MessageStore store = mock(MessageStore.class);
        ChatSocket socket = mock(ChatSocket.class);
        when(store.expiredAccounts()).thenReturn(List.of("broken", "healthy"));
        when(store.deleteIfInactive("broken")).thenThrow(new IllegalStateException("database failure"));
        when(store.deleteIfInactive("healthy"))
            .thenReturn(Optional.of(new MessageStore.DeletedAccount("generation", List.of("peer"))));

        new AccountCleanup(store, socket).removeInactiveAccounts();

        verify(store).deleteIfInactive("healthy");
        verify(socket).disconnectDeletedAccount("healthy", "generation");
        verify(socket).notifyAccountDeleted("healthy", "generation", List.of("peer"));
        verify(socket).notifyContactChanged("healthy", "peer");
    }
}
