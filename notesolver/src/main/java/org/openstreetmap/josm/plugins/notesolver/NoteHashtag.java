package org.openstreetmap.josm.plugins.notesolver;

/**
 * One selectable note hashtag and its suggested default close action.
 * Hashtag and default action stay independent so the dialog can override.
 */
public final class NoteHashtag {

	private final String tag;
	private final boolean defaultClose;
	private final String suggestedText;

	public NoteHashtag(String tag, boolean defaultClose, String suggestedText) {
		if (tag == null || tag.isEmpty() || tag.charAt(0) != '#') {
			throw new IllegalArgumentException("tag must start with #");
		}
		this.tag = tag;
		this.defaultClose = defaultClose;
		this.suggestedText = suggestedText == null ? "" : suggestedText;
	}

	public String tag() {
		return tag;
	}

	public boolean defaultClose() {
		return defaultClose;
	}

	public String suggestedText() {
		return suggestedText;
	}

	@Override
	public String toString() {
		return tag;
	}
}
