package modules.common.model;

/**
 * How bad the impact of an incident is. {@code SEV1} is the most severe. Orders the team's queue;
 * an escalation must raise it.
 */
public enum Severity {
    /** Critical: total outage or data loss. */
    SEV1,
    /** Major: important function down for many users. */
    SEV2,
    /** Minor: degraded, a workaround exists. */
    SEV3,
    /** Low: cosmetic or a single user. */
    SEV4;

    /**
     * The most severe level a USER reporter may set.
     */
    public static final Severity MAX_FOR_REPORTER = SEV2;

    public boolean isHigherThan(Severity other) {
        return ordinal() < other.ordinal();
    }
}
