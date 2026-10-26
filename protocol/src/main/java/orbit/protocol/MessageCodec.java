package orbit.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** JSON encoding of {@link Message}s. Thread-safe. */
public final class MessageCodec {

    private static final ObjectMapper MAPPER = newMapper();

    private MessageCodec() {
    }

    /** A mapper configured the way Orbit wants for messages and config files. */
    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    public static String encode(Message message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode " + message, e);
        }
    }

    public static Message decode(String json) throws ProtocolException {
        try {
            return MAPPER.readValue(json, Message.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ProtocolException("malformed message: " + originalMessage(e), e);
        }
    }

    private static String originalMessage(Exception e) {
        return e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
    }
}
