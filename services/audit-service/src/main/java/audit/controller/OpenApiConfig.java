package audit.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import audit.web.ActingUser;
import audit.web.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of audit-service ({@code /swagger-ui.html}, spec at {@code /v3/api-docs}). The caller header,
 * the problem-detail schema and the error responses come from {@link audit.web.OpenApiSupport}.
 */
@Configuration
public class OpenApiConfig {

    static final String INCIDENTS = "Incidents";

    @Bean
    OpenAPI auditOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Audit service")
                        .version("1")
                        .description("""
                                The append-only history of every incident, built from incident events.

                                **Caller:** the `%s` header, a username (demo users `ada`, `alice`, `bob`, `carol`, \
                                `dan`, `erin`) or a user id. Click **Authorize** and enter e.g. `dan`.

                                **Errors** are RFC 9457 problem details (`application/problem+json`)."""
                                .formatted(ActingUser.HEADER)))
                .addServersItem(new Server().url("/").description("This service"))
                .addServersItem(new Server().url("http://localhost:8083")
                        .description("Local, default port"))
                .addTagsItem(new Tag().name(INCIDENTS).description("History of an incident"));
    }

    /**
     * Error responses per endpoint (operation id = controller method name).
     */
    @Bean
    ApiErrors auditApiErrors() {
        return new ApiErrors(Map.of("getIncidentHistory", List.of("400", "401", "404", "503")));
    }
}
