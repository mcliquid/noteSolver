package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoteClosePendingTest {

	private NoteClosePending pending;

	@BeforeEach
	void setUp() {
		pending = new NoteClosePending();
	}

	@Test
	void happyPathClosesOnceWithBoundId() {
		pending.wantClose(Arrays.asList(1L, 2L));
		assertTrue(pending.arm());
		assertTrue(pending.bindFromUploadedChangesetIds(Collections.singletonList(42)));
		assertEquals(Integer.valueOf(42), pending.matchCacheEvent(42, true));
		pending.markClosed(42);
		assertNull(pending.matchCacheEvent(42, true));
		assertEquals(NoteClosePending.Phase.IDLE, pending.phase());
	}

	@Test
	void cacheAloneNeverCloses() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertNull(pending.matchCacheEvent(99, true));
		assertNull(pending.matchCacheEvent(0, true));
	}

	@Test
	void abortBeforeLateHookLeavesNoClose() {
		pending.wantClose(Collections.singletonList(1L));
		pending.resetIncomplete();
		assertFalse(pending.arm());
		assertFalse(pending.bindFromUploadedChangesetIds(Collections.singletonList(7)));
		assertNull(pending.matchCacheEvent(7, true));
	}

	@Test
	void declineClearsWant() {
		pending.wantClose(Collections.singletonList(1L));
		pending.decline();
		assertFalse(pending.arm());
	}

	@Test
	void emptyUploadDoesNotBind() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertFalse(pending.bindFromUploadedChangesetIds(Collections.emptyList()));
		assertNull(pending.matchCacheEvent(1, true));
	}

	@Test
	void mixedChangesetIdsDoNotBind() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertFalse(pending.bindFromUploadedChangesetIds(Arrays.asList(10, 11)));
		assertNull(pending.matchCacheEvent(10, true));
	}

	@Test
	void wrongCacheIdIgnored() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(42));
		assertNull(pending.matchCacheEvent(43, true));
		assertEquals(Integer.valueOf(42), pending.matchCacheEvent(42, true));
	}

	@Test
	void duplicateCacheEventsDeduped() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(42));
		assertEquals(Integer.valueOf(42), pending.matchCacheEvent(42, true));
		pending.markClosed(42);
		assertNull(pending.matchCacheEvent(42, true));
	}

	@Test
	void laterUploadDoesNotReuseOldBinding() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(42));
		pending.markClosed(42);

		pending.wantClose(Collections.singletonList(2L));
		pending.arm();
		assertNull(pending.matchCacheEvent(42, true));
		pending.bindFromUploadedChangesetIds(Collections.singletonList(100));
		assertEquals(Integer.valueOf(100), pending.matchCacheEvent(100, true));
	}

	@Test
	void openChangesetStillMatches() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertTrue(pending.bindFromUploadedChangesetIds(Collections.singletonList(55)));
		assertEquals(Integer.valueOf(55), pending.matchCacheEvent(55, true));
	}

	@Test
	void partialUploadWhileLayerDirtyDoesNotClose() {
		// OsmServerWriter runs postprocessors in finally even after errors;
		// remaining dirty primitives keep requiresUploadToServer=true.
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertTrue(pending.bindFromUploadedChangesetIds(Collections.singletonList(42)));
		assertNull(pending.matchCacheEvent(42, false));
		assertEquals(NoteClosePending.Phase.BOUND, pending.phase());
	}

	@Test
	void afterPartialFailureRetryNeedsNewArmAndBind() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(42));
		assertNull(pending.matchCacheEvent(42, false));
		pending.resetIncomplete();

		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(99));
		assertEquals(Integer.valueOf(99), pending.matchCacheEvent(99, true));
	}

	@Test
	void delayedCacheEventAfterCleanLayerCloses() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		pending.bindFromUploadedChangesetIds(Collections.singletonList(42));
		// first cache update may arrive before cleanup (layer still dirty)
		assertNull(pending.matchCacheEvent(42, false));
		// cleanupAfterUpload then cache update again
		assertEquals(Integer.valueOf(42), pending.matchCacheEvent(42, true));
	}

	@Test
	void multiChangesetMixedIdsRefuseBind() {
		pending.wantClose(Arrays.asList(1L, 2L));
		pending.arm();
		assertFalse(pending.bindFromUploadedChangesetIds(Arrays.asList(100, 100, 101)));
		assertNull(pending.matchCacheEvent(100, true));
	}

	@Test
	void chunkedUploadSameIdBinds() {
		pending.wantClose(Collections.singletonList(1L));
		pending.arm();
		assertTrue(pending.bindFromUploadedChangesetIds(Arrays.asList(77, 77, 77)));
		assertEquals(Integer.valueOf(77), pending.matchCacheEvent(77, true));
	}
}
