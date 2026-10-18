package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.data.osm.NoteData;
import org.openstreetmap.josm.gui.layer.NoteLayer;

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

	@Test
	void noteIdsOnVisibleLayers_skipsHiddenLayer() {
		Note onMap = new Note(new LatLon(1, 1));
		onMap.setId(10L);
		NoteLayer visible = new NoteLayer(new NoteData(Collections.singleton(onMap)), "visible");
		Note hiddenNote = new Note(new LatLon(2, 2));
		hiddenNote.setId(20L);
		NoteLayer hidden = new NoteLayer(new NoteData(Collections.singleton(hiddenNote)), "hidden");
		hidden.setVisible(false);

		Set<Long> ids = RememberedNotesOverlay.noteIdsOnVisibleLayers(Arrays.asList(visible, hidden));
		assertEquals(new HashSet<>(Collections.singleton(10L)), ids);
	}

	@Test
	void findOnMap_returnsLayerNote_notRememberedCopy() {
		Note layerNote = new Note(new LatLon(50.1, 8.2));
		layerNote.setId(42L);
		Note remembered = new Note(new LatLon(0, 0));
		remembered.setId(42L);
		NoteLayer layer = new NoteLayer(new NoteData(Collections.singleton(layerNote)), "notes");
		Set<Long> ids = RememberedNotesOverlay.noteIdsOnVisibleLayers(Collections.singletonList(layer));

		Note drawn = RememberedNotesOverlay.findOnMap(remembered, ids, Collections.singletonList(layer));
		assertEquals(layerNote.getLatLon(), drawn.getLatLon());
	}

	@Test
	void findOnMap_nullWhenNotOnVisibleLayer() {
		Note remembered = new Note(new LatLon(1, 1));
		remembered.setId(99L);
		assertNull(RememberedNotesOverlay.findOnMap(remembered, Collections.emptySet(), Collections.emptyList()));
	}

	@Test
	void iconCenterY_alignsWithNoteLayerDrawImageAnchor() {
		// NoteLayer 19649: drawImage at (x - w/2, y - h) for anchor point (x, y).
		int iconH = 16;
		int top = 32;
		assertEquals(top + iconH / 2, RememberedNotesOverlay.iconCenterY(top + iconH, iconH));
	}
}
