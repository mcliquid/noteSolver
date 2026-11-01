package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.notes.Note.State;

class AutoCloseLifecycleTest {

	@TempDir
	java.nio.file.Path tempRoot;

	private java.nio.file.Path testLogDir;
	private AutoCloseOrchestrator orchestrator;
	private FakeRemember remembered;
	private FakeCallbacks callbacks;
	private FakeApi api;
	private final List<Thread> backgroundThreads = new CopyOnWriteArrayList<>();
	private static final String CID = "testcorr0001";
	private static final int CS = 4242;

	@BeforeEach
	void setUp() throws Exception {
		NoteManageService.resetBusyForTests();
		DiagnosticLog.resetForTests();
		testLogDir = Files.createTempDirectory(tempRoot, "log-");
		DiagnosticLog.installForTests(testLogDir, () -> false);
		orchestrator = new AutoCloseOrchestrator();
		remembered = new FakeRemember();
		callbacks = new FakeCallbacks();
		api = new FakeApi();
	}

	@AfterEach
	void tearDown() throws Exception {
		awaitBackgroundThreads();
		orchestrator.resetForTests();
		NoteManageService.resetBusyForTests();
		if (DiagnosticLog.get().getLogDirectory() != null) {
			DiagnosticLog.get().flushPendingWritesForTests();
		}
		DiagnosticLog.resetForTests();
	}

	@Test
	void oneNoteConfirmedRemovesRemember() throws Exception {
		Note n = openNote(1L);
		remembered.add(n);
		runSyncClose(n);
		assertFalse(remembered.contains(n));
		assertLogContains("outcome=CONFIRMED", "outcome=REMEMBER_REMOVED", "noteId=1");
		assertLogDoesNotContainForNote(1L, "outcome=REMEMBER_RETAINED");
	}

	@Test
	void oneNoteFailedRetainsRemember() throws Exception {
		Note n = openNote(2L);
		remembered.add(n);
		api.failWith(new java.io.IOException("network down"));
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertLogContains("outcome=FAILED", "outcome=REMEMBER_RETAINED");
		assertLogDoesNotContainForNote(2L, "outcome=REMEMBER_REMOVED");
	}

	@Test
	void multipleNotesAllConfirmed() throws Exception {
		Note a = openNote(10L);
		Note b = openNote(11L);
		remembered.add(a);
		remembered.add(b);
		orchestrator.closePendingNotes(Arrays.asList(a, b), CS, CID, api, Runnable::run, remembered, callbacks, null);
		assertFalse(remembered.contains(a));
		assertFalse(remembered.contains(b));
	}

	@Test
	void multipleNotesPartialFailure() throws Exception {
		Note ok = openNote(20L);
		Note bad = openNote(21L);
		remembered.add(ok);
		remembered.add(bad);
		api.failForNoteId(21L, new java.net.SocketTimeoutException("timeout"));
		orchestrator.closePendingNotes(Arrays.asList(ok, bad), CS, CID, api, Runnable::run, remembered, callbacks,
				null);
		assertFalse(remembered.contains(ok));
		assertTrue(remembered.contains(bad));
	}

	@Test
	void uploadCancelledRetainsRemember() throws Exception {
		Note n = openNote(3L);
		remembered.add(n);
		api.failWith(new java.util.concurrent.CancellationException("user abort"));
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertLogContains("outcome=CANCELLED", "outcome=REMEMBER_RETAINED");
	}

	@Test
	void networkTimeoutRetainsRemember() throws Exception {
		Note n = openNote(4L);
		remembered.add(n);
		api.failWith(new java.net.SocketTimeoutException("read timed out"));
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertLogContains("outcome=FAILED", "timed out");
	}

