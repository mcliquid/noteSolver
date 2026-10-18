package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;

class NoteManageServiceTest {

	private Note note;
	private final List<String> calls = new ArrayList<>();
	private final AtomicReference<NoteManageService.Outcome> done = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		NoteManageService.resetBusyForTests();
		calls.clear();
		done.set(null);
		note = new Note(new LatLon(0, 0));
		note.setId(42L);
	}

	/** Runs the upload on the calling thread so onDone fires before start() returns past the runner. */
	private static final NoteManageService.AsyncRunner SYNC = Runnable::run;

	private NoteManageService.Outcome start(String comment, boolean close, boolean noteClosed,
			NoteManageService.NoteApi api) {
		return NoteManageService.start(note, comment, close, noteClosed, api, done::set, SYNC);
	}

	@Test
	void successfulCommentOnly() {
		NoteManageService.Outcome started = start("ping", false, false,
			(n, c, close) -> calls.add(close ? "close" : "comment"));
		assertEquals(NoteManageService.Kind.STARTED, started.kind);
		assertEquals(NoteManageService.Kind.OK, done.get().kind);
		assertFalse(done.get().closed);
		assertEquals(Collections.singletonList("comment"), calls);
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void failedCommentOnlyKeepsRememberPolicyFalse() {
		NoteManageService.Outcome started = start("ping", false, false,
			(n, c, close) -> {
				throw new RuntimeException("network down");
			});
		assertEquals(NoteManageService.Kind.STARTED, started.kind);
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertEquals("network down", done.get().message);
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void successfulCommentAndCloseAllowsForget() {
		start("#fixed\ndone", true, false, (n, c, close) -> calls.add("close"));
		assertEquals(NoteManageService.Kind.OK, done.get().kind);
		assertTrue(done.get().closed);
		assertTrue(NoteManageService.shouldForgetRemembered(done.get()));
	}

	@Test
	void failedCommentAndCloseDoesNotForget() {
		start("#fixed", true, false, (n, c, close) -> {
			throw new RuntimeException("403 Forbidden");
		});
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertFalse(done.get().closed);
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
	}

	@Test
	void uploadCancelledDoesNotForget() {
		start("#fixed", true, false, (n, c, close) -> {
			throw new CancellationException("user cancel");
		});
		assertEquals(NoteManageService.Kind.CANCELLED, done.get().kind);
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
	}

	@Test
	void timeoutOrNetworkErrorDoesNotForget() {
		start("#surveyme\nping", false, false, (n, c, close) -> {
			throw new java.net.SocketTimeoutException("Read timed out");
		});
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertTrue(done.get().message.contains("timed out") || done.get().message.contains("Read"));
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
	}

	@Test
	void doubleClickWhileUploadInFlightReturnsBusy() throws Exception {
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		AtomicReference<NoteManageService.Outcome> asyncDone = new AtomicReference<>();

		Thread worker = new Thread(() -> NoteManageService.start(
			note, "#fixed", true, false,
			(n, c, close) -> {
				entered.countDown();
				hold.await();
			},
			asyncDone::set,
			Runnable::run));
		worker.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		assertTrue(NoteManageService.isInFlight(42L));

		NoteManageService.Outcome busy = NoteManageService.start(
			note, "#fixed", true, false,
			(n, c, close) -> calls.add("second"),
			done::set,
			SYNC);
		assertEquals(NoteManageService.Kind.BUSY, busy.kind);
		assertTrue(calls.isEmpty());

		hold.countDown();
		worker.join(2000);
		assertEquals(NoteManageService.Kind.OK, asyncDone.get().kind);
		assertFalse(NoteManageService.isInFlight());
	}

	@Test
	void rememberedNoteSurvivesUntilServerCloseConfirmed() throws Exception {
		NoteList remembered = new NoteList();
		remembered.add(note);
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		AtomicBoolean forgottenEarly = new AtomicBoolean(false);
		AtomicReference<NoteManageService.Outcome> asyncDone = new AtomicReference<>();

		Thread worker = new Thread(() -> NoteManageService.start(
			note, "#fixed", true, false,
			(n, c, close) -> {
				entered.countDown();
				hold.await();
			},
			outcome -> {
				asyncDone.set(outcome);
				if (NoteManageService.shouldForgetRemembered(outcome) && remembered.containsNote(note)) {
					remembered.remove(note);
				}
			},
			Runnable::run));
		worker.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));

		// STARTED must not clear remember (simulates previous bug surface).
		assertTrue(remembered.containsNote(note));
		assertFalse(forgottenEarly.get());

		hold.countDown();
		worker.join(2000);
		assertEquals(NoteManageService.Kind.OK, asyncDone.get().kind);
		assertFalse(remembered.containsNote(note));
	}

	@Test
	void simultaneousAutoClosePendingUntouchedByCommentOnly() {
		NoteClosePending pending = new NoteClosePending();
		pending.wantClose(Collections.singletonList(42L));
		start("ping", false, false, (n, c, close) -> { });
		assertEquals(NoteClosePending.Phase.WANT_CLOSE, pending.phase());
		assertTrue(pending.noteIds().contains(42L));
		assertTrue(pending.arm());
	}

	@Test
	void delayedUploadConfirmsCloseOnlyAfterApplyReturns() throws Exception {
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch entered = new CountDownLatch(1);
		AtomicReference<NoteManageService.Outcome> asyncDone = new AtomicReference<>();
		AtomicInteger forgetChecks = new AtomicInteger();

		Thread worker = new Thread(() -> NoteManageService.start(
			note, "#irrelevant", true, false,
			(n, c, close) -> {
				entered.countDown();
				hold.await();
			},
			outcome -> {
				asyncDone.set(outcome);
				if (NoteManageService.shouldForgetRemembered(outcome)) {
					forgetChecks.incrementAndGet();
				}
			},
			Runnable::run));
		worker.start();
		assertTrue(entered.await(2, TimeUnit.SECONDS));
		assertEquals(0, forgetChecks.get());
		assertTrue(asyncDone.get() == null);

		hold.countDown();
		worker.join(2000);
		assertEquals(1, forgetChecks.get());
		assertTrue(asyncDone.get().closed);
	}

	@Test
	void errorAfterLocalMutationStillDoesNotForget() {
		// Fake: "local" mutation happened, then server failed — still ERROR, no forget.
		AtomicBoolean localClosed = new AtomicBoolean(false);
		start("#fixed", true, false, (n, c, close) -> {
			localClosed.set(true);
			throw new RuntimeException("server rejected");
		});
		assertTrue(localClosed.get());
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertFalse(NoteManageService.shouldForgetRemembered(done.get()));
	}

	@Test
	void retryAfterErrorSucceeds() {
		AtomicInteger attempts = new AtomicInteger();
		NoteManageService.NoteApi flaky = (n, c, close) -> {
			if (attempts.getAndIncrement() == 0) {
				throw new RuntimeException("first fail");
			}
		};

		start("#fixed", true, false, flaky);
		assertEquals(NoteManageService.Kind.ERROR, done.get().kind);
		assertFalse(NoteManageService.isInFlight());

		done.set(null);
		start("#fixed", true, false, flaky);
		assertEquals(NoteManageService.Kind.OK, done.get().kind);
		assertTrue(NoteManageService.shouldForgetRemembered(done.get()));
		assertEquals(2, attempts.get());
	}

	@Test
	void emptyRejectedWithoutApiCall() {
		NoteManageService.Outcome out = start("  ", true, false, (n, c, close) -> calls.add("x"));
		assertEquals(NoteManageService.Kind.EMPTY, out.kind);
		assertTrue(calls.isEmpty());
		assertTrue(done.get() == null);
	}

	@Test
	void alreadyClosedSkipped() {
		NoteManageService.Outcome out = start("#fixed", true, true, (n, c, close) -> calls.add("x"));
		assertEquals(NoteManageService.Kind.ALREADY_CLOSED, out.kind);
		assertTrue(calls.isEmpty());
	}

	@Test
	void startedMustNotForgetRemembered() {
		assertFalse(NoteManageService.shouldForgetRemembered(NoteManageService.Outcome.started(true)));
		assertFalse(NoteManageService.shouldForgetRemembered(NoteManageService.Outcome.ok(false)));
		assertTrue(NoteManageService.shouldForgetRemembered(NoteManageService.Outcome.ok(true)));
	}
}
