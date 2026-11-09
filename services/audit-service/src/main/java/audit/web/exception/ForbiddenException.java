package audit.web.exception;

/**
 * The actor is known but not allowed to perform the action. HTTP 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
