package notifications.exception;

/**
 * A business rule forbids the action, e.g. an illegal status change. HTTP 409.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
