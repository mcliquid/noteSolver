package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoteHashtagsTest {

	@Test
	void catalogContainsSurveymeNotSurveyUnderscoreMe() {
		assertNotNull(NoteHashtags.findByTag("#surveyme"));
		assertNull(NoteHashtags.findByTag("#survey_me"));
	}

	@Test
	void closeDefaultsMatchIssueTable() {
		assertTrue(NoteHashtags.findByTag("#added").defaultClose());
		assertTrue(NoteHashtags.findByTag("#fixed").defaultClose());
		assertTrue(NoteHashtags.findByTag("#updated").defaultClose());
		assertTrue(NoteHashtags.findByTag("#already_fixed").defaultClose());
		assertTrue(NoteHashtags.findByTag("#already_updated").defaultClose());
		assertTrue(NoteHashtags.findByTag("#irrelevant").defaultClose());
		assertFalse(NoteHashtags.findByTag("#need_more_info").defaultClose());
		assertFalse(NoteHashtags.findByTag("#not_enough_info").defaultClose());
		assertFalse(NoteHashtags.findByTag("#surveyme").defaultClose());
		assertFalse(NoteHashtags.findByTag("#need_confirmation").defaultClose());
	}

	@Test
	void catalogSizeStableForExtension() {
		assertEquals(10, NoteHashtags.all().size());
	}

	@Test
	void hashtagIndependentFromAction() {
		NoteHashtag h = NoteHashtags.findByTag("#fixed");
		assertEquals("#fixed", h.tag());
		assertTrue(h.defaultClose());
		// override is a dialog concern; tag itself never forces close
		assertFalse(NoteManageComposer.effectiveClose(h, Boolean.FALSE));
	}
}
