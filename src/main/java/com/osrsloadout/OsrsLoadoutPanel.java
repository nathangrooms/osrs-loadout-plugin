/*
 * Copyright (c) 2026, osrsloadout
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrsloadout;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The panel exists because the settings panel could not say any of this.
 *
 * RuneLite renders a config item as whatever its return type suggests - a boolean is a checkbox, a String is
 * a text box - and there is no type that is a button or a paragraph. So three actions had to ship as three
 * checkboxes you tick and which untick themselves, the explanation of each was a tooltip nobody hovers, and
 * the link code lived in a chat line that scrolls away. Every one of those is the same defect: the thing the
 * player needs is present but not visible.
 *
 * Here the state is a sentence, the code is a code with a Copy button beside it, and the three actions are
 * three buttons that say what they do. Nothing here is a preference; the one real setting stays in the
 * config panel where settings belong.
 */
class OsrsLoadoutPanel extends PluginPanel
{
	/** What the panel can ask the plugin to do. Implemented by the plugin, so the panel owns no game state. */
	interface Actions
	{
		void syncNow();

		void newCode();

		void resetKey();
	}

	private static final Color GOOD = new Color(0x4C, 0xAF, 0x50);
	private static final String SITE = "https://www.osrsloadout.com/";

	private final Actions actions;

	private final JLabel status = new JLabel();
	private final JLabel detail = new JLabel();
	private final JPanel codeBox = new JPanel(new BorderLayout(6, 0));
	private final JLabel code = new JLabel();
	private final JButton copy = new JButton("Copy");
	private final JLabel codeHint = new JLabel();
	private final JButton sync = new JButton("Sync my bank now");
	private final JButton link = new JButton("Get a link code");
	private final JButton reset = new JButton("Reset sync key");

	OsrsLoadoutPanel(Actions actions)
	{
		super(false);
		this.actions = actions;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("OSRS Loadout");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		body.add(left(title));
		body.add(Box.createVerticalStrut(10));

		status.setFont(FontManager.getRunescapeSmallFont());
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(left(status));
		body.add(Box.createVerticalStrut(3));
		body.add(left(detail));
		body.add(Box.createVerticalStrut(12));

		// The code, big enough to read off and copy in one press, because reading eight characters off a
		// chat line and typing them into a browser is the only thing this plugin ever asks anybody to do.
		code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
		code.setForeground(Color.WHITE);
		code.setHorizontalAlignment(JLabel.CENTER);
		copy.setToolTipText("Copy the code to the clipboard");
		copy.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(code.getText()), null);
			copy.setText("Copied");
			new javax.swing.Timer(1500, ev -> copy.setText("Copy"))
			{
				{
					setRepeats(false);
				}
			}.start();
		});
		codeBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		codeBox.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		codeBox.add(code, BorderLayout.CENTER);
		codeBox.add(copy, BorderLayout.EAST);
		codeBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		body.add(codeBox);

		codeHint.setFont(FontManager.getRunescapeSmallFont());
		codeHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		body.add(Box.createVerticalStrut(4));
		body.add(left(codeHint));
		body.add(Box.createVerticalStrut(12));

		final JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 5));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sync.setToolTipText("Upload your bank again now, even if nothing has changed");
		sync.addActionListener(e -> actions.syncNow());
		link.setToolTipText("Get a fresh code to link another browser");
		link.addActionListener(e -> actions.newCode());
		reset.setToolTipText("Move your bank to a new address and unlink every browser");
		reset.addActionListener(e -> confirmReset());
		buttons.add(sync);
		buttons.add(link);
		buttons.add(reset);
		body.add(buttons);
		body.add(Box.createVerticalStrut(14));

		final JButton open = new JButton("Open osrsloadout.com");
		open.addActionListener(e -> LinkBrowser.browse(SITE));
		body.add(open);
		body.add(Box.createVerticalStrut(14));

		body.add(left(help()));

		add(body, BorderLayout.NORTH);
		setLinked(false, null, 0, 0);
	}

	/** Three steps, in the order they happen, and no fourth. */
	private JLabel help()
	{
		final JLabel l = new JLabel("<html><body style='width:170px'>"
			+ "<b>How this works</b><br>"
			+ "1. Open a bank in game.<br>"
			+ "2. Type the code above at osrsloadout.com.<br>"
			+ "3. Nothing else, ever &mdash; every bank you open updates the site on its own.<br><br>"
			+ "Your bank is stored under a key only this RuneLite install holds, so knowing your "
			+ "character name gets nobody anything."
			+ "</body></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	/**
	 * Resetting is the one thing here that cannot be undone and that breaks something the player set up, so
	 * it asks - and it says what actually happens rather than "are you sure".
	 */
	private void confirmReset()
	{
		final int a = JOptionPane.showConfirmDialog(this,
			"This moves your bank to a new address.\n\n"
				+ "Every browser you have linked stops seeing it, on every device, and each one needs a "
				+ "new code. This cannot be undone.\n\nReset the key?",
			"Reset sync key", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (a == JOptionPane.YES_OPTION)
		{
			actions.resetKey();
		}
	}

	/** Left-aligns a label in the vertical box, which BoxLayout will otherwise centre. */
	private static Component left(JLabel l)
	{
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/**
	 * @param linked whether a browser has ever claimed a code for this key
	 * @param when   a human-readable time of the last successful upload, or null
	 * @param items  how many items that upload carried
	 * @param placeholders how many bank slots were ignored as placeholders
	 */
	void setLinked(boolean linked, String when, int items, int placeholders)
	{
		SwingUtilities.invokeLater(() -> {
			status.setText(linked ? "Linked" : "Not linked yet");
			status.setForeground(linked ? GOOD : ColorScheme.PROGRESS_INPROGRESS_COLOR);
			detail.setText(when == null
				? "<html>No bank read yet. Open one in game.</html>"
				: "<html>Last sync: " + items + " items at " + when
					+ (placeholders > 0 ? ", " + placeholders + " placeholders ignored" : "")
					+ "</html>");
			link.setText(linked ? "Link another browser" : "Get a link code");
		});
	}

	/** A code to show, or null to hide the box entirely rather than show an empty one. */
	void setCode(String value)
	{
		SwingUtilities.invokeLater(() -> {
			final boolean has = value != null && !value.isEmpty();
			codeBox.setVisible(has);
			codeHint.setVisible(has);
			if (has)
			{
				code.setText(value);
				codeHint.setText("<html><body style='width:170px'>Type this at osrsloadout.com. "
					+ "It lasts ten minutes and works once.</body></html>");
			}
			revalidate();
			repaint();
		});
	}
}
