package org.example.controller.model;

import java.util.UUID;

import org.example.common.model.Severity;
import org.example.incidents.model.IncidentStatus;

/**
 * JSON bodies of the HTTP endpoints that take more than an id. Validation stays in the services and entities.
 */
public final class HttpRequests {

    private HttpRequests() {
    }

    /**
     * @param title       null keeps the current title
     * @param description null keeps the current description, blank clears it
     */
    public record UpdateIncident(String title, String description) {
    }

    public record AddComment(String text) {
    }

    public record Resolve(String note) {
    }

    /**
     * @param note required to resolve, cancel or reopen
     */
    public record ChangeStatus(IncidentStatus status, String note) {
    }

    public record ChangeSeverity(Severity severity) {
    }

    public record Reassign(UUID targetTeamId, String reason) {
    }
}
