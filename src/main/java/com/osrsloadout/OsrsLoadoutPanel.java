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
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** The side panel: sync status, the link code, and the three actions. */
class OsrsLoadoutPanel extends PluginPanel
{
	/** Implemented by the plugin, so the panel holds no game state. */
	interface Actions
	{
		void syncNow();

		void newCode();

		void resetKey();
	}

	private static final Color GOOD = new Color(0x4C, 0xAF, 0x50);
	/** Width available to text: the panel, less the scrollbar and this panel's padding. */
	private static final int INNER = PANEL_WIDTH - SCROLLBAR_WIDTH - 20;

	private final List<JTextArea> wraps = new ArrayList<>();
	private final JLabel status = new JLabel();
	private final JTextArea detail = wrapped();
	private final JPanel codeBox = new JPanel(new BorderLayout(6, 0));
	private final JLabel code = new JLabel();
	private final JTextArea codeHint = wrapped();
	private final JButton link = new JButton("Get a link code");

	OsrsLoadoutPanel(Actions actions)
	{
		super(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		status.setFont(FontManager.getRunescapeBoldFont());
		body.add(row(status));
		body.add(Box.createVerticalStrut(4));
		body.add(row(detail));
		body.add(Box.createVerticalStrut(14));

		final JButton copy = new JButton("Copy");
		code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
		code.setForeground(Color.WHITE);
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setMargin(new Insets(2, 6, 2, 6));
		copy.addActionListener(e ->
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code.getText()), null);
			copy.setText("Copied");
			final Timer t = new Timer(1500, ev -> copy.setText("Copy"));
			t.setRepeats(false);
			t.start();
		});
		codeBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		codeBox.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 7));
		codeBox.add(code, BorderLayout.CENTER);
		codeBox.add(copy, BorderLayout.EAST);
		body.add(row(codeBox));
		body.add(Box.createVerticalStrut(5));
		body.add(row(codeHint));
		body.add(Box.createVerticalStrut(14));

		final JButton sync = new JButton("Sync now");
		sync.setToolTipText("Upload your bank to osrsloadout.com now");
		sync.addActionListener(e -> actions.syncNow());
		link.setToolTipText("Get a code to link a browser to your bank");
		link.addActionListener(e -> actions.newCode());
		final JButton reset = new JButton("Reset sync key");
		reset.setToolTipText("Move your bank to a new key and unlink every browser");
		reset.addActionListener(e -> confirmReset(actions));

		final JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.add(sync);
		buttons.add(link);
		buttons.add(reset);
		body.add(row(buttons));
		body.add(Box.createVerticalStrut(14));

		final JTextArea help = wrapped();
		help.setText("Open your bank, then press Sync now. The first time, a code appears here: enter it at "
			+ "osrsloadout.com to link your browser.");
		body.add(row(help));

		add(body, BorderLayout.NORTH);
		update(false, false, null, 0, null);
	}

	void update(boolean syncOn, boolean linked, String when, int items, String linkCode)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(!syncOn ? "Syncing off" : linked ? "Linked" : "Not linked yet");
			status.setForeground(!syncOn ? ColorScheme.LIGHT_GRAY_COLOR
				: linked ? GOOD : ColorScheme.PROGRESS_INPROGRESS_COLOR);
			detail.setText(!syncOn
				? "Turn on \"Sync bank to osrsloadout.com\" in this plugin's settings."
				: when == null ? "Open your bank, then press Sync now." : "Last synced " + items + " items at " + when + ".");
			link.setText(linked ? "Link another browser" : "Get a link code");

			final boolean hasCode = syncOn && linkCode != null && !linkCode.isEmpty();
			codeBox.setVisible(hasCode);
			codeHint.setVisible(hasCode);
			if (hasCode)
			{
				code.setText(linkCode);
				codeHint.setText("Enter at osrsloadout.com. Valid for ten minutes, once.");
			}
			resize();
		});
	}

	private void confirmReset(Actions actions)
	{
		final int answer = JOptionPane.showConfirmDialog(this,
			"This moves your bank to a new key. Every browser you have linked stops seeing it and needs a new "
				+ "code.\n\nReset the key?",
			"Reset sync key", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer == JOptionPane.YES_OPTION)
		{
			actions.resetKey();
		}
	}

	private JTextArea wrapped()
	{
		final JTextArea a = new JTextArea();
		a.setEditable(false);
		a.setOpaque(false);
		a.setFocusable(false);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setFont(FontManager.getRunescapeSmallFont());
		a.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		a.setBorder(null);
		wraps.add(a);
		return a;
	}

	/** Pins a component to the text width so BoxLayout neither stretches nor centres it. */
	private static Component row(JComponent c)
	{
		c.setMaximumSize(new Dimension(INNER, Integer.MAX_VALUE));
		if (!(c instanceof JTextArea))
		{
			c.setPreferredSize(new Dimension(INNER, c.getPreferredSize().height));
		}
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	/** Re-measures wrapped text; a preferred size left in place would keep each area at its first height. */
	private void resize()
	{
		for (JTextArea a : wraps)
		{
			a.setPreferredSize(null);
			a.setMaximumSize(null);
			a.setSize(INNER, Short.MAX_VALUE);
			final int h = a.getPreferredSize().height;
			a.setPreferredSize(new Dimension(INNER, h));
			a.setMaximumSize(new Dimension(INNER, h));
		}
		revalidate();
		repaint();
	}
}
