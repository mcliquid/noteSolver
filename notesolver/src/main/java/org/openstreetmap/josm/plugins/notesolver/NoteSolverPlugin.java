package org.openstreetmap.josm.plugins.notesolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.openstreetmap.josm.actions.*;
import org.openstreetmap.josm.actions.upload.*;

import org.openstreetmap.josm.data.*;
import org.openstreetmap.josm.data.notes.*;
import org.openstreetmap.josm.data.notes.Note.State;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.osm.NoteData.*;
import org.openstreetmap.josm.data.osm.event.*;

import org.openstreetmap.josm.gui.*;
import org.openstreetmap.josm.gui.layer.*;
import org.openstreetmap.josm.gui.layer.LayerManager.*;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;

import org.openstreetmap.josm.io.OsmServerWritePostprocessor;
import org.openstreetmap.josm.io.OsmServerWriter;

import org.openstreetmap.josm.plugins.*;
import org.openstreetmap.josm.spi.preferences.Config;

import org.openstreetmap.josm.tools.Destroyable;
import org.openstreetmap.josm.tools.I18n;
import org.openstreetmap.josm.tools.OpenBrowser;

import java.awt.event.*;
import java.awt.*;
import javax.swing.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class NoteSolverPlugin extends Plugin implements Destroyable {
	static JMenu noteSolverMenu;
	public NoteList rememberedNotes = new NoteList();
	private NoteList solvedNotes = new NoteList();
	private NoteList pendingNotes = new NoteList();
	public Note selectedNote;

	private final NoteClosePending closePending = new NoteClosePending();
	private final RememberedNotesOverlay rememberedNotesOverlay = new RememberedNotesOverlay(() -> rememberedNotes);

	NoteSolverPlugin me = this;
	private PluginInformation myPluginInformation;

	public NoteSolverPlugin(final PluginInformation info) {
		super(info);
		myPluginInformation = info;
		createMenu();
		updateMenu();
		MainApplication.getLayerManager().addAndFireLayerChangeListener(layerChangeListener);
		if (
			MainApplication.getLayerManager().getNoteLayer() != null
			&&
			MainApplication.getLayerManager().getNoteLayer().getNoteData() != null
			&&
			MainApplication.getLayerManager().getNoteLayer().getNoteData().getSelectedNote() != null
		) {
			selectedNote = MainApplication.getLayerManager().getNoteLayer().getNoteData().getSelectedNote();
			updateMenu();
		}
		UploadAction.registerUploadHook(earlyUploadHook, false);
		UploadAction.registerUploadHook(lateUploadHook, true);
		OsmServerWriter.registerPostprocessor(writePostprocessor);
		ChangesetCache.getInstance().addChangesetCacheListener(changesetCacheListener);
	}

	@Override
	public void destroy() {
		detachRememberedNotesOverlay(MainApplication.getMap());
		UploadAction.unregisterUploadHook(earlyUploadHook);
		UploadAction.unregisterUploadHook(lateUploadHook);
		OsmServerWriter.unregisterPostprocessor(writePostprocessor);
		ChangesetCache.getInstance().removeChangesetCacheListener(changesetCacheListener);
		MainApplication.getLayerManager().removeLayerChangeListener(layerChangeListener);
		closePending.clear();
		pendingNotes = new NoteList();
	}

	private void removeNoteSolverTags(DataSet ds) {
		if (ds == null) {
			return;
		}
		ds.getChangeSetTags().remove("closed:note");
	}

	private final UploadHook earlyUploadHook = new UploadHook() {
		@Override
		public boolean checkUpload(APIDataSet apiDataSet) {
			DataSet ds = MainApplication.getLayerManager().getEditDataSet();
			// Abandoned Yes / armed upload without success must not linger (#16/#18).
			closePending.resetIncomplete();
			pendingNotes = new NoteList();
			removeNoteSolverTags(ds);

			if (rememberedNotes != null && rememberedNotes.size() > 0) {
				for (Note note : rememberedNotes) {
					String cOnlineStatus = getOnlineNoteStatus(note.getId());
					if (note.getState() == State.CLOSED || cOnlineStatus.toLowerCase().trim().equals("closed")) {
						solvedNotes.add(note);
					}
				}
				for (Note note : solvedNotes)
					if (rememberedNotes.containsNote(note)) rememberedNotes.remove(note);
				refreshRememberedNotesOnMap();
			}
			if (rememberedNotes == null || rememberedNotes.size() == 0) {
				return true;
			}

			StringBuilder noteList = new StringBuilder();
			rememberedNotes.forEach((n) -> noteList.append("\n" + NoteText.noteShortText(n)));

			int outVal = JOptionPane.showConfirmDialog(
				null,
				I18n.trn("Automatically resolve Note\n{0}?", "Automatically resolve Notes\n{0}?", rememberedNotes.size(), noteList),
				null,
				JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.QUESTION_MESSAGE
			);
			if (outVal == JOptionPane.CANCEL_OPTION) {
				closePending.decline();
				return false;
			}
			if (outVal != JOptionPane.YES_OPTION) {
				closePending.decline();
				return true;
			}

			String comment = ds.getChangeSetTags().get("comment");
			List<Long> ids = rememberedNotes.stream().map(Note::getId).collect(Collectors.toList());
			List<Long> solvedIds = solvedNotes.stream().map(Note::getId).collect(Collectors.toList());
			comment = ChangeSetNoteTags.prepareUploadCommentTag(comment, ids, solvedIds, noteId -> {
				for (Note note : solvedNotes) {
					if (note.getId() == noteId) {
						return getChangesetComment(note);
					}
				}
				return ChangeSetNoteTags.compactCloseSnippet(noteId);
			});
			String closedNoteTag = ChangeSetNoteTags.closedNoteTag(ids);
			ds.addChangeSetTag("created_by", "noteSolver_plugin/" + myPluginInformation.version);
			ds.addChangeSetTag("comment", comment);
			if (closedNoteTag == "") {
				ds.getChangeSetTags().remove("closed:note");
			} else {
				ds.addChangeSetTag("closed:note", closedNoteTag);
			}

			closePending.wantClose(ids);
			return true;
		}
	};

	private final UploadHook lateUploadHook = new UploadHook() {
		@Override
		public boolean checkUpload(APIDataSet apiDataSet) {
			if (closePending.arm()) {
				pendingNotes = new NoteList();
				pendingNotes.addAll(rememberedNotes);
			}
			return true;
		}
	};

	private final OsmServerWritePostprocessor writePostprocessor = (Collection<OsmPrimitive> uploaded, ProgressMonitor progress) -> {
		if (uploaded == null || uploaded.isEmpty()) {
			return;
		}
		List<Integer> ids = new ArrayList<>();
		for (OsmPrimitive p : uploaded) {
			ids.add(p.getChangesetId());
		}
		closePending.bindFromUploadedChangesetIds(ids);
	};

	private final ChangesetCacheListener changesetCacheListener = event -> {
		for (Changeset cs : event.getAddedChangesets()) {
			maybeCloseNotesForCacheChangeset(cs);
		}
		for (Changeset cs : event.getUpdatedChangesets()) {
			maybeCloseNotesForCacheChangeset(cs);
		}
	};

	private boolean isEditLayerClean() {
		OsmDataLayer layer = MainApplication.getLayerManager().getEditLayer();
		return layer == null || !layer.requiresUploadToServer();
	}

	private void maybeCloseNotesForCacheChangeset(Changeset cs) {
		if (cs == null) {
			return;
		}
		Integer changesetId = closePending.matchCacheEvent(cs.getId(), isEditLayerClean());
		if (changesetId == null) {
			return;
		}
		GuiHelper.runInEDT(() -> closePendingNotes(changesetId));
	}

	private void closePendingNotes(int changesetId) {
		Integer matched = closePending.matchCacheEvent(changesetId, isEditLayerClean());
		if (matched == null) {
			return;
		}
		closePending.markClosed(changesetId);

		NoteList toClose = pendingNotes;
		pendingNotes = new NoteList();
		ProgressMonitor pm = null;
		for (Note note : toClose) {
			String cOnlineStatus = getOnlineNoteStatus(note.getId());
			NoteData noteData = new NoteData(java.util.Collections.singleton(note));
			String noteComment = getNoteComment(changesetId);
			if (note.getState() == State.OPEN && cOnlineStatus.toLowerCase().trim().equals("open")) {
				try {
					noteData.closeNote(note, noteComment);
					UploadNotesTask uploadNotesTask = new UploadNotesTask();
					uploadNotesTask.uploadNotes(noteData, pm);
				} catch (Exception e) {
					JOptionPane.showMessageDialog(null, I18n.tr("Upload Note exception:\n{0}", e.getMessage()));
				}
			} else {
				JOptionPane.showMessageDialog(null, I18n.tr("Note {0} was already closed outside of JOSM", Long.toString(note.getId())));
			}
			solvedNotes.add(note);
			if (rememberedNotes.containsNote(note)) {
				rememberedNotes.remove(note);
			}
		}
		DataSet ds = MainApplication.getLayerManager().getEditDataSet();
		if (ds != null) {
			ds.getChangeSetTags().remove("closed:note");
		}
		updateMenu();
		refreshRememberedNotesOnMap();
	}

	private final JosmAction settingsDialogMenu = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933878L;
		@Override
		public void actionPerformed(ActionEvent event) {
			SettingsDialog.showSettingsDialog();
		}
	};
	private final JosmAction aboutDialog = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933877L;
		@Override
		public void actionPerformed(ActionEvent event) {
			AboutDialog.showAboutDialog();
		}
	};

	private final JosmAction forgetNoteAction = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933879L;
		@Override
		public void actionPerformed(ActionEvent event) {
			if (selectedNote == null) {
				JOptionPane.showMessageDialog(null, I18n.tr("No Note selected."));
			} else {
				if (rememberedNotes != null && rememberedNotes.containsNote(selectedNote))
					rememberedNotes.remove(selectedNote);
			}
			updateMenu();
			refreshRememberedNotesOnMap();
		}
	};
	private final JosmAction rememberNoteAction = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933880L;
		@Override
		public void actionPerformed(ActionEvent event) {
			if (selectedNote == null) {
				JOptionPane.showMessageDialog(null, I18n.tr("No Note selected."));
			} else {
				if (rememberedNotes != null && !rememberedNotes.containsNote(selectedNote))
					rememberedNotes.add(selectedNote);
			}
			updateMenu();
			refreshRememberedNotesOnMap();
		}
	};
	private final JosmAction openNoteAction = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933881L;
		@Override
		public void actionPerformed(ActionEvent event) {
			if (selectedNote == null) {
				JOptionPane.showMessageDialog(null, I18n.tr("No Note selected."));
			} else {
				if (selectedNote != null) {
					OpenBrowser.displayUrl(getServerUrl() + "note/" + Long.toString(selectedNote.getId()));
				}
			}
			updateMenu();
		}
	};
	private final NoteDataUpdateListener noteDataUpdateListener = new NoteDataUpdateListener() {
		@Override
		public void selectedNoteChanged(NoteData noteData) {
			selectedNote = noteData.getSelectedNote();
			if (selectedNote != null) {
				JPopupMenu contextMenu = new JPopupMenu();
				for (JMenuItem menuItem : mainMenuEntries(menuTypes.MAIN))
					contextMenu.add(menuItem);
				Dimension size = contextMenu.getPreferredSize();
				MainFrame frame = MainApplication.getMainFrame();
				Point p = frame.getMousePosition();
				if (p != null) {
					Component c = MainApplication.getMainFrame().getComponentAt(p);
					SwingUtilities.convertPointToScreen(p, frame);
					if (c instanceof JComponent) {
						JComponent jc = (JComponent) c;
						contextMenu.setInvoker(c);
						contextMenu.setLocation(p.x, p.y - size.height - 15);
						jc.setComponentPopupMenu(contextMenu);
						contextMenu.setEnabled(true);
						contextMenu.setVisible(true);
					}
				}
			}
		}
		@Override
		public void noteDataUpdated(NoteData noteData) {
			// nothing to do here
		}
	};

	private final LayerChangeListener layerChangeListener = new LayerChangeListener() {
		@Override
		public void layerAdded(LayerAddEvent e) {
			changeListeners(e.getAddedLayer(), false);
		}

		@Override
		public void layerRemoving(LayerRemoveEvent e) {
			changeListeners(e.getRemovedLayer(), true);
		}

		@Override
		public void layerOrderChanged(LayerOrderChangeEvent e) {
			// nothing to do here
		}

		public final void changeListeners(Layer layer, boolean isRemove) {
			if (layer instanceof NoteLayer) {
				NoteData notes = ((NoteLayer) layer).getNoteData();
				if (!isRemove) {
					notes.addNoteDataUpdateListener(noteDataUpdateListener);
				} else {
					notes.removeNoteDataUpdateListener(noteDataUpdateListener);
				}
			}
		}
	};

	private void createMenu() {
		final MainMenu menu = MainApplication.getMenu();
		if (noteSolverMenu == null) {
			noteSolverMenu = menu.addMenu(
				"Note Solver",
				"Note Solver",
				0,
				menu.getDefaultMenuPos(),
				"help"
			);
		}
	}

	public void updateMenu() {
		noteSolverMenu.removeAll();
		noteSolverMenu.add(createMenuItem(I18n.tr("Settings"), I18n.tr("Plugin settings"), settingsDialogMenu, true));
		noteSolverMenu.addSeparator();
		for (JMenuItem j : mainMenuEntries(menuTypes.MAIN)) {
			noteSolverMenu.add(j);
		}
		noteSolverMenu.addSeparator();
		if (rememberedNotes != null && rememberedNotes.size() > 0) {
			noteSolverMenu.add(new JMenuItem(I18n.tr("List of selected Notes")));
			noteSolverMenu.addSeparator();
			for (JMenuItem j : mainMenuEntries(menuTypes.NOTELIST))
				noteSolverMenu.add(j);
			noteSolverMenu.addSeparator();
		}
		noteSolverMenu.add(createMenuItem(I18n.tr("About"), I18n.tr("About noteSolver Plugin"), aboutDialog, true));
	}

	private List<JMenuItem> mainMenuEntries(Enum<menuTypes> menuType) {
		List<JMenuItem> returnList = new ArrayList<>();
		if (menuType == menuTypes.MAIN) {
			boolean bEnaOpn = selectedNote != null;
			boolean bEnaPre = selectedNote != null && rememberedNotes != null;
			boolean bEnaLst = (bEnaPre && rememberedNotes.containsNote(selectedNote));
			boolean bEnaCls = (bEnaPre && selectedNote.getState() == Note.State.CLOSED);
			returnList.add(
				createMenuItem(
					I18n.tr("Open in browser"),
					I18n.tr("Try to open the Note on osm.org with the operating system's standard browser"),
					openNoteAction, bEnaOpn
				)
			);
			returnList.add(
				createMenuItem(
					I18n.tr("Remember Note"),
					I18n.tr("Add the selected Note to the list of Notes that should be automatically closed when uploading a changeset"),
					rememberNoteAction, bEnaPre && !bEnaLst && !bEnaCls
				)
			);
			returnList.add(
				createMenuItem(
					I18n.tr("Forget Note"),
					I18n.tr("Remove the selected Note from the list of Notes that should be automatically closed when uploading a changeset"),
					forgetNoteAction, bEnaPre && bEnaLst
				)
			);
		} else if (menuType == menuTypes.NOTELIST) {
			if (rememberedNotes != null) {
				for(Note note : rememberedNotes) {
					returnList.add(
						createMenuItem(
							NoteText.noteShortText(note),
							(note.getFirstComment() != null ? note.getFirstComment().toString() : ""),
							new ActionListener() {
								@Override
								public void actionPerformed(ActionEvent ev) {
									rememberedNotes.remove(note);
									updateMenu();
									refreshRememberedNotesOnMap();
								}
							}, true
						)
					);
				}
			}
		}
		return returnList;
	}

	private JMenuItem createMenuItem(String title, String tooltip, ActionListener al, boolean enabled) {
		JMenuItem menuItem = new JMenuItem(title);
		menuItem.setToolTipText(tooltip);
		menuItem.addActionListener(al);
		menuItem.setEnabled(enabled);
		return menuItem;
	}

	@Override
	public void mapFrameInitialized(final MapFrame oldFrame, final MapFrame newFrame) {
		detachRememberedNotesOverlay(oldFrame);
		if (newFrame == null) {
			setEnabledMenu(false);
		} else {
			setEnabledMenu(true);
			newFrame.mapView.addTemporaryLayer(rememberedNotesOverlay);
			refreshRememberedNotesOnMap();
		}
	}

	private void detachRememberedNotesOverlay(MapFrame frame) {
		if (frame != null) {
			frame.mapView.removeTemporaryLayer(rememberedNotesOverlay);
		}
	}

	private void refreshRememberedNotesOnMap() {
		rememberedNotesOverlay.invalidate();
	}

	private void setEnabledMenu(final boolean isEnabled) {
		for (final Component menuItem : noteSolverMenu.getMenuComponents()) {
			if (menuItem instanceof JMenuItem) {
				((JMenuItem) menuItem).setEnabled(isEnabled);
			}
		}
	}

	private String getUrl(Object inputObject, Enum<linkTypes> linkType) {
		String returnValue = "";
		String serverUrl = getServerUrl();
		Long thisNumber = 0L;
		if (linkType == linkTypes.NOTE) {
			thisNumber = ((Note)inputObject).getId();
		} else if (linkType == linkTypes.CHANGESET) {
			thisNumber = Integer.toUnsignedLong((Integer)inputObject);
		}
		if (thisNumber > 0L) {
			if (linkType == linkTypes.NOTE && serverUrl.contains("openstreetmap.org")) {
				returnValue = "https://osm.org/note/" + Long.toString(thisNumber);
			} else {
				returnValue = serverUrl + linkType.name().toLowerCase() + "/" + Long.toString(thisNumber);
			}
		}
		return returnValue;
	}

	private String getOnlineNoteStatus(long noteId) {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		String result = "";
		String apiUrl = getServerUrl() + "api/0.6/notes/" + Long.toString(noteId);

		try {
			URLConnection conn = new URL(apiUrl).openConnection();
			try (InputStream is = conn.getInputStream()) {
				dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
				DocumentBuilder dBuilder = dbf.newDocumentBuilder();
				Document doc = dBuilder.parse(is);
				Element element = doc.getDocumentElement();

				NodeList nodeList = element.getElementsByTagName("status");
				if (nodeList.getLength() > 0) {
					result = nodeList.item(0).getTextContent();
				}
			}
		} catch (Exception e) {
			result = "failure";
		}

		return result;
	}

	private static String getServerUrl() {
		final String customServerUrl = Config.getPref().get("osm-server.url");
		String serverUrl =
			(customServerUrl != null && customServerUrl != "" ?
			customServerUrl.replace(".org/api", ".org") :
			"https://www.openstreetmap.org");
		if (!serverUrl.endsWith("/")) serverUrl += "/";
		return serverUrl;
	}

	private String getChangesetComment(Note note) {
		String retVal = String.format("Closes %s", getUrl(note, linkTypes.NOTE));
		if (Config.getPref().get("noteSolver.overrideChangesetComment") != "") {
			retVal = CommentTemplate.formatChangesetComment(
					Config.getPref().get("noteSolver.overrideChangesetComment"),
					note.getId(),
					getUrl(note, linkTypes.NOTE));
		} else {
			if (Config.getPref().getBoolean("noteSolver.useLocalLanguageInChangeset")) {
				retVal = I18n.tr("Closes {0}", getUrl(note, linkTypes.NOTE));
			}
		}
		return retVal;
	}
	private String getNoteComment(Integer changesetID) {
		String retVal = String.format("Resolved with changeset %s by noteSolver_plugin/%s", getUrl(changesetID, linkTypes.CHANGESET), myPluginInformation.version);
		if (Config.getPref().get("noteSolver.overrideNoteComment") != "") {
			retVal = CommentTemplate.formatNoteComment(
					Config.getPref().get("noteSolver.overrideNoteComment"),
					changesetID,
					getUrl(changesetID, linkTypes.CHANGESET));
		} else {
			if (Config.getPref().getBoolean("noteSolver.useLocalLanguageInNote")) {
				retVal = I18n.tr("Resolved with changeset {0} by noteSolver_plugin/{1}", getUrl(changesetID, linkTypes.CHANGESET), myPluginInformation.version);
			}
		}
		return retVal;
	}

	private enum linkTypes {
		NOTE, CHANGESET
	};
	private enum menuTypes {
		MAIN, NOTELIST
	};
}
