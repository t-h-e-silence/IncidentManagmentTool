package incidents.service;

import java.util.UUID;
import java.util.function.Supplier;

import incidents.web.exception.BusinessRuleException;

/**
 * Optional {@code Idempotency-Key} header of the writing endpoints (ADR D9): a repeated request gets the stored
 * response instead of changing the incident again. Keys are per caller and kept for 24 hours.
 */
public interface IdempotencyService {

    String HEADER = "Idempotency-Key";

    /**
     * Runs the action once per key: the first request with the key runs it, in the caller's transaction, and stores
     * its response; a repeat with the same request returns that response. Without a key the action just runs.
     * A failing action stores nothing, so the key can be used again.
     *
     * @param key      the header value, or null
     * @param endpoint e.g. {@code POST /incidents}; part of what makes two requests "the same"
     * @param request  the request body
     * @throws BusinessRuleException if the key was used for a different request
     */
    <T> T execute(String key, UUID actorId, String endpoint, Object request, Class<T> responseType,
                  Supplier<T> action);

    /**
     * @return number of keys older than 24 hours deleted
     */
    int deleteExpired();
}
