package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoteManageComposerTest {

	@Test
	void hashtagSelectionAppearsInPreview() {
		NoteHashtag fixed = NoteHashtags.findByTag("#fixed");
		assertEquals("#fixed", NoteManageComposer.preview(fixed, ""));
	}

	@Test
	void commentPreviewJoinsHashtagAndFreeTextWithNewline() {
		NoteHashtag fixed = NoteHashtags.findByTag("#fixed");
		assertEquals("#fixed\nBuilding outline corrected",
				NoteManageComposer.preview(fixed, "Building outline corrected"));
	}

	@Test
	void defaultActionCloseForFixed() {
		assertTrue(NoteManageComposer.defaultClose(NoteHashtags.findByTag("#fixed")));
	}

	@Test
	void defaultActionOpenForSurveyme() {
		assertFalse(NoteManageComposer.defaultClose(NoteHashtags.findByTag("#surveyme")));
	}

	@Test
	void overrideDefaultActionKeepsHashtag() {
		NoteHashtag fixed = NoteHashtags.findByTag("#fixed");
		assertTrue(NoteManageComposer.defaultClose(fixed));
		assertFalse(NoteManageComposer.effectiveClose(fixed, Boolean.FALSE));
		assertEquals("#fixed\nkeep open", NoteManageComposer.preview(fixed, "keep open"));
	}

	@Test
	void freeTextWithoutHashtag() {
		assertEquals("Just a comment", NoteManageComposer.preview(null, "Just a comment"));
	}

	@Test
	void emptyInputIsBlank() {
		assertTrue(NoteManageComposer.isBlank(NoteManageComposer.preview(null, "")));
		assertTrue(NoteManageComposer.isBlank(NoteManageComposer.preview(null, "   ")));
		assertFalse(NoteManageComposer.isBlank(NoteManageComposer.preview(NoteHashtags.findByTag("#added"), "")));
	}

	@Test
	void multilineCommentsPreservedFromIssue32() {
		String free = "Line one\nLine two\nStraße 🎉";
		assertEquals("#need_more_info\n" + free,
				NoteManageComposer.preview(NoteHashtags.findByTag("#need_more_info"), free));
	}

	@Test
	void escapedNewlinesInFreeText() {
		assertEquals("#surveyme\na\nb",
				NoteManageComposer.preview(NoteHashtags.findByTag("#surveyme"), "a\\nb"));
	}

	@Test
	void suggestedTextFromHashtag() {
		NoteHashtag h = NoteHashtags.findByTag("#irrelevant");
		assertFalse(NoteManageComposer.suggestedFreeText(h).isEmpty());
	}

	@Test
	void noHashtagDefaultsToCommentOnly() {
		assertFalse(NoteManageComposer.defaultClose(null));
		assertFalse(NoteManageComposer.effectiveClose(null, null));
	}
}
