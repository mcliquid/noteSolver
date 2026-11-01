package org.openstreetmap.josm.plugins.notesolver;

import java.util.function.Consumer;

import org.openstreetmap.josm.data.notes.Note;

/**
 * Runs auto-close note uploads after a confirmed changeset, with per-note in-flight guards
 * and remember-list updates only after {@link AutoCloseOutcome#CONFIRMED}.
 */
public final class AutoCloseOrchestrator {

	public interface RememberList {
		boolean contains(Note note);

		void remove(Note note);
	}

	public interface Callbacks {
		boolean isManageUploadInFlight(long noteId);

		/** {@code true} when the note is open locally and online (same rules as the plugin). */
		boolean isCloseable(Note note);

		String noteComment(int changesetId);

		void onFailedUi(long noteId, String detail);

		void onAlreadyClosedUi(long noteId);

		void refreshUi();
	}

	private final AutoCloseFlightTracker flight = new AutoCloseFlightTracker();

	public AutoCloseOrchestrator() {
	}

	AutoCloseFlightTracker flightTrackerForTests() {
		return flight;
	}

	public boolean isAutoCloseInFlight(long noteId) {
		return flight.isInFlight(noteId);
	}

	public boolean hasAnyAutoCloseInFlight() {
		return flight.hasAnyInFlight();
	}

	public int autoCloseUploadsInFlight() {
		return flight.inFlightCount();
	}

	/**
	 * Schedules async close uploads for each note in {@code toClose}. Does not remove remembered notes;
	 * {@link Callbacks} / remember handler runs on completion.
	 */
	public void closePendingNotes(Iterable<Note> toClose, int changesetId, String correlationId,
			NoteManageService.NoteApi api, NoteAutoCloseUpload.AsyncRunner runner, RememberList remembered,
			Callbacks callbacks, Consumer<NoteAutoCloseUpload.AutoCloseUploadResult> afterEach) {
		for (Note note : toClose) {
			closeOne(note, changesetId, correlationId, api, runner, remembered, callbacks, afterEach);
		}
	}

	void closeOne(Note note, int changesetId, String correlationId, NoteManageService.NoteApi api,
			NoteAutoCloseUpload.AsyncRunner runner, RememberList remembered, Callbacks callbacks,
			Consumer<NoteAutoCloseUpload.AutoCloseUploadResult> afterEach) {
		if (note == null) {
			return;
		}
		long noteId = note.getId();
		AutoCloseDiagnostics.noteUpload(noteId, changesetId, correlationId, AutoCloseOutcome.REQUESTED, null);

		if (callbacks.isManageUploadInFlight(noteId)) {
			AutoCloseDiagnostics.skipped(noteId, correlationId, "manageNoteInFlight");
			AutoCloseDiagnostics.rememberRetained(noteId, changesetId, correlationId, AutoCloseOutcome.CANCELLED,
					"manageNoteInFlight");
			return;
		}
		if (!flight.tryReserve(noteId, correlationId, changesetId)) {
			AutoCloseDiagnostics.skipped(noteId, correlationId, "autoCloseAlreadyInFlight");
			AutoCloseDiagnostics.rememberRetained(noteId, changesetId, correlationId, AutoCloseOutcome.UNKNOWN,
					"autoCloseAlreadyInFlight");
			return;
		}

		if (!callbacks.isCloseable(note)) {
			flight.release(noteId);
			AutoCloseDiagnostics.skipped(noteId, correlationId, "alreadyClosedOnlineOrLocal");
			AutoCloseDiagnostics.rememberRetained(noteId, changesetId, correlationId, AutoCloseOutcome.UNKNOWN,
					"alreadyClosedOnlineOrLocal");
			callbacks.onAlreadyClosedUi(noteId);
			return;
		}

		final String comment;
		try {
			comment = callbacks.noteComment(changesetId);
		} catch (RuntimeException e) {
			flight.release(noteId);
			String detail = LogSanitizer.safeDetail(e);
			AutoCloseDiagnostics.noteUpload(noteId, changesetId, correlationId, AutoCloseOutcome.FAILED, detail);
			AutoCloseDiagnostics.rememberRetained(noteId, changesetId, correlationId, AutoCloseOutcome.FAILED, detail);
			return;
		}

		AutoCloseDiagnostics.noteUpload(noteId, changesetId, correlationId, AutoCloseOutcome.STARTED, null);
		final Note noteRef = note;
		try {
			NoteAutoCloseUpload.uploadCloseAsync(noteRef, comment, api, runner, result -> {
				flight.release(noteId);
				finishUpload(noteRef, changesetId, correlationId, remembered, callbacks, result, afterEach);
			});
		} catch (RuntimeException e) {
			flight.release(noteId);
			String detail = LogSanitizer.safeDetail(e);
			AutoCloseDiagnostics.noteUpload(noteId, changesetId, correlationId, AutoCloseOutcome.FAILED, detail);
			AutoCloseDiagnostics.rememberRetained(noteId, changesetId, correlationId, AutoCloseOutcome.FAILED, detail);
		}
	}

	private void finishUpload(Note note, int changesetId, String correlationId, RememberList remembered,
			Callbacks callbacks, NoteAutoCloseUpload.AutoCloseUploadResult result,
			Consumer<NoteAutoCloseUpload.AutoCloseUploadResult> afterEach) {
		AutoCloseDiagnostics.noteUpload(note.getId(), changesetId, correlationId, result.outcome, result.detail);
		if (result.outcome == AutoCloseOutcome.CONFIRMED) {
			if (remembered != null && remembered.contains(note)) {
				remembered.remove(note);
				AutoCloseDiagnostics.rememberRemoved(note.getId(), changesetId, correlationId);
			}
			callbacks.refreshUi();
		} else {
			AutoCloseDiagnostics.rememberRetained(note.getId(), changesetId, correlationId, result.outcome,
					result.detail);
			if (result.outcome == AutoCloseOutcome.FAILED) {
				callbacks.onFailedUi(note.getId(), result.detail);
			}
		}
		if (afterEach != null) {
			afterEach.accept(result);
		}
	}

	/** Plugin shutdown: release reservations without treating uploads as confirmed. */
	public void onPluginDestroy() {
		for (AutoCloseFlightTracker.Flight f : flight.drainAll()) {
			AutoCloseDiagnostics.noteUpload(f.noteId, f.changesetId, f.correlationId, AutoCloseOutcome.CANCELLED,
					"pluginDestroy");
			AutoCloseDiagnostics.rememberRetained(f.noteId, f.changesetId, f.correlationId, AutoCloseOutcome.CANCELLED,
					"pluginDestroy");
		}
	}

	void resetForTests() {
		flight.resetForTests();
	}
}
