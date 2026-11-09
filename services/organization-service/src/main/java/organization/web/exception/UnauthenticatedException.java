package organization.web.exception;

/**
 * The actor is unknown, deactivated or the system user, so it may not call user functions. HTTP 401.
 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException(String message) {
        super(message);
    }
}
