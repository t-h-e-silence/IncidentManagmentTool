package incidents.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;


/**
 * @param description optional
 */
@Schema(description = "Body of POST /incidents")
public record ReportIncidentCommand(
        @Schema(description = "Short name (≤ 200)", example = "Checkout is slow", requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(description = "Free text (≤ 5000); optional, blank means none", example = "Pages take 10 s to load") String description,
        @Schema(description = "An active category; it decides the owning team. Demo data: Payments `30000000-0000-0000-0000-000000000001`, Web application `…0002`, Database `…0003`, VPN `…0004`, Network `…0005`", example = "30000000-0000-0000-0000-000000000003", requiredMode = Schema.RequiredMode.REQUIRED) UUID categoryId,
        @Schema(description = "How bad it is; a reporter may set at most SEV2 (SEV1 → 409)", example = "SEV2", requiredMode = Schema.RequiredMode.REQUIRED) Severity severity) {
}
