package com.myharness.agent.connection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.entity.dto.ProtocolEnvelope;
import com.myharness.agent.entity.enums.AgentCommandType;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import org.springframework.stereotype.Component;

@Component
public class ProtocolCodec {
    private final ObjectMapper objectMapper;

    public ProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProtocolEnvelope decodeCommand(String text) {
        final ProtocolEnvelope envelope;
        try {
            envelope = objectMapper.readValue(text, ProtocolEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new ProtocolException("Malformed protocol message", exception);
        }
        requireText(envelope.getMessageId(), "messageId");
        requireText(envelope.getType(), "type");
        if (!ProtocolEnvelope.CURRENT_VERSION.equals(envelope.getProtocolVersion())) {
            throw new ProtocolException("Unsupported protocol version: " + envelope.getProtocolVersion());
        }
        try {
            AgentCommandType.valueOf(envelope.getType());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Unsupported command type: " + envelope.getType());
        }
        return envelope;
    }

    public String encodeEvent(AgentEvent event, DeviceIdentityVO identity) {
        ProtocolEnvelope envelope = new ProtocolEnvelope();
        envelope.setMessageId(event.getMessageId());
        envelope.setType(event.getType().name());
        envelope.setTimestamp(event.getTimestamp());
        envelope.setDeviceCode(identity.getDeviceCode());
        envelope.setCorrelationId(event.getCorrelationId());
        envelope.setPayload(objectMapper.valueToTree(event.getPayload()));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new ProtocolException("Unable to encode event " + event.getType(), exception);
        }
    }

    public <T> T payload(ProtocolEnvelope envelope, Class<T> payloadType) {
        if (envelope.getPayload() == null || envelope.getPayload().isNull()) {
            throw new ProtocolException("Missing payload for command " + envelope.getType());
        }
        try {
            return objectMapper.treeToValue(envelope.getPayload(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new ProtocolException("Invalid payload for command " + envelope.getType(), exception);
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new ProtocolException("Missing protocol field: " + field);
        }
    }
}
