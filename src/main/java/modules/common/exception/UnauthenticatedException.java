package modules.common.exception;

/**
 * The actor is unknown, deactivated or the system user, so it may not call user functions.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
