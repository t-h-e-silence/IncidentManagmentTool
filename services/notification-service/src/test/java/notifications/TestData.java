package notifications;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import notifications.model.Recipient;
import notifications.model.Team;

/**
 * Ids and people used by unit tests; the same ids as the demo-data migrations of the other services.
 */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static final UUID PLATFORM = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final UUID DATABASE = UUID.fromString("10000000-0000-0000-0000-000000000002");

    public static final Recipient ADA = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000001"),
            "Ada Admin", "ada.admin@example.com");
    public static final Recipient ALICE = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000002"),
            "Alice Lead", "alice@example.com");
    public static final Recipient BOB = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000003"),
            "Bob Reporter", "bob@example.com");
    public static final Recipient DAN = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000005"),
            "Dan Dba", "dan@example.com");

    /** Alice and Dan, as organization-service answers it. */
    public static final Team DATABASE_TEAM = new Team(DATABASE, "Database", false, List.of(
            new Team.Member(ALICE.userId(), ALICE.name(), ALICE.email()),
            new Team.Member(DAN.userId(), DAN.name(), DAN.email())));

    private TestData() {
    }
}
