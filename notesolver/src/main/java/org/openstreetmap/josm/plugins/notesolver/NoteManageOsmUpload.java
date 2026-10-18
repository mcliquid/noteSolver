package org.openstreetmap.josm.plugins.notesolver;

import java.util.Collections;

import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.notes.NoteComment;
import org.openstreetmap.josm.data.osm.NoteData;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.NoteLayer;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmApi;
import org.openstreetmap.josm.io.OsmTransferCanceledException;

/**
 * Blocking note upload via JOSM {@link OsmApi} (same endpoints as {@code UploadNotesTask}).
 * Does not stage a local close before the server answers — avoids false local CLOSED on failure.
 * Layer merge runs on the EDT and skips notes that still have unuploaded local comments.
 */
public final class NoteManageOsmUpload implements NoteManageService.NoteApi {

	enum MergeResult {
		MERGED,
		NO_LAYER,
		NOTE_MISSING,
		SKIPPED_PENDING_LOCAL
	}

	@Override
	public void apply(Note note, String comment, boolean close) throws Exception {
		ProgressMonitor pm = NullProgressMonitor.INSTANCE;
		try {
			Note updated;
			if (close) {
				updated = OsmApi.getOsmApi().closeNote(note, comment, pm);
			} else {
				updated = OsmApi.getOsmApi().addCommentToNote(note, comment, pm);
			}
			final long noteId = note.getId();
			GuiHelper.runInEDTAndWait(() -> mergeFromServer(noteId, updated));
		} catch (OsmTransferCanceledException e) {
			throw new java.util.concurrent.CancellationException(
					e.getMessage() == null ? "cancelled" : e.getMessage());
		}
	}

	/** Looks up the live note layer and merges; safe if the layer was removed mid-upload. */
	static MergeResult mergeFromServer(long noteId, Note updated) {
		try {
			if (MainApplication.getLayerManager() == null) {
				return MergeResult.NO_LAYER;
			}
			NoteLayer layer = MainApplication.getLayerManager().getNoteLayer();
			if (layer == null || layer.getNoteData() == null) {
				return MergeResult.NO_LAYER;
			}
			return mergeInto(layer.getNoteData(), noteId, updated);
		} catch (RuntimeException e) {
			// JOSM not fully initialized, or layer torn down during upload.
			return MergeResult.NO_LAYER;
		}
	}

	/**
	 * Merges a server note into {@code data} by id.
	 * Skips when the layer note still has unuploaded ({@code isNew}) comments so local drafts survive.
	 */
	static MergeResult mergeInto(NoteData data, long noteId, Note updated) {
		if (data == null || updated == null) {
			return MergeResult.NOTE_MISSING;
		}
		Note local = findById(data, noteId);
		if (local == null) {
			return MergeResult.NOTE_MISSING;
		}
		if (hasUnuploadedComments(local)) {
			return MergeResult.SKIPPED_PENDING_LOCAL;
		}
		data.updateNotes(Collections.singletonMap(local, updated));
		return MergeResult.MERGED;
	}

	static Note findById(NoteData data, long noteId) {
		if (data == null) {
			return null;
		}
		for (Note n : data.getNotes()) {
			if (n.getId() == noteId) {
				return n;
			}
		}
		return null;
	}

	static boolean hasUnuploadedComments(Note note) {
		if (note == null || note.getComments() == null) {
			return false;
		}
		for (NoteComment c : note.getComments()) {
			if (c != null && c.isNew()) {
				return true;
			}
		}
		return false;
	}
}
