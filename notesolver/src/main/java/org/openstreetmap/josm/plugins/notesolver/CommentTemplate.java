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

	public static String formatChangesetComment(String template, long noteId, String noteUrl) {
		String retVal = expandEscapes(template);
		retVal = retVal.replace("##noteSolver:NID##", Long.toString(noteId));
		retVal = retVal.replace("##noteSolver:LN##", noteUrl);
		return retVal;
	}
}
