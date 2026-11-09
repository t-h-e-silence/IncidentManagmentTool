package incidents.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import incidents.service.IdempotencyService;
import incidents.web.ActingUser;
import incidents.web.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of incident-service ({@code /swagger-ui.html}, spec at {@code /v3/api-docs}). The caller
 * header, the problem-detail schema and the error responses come from {@link incidents.web.OpenApiSupport}.
 */
@Configuration
public class OpenApiConfig {

    static final String INCIDENTS = "Incidents";

    @Bean
    OpenAPI incidentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Incident service")
                        .version("1")
                        .description("""
                                Report incidents, list them, and work them: rename, describe, escalate or \
                                de-escalate (severity, with emails), and move them through the lifecycle. The \
                                history is served by audit-service (port 8083), the teams by organization-service \
                                (port 8081).

                                **Caller:** every request names the acting user in the `%s` header, as a username \
                                (demo users `ada`, `alice`, `bob`, `carol`, `dan`, `erin`) or a user id. Click \
                                **Authorize** and enter e.g. `dan`.

                                **%s:** optional on `POST` and `PATCH`; a repeat with the same key and body returns \
                                the first response instead of changing the incident again (kept 24 hours).

                                **Demo data:** incidents `40000000-0000-0000-0000-000000000001` (OPEN), `…0002` \
                                (IN_PROGRESS), `…0003` (RESOLVED); teams Platform `10000000-0000-0000-0000-000000000001`, Database \
                                `…0002`, Network `…0003`; categories Payments `30000000-0000-0000-0000-000000000001`, \
                                Web application `…0002`, Database `…0003`, VPN `…0004`, Network `…0005`.

                                **Errors** are RFC 9457 problem details (`application/problem+json`)."""
                                .formatted(ActingUser.HEADER, IdempotencyService.HEADER)))
                .addServersItem(new Server().url("/").description("This service"))
                .addServersItem(new Server().url("http://localhost:8082")
                        .description("Local, default port"))
                .addTagsItem(new Tag().name(INCIDENTS)
                        .description("List, create, update (name, description, severity, status), comment"));
    }

    /**
     * Error responses per endpoint (operation id = controller method name). 503: organization-service, which every
     * endpoint asks about the caller, is unavailable and nothing is cached.
     */
    @Bean
    ApiErrors incidentApiErrors() {
        return new ApiErrors(Map.of(
                "listAllIncidents", List.of("401", "503"),
                "listTeamIncidents", List.of("400", "401", "404", "503"),
                "listUserIncidents", List.of("401", "404", "503"),
                "createIncident", List.of("400", "401", "404", "409", "503"),
                "updateIncident", List.of("400", "401", "403", "404", "409", "503"),
                "getIncident", List.of("400", "401", "404", "503"),
                "addComment", List.of("400", "401", "403", "404", "409", "503")));
    }
}
