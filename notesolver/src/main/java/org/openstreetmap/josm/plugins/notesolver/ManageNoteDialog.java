package org.openstreetmap.josm.plugins.notesolver;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openstreetmap.josm.tools.I18n;

/**
 * Dialog: pick hashtag, optional free text, preview, comment-only vs close.
 */
public final class ManageNoteDialog {

	public static final class Decision {
		public final String comment;
		public final boolean close;

		public Decision(String comment, boolean close) {
			this.comment = comment;
			this.close = close;
		}
	}

	private static final String NONE = I18n.tr("(no hashtag)");

	private ManageNoteDialog() {
	}

	/**
	 * @return decision on OK, or null on cancel / empty comment
	 */
	public static Decision showDialog() {
		JComboBox<Object> hashtagBox = new JComboBox<>();
		hashtagBox.addItem(NONE);
		for (NoteHashtag h : NoteHashtags.all()) {
			hashtagBox.addItem(h);
		}

		JTextArea freeText = new JTextArea(4, 40);
		freeText.setLineWrap(true);
		freeText.setWrapStyleWord(true);

		JTextArea preview = new JTextArea(4, 40);
		preview.setEditable(false);
		preview.setLineWrap(true);
		preview.setWrapStyleWord(true);
		preview.setBackground(freeText.getBackground());

		JRadioButton commentOnly = new JRadioButton(I18n.tr("Comment only"));
		JRadioButton commentAndClose = new JRadioButton(I18n.tr("Comment and close"));
		ButtonGroup group = new ButtonGroup();
		group.add(commentOnly);
		group.add(commentAndClose);
		commentOnly.setSelected(true);

		Runnable refresh = () -> {
			NoteHashtag selected = selectedHashtag(hashtagBox);
			preview.setText(NoteManageComposer.preview(selected, freeText.getText()));
		};

		hashtagBox.addItemListener(e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) {
				return;
			}
			NoteHashtag selected = selectedHashtag(hashtagBox);
			String suggested = NoteManageComposer.suggestedFreeText(selected);
			if (freeText.getText().trim().isEmpty() && !suggested.isEmpty()) {
				freeText.setText(suggested);
			}
			boolean defClose = NoteManageComposer.defaultClose(selected);
			commentAndClose.setSelected(defClose);
			commentOnly.setSelected(!defClose);
			refresh.run();
		});

		DocumentListener dl = new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { refresh.run(); }
			@Override public void removeUpdate(DocumentEvent e) { refresh.run(); }
			@Override public void changedUpdate(DocumentEvent e) { refresh.run(); }
		};
		freeText.getDocument().addDocumentListener(dl);
		refresh.run();

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(2, 2, 2, 2);
		form.add(new JLabel(I18n.tr("Hashtag:")), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(hashtagBox, c);

		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		form.add(new JLabel(I18n.tr("Comment:")), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		JScrollPane freeScroll = new JScrollPane(freeText);
		freeScroll.setPreferredSize(new Dimension(420, 80));
		form.add(freeScroll, c);

		c.gridx = 0;
		c.gridy = 2;
		c.weighty = 0;
		c.fill = GridBagConstraints.NONE;
		form.add(new JLabel(I18n.tr("Preview:")), c);
		c.gridx = 1;
		c.fill = GridBagConstraints.BOTH;
		c.weighty = 1;
		JScrollPane previewScroll = new JScrollPane(preview);
		previewScroll.setPreferredSize(new Dimension(420, 80));
		form.add(previewScroll, c);

		c.gridx = 1;
		c.gridy = 3;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		JPanel actions = new JPanel(new BorderLayout());
		actions.add(commentOnly, BorderLayout.NORTH);
		actions.add(commentAndClose, BorderLayout.SOUTH);
		form.add(actions, c);

		Object[] options = {
			I18n.tr("Apply"),
			I18n.tr("Cancel")
		};
		int result = JOptionPane.showOptionDialog(
			null,
			form,
			I18n.tr("Manage note"),
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE,
			null,
			options,
			options[0]
		);
		if (result != 0) {
			return null;
		}
		String comment = preview.getText();
		if (NoteManageComposer.isBlank(comment)) {
			JOptionPane.showMessageDialog(null,
				I18n.tr("Please select a hashtag or enter a comment."),
				I18n.tr("Manage note"),
				JOptionPane.WARNING_MESSAGE);
			return null;
		}
		return new Decision(comment, commentAndClose.isSelected());
	}

	private static NoteHashtag selectedHashtag(JComboBox<Object> box) {
		Object item = box.getSelectedItem();
		return item instanceof NoteHashtag ? (NoteHashtag) item : null;
	}
}
