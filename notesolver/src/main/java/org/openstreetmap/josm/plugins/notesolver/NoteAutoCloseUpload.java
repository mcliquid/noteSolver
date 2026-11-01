package org.openstreetmap.josm.plugins.notesolver;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.openstreetmap.josm.data.notes.Note;
/**
 * Async note close upload for auto-close, using the same {@link org.openstreetmap.josm.io.OsmApi}
 * endpoints as {@code UploadNotesTask} but with a completion callback for diagnostics.
 * Does not share {@link NoteManageService} busy state (multi-note auto-close may run sequentially).
 */
public final class NoteAutoCloseUpload {

	@FunctionalInterface
	public interface AsyncRunner {
		void run(Runnable task);
	}

	private NoteAutoCloseUpload() {
	}

	public static void uploadCloseAsync(Note note, String comment, NoteManageService.NoteApi api, AsyncRunner runner,
			Consumer<AutoCloseUploadResult> onComplete) {
		runner.run(() -> {
			AutoCloseUploadResult result;
			try {
				api.apply(note, comment, true);
				result = AutoCloseUploadResult.confirmed();
			} catch (CancellationException e) {
				result = AutoCloseUploadResult.cancelled(LogSanitizer.safeDetail(e));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				result = AutoCloseUploadResult.cancelled(LogSanitizer.safeDetail(e));
			} catch (Exception e) {
				result = AutoCloseUploadResult.failed(LogSanitizer.safeDetail(e));
			}
			if (onComplete != null) {
				onComplete.accept(result);
			}
		});
	}

	public static final class AutoCloseUploadResult {
		public final AutoCloseOutcome outcome;
		public final String detail;

		private AutoCloseUploadResult(AutoCloseOutcome outcome, String detail) {
			this.outcome = outcome;
			this.detail = detail;
		}

		static AutoCloseUploadResult confirmed() {
			return new AutoCloseUploadResult(AutoCloseOutcome.CONFIRMED, null);
		}

		static AutoCloseUploadResult failed(String detail) {
			return new AutoCloseUploadResult(AutoCloseOutcome.FAILED, detail);
		}

		static AutoCloseUploadResult cancelled(String detail) {
			return new AutoCloseUploadResult(AutoCloseOutcome.CANCELLED, detail);
		}
	}

	/** Default API for production auto-close uploads. */
	static NoteManageService.NoteApi defaultApi() {
		return new NoteManageOsmUpload();
	}

	/** Blocking helper for tests. */
	static AutoCloseUploadResult uploadCloseBlocking(Note note, String comment, NoteManageService.NoteApi api) {
		if (api == null) {
			throw new NullPointerException("api");
		}
		try {
			api.apply(note, comment, true);
			return AutoCloseUploadResult.confirmed();
		} catch (CancellationException e) {
			return AutoCloseUploadResult.cancelled(LogSanitizer.safeDetail(e));
		} catch (Exception e) {
			return AutoCloseUploadResult.failed(LogSanitizer.safeDetail(e));
		}
	}
}
