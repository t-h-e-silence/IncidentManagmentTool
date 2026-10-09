package modules.incidents.model;

import java.util.UUID;

import modules.common.model.Severity;

/**
 * @param description optional
 */
public record ReportIncidentCommand(String title, String description, UUID categoryId, Severity severity) {
}