	@Test
	void manageUploadInFlightSkipsAutoClose() throws Exception {
		Note n = openNote(5L);
		remembered.add(n);
		CountDownLatch holdManage = new CountDownLatch(1);
		CountDownLatch manageFinished = new CountDownLatch(1);
		NoteManageService.start(n, "#survey", true, false, (a, b, c) -> holdManage.await(5, TimeUnit.SECONDS),
				o -> manageFinished.countDown(), task -> startBackground(task));
		assertTrue(NoteManageService.isInFlight(5L));
		callbacks.manageInFlight = noteId -> NoteManageService.isInFlight(noteId);
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertLogContains("manageNoteInFlight", "outcome=REMEMBER_RETAINED");
		holdManage.countDown();
		assertTrue(manageFinished.await(5, TimeUnit.SECONDS));
	}

	@Test
	void secondCloseWhileInFlightDoesNotDoubleStart() throws Exception {
		Note n = openNote(6L);
		remembered.add(n);
		CountDownLatch apiEntered = new CountDownLatch(1);
		CountDownLatch releaseApi = new CountDownLatch(1);
		CountDownLatch uploadFinished = new CountDownLatch(1);
		api.blockOnNote(6L, apiEntered, releaseApi, uploadFinished);
		orchestrator.closePendingNotes(Collections.singletonList(n), CS, CID, api,
				task -> startBackground(task), remembered, callbacks, null);
		assertTrue(apiEntered.await(2, TimeUnit.SECONDS));
		orchestrator.closePendingNotes(Collections.singletonList(n), CS, "other", api,
				task -> startBackground(task), remembered, callbacks, null);
		assertEquals(1, api.calls.get());
		releaseApi.countDown();
		assertTrue(uploadFinished.await(5, TimeUnit.SECONDS));
	}

	@Test
	void exceptionBeforeUploadStartRetainsRemember() throws Exception {
		Note n = openNote(7L);
		remembered.add(n);
		callbacks.throwOnComment = true;
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertLogContains("outcome=FAILED", "outcome=REMEMBER_RETAINED");
	}

	@Test
	void exceptionDuringUploadRetainsRemember() throws Exception {
		Note n = openNote(8L);
		remembered.add(n);
		api.failWith(new RuntimeException("during upload"));
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		assertTrue(callbacks.failedUi.get());
	}

	@Test
	void pluginDestroyRetainsRememberWithoutConfirmed() throws Exception {
		Note n = openNote(9L);
		remembered.add(n);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch uploadFinished = new CountDownLatch(1);
		api.blockOnNote(9L, entered, hold, uploadFinished);
		orchestrator.closePendingNotes(Collections.singletonList(n), CS, CID, api,
				task -> startBackground(task), remembered, callbacks, null);
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		orchestrator.onPluginDestroy();
		assertTrue(remembered.contains(n));
		assertLogContains("pluginDestroy", "outcome=CANCELLED");
		assertLogDoesNotContainForNote(9L, "outcome=REMEMBER_REMOVED");
		hold.countDown();
		assertTrue(uploadFinished.await(5, TimeUnit.SECONDS));
	}

	@Test
	void retryAfterFailureCanConfirmAndRemove() throws Exception {
		Note n = openNote(12L);
		remembered.add(n);
		api.failWith(new java.io.IOException("fail once"));
		runSyncClose(n);
		assertTrue(remembered.contains(n));
		api.clearFailures();
		runSyncClose(n);
		assertFalse(remembered.contains(n));
	}

	@Test
	void noRememberRemovalWithoutServerConfirmation() throws Exception {
		Note n = openNote(13L);
		remembered.add(n);
		api.failWith(new java.io.IOException("nope"));
		runSyncClose(n);
		assertEquals(1, remembered.size());
		assertLogDoesNotContainForNote(13L, "outcome=REMEMBER_REMOVED");
	}

	private void runSyncClose(Note n) {
		orchestrator.closePendingNotes(Collections.singletonList(n), CS, CID, api, Runnable::run, remembered,
				callbacks, null);
	}

	private void startBackground(Runnable task) {
		Thread t = new Thread(task, "autoclose-test-worker");
		backgroundThreads.add(t);
		t.start();
	}

