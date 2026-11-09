package audit.web.exception;

/**
 * A requested entity does not exist. HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
