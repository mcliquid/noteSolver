package org.openstreetmap.josm.plugins.notesolver;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.notes.Note;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.AbstractMapViewPaintable;
import org.openstreetmap.josm.gui.layer.NoteLayer;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Draws a ring around notes in the plugin's remember-list (issue #31).
 */
public class RememberedNotesOverlay extends AbstractMapViewPaintable {

	private static final Color RING_COLOR = new Color(0xE67E22);
	private static final float RING_WIDTH = 2f;
	private static final int RING_PADDING_PX = 3;

	private final Supplier<? extends Collection<Note>> rememberedNotes;

	public RememberedNotesOverlay(Supplier<? extends Collection<Note>> rememberedNotes) {
		this.rememberedNotes = rememberedNotes;
	}

	@Override
	public void paint(Graphics2D g, MapView mv, Bounds bbox) {
		Collection<Note> notes = rememberedNotes.get();
		if (notes == null || notes.isEmpty()) {
			return;
		}
		int iconW = ImageProvider.ImageSizes.SMALLICON.getAdjustedWidth();
		int iconH = ImageProvider.ImageSizes.SMALLICON.getAdjustedHeight();
		int diameter = Math.max(iconW, iconH) + RING_PADDING_PX * 2;

		Stroke oldStroke = g.getStroke();
		Color oldColor = g.getColor();
		g.setStroke(new BasicStroke(RING_WIDTH));
		g.setColor(RING_COLOR);

		List<NoteLayer> noteLayers = mv.getLayerManager().getLayersOfType(NoteLayer.class);
		Set<Long> onMap = noteIdsOnVisibleLayers(noteLayers);
		for (Note remembered : notesInClip(notes, bbox)) {
			Note onLayer = findOnMap(remembered, onMap, noteLayers);
			if (onLayer == null) {
				continue;
			}
			Point p = mv.getPoint(onLayer.getLatLon());
			int cx = p.x;
			int cy = iconCenterY(p.y, iconH);
			g.drawOval(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
		}

		g.setStroke(oldStroke);
		g.setColor(oldColor);
	}

	static int iconCenterY(int mapAnchorY, int iconHeight) {
		return mapAnchorY - iconHeight / 2;
	}

	static Iterable<Note> notesInClip(Collection<Note> notes, Bounds bbox) {
		return () -> notes.stream().filter(n -> bbox.contains(n.getLatLon())).iterator();
	}

	static Set<Long> noteIdsOnVisibleLayers(Collection<NoteLayer> layers) {
		Set<Long> ids = new HashSet<>();
		for (NoteLayer layer : layers) {
			if (!layer.isVisible()) {
				continue;
			}
			for (Note n : layer.getNoteData().getNotes()) {
				ids.add(n.getId());
			}
		}
		return ids;
	}

	/** Layer note for drawing; remembered list is not modified. */
	static Note findOnMap(Note remembered, Set<Long> idsOnVisibleLayers, Collection<NoteLayer> layers) {
		if (remembered == null || !idsOnVisibleLayers.contains(remembered.getId())) {
			return null;
		}
		long id = remembered.getId();
		for (NoteLayer layer : layers) {
			if (!layer.isVisible()) {
				continue;
			}
			for (Note n : layer.getNoteData().getNotes()) {
				if (n.getId() == id) {
					return n;
				}
			}
		}
		return null;
	}
}
