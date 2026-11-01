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
import org.openstreetmap.josm.data.Version;

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
import java.awt.Desktop;
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
	private final AutoCloseOrchestrator autoCloseOrchestrator = new AutoCloseOrchestrator();
	private String autoCloseCorrelationId;

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
		String josmVersion = "unknown";
		try {
			josmVersion = Version.getInstance().getVersionString();
		} catch (RuntimeException ignored) {
			// unit tests / headless
		}
		DiagnosticLog.start(info.version, josmVersion);
	}

	@Override
	public void destroy() {
		detachRememberedNotesOverlay(MainApplication.getMap());
		UploadAction.unregisterUploadHook(earlyUploadHook);
		UploadAction.unregisterUploadHook(lateUploadHook);
		OsmServerWriter.unregisterPostprocessor(writePostprocessor);
		ChangesetCache.getInstance().removeChangesetCacheListener(changesetCacheListener);
		MainApplication.getLayerManager().removeLayerChangeListener(layerChangeListener);
		AutoCloseDiagnostics.pluginShutdown(closePending, rememberedNotes == null ? 0 : rememberedNotes.size(),
				pendingNotes == null ? 0 : pendingNotes.size());
		autoCloseOrchestrator.onPluginDestroy();
		if (autoCloseOrchestrator.hasAnyAutoCloseInFlight()) {
			DiagnosticLog.get().warn("pluginDestroy autoCloseUploadsInFlight="
					+ autoCloseOrchestrator.autoCloseUploadsInFlight());
		}
		DiagnosticLog.shutdown();
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
			if (closePending.phase() != NoteClosePending.Phase.IDLE) {
				logAutoCloseCancelled("uploadRestartReset", closePending.noteIds());
			}
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
				logAutoCloseCancelled("uploadDialogCancel");
				closePending.decline();
				return false;
			}
			if (outVal != JOptionPane.YES_OPTION) {
				logAutoCloseCancelled("uploadDialogNo");
				closePending.decline();
				return true;
			}

			String comment = ds.getChangeSetTags().get("comment");
			List<Long> ids = rememberedNotes.stream()
					.map(Note::getId)
					.filter(id -> !autoCloseOrchestrator.isAutoCloseInFlight(id))
					.collect(Collectors.toList());
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
			autoCloseCorrelationId = AutoCloseDiagnostics.newCorrelationId();
			AutoCloseDiagnostics.uploadDialog("REQUESTED", autoCloseCorrelationId, ids);
			AutoCloseDiagnostics.pendingPhase(autoCloseCorrelationId, closePending.phase(), null);
			return true;
		}
	};

	private final UploadHook lateUploadHook = new UploadHook() {
		@Override
		public boolean checkUpload(APIDataSet apiDataSet) {
			if (closePending.arm()) {
				AutoCloseDiagnostics.uploadDialog("STARTED", autoCloseCorrelationId, closePending.noteIds());
				AutoCloseDiagnostics.pendingPhase(autoCloseCorrelationId, closePending.phase(), null);
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
		if (closePending.phase() == NoteClosePending.Phase.BOUND) {
			AutoCloseDiagnostics.pendingPhase(autoCloseCorrelationId, closePending.phase(),
					closePending.boundChangesetId());
		}
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
		DiagnosticLog.get().info("autoClose correlationId=" + autoCloseCorrelationId + " changesetId=" + changesetId
				+ " outcome=REQUESTED action=scheduleNoteCloseUpload");
		GuiHelper.runInEDT(() -> closePendingNotes(changesetId));
	}

	private void logAutoCloseCancelled(String reason, java.util.Set<Long> noteIds) {
		if (autoCloseCorrelationId != null) {
			AutoCloseDiagnostics.uploadDialog("CANCELLED", autoCloseCorrelationId, noteIds);
		}
		DiagnosticLog.get().info("autoClose uploadAborted reason=" + reason);
	}

	private void logAutoCloseCancelled(String reason) {
		logAutoCloseCancelled(reason, closePending.noteIds());
	}

	private void closePendingNotes(int changesetId) {
		Integer matched = closePending.matchCacheEvent(changesetId, isEditLayerClean());
		if (matched == null) {
			return;
		}
		closePending.markClosed(changesetId);

		NoteList toClose = pendingNotes;
		pendingNotes = new NoteList();
		final String correlationId = autoCloseCorrelationId;
		autoCloseOrchestrator.closePendingNotes(toClose, changesetId, correlationId,
				NoteAutoCloseUpload.defaultApi(),
				task -> MainApplication.worker.submit(task),
				new AutoCloseOrchestrator.RememberList() {
					@Override
					public boolean contains(Note note) {
						return rememberedNotes != null && rememberedNotes.containsNote(note);
					}

					@Override
					public void remove(Note note) {
						if (rememberedNotes != null) {
							rememberedNotes.remove(note);
						}
					}
				},
				new AutoCloseOrchestrator.Callbacks() {
					@Override
					public boolean isManageUploadInFlight(long noteId) {
						return NoteManageService.isInFlight(noteId);
					}

					@Override
					public boolean isCloseable(Note note) {
						String cOnlineStatus = getOnlineNoteStatus(note.getId());
						return note.getState() == State.OPEN
								&& cOnlineStatus.toLowerCase().trim().equals("open");
					}

					@Override
					public String noteComment(int csId) {
						return getNoteComment(csId);
					}

					@Override
					public void onFailedUi(long noteId, String detail) {
						GuiHelper.runInEDT(() -> JOptionPane.showMessageDialog(null,
								I18n.tr("Upload Note exception:\n{0}", detail)));
					}

					@Override
					public void onAlreadyClosedUi(long noteId) {
						GuiHelper.runInEDT(() -> JOptionPane.showMessageDialog(null,
								I18n.tr("Note {0} was already closed outside of JOSM", Long.toString(noteId))));
					}

					@Override
					public void refreshUi() {
						GuiHelper.runInEDT(() -> {
							updateMenu();
							refreshRememberedNotesOnMap();
						});
					}
				},
				null);
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
				if (rememberedNotes != null && rememberedNotes.containsNote(selectedNote)) {
					rememberedNotes.remove(selectedNote);
					AutoCloseDiagnostics.rememberListChanged("forget", selectedNote.getId());
				}
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
				if (rememberedNotes != null && !rememberedNotes.containsNote(selectedNote)) {
					rememberedNotes.add(selectedNote);
					AutoCloseDiagnostics.rememberListChanged("remember", selectedNote.getId());
				}
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
	private final JosmAction manageNoteAction = new JosmAction() {
		private static final long serialVersionUID = 1927873880648933882L;
		@Override
		public void actionPerformed(ActionEvent event) {
			manageSelectedNote();
		}
	};

	/**
	 * Manual comment/close via JOSM {@link org.openstreetmap.josm.io.OsmApi} (blocking worker upload).
	 * Does not arm or clear {@link NoteClosePending}. {@code rememberedNotes} is updated only after
	 * a confirmed server close — never after local staging or {@link NoteManageService.Kind#STARTED}.
	 */
	void manageSelectedNote() {
		if (selectedNote == null) {
			JOptionPane.showMessageDialog(null, I18n.tr("No Note selected."));
			return;
		}
		boolean closedLocally = selectedNote.getState() == State.CLOSED;
		String online = getOnlineNoteStatus(selectedNote.getId());
		boolean closedOnline = online != null && online.toLowerCase().trim().equals("closed");
		if (closedLocally || closedOnline) {
			JOptionPane.showMessageDialog(null, I18n.tr("Note {0} is already closed.", Long.toString(selectedNote.getId())));
			return;
		}
		if ("failure".equals(online)) {
			int cont = JOptionPane.showConfirmDialog(null,
				I18n.tr("Could not check note status online. Continue with the local note state?"),
				I18n.tr("Manage note"),
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (cont != JOptionPane.YES_OPTION) {
				return;
			}
		}

		ManageNoteDialog.Decision decision = ManageNoteDialog.showDialog();
		if (decision == null) {
			return;
		}

		final Note note = selectedNote;
		if (decision.close
				&& (closePending.phase() != NoteClosePending.Phase.IDLE
						&& closePending.noteIds().contains(note.getId())
						|| autoCloseOrchestrator.isAutoCloseInFlight(note.getId()))) {
			JOptionPane.showMessageDialog(null,
				I18n.tr("This note is already queued for automatic closing after upload. Wait for that to finish, or forget the note first."));
			return;
		}

		NoteManageService.Outcome started = NoteManageService.start(
			note,
			decision.comment,
			decision.close,
			false,
			new NoteManageOsmUpload(),
			outcome -> GuiHelper.runInEDT(() -> onManageNoteFinished(note, outcome)),
			task -> MainApplication.worker.submit(task)
		);

		if (started.kind == NoteManageService.Kind.EMPTY) {
			JOptionPane.showMessageDialog(null, I18n.tr("Please select a hashtag or enter a comment."));
			return;
		}
		if (started.kind == NoteManageService.Kind.BUSY) {
			JOptionPane.showMessageDialog(null, I18n.tr("A note update is already in progress."));
			return;
		}
		if (started.kind == NoteManageService.Kind.ALREADY_CLOSED) {
			JOptionPane.showMessageDialog(null, I18n.tr("Note {0} is already closed.", Long.toString(note.getId())));
		}
		// STARTED: no success UI and no remember change until onManageNoteFinished.
	}

	private void onManageNoteFinished(Note note, NoteManageService.Outcome outcome) {
		if (outcome.kind == NoteManageService.Kind.ERROR) {
			JOptionPane.showMessageDialog(null, I18n.tr("Could not update note:\n{0}", outcome.message));
			updateMenu();
			return;
		}
		if (outcome.kind == NoteManageService.Kind.CANCELLED) {
			JOptionPane.showMessageDialog(null, I18n.tr("Note update was cancelled."));
			updateMenu();
			return;
		}
		if (NoteManageService.shouldForgetRemembered(outcome)
				&& rememberedNotes != null && rememberedNotes.containsNote(note)) {
			rememberedNotes.remove(note);
			refreshRememberedNotesOnMap();
		}
		updateMenu();
	}
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
			refreshRememberedNotesOnMap();
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
			if (e.getRemovedLayer() instanceof NoteLayer) {
				refreshRememberedNotesOnMap();
			}
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
		noteSolverMenu.addSeparator();
		noteSolverMenu.add(createOpenDiagnosticLogFolderItem());
		noteSolverMenu.add(createVerboseDiagnosticsItem());
		noteSolverMenu.add(createMenuItem(I18n.tr("About"), I18n.tr("About noteSolver Plugin"), aboutDialog, true));
	}

	private JMenuItem createOpenDiagnosticLogFolderItem() {
		return createMenuItem(
				I18n.tr("Open diagnostic log folder"),
				I18n.tr("Open the folder where noteSolver stores persistent diagnostic logs"),
				ev -> openDiagnosticLogFolder(),
				true);
	}

	private JCheckBoxMenuItem createVerboseDiagnosticsItem() {
		JCheckBoxMenuItem item = new JCheckBoxMenuItem(
				I18n.tr("Enable verbose diagnostics"),
				Config.getPref().getBoolean(DiagnosticLog.PREF_VERBOSE, false));
		item.setToolTipText(I18n.tr("Log additional DEBUG detail to the diagnostic log file"));
		item.addActionListener(ev -> Config.getPref().putBoolean(DiagnosticLog.PREF_VERBOSE, item.isSelected()));
		return item;
	}

	private void openDiagnosticLogFolder() {
		java.nio.file.Path dir = DiagnosticLog.get().getLogDirectory();
		if (dir == null) {
			JOptionPane.showMessageDialog(null, I18n.tr("Diagnostic logging is not active."));
			return;
		}
		try {
			java.io.File folder = dir.toFile();
			if (!folder.exists()) {
				folder.mkdirs();
			}
			if (Desktop.isDesktopSupported()) {
				Desktop.getDesktop().open(folder);
			} else {
				JOptionPane.showMessageDialog(null, folder.getAbsolutePath());
			}
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null, I18n.tr("Could not open log folder:\n{0}", e.getMessage()));
		}
	}

	private List<JMenuItem> mainMenuEntries(Enum<menuTypes> menuType) {
		List<JMenuItem> returnList = new ArrayList<>();
		if (menuType == menuTypes.MAIN) {
			boolean bEnaOpn = selectedNote != null;
			boolean bEnaPre = selectedNote != null && rememberedNotes != null;
			boolean bEnaLst = (bEnaPre && rememberedNotes.containsNote(selectedNote));
			boolean bEnaCls = (bEnaPre && selectedNote.getState() == Note.State.CLOSED);
			boolean bEnaMng = selectedNote != null && selectedNote.getState() == Note.State.OPEN;
			returnList.add(
				createMenuItem(
					I18n.tr("Open in browser"),
					I18n.tr("Try to open the Note on osm.org with the operating system's standard browser"),
					openNoteAction, bEnaOpn
				)
			);
			returnList.add(
				createMenuItem(
					I18n.tr("Manage note…"),
					I18n.tr("Comment on or close the selected Note with an optional hashtag"),
					manageNoteAction, bEnaMng
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
									AutoCloseDiagnostics.rememberListChanged("forgetMenu", note.getId());
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
		// Default uses bare id + PluginInformation.version (not getUrl → avoids "changeset changeset/N").
		// Custom overrideNoteComment is left untouched; ##noteSolver:CID## / ##noteSolver:LC## still work.
		String version = myPluginInformation != null ? myPluginInformation.version : "";
		String retVal = CommentTemplate.defaultResolvedNoteComment(changesetID, version);
		if (Config.getPref().get("noteSolver.overrideNoteComment") != "") {
			retVal = CommentTemplate.formatNoteComment(
					Config.getPref().get("noteSolver.overrideNoteComment"),
					changesetID,
					getUrl(changesetID, linkTypes.CHANGESET));
		} else if (Config.getPref().getBoolean("noteSolver.useLocalLanguageInNote")) {
			retVal = I18n.tr("Resolved with changeset {0} by noteSolver_plugin/{1}",
					Integer.toString(changesetID), version);
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
