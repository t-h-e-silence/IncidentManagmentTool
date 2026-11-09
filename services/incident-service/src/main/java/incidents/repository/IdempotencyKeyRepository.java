package incidents.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency keys of the public API (ADR D9). Plain SQL, because claiming a key relies on Postgres'
 * {@code insert … on conflict do nothing}, which waits for a concurrent request with the same key to finish.
 */
@Repository
public class IdempotencyKeyRepository {

    /**
     * @param requestHash hash of the request that used the key
     * @param response    stored JSON response, or null while (or if) the request has not completed
     */
    public record StoredKey(String requestHash, String response) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if this request now owns the key; false if it was already used
     */
    public boolean claim(String key, UUID actorId, String requestHash, Instant now) {
        return jdbc.update("""
                insert into incidents.idempotency_key (key, actor_id, request_hash, created_at)
                values (?, ?, ?, ?)
                on conflict do nothing""", key, actorId, requestHash, Timestamp.from(now)) == 1;
    }

    public Optional<StoredKey> find(String key, UUID actorId) {
        List<StoredKey> rows = jdbc.query(
                "select request_hash, response from incidents.idempotency_key where key = ? and actor_id = ?",
                (rs, row) -> new StoredKey(rs.getString("request_hash"), rs.getString("response")), key, actorId);
        return rows.stream().findFirst();
    }

    public void storeResponse(String key, UUID actorId, String response) {
        jdbc.update("update incidents.idempotency_key set response = ? where key = ? and actor_id = ?",
                response, key, actorId);
    }

    public int deleteCreatedBefore(Instant before) {
        return jdbc.update("delete from incidents.idempotency_key where created_at < ?", Timestamp.from(before));
    }
}
