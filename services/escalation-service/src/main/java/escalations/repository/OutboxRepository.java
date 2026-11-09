package escalations.repository;

import java.time.Instant;
import java.util.List;

import escalations.model.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * The oldest unpublished messages, locked; rows another relay instance holds are skipped.
     */
    @Query(value = """
            select * from escalations.outbox
            where published_at is null
            order by id
            limit :limit
            for update skip locked""", nativeQuery = true)
    List<OutboxMessage> lockUnpublished(@Param("limit") int limit);

    @Modifying
    @Query("delete from OutboxMessage m where m.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
