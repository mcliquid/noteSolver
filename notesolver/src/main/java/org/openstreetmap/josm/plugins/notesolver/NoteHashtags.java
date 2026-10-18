package org.openstreetmap.josm.plugins.notesolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Extensible catalog of note hashtags.
 * Spellings follow established usage (#surveyme) and issue #26 discussion;
 * LatAm wiki variants (e.g. #alreadyfixed) are intentionally not mixed in yet.
 *
 * Sources:
 * - https://github.com/kmpoppe/noteSolver/issues/26
 * - https://community.openstreetmap.org/t/tagovi-za-odredivanje-statusa-napomena-i-komentara-napomena/4788
 * - https://wiki.openstreetmap.org/wiki/ES:LatAm/Proyectos/Resoluci%C3%B3n_de_notas/hashtags
 */
public final class NoteHashtags {

	private static final List<NoteHashtag> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
		new NoteHashtag("#added", true,
			"Note was used to create a new object."),
		new NoteHashtag("#fixed", true,
			"Note was used to update the map by the same user that is closing the note. Errors were corrected."),
		new NoteHashtag("#updated", true,
			"Note was used to update the map by the same user that is closing the note. Data was updated with new or fresh information."),
		new NoteHashtag("#already_fixed", true,
			"Information from the note is already on the map so there is no need for an update."),
		new NoteHashtag("#already_updated", true,
			"Information from the note is already on the map so there is no need for an update."),
		new NoteHashtag("#need_more_info", false,
			"Note does not contain enough information to allow updating the map. Please provide more details."),
		new NoteHashtag("#not_enough_info", false,
			"Note does not contain enough information to allow updating the map and no more details were provided upon request."),
		new NoteHashtag("#surveyme", false,
			"Note is not precise or clear. Clarification is possible only by an in-person visit and surveying of the location."),
		new NoteHashtag("#need_confirmation", false,
			"There is the possibility that the note is not accurate or up to date. Confirmations by anyone are welcome."),
		new NoteHashtag("#irrelevant", true,
			"Note contains information that is not useful or irrelevant for updating the map.")
	));

	private NoteHashtags() {
	}

	public static List<NoteHashtag> all() {
		return DEFAULTS;
	}

	public static NoteHashtag findByTag(String tag) {
		if (tag == null) {
			return null;
		}
		for (NoteHashtag h : DEFAULTS) {
			if (h.tag().equalsIgnoreCase(tag)) {
				return h;
			}
		}
		return null;
	}
}
