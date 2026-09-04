package com.myharness.agent.connection;

import com.myharness.agent.codex.AgentSessionManager;
import com.myharness.agent.command.AgentCommandDispatcher;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentServerConnectionTest {

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
