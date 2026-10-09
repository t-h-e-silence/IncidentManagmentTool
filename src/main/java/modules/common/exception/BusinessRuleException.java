package modules.common.exception;

/**
 * A business rule forbids the action, e.g. an illegal status change.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
