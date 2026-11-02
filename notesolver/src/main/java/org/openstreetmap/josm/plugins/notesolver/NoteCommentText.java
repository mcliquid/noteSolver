package org.openstreetmap.josm.plugins.notesolver;

import java.util.regex.Pattern;

import org.openstreetmap.josm.spi.preferences.IPreferences;
import org.openstreetmap.josm.tools.I18n;

/**
 * Resolves the note comment used when auto-closing a note after upload, and migrates
 * stored overrides that match unmodified historical built-in default templates.
 */
public final class NoteCommentText {

	public static final String PREF_OVERRIDE = "noteSolver.overrideNoteComment";
	public static final String PREF_USE_LOCAL_LANGUAGE = "noteSolver.useLocalLanguageInNote";

	/**
	 * Documented legacy override (URL-style changeset placeholder), never the runtime English default.
	 */
	static final String LEGACY_OVERRIDE_LC_CID =
			"Resolved with changeset ##noteSolver:LC## (##noteSolver:CID##)";

	/**
	 * Matches plugin release versions only ({@code major.minor.patch} with optional suffix),
	 * not arbitrary text after {@code noteSolver_plugin/}.
	 */
	private static final String SEMVER =
			"\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z][0-9A-Za-z.-]*)?";

	/** English workaround template with baked semver (0.8.0-style placeholders). */
	private static final Pattern EN_BAKED_CID = Pattern.compile(
			"^Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/(" + SEMVER + ")$");

	private static final Pattern EN_BAKED_CID_LC = Pattern.compile(
			"^Resolved with changeset ##noteSolver:CID## by noteSolver_plugin/(" + SEMVER
					+ ") \\(##noteSolver:LC##\\)$");

	/**
	 * One-shot copy of the pre-0.8.0 runtime default ({@code getUrl} for changeset, semver from then-current plugin).
	 */
	private static final Pattern EN_EXPANDED_URL_DEFAULT = Pattern.compile(
			"^Resolved with changeset https?://\\S+/changeset/[0-9]+ by noteSolver_plugin/(" + SEMVER + ")$");

	/**
	 * German localized default ({@code de.po}) mirrored with placeholders and baked semver.
	 */
	private static final Pattern DE_BAKED_CID = Pattern.compile(
			"^Erledigt mit Changeset ##noteSolver:CID## von noteSolver_plugin/(" + SEMVER + ")$");

	private static final Pattern DE_BAKED_CID_LC = Pattern.compile(
			"^Erledigt mit Changeset ##noteSolver:CID## von noteSolver_plugin/(" + SEMVER
					+ ") \\(##noteSolver:LC##\\)$");

	enum LegacyOverrideKind {
		EN_LC_CID,
		EN_BAKED_CID,
		EN_BAKED_CID_LC,
		EN_EXPANDED_URL,
		DE_BAKED_CID,
		DE_BAKED_CID_LC
	}

	private NoteCommentText() {
	}

	/**
	 * If {@link #PREF_OVERRIDE} equals a known historical built-in template, clear it so runtime uses
	 * {@link PluginInformation#version}. Custom templates are left unchanged.
	 */
	public static void migrateLegacyOverrideIfNeeded(IPreferences pref, String currentPluginVersion) {
		if (pref == null || currentPluginVersion == null || currentPluginVersion.isEmpty()) {
			return;
		}
		String override = pref.get(PREF_OVERRIDE, "");
		LegacyOverrideKind kind = classifyLegacyStandardOverride(override);
		if (kind == null) {
			return;
		}
		pref.put(PREF_OVERRIDE, "");
		recordLegacyOverrideMigration(kind);
	}

	static void recordLegacyOverrideMigration(LegacyOverrideKind kind) {
		DiagnosticLog.get().info("noteCommentOverride migrated kind=" + kind.name());
	}

	/** Package-visible for tests: returns {@code ""} when the stored value is a known historical default. */
	static String migrateLegacyOverrideValue(String overrideTemplate) {
		if (classifyLegacyStandardOverride(overrideTemplate) != null) {
			return "";
		}
		return overrideTemplate == null ? "" : overrideTemplate;
	}

	public static boolean isIdentifiableLegacyStandardOverride(String overrideTemplate) {
		return classifyLegacyStandardOverride(overrideTemplate) != null;
	}

	static LegacyOverrideKind classifyLegacyStandardOverride(String overrideTemplate) {
		if (overrideTemplate == null) {
			return null;
		}
		String t = overrideTemplate.trim();
		if (t.isEmpty() || t.contains("\n") || t.contains("\r")) {
			return null;
		}
		if (LEGACY_OVERRIDE_LC_CID.equals(t)) {
			return LegacyOverrideKind.EN_LC_CID;
		}
		if (EN_BAKED_CID.matcher(t).matches()) {
			return LegacyOverrideKind.EN_BAKED_CID;
		}
		if (EN_BAKED_CID_LC.matcher(t).matches()) {
			return LegacyOverrideKind.EN_BAKED_CID_LC;
		}
		if (EN_EXPANDED_URL_DEFAULT.matcher(t).matches()) {
			return LegacyOverrideKind.EN_EXPANDED_URL;
		}
		if (DE_BAKED_CID.matcher(t).matches()) {
			return LegacyOverrideKind.DE_BAKED_CID;
		}
		if (DE_BAKED_CID_LC.matcher(t).matches()) {
			return LegacyOverrideKind.DE_BAKED_CID_LC;
		}
		return null;
	}

	public static String resolve(int changesetId, String pluginVersion, String overrideTemplate,
			boolean useLocalLanguageInNote, String changesetUrl) {
		String version = pluginVersion == null ? "" : pluginVersion;
		if (overrideTemplate != null && !overrideTemplate.isEmpty()) {
			return CommentTemplate.formatNoteComment(overrideTemplate, changesetId, changesetUrl, version);
		}
		if (useLocalLanguageInNote) {
			return I18n.tr("Resolved with changeset {0} by noteSolver_plugin/{1}",
					Integer.toString(changesetId), version);
		}
		return CommentTemplate.defaultResolvedNoteComment(changesetId, version);
	}
}
