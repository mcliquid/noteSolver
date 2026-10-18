package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;

class RememberedNotesOverlayTest {

	@Test
	void iconCenterY_matchesNoteLayerAnchor() {
		assertEquals(40, RememberedNotesOverlay.iconCenterY(48, 16));
	}

	@Test
	void notesInClip_filtersByBounds() {
		Note inside = new Note(new LatLon(50.0, 8.0));
		inside.setId(1L);
		Note outside = new Note(new LatLon(60.0, 10.0));
		outside.setId(2L);
		Bounds bbox = new Bounds(new LatLon(49.0, 7.0), new LatLon(51.0, 9.0));

		Iterator<Note> it = RememberedNotesOverlay.notesInClip(Arrays.asList(inside, outside), bbox).iterator();
		assertTrue(it.hasNext());
		assertEquals(1L, it.next().getId());
		assertFalse(it.hasNext());
	}

	@Test
	void notesInClip_emptyWhenNoOverlap() {
		Note n = new Note(new LatLon(0.0, 0.0));
		n.setId(3L);
		Bounds bbox = new Bounds(new LatLon(10.0, 10.0), new LatLon(11.0, 11.0));
		assertFalse(RememberedNotesOverlay.notesInClip(Collections.singletonList(n), bbox).iterator().hasNext());
	}

	@Test
	void invalidateDoesNotThrowWithoutMapAttachment() {
		RememberedNotesOverlay overlay = new RememberedNotesOverlay(Collections::emptyList);
		overlay.invalidate();
	}
}
