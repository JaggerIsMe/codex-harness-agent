package com.myharness.agent.connection;

import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class AgentEventBus {
    private final CopyOnWriteArrayList<Consumer<AgentEvent>> subscribers = new CopyOnWriteArrayList<>();

    public AutoCloseable subscribe(Consumer<AgentEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public void publish(AgentEvent event) {
        for (Consumer<AgentEvent> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }
}
