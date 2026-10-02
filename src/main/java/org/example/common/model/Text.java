package org.example.common.model;

/**
 * Small text checks used by entities.
 */
public final class Text {

    private Text() {
    }

    /**
     * @return the stripped value
     * @throws IllegalArgumentException if the value is blank or longer than {@code maxLength}
     */
    public static String require(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return stripped;
    }
}
