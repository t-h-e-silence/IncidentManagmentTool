package notifications.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import notifications.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Due notifications, urgent first, locked for this transaction. Rows locked by another instance are skipped, so
     * several instances of this service never send the same email at the same time.
     */
    @Query(value = """
            select * from notifications.notification
            where status in ('PENDING', 'RETRYING') and next_attempt_at <= :now
            order by case priority when 'URGENT' then 0 else 1 end, next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Notification> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * The given notifications if they are still due, locked; rows the delivery job holds right now are skipped.
     */
    @Query(value = """
            select * from notifications.notification
            where id in (:ids) and status in ('PENDING', 'RETRYING') and next_attempt_at <= :now
            for update skip locked
            """, nativeQuery = true)
    List<Notification> lockDueByIds(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
