package incidents.model;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What this service needs of an organization-service team; its members are ignored (tolerant reader, ADR D4).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Team(UUID id, String name, boolean archived) {
}
