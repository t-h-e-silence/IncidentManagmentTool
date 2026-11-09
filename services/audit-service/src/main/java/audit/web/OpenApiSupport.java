package audit.web;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The caller header as a security scheme (so Swagger UI asks for it under "Authorize"), except on
 * {@code /internal/**}, which other services call without it; the problem-detail schema; each endpoint's
 * error responses from the service's {@link ApiErrors}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiSupport {

    private static final String CALLER = "caller";
    private static final String PROBLEM = "Problem";

    private static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            "400", "Invalid input, e.g. malformed id or JSON, missing value",
            "401", "Header missing, or the user is unknown, deactivated or the system user",
            "403", "Not allowed, e.g. not a member of the owning team",
            "404", "Not found",
            "409", "Business rule violated",
            "503", "A service this one depends on is unavailable, retry");

    static {
        // The caller comes from the header (see the security scheme), not from a request parameter.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(ActingUser.class);
    }

    @Bean
    OpenApiCustomizer commonOpenApi(ObjectProvider<ApiErrors> errors) {
        Content problem = new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)));
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents()
                    .addSecuritySchemes(CALLER, new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name(ActingUser.HEADER)
                            .description("Username (e.g. `bob`) or user id of the acting user"))
                    .addSchemas(PROBLEM, new ObjectSchema()
                            .description("RFC 9457 problem detail, the body of every error response")
                            .addProperty("type", new StringSchema().example("about:blank")
                                    .description("Problem type; always about:blank, the status says it all"))
                            .addProperty("title", new StringSchema().example("Not Found")
                                    .description("Reason phrase of the status code"))
                            .addProperty("status", new IntegerSchema().example(404)
                                    .description("HTTP status code: 400 invalid input, 401 unknown or inactive "
                                            + "caller, 403 not allowed, 404 not found, 409 business rule "
                                            + "violated, 503 a service this one depends on is unavailable"))
                            .addProperty("detail", new StringSchema()
                                    .example("Incident 40000000-0000-0000-0000-000000000009 not found")
                                    .description("What went wrong, for a human"))
                            .addProperty("instance", new StringSchema()
                                    .example("/incidents/40000000-0000-0000-0000-000000000009")
                                    .description("Path of the request")));
            Map<String, List<String>> byOperation = errors.getIfAvailable(() -> new ApiErrors(Map.of()))
                    .byOperationId();
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                operation.setSecurity(path.startsWith("/internal/")
                        ? List.of()
                        : List.of(new SecurityRequirement().addList(CALLER)));
                byOperation.getOrDefault(operation.getOperationId(), List.of()).forEach(code ->
                        operation.getResponses().putIfAbsent(code, new ApiResponse()
                                .description(ERROR_DESCRIPTIONS.get(code)).content(problem)));
            }));
        };
    }
}
