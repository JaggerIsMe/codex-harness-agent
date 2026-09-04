package com.myharness.agent.command;

import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.connection.AgentEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

@Component
public class CommandDeduplicator {
    private final int maximumSize;
    private final Map<String, List<AgentEvent>> completed;
    private final Map<String, CompletableFuture<List<AgentEvent>>> inFlight = new LinkedHashMap<>();

    public CommandDeduplicator(AgentProperties properties) {
        this.maximumSize = properties.getCommandDeduplicationSize();
        this.completed = new LinkedHashMap<String, List<AgentEvent>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<AgentEvent>> eldest) {
                return size() > CommandDeduplicator.this.maximumSize;
            }
        };
    }

    public List<AgentEvent> execute(String messageId, Supplier<List<AgentEvent>> operation) {
        CompletableFuture<List<AgentEvent>> future;
        boolean owner = false;
        synchronized (this) {
            List<AgentEvent> previous = completed.get(messageId);
            if (previous != null) {
                return previous;
            }
            future = inFlight.get(messageId);
            if (future == null) {
                future = new CompletableFuture<>();
                inFlight.put(messageId, future);
                owner = true;
            }
        }

        if (owner) {
            try {
                List<AgentEvent> result = immutable(operation.get());
                synchronized (this) {
                    completed.put(messageId, result);
                    inFlight.remove(messageId);
                }
                future.complete(result);
                return result;
            } catch (RuntimeException exception) {
                synchronized (this) {
                    inFlight.remove(messageId);
                }
                future.completeExceptionally(exception);
                throw exception;
            }
        }

        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentOperationException("COMMAND_WAIT_INTERRUPTED",
                    "Interrupted while waiting for duplicate command result", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AgentOperationException("COMMAND_EXECUTION_FAILED", "Duplicate command failed", cause);
        }
    }

    private List<AgentEvent> immutable(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
