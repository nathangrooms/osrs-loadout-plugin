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
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Everything you can DO lives here; the one thing you can SET lives in the config panel.
 *
 * That split is the whole design. RuneLite renders a config item as whatever its return type suggests, so
 * an action has to ship as a checkbox that unticks itself - and three of those stacked up read as settings
 * somebody forgot to turn on. Buttons need a panel. But having controls in both places is worse than
 * having them in the wrong one, so the config panel keeps exactly one item: whether to sync at all, which
 * is a genuine preference and a Plugin Hub disclosure requirement.
 *
 * On width: this panel is 225px wide and everything in it has to say so. The first version used HTML
 * labels with a hardcoded width, which is a suggestion rather than a constraint - a long word pushes past
 * it - so half the text was clipped off the right edge. Wrapped text areas sized against PANEL_WIDTH
 * cannot do that.
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
	/**
	 * The width text may actually occupy. PANEL_WIDTH is the panel; the scrollbar takes SCROLLBAR_WIDTH
	 * out of it whenever the content is taller than the window, and this panel's own padding takes 20
	 * more. Measuring wraps against PANEL_WIDTH alone is why the first two versions clipped their last
	 * line: the text was laid out for a width it never got.
	 */
	private static final int INNER = PANEL_WIDTH - SCROLLBAR_WIDTH - 20;

	private final Actions actions;

	private final java.util.List<JTextArea> wraps = new java.util.ArrayList<>();

	private final JLabel status = new JLabel();
	private final JTextArea detail = wrapped();
	private final JPanel codeBox = new JPanel(new BorderLayout(6, 0));
	private final JLabel code = new JLabel();
	private final JButton copy = new JButton("Copy");
	private final JTextArea codeHint = wrapped();
	private final JButton sync = new JButton("Sync my bank now");
	private final JButton link = new JButton("Get a link code");

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

		status.setFont(FontManager.getRunescapeBoldFont());
		body.add(row(status));
		body.add(Box.createVerticalStrut(4));
		body.add(row(detail));
		body.add(Box.createVerticalStrut(14));

		// The code, big enough to read off in one glance and copy in one press, because reading eight
		// characters off a chat line and typing them into a browser is the only thing this ever asks anyone
		// to do.
		code.setFont(new Font(Font.MONOSPACED, Font.BOLD, 17));
		code.setForeground(Color.WHITE);
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setMargin(new java.awt.Insets(2, 6, 2, 6));
		copy.addActionListener(e -> {
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(code.getText()), null);
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

		final JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sync.setToolTipText("Upload your bank to osrsloadout.com now");
		sync.addActionListener(e -> actions.syncNow());
		link.setToolTipText("Get a fresh code to link another browser");
		link.addActionListener(e -> actions.newCode());
		final JButton reset = new JButton("Reset sync key");
		reset.setToolTipText("Move your bank to a new address and unlink every browser");
		reset.addActionListener(e -> confirmReset());
		buttons.add(sync);
		buttons.add(link);
		buttons.add(reset);
		body.add(row(buttons));
		body.add(Box.createVerticalStrut(14));

		final JTextArea help = wrapped();
		help.setText("Open a bank, press Sync, then type the code at osrsloadout.com. "
			+ "After that the code is only needed for a new browser.");
		body.add(row(help));

		add(body, BorderLayout.NORTH);
		setLinked(false, null, 0, 0, false);
		setCode(null);
	}

	/**
	 * Text that wraps to the panel instead of running off it. A JLabel would need HTML and a width, and an
	 * HTML width is a hint - one long word and it is over the edge, which is exactly how the first version
	 * of this panel clipped half of its own sentences.
	 */
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

	/** Pins a component to the panel's width so BoxLayout cannot stretch or centre it. */
	private static Component row(Component c)
	{
		// Deliberately no preferred HEIGHT for wrapped text: its height depends on how many lines the
		// text takes at this width, which is not known until it has one. resize() works that out.
		c.setMaximumSize(new Dimension(INNER, Integer.MAX_VALUE));
		if (!(c instanceof JTextArea))
		{
			c.setPreferredSize(new Dimension(INNER, c.getPreferredSize().height));
		}
		if (c instanceof JPanel || c instanceof JLabel || c instanceof JTextArea)
		{
			((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		return c;
	}

	/**
	 * Resetting is the one thing here that cannot be undone and that breaks something the player set up, so
	 * it asks - and says what actually happens rather than "are you sure".
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

	/**
	 * @param linked       whether a browser has ever claimed a code for this key
	 * @param when         a human-readable time of the last successful upload, or null
	 * @param items        how many items that upload carried
	 * @param placeholders how many bank slots were ignored as placeholders
	 * @param syncEnabled  whether the plugin is allowed to talk to the server at all
	 */
	void setLinked(boolean linked, String when, int items, int placeholders, boolean syncEnabled)
	{
		SwingUtilities.invokeLater(() -> {
			status.setText(!syncEnabled ? "Syncing off" : linked ? "Linked" : "Not linked yet");
			status.setForeground(!syncEnabled ? ColorScheme.LIGHT_GRAY_COLOR
				: linked ? GOOD : ColorScheme.PROGRESS_INPROGRESS_COLOR);
			detail.setText(!syncEnabled
				? "Syncing is off. Tick \"Sync my bank to osrsloadout.com\" in this plugin's settings to "
					+ "turn it on."
				: when == null
				? "No bank read yet. Open one in game, then press Sync."
				: items + " items at " + when
					+ (placeholders > 0 ? ", " + placeholders + " placeholders ignored" : ""));
			link.setText(linked ? "Link another browser" : "Get a link code");
			resize();
		});
	}

	/** A code to show, or null to hide the box rather than show an empty one. */
	void setCode(String value)
	{
		SwingUtilities.invokeLater(() -> {
			final boolean has = value != null && !value.isEmpty();
			codeBox.setVisible(has);
			codeHint.setVisible(has);
			if (has)
			{
				code.setText(value);
				codeHint.setText("Type at osrsloadout.com. Ten minutes, one use.");
			}
			resize();
		});
	}

	/**
	 * A wrapped text area's height depends on its text, so it has to be re-measured when the text moves.
	 *
	 * The clearing step is the whole trick. getPreferredSize() returns the value set by setPreferredSize()
	 * if there is one, rather than asking the UI delegate to lay the text out again - so measuring after
	 * having set it just reads back the previous answer, and every area stays at the height it happened to
	 * have the first time. codeHint starts empty and hidden, which is one line, so "Type at
	 * osrsloadout.com. Ten minutes, one use." was clipped to "Ten" forever after.
	 */
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
