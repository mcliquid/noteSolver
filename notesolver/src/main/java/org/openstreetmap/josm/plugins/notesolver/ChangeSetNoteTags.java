package org.openstreetmap.josm.plugins.notesolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.LongFunction;

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

	private static final java.util.regex.Pattern COMPACT_CLOSE_SNIPPET =
			java.util.regex.Pattern.compile("Closes note/\\d+");

	private static final java.util.regex.Pattern URL_CLOSE_SNIPPET =
			java.util.regex.Pattern.compile("Closes https?://\\S+/note/\\d+");

	/**
	 * Removes {@code Closes note/<id>} (and URL variant) snippets whose note id is not in {@code keepNoteIds}.
	 * Mapper text without noteSolver close lines is unchanged.
	 */
	public static String stripCloseSnippetsExcept(String comment, Collection<Long> keepNoteIds) {
		return stripNoteCloseLineSegmentsExcept(comment, keepNoteIds);
	}

	/**
	 * Drops semicolon-separated segments that look like noteSolver auto-close lines for notes not in
	 * {@code keepNoteIds}. Handles compact {@code Closes note/id}, URL form, and localized {@code Erledigt mit …}.
	 */
	public static String stripNoteCloseLineSegmentsExcept(String comment, Collection<Long> keepNoteIds) {
		if (comment == null || comment.isEmpty()) {
			return comment;
		}
		java.util.Set<Long> keep = new java.util.HashSet<>();
		if (keepNoteIds != null) {
			for (Long id : keepNoteIds) {
				if (id != null) {
					keep.add(id);
				}
			}
		}
		String[] parts = comment.split("\\s*;\\s*");
		StringBuilder out = new StringBuilder();
		for (String part : parts) {
			String segment = part.trim();
			if (segment.isEmpty()) {
				continue;
			}
			if (isAutoCloseCommentSegment(segment)) {
				long noteId = parseNoteIdFromCloseSnippet(segment);
				if (noteId >= 0 && !keep.contains(noteId)) {
					continue;
				}
			}
			if (out.length() > 0) {
				out.append("; ");
			}
			out.append(segment);
		}
		return normalizeCommentSeparators(out.toString());
	}

	static boolean isAutoCloseCommentSegment(String segment) {
		if (segment == null || segment.isEmpty()) {
			return false;
		}
		String s = segment.trim();
		if (!s.contains("note/")) {
			return false;
		}
		if (COMPACT_CLOSE_SNIPPET.matcher(s).matches()) {
			return true;
		}
		if (URL_CLOSE_SNIPPET.matcher(s).matches()) {
			return true;
		}
		if (s.matches("(?i)^Erledigt mit\\s+.*note/\\d+.*")) {
			return true;
		}
		return s.matches("(?i)^Closes\\s+.*note/\\d+.*");
	}

	static long parseNoteIdFromCloseSnippet(String snippet) {
		java.util.regex.Matcher id = java.util.regex.Pattern.compile("note/(\\d+)").matcher(snippet);
		if (id.find()) {
			return Long.parseLong(id.group(1));
		}
		return -1L;
	}

	static String removeCloseSnippet(String comment, String snippet) {
		if (comment == null || snippet == null || snippet.isEmpty()) {
			return comment;
		}
		String result = comment.replace("; " + snippet, "");
		result = result.replace(snippet, "");
		return normalizeCommentSeparators(result);
	}

	static String normalizeCommentSeparators(String comment) {
		if (comment == null) {
			return null;
		}
		String result = comment.trim();
		while (result.startsWith(";")) {
			result = result.substring(1).trim();
		}
		while (result.contains(";;")) {
			result = result.replace(";;", ";");
		}
		result = result.replace("; ;", "; ");
		while (result.endsWith(";")) {
			result = result.substring(0, result.length() - 1).trim();
		}
		return result;
	}

	/**
	 * Strips prior noteSolver lines from the mapper comment, then merges close snippets for remembered notes.
	 * {@code changesetLineForNote} must return final text (escapes and placeholders already expanded).
	 */
	public static String prepareUploadCommentTag(
			String baseComment,
			Collection<Long> rememberedNoteIds,
			Collection<Long> solvedNoteIds,
			LongFunction<String> changesetLineForNote) {
		String comment = baseComment;
		if (comment != null && solvedNoteIds != null && changesetLineForNote != null) {
			for (Long id : solvedNoteIds) {
				if (id == null) {
					continue;
				}
				String noteLink = changesetLineForNote.apply(id);
				comment = removeCloseSnippet(comment, noteLink);
				comment = removeCloseSnippet(comment, compactCloseSnippet(id));
			}
		}
		if (comment != null && rememberedNoteIds != null) {
			comment = stripCloseSnippetsExcept(comment, java.util.Collections.emptyList());
		}
		return mergeCommentWithNotes(comment, rememberedNoteIds);
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
