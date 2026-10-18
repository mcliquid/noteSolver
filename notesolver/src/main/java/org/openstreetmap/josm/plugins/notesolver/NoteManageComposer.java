package org.openstreetmap.josm.plugins.notesolver;

/**
 * Pure helpers for the Manage-note dialog: preview text and default close action.
 */
public final class NoteManageComposer {

	private NoteManageComposer() {
	}

	/**
	 * Builds the final note comment from an optional hashtag and free text.
	 * Multiline free text is preserved; hashtag and body are joined with a newline.
	 */
	public static String preview(NoteHashtag hashtag, String freeText) {
		String tag = hashtag == null ? "" : hashtag.tag();
		String body = freeText == null ? "" : freeText;
		// Normalize only escaped \\n from templates; keep real newlines (#32).
		body = unescapeNewlines(body).trim();
		if (tag.isEmpty()) {
			return body;
		}
		if (body.isEmpty()) {
			return tag;
		}
		return tag + "\n" + body;
	}

	public static boolean isBlank(String preview) {
		return preview == null || preview.trim().isEmpty();
	}

	/** Default close action for a hashtag; none → comment only. */
	public static boolean defaultClose(NoteHashtag hashtag) {
		return hashtag != null && hashtag.defaultClose();
	}

	/**
	 * Effective close flag: user override wins; otherwise hashtag default.
	 * @param userOverride null = use default
	 */
	public static boolean effectiveClose(NoteHashtag hashtag, Boolean userOverride) {
		if (userOverride != null) {
			return userOverride.booleanValue();
		}
		return defaultClose(hashtag);
	}

	/** Suggested free-text when a hashtag is picked (may be empty). */
	public static String suggestedFreeText(NoteHashtag hashtag) {
		return hashtag == null ? "" : hashtag.suggestedText();
	}

	static String unescapeNewlines(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				char n = text.charAt(i + 1);
				if (n == 'n') {
					out.append('\n');
					i++;
					continue;
				}
				if (n == 'r' && i + 2 < text.length() && text.charAt(i + 2) == '\\'
						&& i + 3 < text.length() && text.charAt(i + 3) == 'n') {
					out.append('\n');
					i += 3;
					continue;
				}
				if (n == '\\') {
					out.append('\\');
					i++;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}
}
