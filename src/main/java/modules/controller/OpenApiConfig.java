package modules.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document behind Swagger UI ({@code /swagger-ui.html}, spec at {@code /v3/api-docs}). The caller
 * header is declared once as a security scheme, so Swagger UI asks for it under "Authorize"; every endpoint
 * documents the error responses of {@link HttpErrorHandler}.
 */
@Configuration
public class OpenApiConfig {

    static final String TEAMS = "Teams";
    static final String INCIDENTS = "Incidents";

    private static final String CALLER = "caller";
    private static final String PROBLEM = "Problem";

    /**
     * Error responses per endpoint (operation id = controller method name).
     */
    private static final Map<String, List<String>> ERRORS = Map.of(
            "listTeams", List.of("401"),
            "listAllIncidents", List.of("401"),
            "listTeamIncidents", List.of("400", "401", "404"),
            "listUserIncidents", List.of("401", "404"),
            "createIncident", List.of("400", "401", "404", "409"),
            "updateIncident", List.of("400", "401", "403", "404", "409"),
            "getIncident", List.of("400", "401", "404"),
            "addComment", List.of("400", "401", "403", "404", "409"),
            "getIncidentHistory", List.of("400", "401", "404"));

    private static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            "400", "Invalid input, e.g. missing reason, empty update, malformed id or JSON",
            "401", "Header missing, or the user is unknown, deactivated or the system user",
            "403", "Not allowed, e.g. not a member of the owning team",
            "404", "Incident, team, user or category not found",
            "409", "Business rule violated, e.g. illegal status change, severity not raised, incident closed");

    static {
        // The caller comes from the header (see the security scheme), not from a request parameter.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(ActingUser.class);
    }

    @Bean
    OpenAPI incidentManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Incident Management API")
                        .version("1")
                        .description("""
                                Report incidents, list them, and work them: rename, describe, escalate or \
                                de-escalate (severity, with emails), and move them through the lifecycle.

                                **Caller:** every request names the acting user in the `%s` header, as a username \
                                (`ada`, `alice`, `bob`, `carol`, `dan`, `erin` with the `seed` profile) or a user id. \
                                Click **Authorize** and enter e.g. `dan`. There is no login.

                                **Seeded ids:** teams Platform `10000000-0000-0000-0000-000000000001`, Database \
                                `…0002`, Network `…0003`; categories Payments `30000000-0000-0000-0000-000000000001`, \
                                Web application `…0002`, Database `…0003`, VPN `…0004`, Network `…0005`.

                                **Errors** are RFC 9457 problem details (`application/problem+json`)."""
                                .formatted(ActingUser.HEADER)))
                .addServersItem(new Server().url("/").description("This application"))
                .addServersItem(new Server().url("http://localhost:8080").description("Local, default port"))
                .addTagsItem(new Tag().name(TEAMS).description("Teams and their members"))
                .addTagsItem(new Tag().name(INCIDENTS)
                        .description("List, create, update (name, description, severity, status), comment, history"))
                .components(new Components()
                        .addSecuritySchemes(CALLER, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ActingUser.HEADER)
                                .description("Username (e.g. `bob`) or user id of the acting user")))
                .addSecurityItem(new SecurityRequirement().addList(CALLER));
    }

    /**
     * Adds the problem-detail schema and each endpoint's error responses (see {@link HttpErrorHandler}).
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        Content problem = new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)));
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM, new ObjectSchema()
                    .description("RFC 9457 problem detail")
                    .addProperty("type", new StringSchema().example("about:blank"))
                    .addProperty("title", new StringSchema().example("Conflict"))
                    .addProperty("status", new IntegerSchema().example(409))
                    .addProperty("detail", new StringSchema().example("Incident … cannot go from CLOSED to IN_PROGRESS"))
                    .addProperty("instance", new StringSchema().example("/incidents/…")));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    ERRORS.getOrDefault(operation.getOperationId(), List.of()).forEach(code ->
                            operation.getResponses().putIfAbsent(code, new ApiResponse()
                                    .description(ERROR_DESCRIPTIONS.get(code)).content(problem)))));
        };
    }
}
