package org.openstreetmap.josm.plugins.notesolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openstreetmap.josm.data.osm.Changeset;

/**
 * OSM API 0.6 limits changeset tag values to {@link Changeset#MAX_CHANGESET_TAG_LENGTH} characters (#33).
 * Early upload hooks run before the upload dialog; user edits there are not trimmed here.
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

	public static int countIdsInClosedNoteTag(String closedNoteTagValue) {
		if (closedNoteTagValue == null || closedNoteTagValue.isEmpty()) {
			return 0;
		}
		return closedNoteTagValue.split(";", -1).length;
	}

	public static boolean isPartialClosedNoteList(Collection<Long> noteIds) {
		if (noteIds == null || noteIds.isEmpty()) {
			return false;
		}
		int total = 0;
		for (Long id : noteIds) {
			if (id != null) {
				total++;
			}
		}
		return countIdsInClosedNoteTag(closedNoteTag(noteIds)) < total;
	}

	public static String compactCloseSnippet(long noteId) {
		return "Closes note/" + noteId;
	}

	/**
	 * Builds the {@code comment} tag after the caller has stripped snippets for already-solved notes.
	 * The mapper's {@code baseComment} is never shortened; only noteSolver-generated text is limited or omitted.
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
		String base = baseComment == null ? "" : baseComment;
		int max = maxTagValueLength();
		if (base.length() > max) {
			// shortcut: user text already at/above API limit, skip auto additions
			return base;
		}
		boolean partialClosedNote = isPartialClosedNoteList(ids);
		int noteCount = ids.size();

		StringBuilder sb = new StringBuilder(base);
		for (Long id : ids) {
			String snippet = compactCloseSnippet(id);
			String addition = sb.isEmpty() ? snippet : "; " + snippet;
			if (sb.length() + addition.length() > max) {
				return joinBaseWithGeneratedSuffix(base, noteCount, partialClosedNote, max);
			}
			sb.append(addition);
		}
		return sb.toString();
	}

	static String joinBaseWithGeneratedSuffix(String base, int noteCount, boolean partialClosedNote, int max) {
		String[] candidates = partialClosedNote
			? new String[] {
				String.format("Closes %d notes (partial list in closed:note)", noteCount),
				String.format("Closes %d notes (partial closed:note)", noteCount),
				String.format("+%d notes (partial closed:note)", noteCount)
			}
			: new String[] {
				String.format("Closes %d notes (see closed:note)", noteCount),
				String.format("Closes %d notes", noteCount),
				String.format("+%d notes", noteCount)
			};
		for (String suffix : candidates) {
			String merged = mergeBaseAndAddition(base, suffix, max);
			if (merged != null && merged.length() <= max) {
				return merged;
			}
		}
		return base.isEmpty() ? truncateUtf16Safe(candidates[0], max) : base;
	}

	static String mergeBaseAndAddition(String base, String addition, int max) {
		if (addition == null || addition.isEmpty()) {
			return base.isEmpty() ? null : base;
		}
		if (base.isEmpty()) {
			return addition.length() <= max ? addition : truncateUtf16Safe(addition, max);
		}
		if (base.length() > max) {
			return base;
		}
		String combined = base + "; " + addition;
		if (combined.length() <= max) {
			return combined;
		}
		return null;
	}

	/** Only for noteSolver-generated strings; never use on mapper-supplied comment text. */
	static String truncateUtf16Safe(String value, int maxChars) {
		if (value == null) {
			return null;
		}
		if (value.length() <= maxChars) {
			return value;
		}
		int end = maxChars;
		while (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end);
	}

	private static String emptyToNull(String s) {
		if (s == null || s.trim().isEmpty()) {
			return null;
		}
		return s;
	}
}
