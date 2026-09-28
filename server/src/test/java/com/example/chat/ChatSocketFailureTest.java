package com.example.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

class ChatSocketFailureTest {
    private WebSocketSession session;
    private ChatSocket handler;

    @BeforeEach void setup() {
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("failure-test");
        when(session.isOpen()).thenReturn(true);
        handler = new ChatSocket(new ObjectMapper(), mock(JwtDecoder.class), mock(MessageStore.class));
        handler.afterConnectionEstablished(session);
    }

    @Test void transportFailureAllowsClientsToReconnect() throws Exception {
        handler.handleTransportError(session, new IOException("simulated transport failure"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertEquals(1011, status.getValue().getCode());
        handler.handleTransportError(session, new IOException("duplicate callback"));
        verify(session, times(1)).close(any(CloseStatus.class));
    }

    @Test void failedErrorResponseDoesNotMasqueradeAsInvalidCredentials() throws Exception {
        doThrow(new IOException("simulated send failure")).when(session).sendMessage(any());
        handler.handleTextMessage(session, new TextMessage("{"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertEquals(1011, status.getValue().getCode());
    }

    @Test void unauthenticatedMessagesStillRequireFreshLogin() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        var status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertEquals(1008, status.getValue().getCode());
    }
}
