package org.openstreetmap.josm.plugins.notesolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openstreetmap.josm.data.osm.Changeset;

/**
 * OSM API 0.6 limits changeset tag values to {@link Changeset#MAX_CHANGESET_TAG_LENGTH} characters (#33).
 */
public final class ChangeSetNoteTags {

	private ChangeSetNoteTags() {
	}

	public static int maxTagValueLength() {
		return Changeset.MAX_CHANGESET_TAG_LENGTH;
	}

	/** Semicolon-separated note ids for the {@code closed:note} tag; never longer than the API limit. */
	public static String closedNoteTag(Collection<Long> noteIds) {
		if (noteIds == null || noteIds.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		int max = maxTagValueLength();
		for (Long id : noteIds) {
			if (id == null) {
				continue;
			}
			String part = sb.isEmpty() ? Long.toString(id) : ";" + id;
			if (sb.length() + part.length() > max) {
				break;
			}
			sb.append(part);
		}
		return sb.toString();
	}

	public static String compactCloseSnippet(long noteId) {
		return "Closes note/" + noteId;
	}

	/**
	 * Builds the {@code comment} tag after the caller has stripped snippets for already-solved notes.
	 * Uses per-note compact references when they fit; otherwise a summary pointing at {@code closed:note}.
	 */
	public static String mergeCommentWithNotes(String baseComment, Collection<Long> noteIds) {
		if (noteIds == null || noteIds.isEmpty()) {
			return emptyToNull(baseComment);
		}
		List<Long> ids = new ArrayList<>();
		for (Long id : noteIds) {
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			return emptyToNull(baseComment);
		}
		String base = baseComment == null ? "" : baseComment.trim();
		int max = maxTagValueLength();
		StringBuilder sb = new StringBuilder(base);
		for (Long id : ids) {
			String snippet = compactCloseSnippet(id);
			String addition = sb.isEmpty() ? snippet : "; " + snippet;
			if (sb.length() + addition.length() > max) {
				return fitSummaryComment(base, ids.size(), max);
			}
			sb.append(addition);
		}
		return fitToMax(sb.toString());
	}

	static String fitSummaryComment(String base, int noteCount, int max) {
		String[] suffixes = {
			"; Closes %d notes (see closed:note)",
			"; Closes %d notes",
			" (+%d notes, see closed:note)",
			" (+%d notes)"
		};
		for (String suffixFmt : suffixes) {
			String suffix = String.format(suffixFmt, noteCount);
			if (base.isEmpty()) {
				String only = String.format("Closes %d notes (see closed:note)", noteCount);
				if (only.length() <= max) {
					return only;
				}
				only = String.format("Closes %d notes", noteCount);
				if (only.length() <= max) {
					return only;
				}
				continue;
			}
			String combined = base + suffix;
			if (combined.length() <= max) {
				return combined;
			}
		}
		if (base.isEmpty()) {
			return fitToMax(String.format("Closes %d notes", noteCount));
		}
		int reserve = Math.min(40, max / 4);
		String trimmed = base.length() + reserve > max ? base.substring(0, max - reserve).trim() : base;
		String combined = trimmed + String.format(" (+%d notes, see closed:note)", noteCount);
		return fitToMax(combined);
	}

	public static String fitToMax(String value) {
		if (value == null) {
			return null;
		}
		int max = maxTagValueLength();
		if (value.length() <= max) {
			return value;
		}
		return value.substring(0, max);
	}

	private static String emptyToNull(String s) {
		if (s == null || s.trim().isEmpty()) {
			return null;
		}
		return fitToMax(s.trim());
	}
}
