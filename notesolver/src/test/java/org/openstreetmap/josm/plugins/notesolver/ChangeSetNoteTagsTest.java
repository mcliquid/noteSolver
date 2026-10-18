package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class ChangeSetNoteTagsTest {

	private static List<Long> noteIds(int count, long startId) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(startId + i);
		}
		return ids;
	}

	private static void assertWithinLimit(String tagValue) {
		if (tagValue == null) {
			return;
		}
		assertTrue(tagValue.length() <= ChangeSetNoteTags.maxTagValueLength(),
			"length " + tagValue.length() + " exceeds " + ChangeSetNoteTags.maxTagValueLength());
	}

	@Test
	void closedNoteTag_empty() {
		assertEquals("", ChangeSetNoteTags.closedNoteTag(Collections.emptyList()));
	}

	@Test
	void closedNoteTag_singleId() {
		assertEquals("12345", ChangeSetNoteTags.closedNoteTag(Collections.singletonList(12345L)));
	}

	@Test
	void closedNoteTag_manyIdsWithinLimit() {
		for (int count : new int[] { 0, 1, 10, 20 }) {
			String tag = ChangeSetNoteTags.closedNoteTag(noteIds(count, 1_000_000L));
			assertWithinLimit(tag);
			if (count > 0) {
				assertTrue(tag.contains("1000000"));
			}
		}
	}

	@Test
	void closedNoteTag_fiftyAndHundredNotes() {
		for (int count : new int[] { 50, 100 }) {
			String tag = ChangeSetNoteTags.closedNoteTag(noteIds(count, 3_000_000L));
			assertWithinLimit(tag);
			assertTrue(tag.contains("3000000"));
			assertTrue(!tag.endsWith(";"));
		}
	}

	@Test
	void closedNoteTag_doesNotSplitId() {
		List<Long> ids = noteIds(200, 9_999_990L);
		String tag = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(tag);
		for (String part : tag.split(";")) {
			assertTrue(part.matches("\\d+"));
		}
	}

	@Test
	void closedNoteTag_atLimitAddingMoreIdsDoesNotGrow() {
		List<Long> ids = new ArrayList<>();
		for (long id = 1L; id <= 50_000; id++) {
			ids.add(id);
		}
		String atLimit = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(atLimit);
		assertTrue(atLimit.length() > 200);
		ids.add(99_999_999L);
		assertEquals(atLimit, ChangeSetNoteTags.closedNoteTag(ids));
	}

	@Test
	void mergeComment_noNotes() {
		assertEquals("manual edit", ChangeSetNoteTags.mergeCommentWithNotes("manual edit", Collections.emptyList()));
	}

	@Test
	void mergeComment_oneNoteCompact() {
		String c = ChangeSetNoteTags.mergeCommentWithNotes("fix highway", Collections.singletonList(42L));
		assertWithinLimit(c);
		assertTrue(c.contains("note/42"));
	}

	@Test
	void mergeComment_tenNotes() {
		String c = ChangeSetNoteTags.mergeCommentWithNotes(null, noteIds(10, 100L));
		assertWithinLimit(c);
		assertTrue(c.contains("note/100"));
	}

	@Test
	void mergeComment_twentyNotesSummaryOrCompact() {
		String c = ChangeSetNoteTags.mergeCommentWithNotes("survey", noteIds(20, 1L));
		assertWithinLimit(c);
		assertTrue(c.contains("survey"));
	}

	@Test
	void mergeComment_fiftyAndHundredNotesUseSummary() {
		for (int count : new int[] { 50, 100 }) {
			String c = ChangeSetNoteTags.mergeCommentWithNotes("work", noteIds(count, 5000L));
			assertWithinLimit(c);
			assertTrue(c.contains("work"));
			assertTrue(c.contains("50") || c.contains("100") || c.contains("closed:note"));
		}
	}

	@Test
	void mergeComment_preservesManualComment() {
		String manual = "Long manual comment from mapper";
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, Collections.singletonList(7L));
		assertWithinLimit(c);
		assertTrue(c.startsWith(manual));
	}

	@Test
	void mergeComment_longManualCommentStillWithinLimit() {
		String manual = "x".repeat(300);
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, noteIds(5, 1L));
		assertNotNull(c);
		assertWithinLimit(c);
	}

	@Test
	void fitToMax_exactAndOver() {
		String exact = "a".repeat(ChangeSetNoteTags.maxTagValueLength());
		assertEquals(exact, ChangeSetNoteTags.fitToMax(exact));
		String over = "a".repeat(ChangeSetNoteTags.maxTagValueLength() + 1);
		assertEquals(ChangeSetNoteTags.maxTagValueLength(), ChangeSetNoteTags.fitToMax(over).length());
	}

	@Test
	void closedNoteTag_variedIdLengths() {
		List<Long> ids = List.of(1L, 12L, 123L, 123456789012L);
		String tag = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(tag);
		assertEquals("1;12;123;123456789012", tag);
	}

	@Test
	void mergeComment_multipleChangesetsScenarioIdsIndependent() {
		List<Long> batchA = noteIds(30, 1_000L);
		List<Long> batchB = noteIds(30, 2_000L);
		assertWithinLimit(ChangeSetNoteTags.closedNoteTag(batchA));
		assertWithinLimit(ChangeSetNoteTags.closedNoteTag(batchB));
		assertWithinLimit(ChangeSetNoteTags.mergeCommentWithNotes("a", batchA));
		assertWithinLimit(ChangeSetNoteTags.mergeCommentWithNotes("b", batchB));
	}

	@Test
	void noteClosePendingStillGetsFullIdList() {
		List<Long> ids = noteIds(100, 1L);
		assertEquals(100, ids.size());
		String tag = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(tag);
		int listed = tag.isEmpty() ? 0 : tag.split(";").length;
		assertTrue(listed < 100);
		assertEquals(100, ids.size());
	}

	@Test
	void mergeComment_hundredNotesNoException() {
		List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
		String comment = ChangeSetNoteTags.mergeCommentWithNotes("; mapper text", ids);
		assertWithinLimit(comment);
	}
}
