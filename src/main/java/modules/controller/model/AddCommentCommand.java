package modules.controller.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /incidents/{id}/comments}.
 */
public record AddCommentCommand(@Schema(description = "Comment (≤ 5000)", example = "Also affects the mobile app") String text) {
}
