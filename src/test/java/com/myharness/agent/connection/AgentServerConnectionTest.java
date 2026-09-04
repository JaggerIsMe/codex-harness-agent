package com.myharness.agent.connection;

import com.myharness.agent.codex.AgentSessionManager;
import com.myharness.agent.command.AgentCommandDispatcher;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServerConnectionTest {

    @Test
    void schedulesReconnectWhenAsyncHandshakeFails() {
        AgentProperties properties = new AgentProperties();
        properties.setServerUrl(URI.create("ws://127.0.0.1/ws/agent"));
        DeviceIdentityProvider identity = mock(DeviceIdentityProvider.class);
        when(identity.get()).thenReturn(new DeviceIdentityVO("test-device", "test-token"));
        AgentServerConnection connection = new AgentServerConnection(properties, identity,
                mock(ProtocolCodec.class), mock(AgentCommandDispatcher.class), new AgentEventBus(),
                mock(WorkspaceRegistry.class), mock(AgentSessionManager.class));
        StandardWebSocketClient client = mock(StandardWebSocketClient.class);
        CompletableFuture<WebSocketSession> handshake = new CompletableFuture<>();
        when(client.execute(eq(connection), any(WebSocketHttpHeaders.class), eq(properties.getServerUrl())))
                .thenReturn(handshake);
        ReflectionTestUtils.setField(connection, "webSocketClient", client);

        try {
            connection.start();
            handshake.completeExceptionally(new IllegalStateException("test handshake failure"));
            ScheduledFuture<?> reconnect = (ScheduledFuture<?>) ReflectionTestUtils.getField(connection, "reconnectFuture");
            assertNotNull(reconnect);
            verify(client).execute(eq(connection), any(WebSocketHttpHeaders.class), eq(properties.getServerUrl()));
            connection.stop();
            assertNull(ReflectionTestUtils.getField(connection, "reconnectFuture"));
        } finally {
            connection.stop();
        }
    }

    @Test
    void closesHandshakeThatCompletesAfterShutdown() throws Exception {
        AgentServerConnection connection = new AgentServerConnection(new AgentProperties(),
                mock(DeviceIdentityProvider.class), mock(ProtocolCodec.class), mock(AgentCommandDispatcher.class),
                new AgentEventBus(), mock(WorkspaceRegistry.class), mock(AgentSessionManager.class));
        ReflectionTestUtils.setField(connection, "running", true);
        connection.stop();
        WebSocketSession lateSession = mock(WebSocketSession.class);

        connection.afterConnectionEstablished(lateSession);

        verify(lateSession).close();
        assertFalse(connection.isRunning());
        AtomicReference<?> current = (AtomicReference<?>) ReflectionTestUtils.getField(connection, "session");
        assertNotNull(current);
        assertNull(current.get());
    }

    @Test
    void stopsWhenServerReplacesDuplicateDeviceConnection() {
        AgentSessionManager sessionManager = mock(AgentSessionManager.class);
        AgentServerConnection connection = new AgentServerConnection(
                new AgentProperties(),
                mock(DeviceIdentityProvider.class),
                mock(ProtocolCodec.class),
                mock(AgentCommandDispatcher.class),
                mock(AgentEventBus.class),
                mock(WorkspaceRegistry.class),
                sessionManager);
        WebSocketSession closed = mock(WebSocketSession.class);

        @SuppressWarnings("unchecked")
        AtomicReference<WebSocketSession> current =
                (AtomicReference<WebSocketSession>) ReflectionTestUtils.getField(connection, "session");
        assertNotNull(current);
        current.set(closed);
        ReflectionTestUtils.setField(connection, "running", true);

        try {
            connection.afterConnectionClosed(closed,
                    CloseStatus.NORMAL.withReason("Replaced by a newer connection"));

            assertFalse(connection.isRunning());
            verify(sessionManager).interruptAll();
        } finally {
            connection.stop();
        }
    }
}
