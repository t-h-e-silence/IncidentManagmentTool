package incidents.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import incidents.model.Actor;
import incidents.model.CategoryRouting;
import incidents.model.Team;
import incidents.web.exception.BusinessRuleException;
import incidents.web.exception.NotFoundException;
import incidents.web.exception.ServiceUnavailableException;
import incidents.web.exception.UnauthenticatedException;
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
    static final String KEY_PREFIX = "incident-service:";

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
    public Actor getActiveActor(UUID userId) {
        return cached("actor:" + userId, Actor.class, () -> get("/internal/actors/{user}", Actor.class, userId));
    }

    @Override
    public Optional<UUID> findUserId(String userIdOrUsername) {
        if (userIdOrUsername == null || userIdOrUsername.isBlank()) {
            return Optional.empty();
        }
        String value = userIdOrUsername.strip();
        try {
            // An id is returned as it is, as organization-service does.
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            String username = value.toLowerCase(Locale.ROOT);
            try {
                return Optional.of(cached("user-id:" + username, UserId.class,
                        () -> get("/internal/users/{user}/id", UserId.class, username)).id());
            } catch (NotFoundException unknown) {
                return Optional.empty();
            }
        }
    }

    @Override
    public CategoryRouting getRouting(UUID categoryId) {
        return cached("routing:" + categoryId, CategoryRouting.class,
                () -> get("/internal/categories/{id}/routing", CategoryRouting.class, categoryId));
    }

    @Override
    public Team getTeam(UUID teamId) {
        return cached("team:" + teamId, Team.class, () -> get("/internal/teams/{id}", Team.class, teamId));
    }

    @Override
    public void requireActiveTeam(UUID teamId) {
        try {
            if (!getTeam(teamId).archived()) {
                return;
            }
        } catch (NotFoundException unknown) {
            // same answer as for an archived team
        }
        throw new BusinessRuleException("Team " + teamId + " does not exist or is archived");
    }

    /**
     * Body of {@code GET /internal/users/{user}/id}.
     */
    record UserId(UUID id) {
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
     * 401 and 404 become the same exceptions as in organization-service; anything else means it cannot answer.
     */
    private <T> T get(String uri, Class<T> type, Object... variables) {
        try {
            return organization.get().uri(uri, variables).retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNAUTHORIZED.value(), (request, response) -> {
                        throw new UnauthenticatedException(detail(response));
                    })
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
