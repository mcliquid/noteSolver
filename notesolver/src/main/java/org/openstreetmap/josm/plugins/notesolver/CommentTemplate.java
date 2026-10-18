package org.openstreetmap.josm.plugins.notesolver;

/** Escape sequences and placeholders for configurable comment templates. */
public final class CommentTemplate {

	private CommentTemplate() {
	}

	/**
	 * Expands {@code \n}, {@code \r\n}, and {@code \\} in stored template text.
	 * Real line breaks and unknown escape sequences are left unchanged.
	 */
	public static String expandEscapes(String template) {
		if (template == null || template.isEmpty()) {
			return template;
		}
		StringBuilder out = new StringBuilder(template.length());
		for (int i = 0; i < template.length(); i++) {
			char c = template.charAt(i);
			if (c != '\\' || i + 1 >= template.length()) {
				out.append(c);
				continue;
			}
			char next = template.charAt(i + 1);
			if (next == '\\') {
				out.append('\\');
				i++;
				continue;
			}
			if (next == 'r' && i + 3 < template.length()
					&& template.charAt(i + 2) == '\\' && template.charAt(i + 3) == 'n') {
				out.append('\n');
				i += 3;
				continue;
			}
			if (next == 'n') {
				out.append('\n');
				i++;
				continue;
			}
			out.append('\\');
			out.append(next);
			i++;
		}
		return out.toString();
	}

	public static String formatNoteComment(String template, int changesetId, String changesetUrl) {
		String retVal = expandEscapes(template);
		retVal = retVal.replace("##noteSolver:CID##", Integer.toString(changesetId));
		retVal = retVal.replace("##noteSolver:LC##", changesetUrl);
		return retVal;
	}

	/**
	 * Default auto-close note text: bare changeset id (not a URL / {@code changeset/N} path)
	 * plus the plugin version from JOSM {@code PluginInformation}.
	 */
	public static String defaultResolvedNoteComment(int changesetId, String pluginVersion) {
		String version = pluginVersion == null ? "" : pluginVersion;
		return String.format(
				"Resolved with changeset %s by noteSolver_plugin/%s",
				Integer.toString(changesetId),
				version);
	}

	/**
	 * Strips a changeset URL or {@code changeset/12345} down to the numeric id.
	 * Already-bare ids are returned unchanged.
	 */
	public static String bareChangesetId(String ref) {
		if (ref == null || ref.isEmpty()) {
			return "";
		}
		String s = ref.trim();
		int marker = s.lastIndexOf("changeset/");
		if (marker >= 0) {
			s = s.substring(marker + "changeset/".length());
		} else {
			int slash = s.lastIndexOf('/');
			if (slash >= 0 && slash + 1 < s.length()) {
				s = s.substring(slash + 1);
			}
		}
		int cut = s.indexOf('?');
		if (cut >= 0) {
			s = s.substring(0, cut);
		}
		cut = s.indexOf('#');
		if (cut >= 0) {
			s = s.substring(0, cut);
		}
		return s;
	}

	public static String formatChangesetComment(String template, long noteId, String noteUrl) {
		String retVal = expandEscapes(template);
		retVal = retVal.replace("##noteSolver:NID##", Long.toString(noteId));
		retVal = retVal.replace("##noteSolver:LN##", noteUrl);
		return retVal;
	}
}
