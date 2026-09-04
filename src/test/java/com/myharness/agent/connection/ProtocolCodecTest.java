package com.myharness.agent.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.entity.dto.PongEventDTO;
import com.myharness.agent.entity.dto.ProtocolEnvelope;
import com.myharness.agent.entity.enums.AgentEventType;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProtocolCodec codec = new ProtocolCodec(objectMapper);

    @Test
    void shouldDecodeSupportedCommand() {
        ProtocolEnvelope envelope = codec.decodeCommand("{\"protocolVersion\":\"1.0\","
                + "\"messageId\":\"message-1\",\"type\":\"PING\",\"timestamp\":1,\"payload\":{}}");

        assertEquals("message-1", envelope.getMessageId());
        assertEquals("PING", envelope.getType());
    }

    @Test
    void shouldRejectUnknownProtocolVersionAndCommand() {
        assertThrows(ProtocolException.class, () -> codec.decodeCommand("{\"protocolVersion\":\"2.0\","
                + "\"messageId\":\"message-1\",\"type\":\"PING\",\"payload\":{}}"));
        assertThrows(ProtocolException.class, () -> codec.decodeCommand("{\"protocolVersion\":\"1.0\","
                + "\"messageId\":\"message-1\",\"type\":\"RUN_SHELL\",\"payload\":{}}"));
        assertThrows(ProtocolException.class, () -> codec.decodeCommand("not-json"));
    }

    @Test
    void shouldEncodeEventWithoutDeviceToken() throws Exception {
        AgentEvent event = new AgentEvent(AgentEventType.PONG, "turn-1",
                new PongEventDTO("message-1", 10L));

        JsonNode encoded = objectMapper.readTree(codec.encodeEvent(event,
                new DeviceIdentityVO("device-1", "secret-token")));

        assertEquals("1.0", encoded.path("protocolVersion").asText());
        assertEquals("device-1", encoded.path("deviceCode").asText());
        assertEquals("PONG", encoded.path("type").asText());
        assertEquals("message-1", encoded.path("payload").path("commandMessageId").asText());
        assertFalse(encoded.toString().contains("secret-token"));
        JsonNode retried=objectMapper.readTree(codec.encodeEvent(event,new DeviceIdentityVO("device-1","secret-token")));
        assertEquals(encoded.path("messageId"),retried.path("messageId"));
        assertEquals(encoded.path("timestamp"),retried.path("timestamp"));
    }
}
