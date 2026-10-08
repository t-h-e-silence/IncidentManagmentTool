package org.example.controller.model;

/**
 * Body of {@code POST /incidents/{id}/comments}.
 */
public record AddCommentCommand(String text) {
}
