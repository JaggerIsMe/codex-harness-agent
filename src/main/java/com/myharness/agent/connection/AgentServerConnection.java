package com.myharness.agent.connection;

import com.myharness.agent.codex.AgentSessionManager;
import com.myharness.agent.command.AgentCommandDispatcher;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ErrorEventDTO;
import com.myharness.agent.entity.dto.HeartbeatEventDTO;
import com.myharness.agent.entity.dto.ProtocolEnvelope;
import com.myharness.agent.entity.dto.RegisterEventDTO;
import com.myharness.agent.entity.enums.AgentEventType;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.AgentMetadata;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AgentServerConnection extends AbstractWebSocketHandler implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentServerConnection.class);
    private static final int[] BACKOFF_SECONDS = {1, 2, 5, 10, 30};
    private static final int MAX_TEXT_MESSAGE_BYTES = 4 * 1024 * 1024;
    private static final String REPLACED_CONNECTION_REASON = "Replaced by a newer connection";

    private final AgentProperties properties;
    private final DeviceIdentityProvider identityProvider;
    private final ProtocolCodec codec;
    private final AgentCommandDispatcher dispatcher;
    private final AgentEventBus eventBus;
    private final WorkspaceRegistry workspaceRegistry;
    private final AgentSessionManager sessionManager;
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemonThreads());
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
    private final AtomicInteger reconnectAttempt = new AtomicInteger();
    private final Object sendLock = new Object();
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean running;
    private volatile AutoCloseable subscription;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ScheduledFuture<?> heartbeatFuture;

    public AgentServerConnection(AgentProperties properties, DeviceIdentityProvider identityProvider,
                                 ProtocolCodec codec, AgentCommandDispatcher dispatcher, AgentEventBus eventBus,
                                 WorkspaceRegistry workspaceRegistry, AgentSessionManager sessionManager) {
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.codec = codec;
        this.dispatcher = dispatcher;
        this.eventBus = eventBus;
        this.workspaceRegistry = workspaceRegistry;
        this.sessionManager = sessionManager;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        validateServerUri(properties.getServerUrl());
        identityProvider.get();
        running = true;
        subscription = eventBus.subscribe(this::send);
        connect();
        heartbeatFuture = scheduler.scheduleWithFixedDelay(this::heartbeat,
                properties.getHeartbeatIntervalSeconds(), properties.getHeartbeatIntervalSeconds(), TimeUnit.SECONDS);
    }

    private void connect() {
        if (!running || isConnected()) {
            return;
        }
        DeviceIdentityVO identity = identityProvider.get();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + identity.getDeviceToken());
        headers.set("X-Harness-Device-Code", identity.getDeviceCode());
        headers.set("X-Harness-Protocol-Version", ProtocolEnvelope.CURRENT_VERSION);
        try {
            CompletableFuture<WebSocketSession> future = webSocketClient.execute(this, headers, properties.getServerUrl());
            future.whenComplete((connected, failure) -> {
                if (failure != null) {
                    scheduleReconnect("Connection failed");
                }
            });
        } catch (RuntimeException exception) {
            scheduleReconnect("Connection failed");
        }
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession established) {
        if (!running) {
            closeQuietly(established);
            return;
        }
        WebSocketSession previous = session.getAndSet(established);
        if (previous != null && previous != established) {
            closeQuietly(previous);
        }
        established.setTextMessageSizeLimit(MAX_TEXT_MESSAGE_BYTES);
        reconnectAttempt.set(0);
        cancelReconnect();
        send(registerEvent());
        send(new AgentEvent(AgentEventType.WORKSPACES_CHANGED, null, workspaceRegistry.list()));
        LOGGER.info("Connected to Harness Server as device {}", identityProvider.get().getDeviceCode());
    }

    @Override
    protected void handleTextMessage(WebSocketSession current, TextMessage message) {
        try {
            ProtocolEnvelope envelope = codec.decodeCommand(message.getPayload());
            dispatcher.handle(envelope);
        } catch (ProtocolException exception) {
            send(new AgentEvent(AgentEventType.ERROR, null,
                    new ErrorEventDTO("PROTOCOL_ERROR", exception.getMessage(), null, null)));
        } catch (RuntimeException exception) {
            send(new AgentEvent(AgentEventType.ERROR, null,
                    new ErrorEventDTO("DISPATCH_ERROR", safeMessage(exception), null, null)));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession current, Throwable exception) {
        LOGGER.warn("Harness Server transport error: {}", safeMessage(exception));
        closeQuietly(current);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession closed, CloseStatus status) {
        if (session.compareAndSet(closed, null) && running) {
            sessionManager.interruptAll();
            if (status != null && REPLACED_CONNECTION_REASON.equals(status.getReason())) {
                LOGGER.error("Harness Server replaced this connection because another Agent is using the same device identity; automatic reconnect stopped");
                stop();
                return;
            }
            scheduleReconnect("Connection closed");
        }
    }

    private AgentEvent registerEvent() {
        String osName=System.getProperty("os.name");
        String isolationMode=osName!=null && osName.toLowerCase(java.util.Locale.ROOT).contains("win")
                && properties.isStrictProjectIsolation() && "elevated".equalsIgnoreCase(properties.getWindowsSandbox())
                ? "WINDOWS_PROJECT_PROFILE" : "UNSUPPORTED";
        RegisterEventDTO payload = new RegisterEventDTO(properties.getDeviceName(), AgentMetadata.version(),
                osName,System.getProperty("os.version"),isolationMode,workspaceRegistry.list(),
                workspaceRegistry.roots());
        return new AgentEvent(AgentEventType.REGISTER, null, payload);
    }

    private void heartbeat() {
        if (!running || !isConnected()) {
            return;
        }
        long uptime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startedAt);
        send(new AgentEvent(AgentEventType.HEARTBEAT, null,
                new HeartbeatEventDTO(sessionManager.activeTurnCount(), uptime)));
    }

    private void send(AgentEvent event) {
        WebSocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            return;
        }
        final String encoded;
        try {
            encoded = codec.encodeEvent(event, identityProvider.get());
        } catch (RuntimeException exception) {
            LOGGER.error("Unable to encode Agent event {}: {}", event.getType(), safeMessage(exception));
            return;
        }
        synchronized (sendLock) {
            try {
                current.sendMessage(new TextMessage(encoded));
            } catch (IOException exception) {
                LOGGER.warn("Unable to send event {} to Harness Server", event.getType());
                closeQuietly(current);
            }
        }
    }

    private synchronized void scheduleReconnect(String reason) {
        if (!running || isConnected() || (reconnectFuture != null && !reconnectFuture.isDone())) {
            return;
        }
        int attempt = reconnectAttempt.getAndIncrement();
        int configuredMaximum = (int) Math.min(Integer.MAX_VALUE, properties.getReconnectMaxDelaySeconds());
        int delay = Math.min(BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)], configuredMaximum);
        LOGGER.warn("{}; reconnecting in {} seconds", reason, delay);
        reconnectFuture = scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    private boolean isConnected() {
        WebSocketSession current = session.get();
        return current != null && current.isOpen();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        cancelReconnect();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Subscription close is in-memory and best effort during shutdown.
            }
            subscription = null;
        }
        WebSocketSession current = session.getAndSet(null);
        closeQuietly(current);
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private void closeQuietly(WebSocketSession current) {
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException ignored) {
            // Reconnect logic handles closed sessions.
        }
    }

    private void validateServerUri(URI uri) {
        if (uri == null || uri.getHost() == null
                || !("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("Harness Server URL must use ws or wss");
        }
        if ("ws".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
            throw new IllegalStateException("Plain ws is only allowed for loopback Harness Server URLs");
        }
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private String safeMessage(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? (exception == null ? "Unknown error" : exception.getClass().getSimpleName()) : message;
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "harness-agent-connection-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
