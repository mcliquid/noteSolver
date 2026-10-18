package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

/**
 * End-to-end path: override template → {@link CommentTemplate#formatChangesetComment}
 * → strip → {@link ChangeSetNoteTags#mergeCommentWithNotes} (255-char limit, #33).
 */
class ChangesetCommentTemplateIntegrationTest {

	private static final String NOTE_URL_PREFIX = "https://www.openstreetmap.org/note/";

	private static List<Long> noteIds(int count, long startId) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(startId + i);
		}
		return ids;
	}

	private static String noteUrl(long id) {
		return NOTE_URL_PREFIX + id;
	}

	private static String formatLine(String template, long noteId) {
		return CommentTemplate.formatChangesetComment(template, noteId, noteUrl(noteId));
	}

	private static String buildUploadComment(String manual, String template, List<Long> remembered, List<Long> solved) {
		return ChangeSetNoteTags.prepareUploadCommentTag(
				manual,
				remembered,
				solved,
				noteId -> formatLine(template, noteId));
	}

	private static void assertWithinLimit(String tagValue) {
		assertTrue(tagValue.length() <= ChangeSetNoteTags.maxTagValueLength(),
				"length " + tagValue.length() + " exceeds " + ChangeSetNoteTags.maxTagValueLength());
	}

	@Test
	void thirtyNotes_multilineTemplateWithPlaceholders_respectsLimitAndManualPrefix() {
		String template = "Batch close\\nnote ##noteSolver:NID##\\n##noteSolver:LN##";
		List<Long> ids = noteIds(30, 1_000_000L);
		String manual = "Field survey north";

		String comment = buildUploadComment(manual, template, ids, ids);

		assertWithinLimit(comment);
		assertTrue(comment.startsWith(manual));
		assertTrue(comment.contains("note/1000000") || comment.contains("30 notes"));
	}

	@Test
	void stripsExpandedMultilineTemplateBeforeMerge_manualUnchanged() {
		long noteId = 42L;
		String template = "Done\\n##noteSolver:NID##";
		String expanded = formatLine(template, noteId);
		String manual = "Mapper lead-in; " + expanded + "; tail";

		String comment = buildUploadComment(
				manual,
				template,
				List.of(noteId),
				List.of(noteId));

		assertEquals("Mapper lead-in; tail; Closes note/42", comment);
		assertWithinLimit(comment);
	}

	@Test
	void backslashNInTemplate_expandedOnce_notDoubleNewline() {
		String template = "path\\\\n##noteSolver:NID##";
		String expanded = formatLine(template, 7L);
		assertEquals("path\\n7", expanded);
		assertFalse(expanded.contains("\n"));
	}

	@Test
	void expandedTemplateLengthCountedInMerge_notRawEscapeChars() {
		String template = "x".repeat(200) + "\\n##noteSolver:NID##";
		List<Long> ids = noteIds(30, 100L);
		String manual = "m";

		String comment = buildUploadComment(manual, template, ids, ids);

		assertWithinLimit(comment);
		assertTrue(comment.startsWith("m"));
		assertTrue(formatLine(template, 100L).contains("\n"));
	}

	@Test
	void manualAt255Chars_notTruncatedWithThirtyNotes() {
		String manual = "u".repeat(ChangeSetNoteTags.maxTagValueLength());
		List<Long> ids = noteIds(30, 1L);
		String comment = buildUploadComment(manual, "Closes\\n##noteSolver:LN##", ids, ids);
		assertEquals(manual, comment);
	}

	@Test
	void hundredRememberedNotes_samePipelineAsPlugin() {
		List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
		String comment = buildUploadComment("; mapper text", "Line\\n##noteSolver:NID##", ids, ids);
		assertWithinLimit(comment);
		assertTrue(comment.contains("mapper text"));
	}
}
