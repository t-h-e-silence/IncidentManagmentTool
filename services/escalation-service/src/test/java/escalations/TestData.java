package escalations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import escalations.model.Recipient;
import escalations.model.Team;

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
    public static final Recipient CAROL = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000004"),
            "Carol Platform", "carol@example.com");
    public static final Recipient DAN = new Recipient(UUID.fromString("20000000-0000-0000-0000-000000000005"),
            "Dan Dba", "dan@example.com");

    public static Team team(UUID id, String name, Recipient... members) {
        return new Team(id, name, false, List.of(members).stream()
                .map(member -> new Team.Member(member.userId(), member.name(), member.email())).toList());
    }

    private TestData() {
    }
}
