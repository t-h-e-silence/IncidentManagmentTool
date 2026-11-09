package incidents.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import incidents.model.Text;
import incidents.repository.IdempotencyKeyRepository;
import incidents.repository.IdempotencyKeyRepository.StoredKey;
import incidents.web.exception.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    static final int KEY_MAX_LENGTH = 100;
    static final Duration KEEP = Duration.ofHours(24);

    private final IdempotencyKeyRepository keys;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyServiceImpl(IdempotencyKeyRepository keys, ObjectMapper json, Clock clock) {
        this.keys = keys;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public <T> T execute(String key, UUID actorId, String endpoint, Object request, Class<T> responseType,
                         Supplier<T> action) {
        if (key == null) {
            return action.get();
        }
        String checkedKey = Text.require(key, HEADER, KEY_MAX_LENGTH);
        String requestHash = hash(endpoint, request);
        if (keys.claim(checkedKey, actorId, requestHash, clock.instant())) {
            T response = action.get();
            keys.storeResponse(checkedKey, actorId, write(response));
            return response;
        }
        StoredKey stored = keys.find(checkedKey, actorId)
                .orElseThrow(() -> new BusinessRuleException(HEADER + " " + checkedKey + " is in use, retry"));
        if (!stored.requestHash().equals(requestHash)) {
            throw new BusinessRuleException(HEADER + " " + checkedKey + " was already used for a different request");
        }
        if (stored.response() == null) {
            throw new BusinessRuleException(HEADER + " " + checkedKey + " is in use, retry");
        }
        log.info("{} {}: repeated {} {}, returning the stored response", endpoint, actorId, HEADER, checkedKey);
        return read(stored.response(), responseType);
    }

    @Override
    @Transactional
    public int deleteExpired() {
        return keys.deleteCreatedBefore(clock.instant().minus(KEEP));
    }

    private String hash(String endpoint, Object request) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(endpoint.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
            sha256.update(json.writeValueAsBytes(request));
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot hash request of " + endpoint, e);
        }
    }

    private String write(Object response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response cannot be written as JSON", e);
        }
    }

    private <T> T read(String response, Class<T> type) {
        try {
            return json.readValue(response, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response cannot be read", e);
        }
    }
}
