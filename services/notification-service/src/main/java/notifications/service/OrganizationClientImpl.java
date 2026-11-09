package notifications.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import notifications.exception.NotFoundException;
import notifications.exception.ServiceUnavailableException;
import notifications.model.Recipient;
import notifications.model.Team;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cache-aside over organization-service (ADR D6): Redis first, then {@code /internal/**}, then store the answer
 * under this service's key prefix for {@code organization.cache-ttl}. Errors are not cached. When Redis is down,
 * organization-service is asked directly.
 */
@Service
public class OrganizationClientImpl implements OrganizationClient {

    private static final Logger log = LoggerFactory.getLogger(OrganizationClientImpl.class);

    /** Only this service reads or writes keys under its prefix. */
    static final String KEY_PREFIX = "notification-service:";

    private final RestClient organization;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public OrganizationClientImpl(RestClient.Builder http, @Value("${organization.url}") String organizationUrl,
                                  StringRedisTemplate redis, ObjectMapper json,
                                  @Value("${organization.cache-ttl}") Duration ttl) {
        this.organization = http.baseUrl(organizationUrl).build();
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override
    public Team getTeam(UUID teamId) {
        return cached("team:" + teamId, Team.class, () -> get("/internal/teams/{id}", Team.class, teamId));
    }

    @Override
    public List<Recipient> getActiveAdmins() {
        return Arrays.asList(cached("admins", Recipient[].class,
                () -> get("/internal/admins", Recipient[].class)));
    }

    @Override
    public Optional<Recipient> findRecipient(UUID userId) {
        try {
            return Optional.of(cached("recipient:" + userId, Recipient.class,
                    () -> get("/internal/users/{id}/recipient", Recipient.class, userId)));
        } catch (NotFoundException inactive) {
            return Optional.empty();
        }
    }

    private <T> T cached(String key, Class<T> type, Supplier<T> load) {
        String redisKey = KEY_PREFIX + key;
        try {
            String cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                return json.readValue(cached, type);
            }
        } catch (RuntimeException | IOException e) {
            log.warn("Redis read of {} failed, asking organization-service: {}", redisKey, e.getMessage());
        }
        T value = load.get();
        try {
            redis.opsForValue().set(redisKey, json.writeValueAsString(value), ttl);
        } catch (RuntimeException | IOException e) {
            log.warn("Redis write of {} failed: {}", redisKey, e.getMessage());
        }
        return value;
    }

    /**
     * 404 becomes {@link NotFoundException}; anything else means organization-service cannot answer.
     */
    private <T> T get(String uri, Class<T> type, Object... variables) {
        try {
            return organization.get().uri(uri, variables).retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                        throw new NotFoundException(detail(response));
                    })
                    .body(type);
        } catch (RestClientException e) {
            log.error("organization-service did not answer GET {}: {}", uri, e.getMessage());
            throw new ServiceUnavailableException("organization-service is unavailable, retry", e);
        }
    }

    private String detail(ClientHttpResponse response) throws IOException {
        return json.readTree(response.getBody()).path("detail").asText(response.getStatusText());
    }
}
