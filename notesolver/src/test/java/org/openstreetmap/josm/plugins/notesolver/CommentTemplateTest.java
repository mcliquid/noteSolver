package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CommentTemplateTest {

	private static final String CS_URL = "https://www.openstreetmap.org/changeset/12345";
	private static final String NOTE_URL = "https://www.openstreetmap.org/note/99";

	@Test
	void singleLineNoteComment() {
		assertEquals("Fixed in field", CommentTemplate.formatNoteComment("Fixed in field", 1, CS_URL));
	}

	@Test
	void oneEscapedNewlineInNoteComment() {
		assertEquals("Line one\nLine two", CommentTemplate.formatNoteComment("Line one\\nLine two", 1, CS_URL));
	}

	@Test
	void multipleEscapedNewlinesInNoteComment() {
		assertEquals("a\nb\nc", CommentTemplate.formatNoteComment("a\\nb\\nc", 1, CS_URL));
	}

	@Test
	void realNewlinesPreservedInNoteComment() {
		assertEquals("a\nb", CommentTemplate.formatNoteComment("a\nb", 1, CS_URL));
	}

	@Test
	void windowsLineBreaksPreservedInNoteComment() {
		assertEquals("a\r\nb", CommentTemplate.formatNoteComment("a\r\nb", 1, CS_URL));
	}

	@Test
	void escapedWindowsNewlineNormalizedInNoteComment() {
		assertEquals("a\nb", CommentTemplate.formatNoteComment("a\\r\\nb", 1, CS_URL));
	}

	@Test
	void literalBackslashesInNoteComment() {
		assertEquals("C:\\maps\\note", CommentTemplate.formatNoteComment("C:\\\\maps\\\\note", 1, CS_URL));
	}

	@Test
	void placeholdersWithNewlinesInNoteComment() {
		String template = "Resolved\\nchangeset ##noteSolver:CID##\\n##noteSolver:LC##";
		assertEquals("Resolved\nchangeset 42\n" + CS_URL,
				CommentTemplate.formatNoteComment(template, 42, CS_URL));
	}

	@Test
	void emptyNoteCommentTemplate() {
		assertEquals("", CommentTemplate.formatNoteComment("", 1, CS_URL));
	}

	@Test
	void legacyPlainTemplateUnchanged() {
		String legacy = "Resolved with changeset ##noteSolver:LC## (##noteSolver:CID##)";
		assertEquals("Resolved with changeset " + CS_URL + " (7)",
				CommentTemplate.formatNoteComment(legacy, 7, CS_URL));
	}

	@Test
	void unicodeAndSpecialCharactersInNoteComment() {
		assertEquals("Überarbeitet\nStraße 🎉",
				CommentTemplate.formatNoteComment("Überarbeitet\\nStraße 🎉", 1, CS_URL));
	}

	@Test
	void unknownEscapeSequenceLeftIntact() {
		assertEquals("ok\\qthere", CommentTemplate.formatNoteComment("ok\\qthere", 1, CS_URL));
	}

	@Test
	void changesetCommentTemplateWithNewline() {
		assertEquals("Closes\n" + NOTE_URL,
				CommentTemplate.formatChangesetComment("Closes\\n##noteSolver:LN##", 99L, NOTE_URL));
	}

	@Test
	void changesetTemplateEscapedBackslashBeforeN_notNewline() {
		assertEquals("path\\n99", CommentTemplate.formatChangesetComment("path\\\\n##noteSolver:NID##", 99L, NOTE_URL));
	}

	@Test
	void trailingBackslashPreserved() {
		assertEquals("path\\", CommentTemplate.formatNoteComment("path\\", 1, CS_URL));
	}
}
