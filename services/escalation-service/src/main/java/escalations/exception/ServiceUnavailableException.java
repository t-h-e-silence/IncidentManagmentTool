package escalations.exception;

/**
 * A service this one depends on (e.g. organization-service) cannot answer right now. HTTP 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