	private void awaitBackgroundThreads() throws InterruptedException {
		for (Thread t : backgroundThreads) {
			t.join(5000);
		}
		backgroundThreads.clear();
	}

	private void assertLogContains(String... parts) throws Exception {
		DiagnosticLog.get().flushPendingWritesForTests();
		String text = Files.readString(DiagnosticLog.get().currentLogFile(), StandardCharsets.UTF_8);
		for (String p : parts) {
			assertTrue(text.contains(p), "missing: " + p);
		}
	}

	private void assertLogDoesNotContainForNote(long noteId, String fragment) throws Exception {
		DiagnosticLog.get().flushPendingWritesForTests();
		if (DiagnosticLog.get().currentLogFile() == null) {
			return;
		}
		String needle = "noteId=" + noteId;
		for (String line : Files.readAllLines(DiagnosticLog.get().currentLogFile(), StandardCharsets.UTF_8)) {
			if (line.contains(needle) && line.contains(fragment)) {
				fail("unexpected log line for noteId=" + noteId + ": " + line);
			}
		}
	}

	private static Note openNote(long id) {
		Note n = new Note(new LatLon(1, 2));
		n.setId(id);
		n.setState(State.OPEN);
		return n;
	}

	static final class FakeRemember implements AutoCloseOrchestrator.RememberList {
		private final List<Note> notes = new ArrayList<>();

		void add(Note n) {
			notes.add(n);
		}

		int size() {
			return notes.size();
		}

		@Override
		public boolean contains(Note note) {
			return notes.stream().anyMatch(n -> n.getId() == note.getId());
		}

		@Override
		public void remove(Note note) {
			notes.removeIf(n -> n.getId() == note.getId());
		}
	}

	static final class FakeCallbacks implements AutoCloseOrchestrator.Callbacks {
		boolean throwOnComment;
		java.util.function.LongPredicate manageInFlight = id -> false;
		final AtomicBoolean failedUi = new AtomicBoolean();

		@Override
		public boolean isManageUploadInFlight(long noteId) {
			return manageInFlight.test(noteId);
		}

		@Override
		public boolean isCloseable(Note note) {
			return note.getState() == State.OPEN;
		}

		@Override
		public String noteComment(int changesetId) {
			if (throwOnComment) {
				throw new IllegalStateException("comment build failed");
			}
			return "resolved " + changesetId;
		}

		@Override
		public void onFailedUi(long noteId, String detail) {
			failedUi.set(true);
		}

		@Override
		public void onAlreadyClosedUi(long noteId) {
			// no-op
		}

		@Override
		public void refreshUi() {
			// no-op
		}
	}

	static final class FakeApi implements NoteManageService.NoteApi {
		private final AtomicInteger calls = new AtomicInteger();
		private Exception failAll;
		private Long failNoteId;
		private Exception failNoteException;
		private Long blockNoteId;
		private CountDownLatch entered;
		private CountDownLatch release;
		private CountDownLatch finished;

		void failWith(Exception e) {
			failAll = e;
		}

		void failForNoteId(long id, Exception e) {
			failNoteId = id;
			failNoteException = e;
		}

		void clearFailures() {
			failAll = null;
			failNoteId = null;
			failNoteException = null;
		}

		void blockOnNote(long id, CountDownLatch entered, CountDownLatch release, CountDownLatch finished) {
			blockNoteId = id;
			this.entered = entered;
			this.release = release;
			this.finished = finished;
		}

		@Override
		public void apply(Note note, String comment, boolean close) throws Exception {
			calls.incrementAndGet();
			try {
				if (blockNoteId != null && note.getId() == blockNoteId) {
					entered.countDown();
					release.await(5, TimeUnit.SECONDS);
				}
				if (failNoteId != null && note.getId() == failNoteId.longValue()) {
					throw failNoteException;
				}
				if (failAll != null) {
					throw failAll;
				}
			} finally {
				if (blockNoteId != null && note.getId() == blockNoteId && finished != null) {
					finished.countDown();
				}
			}
		}
	}
}
