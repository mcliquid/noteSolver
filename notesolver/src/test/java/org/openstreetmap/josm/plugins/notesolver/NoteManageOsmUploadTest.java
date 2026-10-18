package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.notes.Note.State;
import org.openstreetmap.josm.data.notes.NoteComment;
import org.openstreetmap.josm.data.osm.NoteData;
import org.openstreetmap.josm.gui.util.GuiHelper;

class NoteManageOsmUploadTest {

	private Note layerNote;
	private NoteData data;

	@BeforeEach
	void setUp() {
		NoteManageService.resetBusyForTests();
		layerNote = new Note(new LatLon(1.0, 2.0));
		layerNote.setId(99L);
		layerNote.setState(State.OPEN);
		data = new NoteData(Collections.singleton(layerNote));
	}

	@Test
	void successfulMergeUpdatesLayerNoteAndNotifies() {
		Note server = copyAsClosed(layerNote, "done");
		NoteManageOsmUpload.MergeResult result = NoteManageOsmUpload.mergeInto(data, 99L, server);
		assertEquals(NoteManageOsmUpload.MergeResult.MERGED, result);
		assertEquals(State.CLOSED, layerNote.getState());
		assertSame(layerNote, NoteManageOsmUpload.findById(data, 99L));
		assertFalse(NoteManageOsmUpload.hasUnuploadedComments(layerNote));
	}

	@Test
	void mergeAfterLayerGoneIsNoLayer() {
		// No JOSM map/note layer in unit tests → treat as removed layer.
		assertEquals(NoteManageOsmUpload.MergeResult.NO_LAYER,
				NoteManageOsmUpload.mergeFromServer(99L, copyAsClosed(layerNote, "x")));
	}

	@Test
	void mergeIntoNullDataIsMissing() {
		assertEquals(NoteManageOsmUpload.MergeResult.NOTE_MISSING,
				NoteManageOsmUpload.mergeInto(null, 99L, copyAsClosed(layerNote, "x")));
	}

	@Test
	void mergeSkipsWhenLocalUnuploadedCommentsPresent() {
		layerNote.addComment(new NoteComment(
				Instant.now(), null, "draft local", NoteComment.Action.COMMENTED, true));
		assertTrue(NoteManageOsmUpload.hasUnuploadedComments(layerNote));

		Note server = copyAsClosed(layerNote, "server");
		NoteManageOsmUpload.MergeResult result = NoteManageOsmUpload.mergeInto(data, 99L, server);
		assertEquals(NoteManageOsmUpload.MergeResult.SKIPPED_PENDING_LOCAL, result);
		assertEquals(State.OPEN, layerNote.getState());
		assertTrue(NoteManageOsmUpload.hasUnuploadedComments(layerNote));
	}

	@Test
	void missingNoteIdYieldsNoteMissing() {
		Note server = copyAsClosed(layerNote, "x");
		assertEquals(NoteManageOsmUpload.MergeResult.NOTE_MISSING,
				NoteManageOsmUpload.mergeInto(data, 12345L, server));
	}

	@Test
	void manageAndAutoCloseCoordination_sameId() throws Exception {
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		AtomicReference<NoteManageService.Outcome> done = new AtomicReference<>();

		Thread manage = new Thread(() -> NoteManageService.start(
				layerNote, "#fixed", true, false,
				(n, c, close) -> {
					entered.countDown();
					hold.await();
				},
				done::set,
				Runnable::run));
		manage.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		assertTrue(NoteManageService.isInFlight(99L));
		// Auto-close path must skip this id while manage is reserved.
		assertTrue(NoteManageService.isInFlight(99L));
		assertFalse(NoteManageService.isInFlight(100L));

		hold.countDown();
		manage.join(2000);
		assertFalse(NoteManageService.isInFlight());
		assertEquals(NoteManageService.Kind.OK, done.get().kind);
	}

	@Test
	void exceptionBeforeReservationLeavesIdle() {
		NoteManageService.Outcome out = NoteManageService.start(
				layerNote, "  ", true, false,
				(n, c, close) -> {
					throw new AssertionError("must not run");
				},
				o -> {
					throw new AssertionError("must not complete");
				},
				Runnable::run);
		assertEquals(NoteManageService.Kind.EMPTY, out.kind);
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void exceptionAfterReservationReleasesBusy() {
		AtomicReference<NoteManageService.Outcome> done = new AtomicReference<>();
		NoteManageService.start(
				layerNote, "x", true, false,
				(n, c, close) -> {
					throw new RuntimeException("boom");
				},
				done::set,
				Runnable::run);
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertFalse(NoteManageService.isInFlight());
		assertFalse(NoteManageService.isInFlight(99L));
	}

	@Test
	void cancelReleasesBusy() {
		AtomicReference<NoteManageService.Outcome> done = new AtomicReference<>();
		NoteManageService.start(
				layerNote, "x", false, false,
				(n, c, close) -> {
					throw new java.util.concurrent.CancellationException("stop");
				},
				done::set,
				Runnable::run);
		assertEquals(NoteManageService.Kind.CANCELLED, done.get().kind);
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void runnerRejectReleasesReservation() {
		AtomicReference<NoteManageService.Outcome> done = new AtomicReference<>();
		NoteManageService.Outcome out = NoteManageService.start(
				layerNote, "x", true, false,
				(n, c, close) -> {
					throw new AssertionError("api must not run");
				},
				done::set,
				task -> {
					throw new IllegalStateException("executor full");
				});
		assertEquals(NoteManageService.Kind.ERROR, out.kind);
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void completionCallbackCanBeMarshalledToEdt() throws Exception {
		AtomicBoolean ranOnEdt = new AtomicBoolean(false);
		CountDownLatch done = new CountDownLatch(1);
		NoteManageService.start(
				layerNote, "ok", false, false,
				(n, c, close) -> { },
				outcome -> GuiHelper.runInEDT(() -> {
					ranOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
					done.countDown();
				}),
				task -> new Thread(task, "note-manage-test").start());
		assertTrue(done.await(3, TimeUnit.SECONDS));
		assertTrue(ranOnEdt.get());
	}

	private static Note copyAsClosed(Note source, String text) {
		Note server = new Note(source.getLatLon());
		server.setId(source.getId());
		server.setState(State.CLOSED);
		server.addComment(new NoteComment(
				Instant.now(), null, text, NoteComment.Action.CLOSED, false));
		return server;
	}
}
