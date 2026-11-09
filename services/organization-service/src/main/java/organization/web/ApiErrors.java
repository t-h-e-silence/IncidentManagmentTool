package organization.web;

import java.util.List;
import java.util.Map;

/**
 * Error responses each endpoint documents in OpenAPI, by operation id (= controller method name), e.g.
 * {@code "listTeams" -> ["401"]}. Declared by this service's {@code OpenApiConfig}; codes are those of {@link HttpErrorHandler}.
 */
public record ApiErrors(Map<String, List<String>> byOperationId) {

    public ApiErrors {
        byOperationId = Map.copyOf(byOperationId);
    }
}
