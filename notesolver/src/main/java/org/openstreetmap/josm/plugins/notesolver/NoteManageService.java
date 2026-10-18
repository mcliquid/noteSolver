package org.openstreetmap.josm.plugins.notesolver;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.openstreetmap.josm.data.notes.Note;

/**
 * Manage-note apply without touching {@link NoteClosePending}.
 * Busy stays set until the injected upload finishes (success, error, or cancel).
 * {@code rememberedNotes} must only be cleared when {@link #shouldForgetRemembered(Outcome)} is true.
 */
public final class NoteManageService {

	public enum Kind {
		/** Validation passed; upload scheduled. Not a server success. */
		STARTED,
		OK,
		EMPTY,
		ALREADY_CLOSED,
		ERROR,
		BUSY,
		CANCELLED
	}

	public static final class Outcome {
		public final Kind kind;
		public final String message;
		/** True only when a close was requested and the server confirmed it. */
		public final boolean closed;

		Outcome(Kind kind, String message, boolean closed) {
			this.kind = kind;
			this.message = message;
			this.closed = closed;
		}

		public static Outcome started(boolean closeRequested) {
			return new Outcome(Kind.STARTED, null, closeRequested);
		}

		public static Outcome ok(boolean closedOnServer) {
			return new Outcome(Kind.OK, null, closedOnServer);
		}

		public static Outcome empty() {
			return new Outcome(Kind.EMPTY, "empty", false);
		}

		public static Outcome alreadyClosed() {
			return new Outcome(Kind.ALREADY_CLOSED, "already closed", false);
		}

		public static Outcome error(String message) {
			return new Outcome(Kind.ERROR, message, false);
		}

		public static Outcome busy() {
			return new Outcome(Kind.BUSY, "busy", false);
		}

		public static Outcome cancelled() {
			return new Outcome(Kind.CANCELLED, "cancelled", false);
		}
	}

	@FunctionalInterface
	public interface NoteApi {
		/** Blocking upload to the OSM API (or a test fake). Throw on failure/cancel. */
		void apply(Note note, String comment, boolean close) throws Exception;
	}

	@FunctionalInterface
	public interface AsyncRunner {
		void run(Runnable task);
	}

	private static final Object LOCK = new Object();
	private static boolean inFlight;
	private static Long inFlightNoteId;

	private NoteManageService() {
	}

	/** Forget remembered note only after confirmed server close — never on STARTED or local staging. */
	public static boolean shouldForgetRemembered(Outcome outcome) {
		return outcome != null && outcome.kind == Kind.OK && outcome.closed;
	}

	public static boolean isInFlight() {
		synchronized (LOCK) {
			return inFlight;
		}
	}

	public static boolean isInFlight(long noteId) {
		synchronized (LOCK) {
			return inFlight && inFlightNoteId != null && inFlightNoteId.longValue() == noteId;
		}
	}

	/**
	 * Starts an upload asynchronously. Returns {@link Kind#STARTED} immediately on accept,
	 * or a sync validation outcome. {@code onDone} receives {@link Kind#OK}/{@link Kind#ERROR}/
	 * {@link Kind#CANCELLED} when the upload finishes; busy is cleared only then.
	 */
	public static Outcome start(Note note, String comment, boolean close, boolean noteIsClosed,
			NoteApi api, Consumer<Outcome> onDone, AsyncRunner runner) {
		if (NoteManageComposer.isBlank(comment)) {
			return Outcome.empty();
		}
		if (note == null || noteIsClosed) {
			return Outcome.alreadyClosed();
		}
		synchronized (LOCK) {
			if (inFlight) {
				return Outcome.busy();
			}
			inFlight = true;
			inFlightNoteId = Long.valueOf(note.getId());
		}
		final boolean closeRequested = close;
		try {
			runner.run(() -> {
				Outcome result;
				try {
					api.apply(note, comment, closeRequested);
					result = Outcome.ok(closeRequested);
				} catch (CancellationException e) {
					result = Outcome.cancelled();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					result = Outcome.cancelled();
				} catch (Exception e) {
					String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
					result = Outcome.error(msg);
				}
				finish(result, onDone);
			});
		} catch (RuntimeException | Error e) {
			// Reservation must not stick if the async runner fails to accept the task.
			String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			finish(Outcome.error(msg), onDone);
			return Outcome.error(msg);
		}
		return Outcome.started(closeRequested);
	}

	private static void finish(Outcome result, Consumer<Outcome> onDone) {
		synchronized (LOCK) {
			inFlight = false;
			inFlightNoteId = null;
		}
		if (onDone != null) {
			onDone.accept(result);
		}
	}

	/** Test-only: reset double-submit guard. */
	static void resetBusyForTests() {
		synchronized (LOCK) {
			inFlight = false;
			inFlightNoteId = null;
		}
	}
}
