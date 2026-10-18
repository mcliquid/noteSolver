package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
			assertFalse(tag.endsWith(";"));
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
	void isPartialClosedNoteList_hundredNotes() {
		List<Long> ids = noteIds(100, 1L);
		assertTrue(ChangeSetNoteTags.isPartialClosedNoteList(ids));
		assertTrue(ChangeSetNoteTags.countIdsInClosedNoteTag(ChangeSetNoteTags.closedNoteTag(ids)) < 100);
	}

	@Test
	void isPartialClosedNoteList_smallBatchComplete() {
		List<Long> ids = noteIds(5, 100L);
		assertFalse(ChangeSetNoteTags.isPartialClosedNoteList(ids));
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
		assertEquals("fix highway; Closes note/42", c);
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
	void mergeComment_hundredNotesPartialWording() {
		List<Long> ids = noteIds(100, 5000L);
		String c = ChangeSetNoteTags.mergeCommentWithNotes("work", ids);
		assertWithinLimit(c);
		assertEquals("work", c.substring(0, 4));
		assertTrue(c.contains("partial list in closed:note"));
		assertTrue(c.contains("100"));
	}

	@Test
	void mergeComment_preservesManualComment() {
		String manual = "Long manual comment from mapper";
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, Collections.singletonList(7L));
		assertWithinLimit(c);
		assertTrue(c.startsWith(manual));
	}

	@Test
	void mergeComment_manualExactly255CharsNotTruncated() {
		String manual = "m".repeat(ChangeSetNoteTags.maxTagValueLength());
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, noteIds(10, 1L));
		assertEquals(manual, c);
		assertEquals(ChangeSetNoteTags.maxTagValueLength(), c.length());
	}

	@Test
	void mergeComment_manual256CharsNotTruncated() {
		String manual = "m".repeat(ChangeSetNoteTags.maxTagValueLength() + 1);
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, noteIds(5, 1L));
		assertEquals(manual, c);
		assertEquals(ChangeSetNoteTags.maxTagValueLength() + 1, c.length());
	}

	@Test
	void mergeComment_manual255WithOneNoteOmitsAddition() {
		String manual = "x".repeat(ChangeSetNoteTags.maxTagValueLength() - 5);
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, Collections.singletonList(99L));
		assertTrue(c.equals(manual) || c.startsWith(manual));
		assertFalse(c.contains("note/99") && c.length() > ChangeSetNoteTags.maxTagValueLength());
	}

	@Test
	void truncateUtf16Safe_doesNotSplitSurrogatePair() {
		String emoji = "\uD83D\uDE00"; // 😀
		String generated = "Closes 100 notes (partial list in closed:note)" + emoji;
		String cut = ChangeSetNoteTags.truncateUtf16Safe(generated, generated.length() - 1);
		assertTrue(cut.length() < generated.length());
		assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
	}

	@Test
	void mergeComment_unicodeManualPreserved() {
		String manual = "Stra\u00dfe \uD83D\uDE00 fix";
		String c = ChangeSetNoteTags.mergeCommentWithNotes(manual, Collections.singletonList(1L));
		assertTrue(c.startsWith(manual));
	}

	@Test
	void closedNoteTag_variedIdLengths() {
		List<Long> ids = List.of(1L, 12L, 123L, 123456789012L);
		String tag = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(tag);
		assertEquals("1;12;123;123456789012", tag);
	}

	@Test
	void mergeComment_multipleUploadAttemptsSameManualBase() {
		String manual = "persistent mapper comment";
		List<Long> batch = noteIds(100, 1L);
		String first = ChangeSetNoteTags.mergeCommentWithNotes(manual, batch);
		String second = ChangeSetNoteTags.mergeCommentWithNotes(manual, batch);
		assertEquals(first, second);
		assertTrue(first.startsWith(manual));
	}

	@Test
	void noteClosePendingStillGetsFullIdList() {
		List<Long> ids = noteIds(100, 1L);
		assertEquals(100, ids.size());
		String tag = ChangeSetNoteTags.closedNoteTag(ids);
		assertWithinLimit(tag);
		int listed = ChangeSetNoteTags.countIdsInClosedNoteTag(tag);
		assertTrue(listed < 100);
		assertEquals(100, ids.size());
	}

	@Test
	void mergeComment_hundredNotesNoException() {
		List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
		String comment = ChangeSetNoteTags.mergeCommentWithNotes("; mapper text", ids);
		assertWithinLimit(comment);
		assertNotNull(comment);
	}

	@Test
	void mergeComment_cancelledUploadRetryManualUnchanged() {
		String manual = "only my words";
		assertEquals(manual, ChangeSetNoteTags.mergeCommentWithNotes(manual, Collections.emptyList()));
	}
}
