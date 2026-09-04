package com.myharness.agent.command;

import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.connection.AgentEvent;
import com.myharness.agent.entity.enums.AgentEventType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandDeduplicatorTest {

    @Test
    void shouldExecuteCommandOnceAndReturnCachedResult() {
        AgentProperties properties = new AgentProperties();
        properties.setCommandDeduplicationSize(10);
        CommandDeduplicator deduplicator = new CommandDeduplicator(properties);
        AtomicInteger executions = new AtomicInteger();
        AgentEvent expected = new AgentEvent(AgentEventType.PONG, null, "ok");

        List<AgentEvent> first = deduplicator.execute("message-1", () -> {
            executions.incrementAndGet();
            return Collections.singletonList(expected);
        });
        List<AgentEvent> second = deduplicator.execute("message-1", () -> {
            executions.incrementAndGet();
            return Collections.emptyList();
        });

        assertEquals(1, executions.get());
        assertSame(expected, first.get(0));
        assertSame(expected, second.get(0));
    }
}
