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

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Collection;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "OSRS Loadout",
	description = "Copies a link to your bank, equipment and inventory for osrsloadout.com. Nothing is uploaded.",
	tags = {"bank", "gear", "loadout", "link", "clipboard", "export"}
)
public class OsrsLoadoutPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	/**
	 * RuneLite's shared worker pool. The clipboard is the one thing here that can block: on Windows and X11
	 * writing it means negotiating with whatever process currently owns it, and a stall on the game thread is
	 * a visible freeze. runelite-client's own ImageCapture moves off the client thread for exactly this.
	 */
	@Inject
	private ScheduledExecutorService executor;

	/**
	 * Set when the bank interface loads, cleared once we have read it. This is the whole debounce: the bank
	 * finishes building on every tab switch, every search keystroke and every withdrawal, and we want one
	 * copy per visit to a banker rather than dozens.
	 */
	private boolean pendingCapture;

	@Override
	protected void shutDown()
	{
		pendingCapture = false;
	}

	/**
	 * WidgetLoaded fires once when the bank opens and not again until it is closed and reopened, which makes
	 * it the correct signal for "the player has just opened a bank". runelite-client's own BankPlugin tracks
	 * bankOpen off this same event and group id.
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			pendingCapture = true;
		}
	}

	/**
	 * The bank container is not read here on WidgetLoaded, because the interface opening and the server
	 * having sent the container are two different things. BANKMAIN_FINISHBUILDING runs after the client has
	 * laid the bank out from that container, so by definition the container is present and current. This is
	 * also why ItemContainerChanged is not used as the trigger: it fires when a stack size changes, so on a
	 * bank that has not changed since login there is nothing to fire, and the player would open their bank
	 * and get nothing. BankPlugin computes its bank value on this same script for the same reason.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING || !pendingCapture)
		{
			return;
		}

		pendingCapture = false;
		capture();
	}

	/**
	 * Runs on the client thread, as an event handler: ItemManager#canonicalize reaches through to
	 * Client#getItemDefinition, and the item containers are live game state, so neither may be touched from
	 * anywhere else.
	 */
	private void capture()
	{
		// A set, not a list, so that the count reported in chat is the number of distinct items the link
		// actually carries rather than the number of bank slots walked.
		final TreeSet<Integer> ids = new TreeSet<>();

		// Equipment and inventory are included alongside the bank because the question the site asks is
		// "what do you own", and a player's best items are usually the ones they are wearing. Reading the
		// bank alone would tell the planner they do not own their own gear. This costs no extra user-facing
		// surface: it happens in the same pass, on the same trigger.
		collect(InventoryID.BANK, ids);
		collect(InventoryID.WORN, ids);
		collect(InventoryID.INV, ids);

		if (ids.isEmpty())
		{
			// The site rejects an empty set, so an empty link is worse than no link. This is reachable:
			// a brand new account opening an empty bank with nothing worn and nothing carried.
			log.debug("Nothing to copy");
			return;
		}

		final String url = LoadoutLink.url(LoadoutLink.pack(ids));
		final int count = ids.size();

		executor.execute(() -> copyAndAnnounce(url, count));
	}

	private void collect(int containerId, Collection<Integer> into)
	{
		final ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}

		for (Item item : container.getItems())
		{
			final int id = item.getId();

			// A placeholder is stored in the bank as the item with a quantity of zero. The player does not
			// own it, so sending it would make the planner claim gear they have already spent. Filtering on
			// quantity catches this and the empty slots the container reports, in one condition.
			if (id <= 0 || item.getQuantity() <= 0 || id == ItemID.BANK_FILLER)
			{
				continue;
			}

			// canonicalize resolves a noted id to its unnoted one, a placeholder to the real item, and the
			// "worn" variants some equipment has to the base id. Doing it by hand via getNote() and
			// getLinkedNoteId() would reimplement this and miss the last case.
			into.add(itemManager.canonicalize(id));
		}
	}

	/**
	 * Off the client thread. ChatMessageManager#queue is a ConcurrentLinkedQueue add that the client thread
	 * drains, so it is safe to call from here; it is the only part of RuneLite that is.
	 */
	private void copyAndAnnounce(String url, int count)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
		}
		catch (Exception e)
		{
			// A headless or locked-down desktop can refuse clipboard access outright. Telling the player is
			// more use than a silent failure they would read as the plugin not working at all.
			log.warn("Could not write to the clipboard", e);
			say("OSRS Loadout could not write to your clipboard.");
			return;
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.NORMAL)
				.append("Copied a link to your ")
				.append(ChatColorType.HIGHLIGHT)
				.append(Integer.toString(count))
				.append(ChatColorType.NORMAL)
				.append(" items. Paste it into your browser to open it on osrsloadout.com.")
				.build())
			.build());
	}

	private void say(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.NORMAL)
				.append(message)
				.build())
			.build());
	}
}
