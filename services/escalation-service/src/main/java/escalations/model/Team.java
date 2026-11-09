package escalations.model;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What this service needs of an organization-service team (tolerant reader, ADR D4).
 *
 * @param members active members only, by name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Team(UUID id, String name, boolean archived, List<Member> members) {

    public Team {
        members = members == null ? List.of() : List.copyOf(members);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(UUID userId, String name, String email) {
    }

    public List<Recipient> recipients() {
        return members.stream().map(member -> new Recipient(member.userId(), member.name(), member.email())).toList();
    }
}
