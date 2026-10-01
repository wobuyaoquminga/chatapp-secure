package com.example.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatSocketFailureTest {
    private WebSocketSession session;
    private ChatSocket handler;
    private JwtDecoder jwt;
    private MessageStore store;

    @BeforeEach void setup() {
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("failure-test");
        when(session.isOpen()).thenReturn(true);
        jwt = mock(JwtDecoder.class);
        store = mock(MessageStore.class);
        when(store.recordConnection(anyString(), anyString())).thenReturn(true);
        when(store.currentGeneration(anyString(), anyString())).thenReturn(true);
        handler = new ChatSocket(new ObjectMapper(), jwt, store);
        handler.afterConnectionEstablished(session);
    }

    @Test void transportFailureAllowsClientsToReconnect() throws Exception {
        handler.handleTransportError(session, new IOException("simulated transport failure"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session, timeout(2000)).close(status.capture());
        assertEquals(1011, status.getValue().getCode());
        handler.handleTransportError(session, new IOException("duplicate callback"));
        verify(session, times(1)).close(any(CloseStatus.class));
    }

    @Test void failedErrorResponseDoesNotMasqueradeAsInvalidCredentials() throws Exception {
        doThrow(new IOException("simulated send failure")).when(session).sendMessage(any());
        handler.handleTextMessage(session, new TextMessage("{"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session, timeout(2000)).close(status.capture());
        assertEquals(1011, status.getValue().getCode());
    }

    @Test void unauthenticatedMessagesStillRequireFreshLogin() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertEquals(1008, status.getValue().getCode());
    }

    @Test void missedCloseCallbackStillNotifiesContactsOffline() throws Exception {
        WebSocketSession alice = mock(WebSocketSession.class);
        AtomicBoolean aliceOpen = new AtomicBoolean(true);
        when(alice.getId()).thenReturn("alice-socket");
        when(alice.isOpen()).thenAnswer(call -> aliceOpen.get());
        when(jwt.decode("bob-token")).thenReturn(token("bob"));
        when(jwt.decode("alice-token")).thenReturn(token("alice"));
        when(store.recordConnection(anyString(), anyString())).thenReturn(true);
        when(store.currentGeneration(anyString(), anyString())).thenReturn(true);
        when(store.contacts("alice")).thenReturn(List.of(new MessageStore.Contact("bob", "accepted", false)));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"bob-token\"}"));
        handler.afterConnectionEstablished(alice);
        handler.handleTextMessage(alice, new TextMessage("{\"type\":\"auth\",\"token\":\"alice-token\"}"));
        verify(session, timeout(2000).times(2)).sendMessage(any());
        clearInvocations(session);

        aliceOpen.set(false); // The transport closes without invoking afterConnectionClosed.
        handler.expireConnections();
        handler.expireConnections(); // A removed connection must not send repeated offline events.

        var frames = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, timeout(2000).times(1)).sendMessage(frames.capture());
        assertTrue(frames.getAllValues().stream().map(frame -> frame.getPayload().toString())
            .anyMatch(payload -> payload.contains("\"type\":\"presence\"")
                && payload.contains("\"username\":\"alice\"")
                && payload.contains("\"online\":false")));
    }

    @Test void aBlockedSocketDoesNotDelayAnotherUser() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { blocked.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; }).when(session).sendMessage(any());
        authenticate(session, "slow");
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        WebSocketSession fast = mock(WebSocketSession.class);
        when(fast.getId()).thenReturn("fast"); when(fast.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(fast);
        try {
            authenticate(fast, "fast");
            verify(fast, timeout(1000)).sendMessage(any());
            handler.handleTextMessage(fast, new TextMessage("{\"type\":\"ping\"}"));
            verify(fast, timeout(1000).times(2)).sendMessage(any());
        } finally { release.countDown(); }
    }

    @Test void anOverflowingSocketClosesWithoutAcknowledgingUnsentMessages() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { blocked.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(session).sendMessage(any());
        ReflectionTestUtils.setField(handler, "pingBurst", 1000);
        authenticate(session, "slow");
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        try {
            for (int i=0; i<257; i++) handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
            var status = ArgumentCaptor.forClass(CloseStatus.class);
            verify(session).close(status.capture());
            assertEquals(1011, status.getValue().getCode());
            verify(store, never()).acknowledge(anyString(), anyLong(), anyString());
        } finally { release.countDown(); }
    }

    @Test void aStalledPhysicalSendIsClosedByTheWatchdog() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { blocked.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(session).sendMessage(any());
        authenticate(session, "slow");
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        try {
            Object connection = ((Map<?,?>) ReflectionTestUtils.getField(handler, "connections")).get(session.getId());
            ReflectionTestUtils.setField(connection, "sendStarted", System.nanoTime() - TimeUnit.SECONDS.toNanos(6));
            handler.expireConnections();
            verify(session).close(argThat(status -> status.getCode()==1011 && status.getReason().equals("send timeout")));
        } finally { release.countDown(); }
    }

    @Test void renewalKeepsSameSocketAndRejectsChangingTheSubject() throws Exception {
        authenticate(session, "alice");
        when(jwt.decode("renewed")).thenReturn(token("alice"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"renewed\"}"));
        var frames = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, timeout(2000).times(2)).sendMessage(frames.capture());
        assertTrue(frames.getAllValues().get(1).getPayload().toString().contains("reauthenticated"));
        verify(session, never()).close(any());
        when(jwt.decode("other")).thenReturn(token("bob"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"other\"}"));
        verify(session).close(argThat(status -> status.getCode()==1008));
    }

    @Test void renewalCannotReviveAnExpiredConnection() throws Exception {
        authenticate(session, "alice");
        Object connection = ((Map<?,?>) ReflectionTestUtils.getField(handler, "connections")).get(session.getId());
        ReflectionTestUtils.setField(connection, "expires", Instant.now().minusSeconds(1));
        when(jwt.decode("renewed")).thenReturn(token("alice"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"auth\",\"token\":\"renewed\"}"));
        verify(session).close(argThat(status -> status.getCode()==1008));
        verify(jwt, never()).decode("renewed");
    }

    @Test void onlyTheLastConnectionEmitsOfflinePresence() throws Exception {
        when(store.contacts("alice")).thenReturn(List.of(new MessageStore.Contact("bob", "accepted", false)));
        authenticate(session, "alice");
        WebSocketSession second = mock(WebSocketSession.class), bob = mock(WebSocketSession.class);
        when(second.getId()).thenReturn("alice-second"); when(second.isOpen()).thenReturn(true);
        when(bob.getId()).thenReturn("bob"); when(bob.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(second); authenticate(second, "alice");
        handler.afterConnectionEstablished(bob); authenticate(bob, "bob");
        verify(bob, timeout(2000)).sendMessage(any()); clearInvocations(bob);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertTrue(handler.isOnline("alice"));
        verify(bob, never()).sendMessage(any());
        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        verify(bob, timeout(2000)).sendMessage(argThat(frame -> frame.getPayload().toString().contains("\"online\":false")));
        assertFalse(handler.isOnline("alice"));
    }

    @Test void ackBurstAllowsTwoHundredDeliveryConfirmations() throws Exception {
        when(store.acknowledge(eq("alice"), anyLong(), eq("alice-account"))).thenReturn(
            new MessageStore.Message("1", UUID.randomUUID().toString(), "bob", "alice", "opaque", Instant.now().toString(), false));
        authenticate(session, "alice");
        for (int i=1; i<=200; i++) handler.handleTextMessage(session, new TextMessage("{\"type\":\"ack\",\"id\":\""+i+"\"}"));
        verify(store, times(200)).acknowledge(eq("alice"), anyLong(), eq("alice-account"));
        verify(session, timeout(2000).times(201)).sendMessage(any());
        verify(session, never()).close(any());
    }

    @Test void sendRateIsSharedByAllConnectionsOfOneAccount() throws Exception {
        ReflectionTestUtils.setField(handler, "sendPerSecond", 1);
        ReflectionTestUtils.setField(handler, "sendBurst", 1);
        when(store.keysExist(anyString())).thenReturn(true);
        when(store.save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(
            new MessageStore.SaveResult(new MessageStore.Message("1", "client", "alice", "bob", "opaque", Instant.now().toString(), false), false));
        authenticate(session, "alice");
        WebSocketSession second = mock(WebSocketSession.class);
        when(second.getId()).thenReturn("alice-second"); when(second.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(second); authenticate(second, "alice");
        String ciphertext = new ObjectMapper().writeValueAsString(Map.of("v", 1, "type", 2, "data", "A".repeat(32)));
        for (WebSocketSession target : List.of(session, second)) handler.handleTextMessage(target,
            new TextMessage(new ObjectMapper().writeValueAsString(Map.of("type", "send", "clientId", UUID.randomUUID().toString(),
                "to", "bob", "toAccountId", UUID.randomUUID().toString(), "ciphertext", ciphertext))));
        verify(store, times(1)).save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(second, timeout(2000)).sendMessage(argThat(frame -> frame.getPayload().toString().contains("操作过于频繁")));
    }

    @Test void aNullExceptionMessageStillProducesASafeError() throws Exception {
        when(store.keysExist(anyString())).thenReturn(true);
        when(store.save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
        authenticate(session, "alice");
        verify(session, timeout(2000)).sendMessage(any());
        handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of("type", "send",
            "clientId", UUID.randomUUID().toString(), "to", "bob", "toAccountId", UUID.randomUUID().toString(),
            "ciphertext", new ObjectMapper().writeValueAsString(Map.of("v", 1, "type", 2, "data", "A".repeat(32)))))));
        verify(session, timeout(2000)).sendMessage(argThat(frame -> frame.getPayload().toString().contains("服务暂时不可用")));
    }

    private void authenticate(WebSocketSession socket, String user) throws Exception {
        when(jwt.decode(user + "-token")).thenReturn(token(user));
        handler.handleTextMessage(socket, new TextMessage("{\"type\":\"auth\",\"token\":\""+user+"-token\"}"));
    }

    private Jwt token(String user) {
        return Jwt.withTokenValue(user).header("alg", "none").subject(user)
            .claim("account_id", user + "-account").expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
