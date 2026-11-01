package org.openstreetmap.josm.plugins.notesolver;

/**
 * Server / upload lifecycle for auto-close note uploads.
 * STARTED must not be treated as success without CONFIRMED.
 */
public enum AutoCloseOutcome {
	REQUESTED,
	STARTED,
	CONFIRMED,
	FAILED,
	CANCELLED,
	UNKNOWN
}
