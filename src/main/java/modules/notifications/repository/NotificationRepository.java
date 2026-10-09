package modules.notifications.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import modules.notifications.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Due notifications, locked for this transaction. Rows locked by another running job are skipped,
     * so several application instances never send the same email at the same time.
     */
    @Query(value = """
            select * from notifications.notification
            where status in ('PENDING', 'RETRYING') and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Notification> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
