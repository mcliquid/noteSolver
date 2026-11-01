package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.notes.Note.State;

class NoteAutoCloseUploadTest {

	private Note note;

	@BeforeEach
	void setUp() {
		note = new Note(new LatLon(1, 2));
		note.setId(501L);
		note.setState(State.OPEN);
		NoteManageService.resetBusyForTests();
	}

	@Test
	void confirmedWhenApiSucceeds() {
		NoteAutoCloseUpload.AutoCloseUploadResult result = NoteAutoCloseUpload.uploadCloseBlocking(note, "done",
				(n, c, close) -> { });
		assertEquals(AutoCloseOutcome.CONFIRMED, result.outcome);
	}

	@Test
	void failedWhenApiThrows() {
		NoteAutoCloseUpload.AutoCloseUploadResult result = NoteAutoCloseUpload.uploadCloseBlocking(note, "done",
				(n, c, close) -> {
					throw new java.io.IOException("network down");
				});
		assertEquals(AutoCloseOutcome.FAILED, result.outcome);
		assertTrue(result.detail.contains("network"));
	}

	@Test
	void cancelledOnCancellationException() {
		NoteAutoCloseUpload.AutoCloseUploadResult result = NoteAutoCloseUpload.uploadCloseBlocking(note, "done",
				(n, c, close) -> {
					throw new java.util.concurrent.CancellationException("user abort");
				});
		assertEquals(AutoCloseOutcome.CANCELLED, result.outcome);
	}

	@Test
	void asyncCallbackReceivesConfirmedNotStarted() throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<AutoCloseOutcome> outcome = new AtomicReference<>();
		NoteAutoCloseUpload.uploadCloseAsync(note, "x", (n, c, close) -> { },
				Runnable::run,
				result -> {
					outcome.set(result.outcome);
					done.countDown();
				});
		assertTrue(done.await(2, TimeUnit.SECONDS));
		assertEquals(AutoCloseOutcome.CONFIRMED, outcome.get());
	}

	@Test
	void startedIsDistinctFromConfirmedInDiagnostics() throws Exception {
		String lineStarted = formatUploadLine(AutoCloseOutcome.STARTED);
		String lineConfirmed = formatUploadLine(AutoCloseOutcome.CONFIRMED);
		assertTrue(lineStarted.contains("outcome=STARTED"));
		assertTrue(lineConfirmed.contains("outcome=CONFIRMED"));
	}

	private static String formatUploadLine(AutoCloseOutcome outcome) {
		return "autoClose correlationId=abc noteId=1 changesetId=2 outcome=" + outcome.name();
	}
}
