package organization.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import organization.web.ActingUser;
import organization.web.ApiErrors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of organization-service ({@code /swagger-ui.html}, spec at {@code /v3/api-docs}). The caller
 * header, the problem-detail schema and the error responses come from {@link organization.web.OpenApiSupport}.
 */
@Configuration
public class OpenApiConfig {

    static final String TEAMS = "Teams";
    static final String INTERNAL = "Internal";

    @Bean
    OpenAPI organizationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Organization service")
                        .version("1")
                        .description("""
                                Users, teams, memberships and category routing.

                                **Public:** `GET /teams`, on this service's own port. The caller is the `%s` \
                                header, a username (demo users `ada`, `alice`, `bob`, `carol`, `dan`, `erin`) or a \
                                user id. Click **Authorize** and enter e.g. `dan`.

                                **Internal:** `/internal/**`, for the other services only; not part of the public API.

                                **Errors** are RFC 9457 problem details (`application/problem+json`)."""
                                .formatted(ActingUser.HEADER)))
                .addServersItem(new Server().url("/").description("This service"))
                .addServersItem(new Server().url("http://localhost:8081")
                        .description("Local, default port"))
                .addTagsItem(new Tag().name(TEAMS).description("Teams and their members"))
                .addTagsItem(new Tag().name(INTERNAL).description("Organization data for the other services"));
    }

    /**
     * Error responses per endpoint (operation id = controller method name).
     */
    @Bean
    ApiErrors organizationApiErrors() {
        return new ApiErrors(Map.of(
                "listTeams", List.of("401"),
                "getActiveActor", List.of("401"),
                "findUserId", List.of("404"),
                "getRouting", List.of("400", "404"),
                "getTeam", List.of("400", "404"),
                "getRecipient", List.of("400", "404")));
    }
}
