package modules;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import modules.common.model.Actor;
import modules.common.model.Recipient;
import modules.common.model.SystemRole;

/**
 * Ids and actors used by unit tests; the same ids as the {@code seed} profile.
 */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static final UUID PLATFORM = UUID.fromString("10000000-0000-0000-0000-000000000001");
    public static final UUID DATABASE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    public static final UUID NETWORK = UUID.fromString("10000000-0000-0000-0000-000000000003");

    public static final UUID PAYMENTS = UUID.fromString("30000000-0000-0000-0000-000000000001");
    public static final UUID DATABASE_CATEGORY = UUID.fromString("30000000-0000-0000-0000-000000000003");

    public static final Actor ADA = new Actor(UUID.fromString("20000000-0000-0000-0000-000000000001"), "Ada Admin",
            SystemRole.ADMIN, Set.of());
    public static final Actor ALICE = new Actor(UUID.fromString("20000000-0000-0000-0000-000000000002"), "Alice Lead",
            SystemRole.USER, Set.of(DATABASE, NETWORK));
    public static final Actor BOB = new Actor(UUID.fromString("20000000-0000-0000-0000-000000000003"), "Bob Reporter",
            SystemRole.USER, Set.of());
    public static final Actor CAROL = new Actor(UUID.fromString("20000000-0000-0000-0000-000000000004"),
            "Carol Platform", SystemRole.USER, Set.of(PLATFORM));
    public static final Actor DAN = new Actor(UUID.fromString("20000000-0000-0000-0000-000000000005"), "Dan Dba",
            SystemRole.USER, Set.of(DATABASE));

    private TestData() {
    }

    public static Recipient recipient(Actor actor) {
        return new Recipient(actor.id(), actor.name(), actor.name().split(" ")[0].toLowerCase() + "@example.com");
    }
}
