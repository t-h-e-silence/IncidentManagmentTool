package modules.common.exception;

/**
 * The actor is known but not allowed to perform the action.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
