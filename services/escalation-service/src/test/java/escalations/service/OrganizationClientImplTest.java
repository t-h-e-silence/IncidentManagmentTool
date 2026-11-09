package escalations.service;

import static escalations.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import escalations.exception.ServiceUnavailableException;
import escalations.model.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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

    private MockRestServiceServer server;
    private OrganizationClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder http = RestClient.builder();
        server = MockRestServiceServer.bindTo(http).build();
        client = new OrganizationClientImpl(http, ORGANIZATION, redis, new ObjectMapper(), TTL);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void teamComesWithItsMembersAndIsCached() {
        server.expect(requestTo(ORGANIZATION + "/internal/teams/" + DATABASE)).andRespond(withSuccess("""
                {"id": "%s", "name": "Database", "archived": false, "members": [
                  {"userId": "%s", "name": "Dan Dba", "email": "dan@example.com", "role": "RESPONDER"}]}"""
                .formatted(DATABASE, DAN.userId()), MediaType.APPLICATION_JSON));

        Team team = client.getTeam(DATABASE);

        assertThat(team.recipients()).containsExactly(DAN);
        verify(values).set(eq("escalation-service:team:" + DATABASE), anyString(), eq(TTL));
    }

    @Test
    void cachedAdminsAskNobody() {
        when(values.get("escalation-service:admins")).thenReturn("""
                [{"userId": "%s", "name": "Ada Admin", "email": "ada.admin@example.com"}]""".formatted(ADA.userId()));

        assertThat(client.getActiveAdmins()).containsExactly(ADA);
        server.verify();
    }

    @Test
    void inactiveUserHasNoRecipient() {
        server.expect(requestTo(ORGANIZATION + "/internal/users/" + BOB.userId() + "/recipient"))
                .andRespond(withResourceNotFound());

        assertThat(client.findRecipient(BOB.userId())).isEmpty();
    }

    @Test
    void organizationDownIsServiceUnavailable() {
        server.expect(requestTo(ORGANIZATION + "/internal/teams/" + DATABASE)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThatThrownBy(() -> client.getTeam(DATABASE)).isInstanceOf(ServiceUnavailableException.class);
    }
}
