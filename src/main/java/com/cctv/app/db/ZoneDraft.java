package com.cctv.app.db;

/**
 * An editable zone rectangle (normalized 0..1) as the editor holds it before it has a database id.
 * Validation lives in {@link ZoneStore.Zone}; this is the raw transport into the single writer.
 */
public record ZoneDraft(String name, double x, double y, double w, double h, boolean active) {
}
