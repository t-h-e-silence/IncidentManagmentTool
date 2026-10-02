package org.example.incidents.model;

import java.util.UUID;

import org.example.common.model.Severity;

/**
 * @param description optional
 */
public record ReportIncidentCommand(String title, String description, UUID categoryId, Severity severity) {
}
