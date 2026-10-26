package orbit.protocol;

/** A message that could not be decoded or is not valid in the current connection state. */
public class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
