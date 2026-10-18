package org.openstreetmap.josm.plugins.notesolver;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pending-state for auto-closing notes after a confirmed OSM upload.
 * Close requires all of: WANT→ARMED (late hook), BOUND (write postprocessor),
 * matching ChangesetCache event. Cache alone never closes.
 */
public final class NoteClosePending {

	public enum Phase {
		IDLE,
		WANT_CLOSE,
		ARMED,
		BOUND
	}

	private Phase phase = Phase.IDLE;
	private final Set<Long> noteIds = new LinkedHashSet<>();
	private Integer boundChangesetId;
	private Integer closedForChangesetId;

	public Phase phase() {
		return phase;
	}

	public Set<Long> noteIds() {
		return Collections.unmodifiableSet(noteIds);
	}

	public Integer boundChangesetId() {
		return boundChangesetId;
	}

	/** Drop any incomplete cycle (validator cancel, failed upload, #18). */
	public void resetIncomplete() {
		if (phase != Phase.IDLE) {
			clear();
		}
	}

	public void wantClose(Collection<Long> ids) {
		clear();
		if (ids == null || ids.isEmpty()) {
			return;
		}
		noteIds.addAll(ids);
		phase = Phase.WANT_CLOSE;
	}

	public void decline() {
		clear();
	}

	/** Late upload hook: upload dialog accepted, upload will start. */
	public boolean arm() {
		if (phase != Phase.WANT_CLOSE || noteIds.isEmpty()) {
			return false;
		}
		phase = Phase.ARMED;
		return true;
	}

	/**
	 * Bind changeset id from primitives that were actually uploaded.
	 * Mixed or non-positive ids → no bind (do not close).
	 */
	public boolean bindFromUploadedChangesetIds(Collection<Integer> changesetIds) {
		if (phase != Phase.ARMED) {
			return false;
		}
		if (changesetIds == null || changesetIds.isEmpty()) {
			return false;
		}
		Integer id = null;
		for (Integer cs : changesetIds) {
			if (cs == null || cs <= 0) {
				return false;
			}
			if (id == null) {
				id = cs;
			} else if (!id.equals(cs)) {
				return false;
			}
		}
		boundChangesetId = id;
		phase = Phase.BOUND;
		return true;
	}

	/**
	 * @param dataLayerClean true only when the edit layer no longer requires upload
	 *        (rejects partial upload + error/cancel; see OsmServerWriter.finally)
	 * @return changeset id to close notes with, or null if this cache event must be ignored
	 */
	public Integer matchCacheEvent(int changesetId, boolean dataLayerClean) {
		if (!dataLayerClean) {
			return null;
		}
		if (phase != Phase.BOUND || boundChangesetId == null) {
			return null;
		}
		if (changesetId <= 0 || changesetId != boundChangesetId.intValue()) {
			return null;
		}
		if (closedForChangesetId != null && closedForChangesetId.intValue() == changesetId) {
			return null;
		}
		return boundChangesetId;
	}

	public void markClosed(int changesetId) {
		closedForChangesetId = changesetId;
		phase = Phase.IDLE;
		noteIds.clear();
		boundChangesetId = null;
	}

	public void clear() {
		phase = Phase.IDLE;
		noteIds.clear();
		boundChangesetId = null;
		closedForChangesetId = null;
	}
}
