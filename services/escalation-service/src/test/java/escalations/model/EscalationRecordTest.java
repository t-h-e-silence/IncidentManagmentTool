package escalations.model;

import static escalations.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EscalationRecordTest {

    private final UUID incidentId = UUID.randomUUID();

    @Test
    void directionAndHandOverFollowFromTheChange() {
        EscalationRecord up = new EscalationRecord(incidentId, "DB down", DAN.userId(), "Dan Dba", "lag",
                Severity.SEV3, Severity.SEV1, DATABASE, "Database", PLATFORM, "Platform");

        assertThat(up.direction()).isEqualTo(EscalationDirection.UP);
        assertThat(up.handedOver()).isTrue();
    }

    @Test
    void severityMustChange() {
        assertThatThrownBy(() -> new EscalationRecord(incidentId, "DB down", DAN.userId(), "Dan", "why",
                Severity.SEV2, Severity.SEV2, DATABASE, "Database", DATABASE, "Database"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> new EscalationRecord(incidentId, "DB down", DAN.userId(), "Dan", " ",
                Severity.SEV3, Severity.SEV1, DATABASE, "Database", DATABASE, "Database"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
