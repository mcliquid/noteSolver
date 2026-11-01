package org.openstreetmap.josm.plugins.notesolver;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/** Structured auto-close events for {@link DiagnosticLog}. */
public final class AutoCloseDiagnostics {

	private AutoCloseDiagnostics() {
	}

	public static String newCorrelationId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	public static void rememberListChanged(String action, long noteId) {
		DiagnosticLog.get().info("remember noteId=" + noteId + " action=" + action);
	}

	public static void uploadDialog(String action, String correlationId, Collection<Long> noteIds) {
		DiagnosticLog.get().info("autoClose correlationId=" + correlationId + " uploadDialog=" + action
				+ " noteIds=" + formatIds(noteIds));
	}

	public static void pendingPhase(String correlationId, NoteClosePending.Phase phase, Integer changesetId) {
		String cs = changesetId == null ? "-" : changesetId.toString();
		DiagnosticLog.get().info("autoClose correlationId=" + correlationId + " pendingPhase=" + phase.name()
				+ " changesetId=" + cs);
	}

	public static void noteUpload(long noteId, int changesetId, String correlationId, AutoCloseOutcome outcome,
			String detail) {
		String level = outcome == AutoCloseOutcome.FAILED ? "ERROR" : "INFO";
		String msg = "autoClose correlationId=" + correlationId + " noteId=" + noteId + " changesetId=" + changesetId
				+ " outcome=" + outcome.name() + (detail == null || detail.isEmpty() ? "" : " detail=" + LogSanitizer.sanitize(detail));
		if ("ERROR".equals(level)) {
			DiagnosticLog.get().error(msg);
		} else {
			DiagnosticLog.get().info(msg);
		}
	}

	public static void skipped(long noteId, String correlationId, String reason) {
		DiagnosticLog.get().warn("autoClose correlationId=" + correlationId + " noteId=" + noteId + " skipped="
				+ LogSanitizer.sanitize(reason));
	}

	public static void pluginShutdown(NoteClosePending pending, int rememberedCount, int pendingCloseCount) {
		DiagnosticLog.get().warn("pluginDestroy pendingPhase=" + pending.phase().name()
				+ " pendingNoteIds=" + formatIds(pending.noteIds())
				+ " boundChangesetId=" + (pending.boundChangesetId() == null ? "-" : pending.boundChangesetId())
				+ " rememberedCount=" + rememberedCount
				+ " pendingCloseCount=" + pendingCloseCount);
	}

	private static String formatIds(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return "[]";
		}
		return ids.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
	}
}
