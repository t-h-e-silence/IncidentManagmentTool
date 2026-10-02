package org.example.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeverityTest {

    @Test
    void sev1IsTheMostSevere() {
        assertThat(Severity.SEV1.isHigherThan(Severity.SEV2)).isTrue();
        assertThat(Severity.SEV3.isHigherThan(Severity.SEV2)).isFalse();
        assertThat(Severity.SEV2.isHigherThan(Severity.SEV2)).isFalse();
    }
}
