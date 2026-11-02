package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.notes.Note.State;

class NoteCommentTextTest {

	private static final String CS_URL = "https://www.openstreetmap.org/changeset/190717183";

	@TempDir
	Path tempDir;

	@AfterEach
	void tearDown() throws Exception {
		if (DiagnosticLog.get().getLogDirectory() != null) {
			DiagnosticLog.get().flushPendingWritesForTests();
		}
		DiagnosticLog.resetForTests();
	}

	@Test
	void freshInstallDefaultEnglishPathUsesLivePluginVersion() {
		String text = NoteCommentText.resolve(190717183, "0.8.0", "", false, CS_URL);
		assertEquals(
				"Resolved with changeset 190717183 by noteSolver_plugin/0.8.0",
				text);
		assertFalse(text.contains("0.5.0"));
	}

	@Test
	void freshInstallLocalLanguagePathUsesLivePluginVersion() {
		String text = NoteCommentText.resolve(42, "0.8.0", "", true, CS_URL);
		assertTrue(text.contains("noteSolver_plugin/0.8.0"));
		assertTrue(text.contains("42"));
		assertFalse(text.contains("0.5.0"));
		assertEquals(1, text.split("changeset", -1).length - 1);
	}

	@Test
	void positiveMigrationEnglishBakedCid050() {
		assertEquals(NoteCommentText.LegacyOverrideKind.EN_BAKED_CID,
				NoteCommentText.classifyLegacyStandardOverride(
						"Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.5.0"));
		assertEquals("", NoteCommentText.migrateLegacyOverrideValue(
				"Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.5.0"));
	}

	@Test
	void positiveMigrationEnglishBakedCidLc080() {
		String stored = "Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.8.0 (##noteSolver:LC##)";
		assertEquals(NoteCommentText.LegacyOverrideKind.EN_BAKED_CID_LC,
				NoteCommentText.classifyLegacyStandardOverride(stored));
		assertEquals("", NoteCommentText.migrateLegacyOverrideValue(stored));
	}

	@Test
	void positiveMigrationLegacyLcCid() {
		assertEquals(NoteCommentText.LegacyOverrideKind.EN_LC_CID,
				NoteCommentText.classifyLegacyStandardOverride(
						NoteCommentText.LEGACY_OVERRIDE_LC_CID));
	}

	@Test
	void positiveMigrationExpandedUrlFromPre080Runtime() {
		String stored = "Resolved with changeset https://www.openstreetmap.org/changeset/99 by noteSolver_plugin/0.5.0";
		assertEquals(NoteCommentText.LegacyOverrideKind.EN_EXPANDED_URL,
				NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void positiveMigrationGermanBakedCid() {
		String stored = "Erledigt mit Changeset ##noteSolver:CID## von noteSolver_plugin/0.5.0";
		assertEquals(NoteCommentText.LegacyOverrideKind.DE_BAKED_CID,
				NoteCommentText.classifyLegacyStandardOverride(stored));
		assertEquals("", NoteCommentText.migrateLegacyOverrideValue(stored));
	}

	@Test
	void positiveMigrationGermanBakedCidLc() {
		String stored = "Erledigt mit Changeset ##noteSolver:CID## von noteSolver_plugin/0.6.0 (##noteSolver:LC##)";
		assertEquals(NoteCommentText.LegacyOverrideKind.DE_BAKED_CID_LC,
				NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void negativePrefixBeforeHistoricalStandard() {
		String stored = "Mapper note: Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.5.0";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
		assertEquals(stored, NoteCommentText.migrateLegacyOverrideValue(stored));
	}

	@Test
	void negativeSuffixAfterHistoricalStandard() {
		String stored = "Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.5.0 — danke";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void negativeExtraLineAfterHistoricalStandard() {
		String stored = "Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/0.5.0\n#survey";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void negativeBareIdExpandedNotHistoricalRuntimeDefault() {
		String stored = "Resolved with changeset 99 by noteSolver_plugin/0.5.0";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void negativeVerPlaceholderTemplateIsCustom() {
		String stored = "Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/##noteSolver:VER##";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
		assertEquals(stored, NoteCommentText.migrateLegacyOverrideValue(stored));
	}

	@Test
	void negativeSemverAloneInProse() {
		String custom = "Surveyed on site; still open. (noteSolver_plugin/0.5.0 mention in prose)";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(custom));
		assertEquals(custom, NoteCommentText.migrateLegacyOverrideValue(custom));
	}

	@Test
	void negativeDifferentPlaceholderOrder() {
		String stored = "Resolved with changeset ##noteSolver:LC## by noteSolver_plugin/0.5.0 (##noteSolver:CID##)";
		assertNull(NoteCommentText.classifyLegacyStandardOverride(stored));
	}

	@Test
	void customTemplateWithVerPlaceholderUsesRuntimeVersion() {
		String template = "Fixed via ##noteSolver:CID## by noteSolver_plugin/##noteSolver:VER##";
		String text = NoteCommentText.resolve(7, "0.8.0", template, false, CS_URL);
		assertEquals("Fixed via 7 by noteSolver_plugin/0.8.0", text);
	}

	@Test
	void migrationLogLineHasKindOnlyWithoutOverrideText() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		NoteCommentText.recordLegacyOverrideMigration(NoteCommentText.LegacyOverrideKind.EN_BAKED_CID);
		DiagnosticLog.get().flushPendingWritesForTests();
		String log = Files.readString(DiagnosticLog.get().currentLogFile(), StandardCharsets.UTF_8);
		assertTrue(log.contains("noteCommentOverride migrated kind=EN_BAKED_CID"));
		assertFalse(log.contains("0.5.0"));
		assertFalse(log.contains("##noteSolver"));
	}

	@Test
	void autoClosePipelineUsesResolvedCommentOnApi() {
		String comment = NoteCommentText.resolve(555, "0.8.0", "", false, CS_URL);
		Note note = new Note(new LatLon(0, 0));
		note.setId(1L);
		note.setState(State.OPEN);
		final String[] seen = new String[1];
		NoteAutoCloseUpload.uploadCloseBlocking(note, comment, (n, c, close) -> seen[0] = c);
		assertEquals(comment, seen[0]);
		assertFalse(seen[0].contains("0.5.0"));
	}
}
