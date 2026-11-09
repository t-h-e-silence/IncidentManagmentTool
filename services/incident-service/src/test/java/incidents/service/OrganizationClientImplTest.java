package incidents.service;

import static incidents.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import incidents.model.Actor;
import incidents.web.exception.BusinessRuleException;
import incidents.web.exception.ServiceUnavailableException;
import incidents.web.exception.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class OrganizationClientImplTest {

    private static final String ORGANIZATION = "http://organization-service:8081";
    private static final Duration TTL = Duration.ofMinutes(5);

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> values;

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OrganizationClientImpl client;

    private final String danJson = """
            {"id": "%s", "name": "Dan Dba", "role": "USER", "teamIds": ["%s"]}""".formatted(DAN.id(), DATABASE);

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        server = MockRestServiceServer.bindTo(http).build();
        client = new OrganizationClientImpl(http, ORGANIZATION, redis, json, TTL);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void cacheHitAsksNobody() {
        when(values.get("incident-service:actor:" + DAN.id())).thenReturn(danJson);

        Actor actor = client.getActiveActor(DAN.id());

        assertThat(actor).isEqualTo(DAN);
        server.verify();
    }

    @Test
    void cacheMissAsksOrganizationAndStoresTheAnswer() {
        server.expect(requestTo(ORGANIZATION + "/internal/actors/" + DAN.id()))
                .andRespond(withSuccess(danJson, MediaType.APPLICATION_JSON));

        assertThat(client.getActiveActor(DAN.id())).isEqualTo(DAN);

        server.verify();
        verify(values).set(eq("incident-service:actor:" + DAN.id()), anyString(), eq(TTL));
    }

    @Test
    void unauthorizedBecomesUnauthenticatedAndIsNotCached() {
        server.expect(requestTo(ORGANIZATION + "/internal/actors/" + BOB.id()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\": 401, \"detail\": \"User is unknown or inactive\"}"));

        assertThatThrownBy(() -> client.getActiveActor(BOB.id()))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("User is unknown or inactive");
        verify(values, never()).set(anyString(), anyString(), eq(TTL));
    }

    @Test
    void usernameIsLookedUpAndIdsPassThrough() {
        server.expect(requestTo(ORGANIZATION + "/internal/users/bob/id"))
                .andRespond(withSuccess("{\"id\": \"%s\"}".formatted(BOB.id()), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORGANIZATION + "/internal/users/nobody/id"))
                .andRespond(withResourceNotFound());

        assertThat(client.findUserId(" Bob ")).contains(BOB.id());
        assertThat(client.findUserId("nobody")).isEmpty();
        UUID id = UUID.randomUUID();
        assertThat(client.findUserId(id.toString())).contains(id);
        server.verify();
    }

    @Test
    void archivedOrUnknownTeamIsNotActive() {
        server.expect(requestTo(ORGANIZATION + "/internal/teams/" + PLATFORM))
                .andRespond(withSuccess("{\"id\": \"%s\", \"name\": \"Platform\", \"archived\": true, \"members\": []}"
                        .formatted(PLATFORM), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORGANIZATION + "/internal/teams/" + NETWORK)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.requireActiveTeam(PLATFORM)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> client.requireActiveTeam(NETWORK)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void organizationDownIsServiceUnavailable() {
        server.expect(requestTo(ORGANIZATION + "/internal/categories/" + PAYMENTS + "/routing"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThatThrownBy(() -> client.getRouting(PAYMENTS)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void redisDownFallsBackToOrganization() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.opsForValue()).thenReturn(values);
        server.expect(requestTo(ORGANIZATION + "/internal/actors/" + DAN.id()))
                .andRespond(withSuccess(danJson, MediaType.APPLICATION_JSON));

        assertThat(client.getActiveActor(DAN.id())).isEqualTo(DAN);
    }
}
