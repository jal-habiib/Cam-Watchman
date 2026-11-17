package com.cctv.app.db;

/**
 * Editable form of a counting line (plan 7b): no database id, so a whole camera's lines can be
 * replaced in one writer transaction the way zones are. Geometry is validated on save.
 */
public record LineDraft(String name, double x1, double y1, double x2, double y2, boolean active) {

    public LineDraft {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("line name is required");
        }
        if (Math.hypot(x2 - x1, y2 - y1) < 0.02) {
            throw new IllegalArgumentException("line is too short to be usable");
        }
    }
}
