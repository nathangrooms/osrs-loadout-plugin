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

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "OSRS Loadout",
	description = "Syncs your bank to osrsloadout.com when you open a bank. Uploads your item ids and "
		+ "character name to a third party server.",
	tags = {"bank", "gear", "loadout", "sync", "export"}
)
public class OsrsLoadoutPlugin extends Plugin
{
	static final String CONFIG_GROUP = "osrsloadout";

	/**
	 * Not a @ConfigItem. Keeping it out of the config interface keeps it out of the settings panel, so there
	 * is nothing for a player to read out to someone who asks nicely.
	 */
	private static final String SECRET_KEY = "syncKey";

	private static final MediaType JSON = MediaType.parse("application/json");

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OsrsLoadoutConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	/**
	 * Set when the bank interface loads, cleared once we have read it. This is the whole debounce: the bank
	 * finishes building on every tab switch, every search keystroke and every withdrawal, and we want at most
	 * one request per visit to a banker rather than dozens.
	 */
	private boolean pendingCapture;

	/**
	 * The last set of ids accepted by the server, and who it was for. A hash would do, but the set itself is
	 * about four kilobytes for a full bank and comparing it is exact, so there is no reason to introduce a
	 * collision that would silently stop syncing.
	 */
	private Set<Integer> lastSent;
	private String lastSentRsn;

	/**
	 * Latched on a 409. The server is telling us another install already owns this name, which will still be
	 * true on the next bank and the one after that, so retrying is pure noise for both ends.
	 */
	private boolean nameClaimed;

	private boolean announced;

	@Provides
	OsrsLoadoutConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsLoadoutConfig.class);
	}

	@Override
	protected void shutDown()
	{
		pendingCapture = false;
		forgetSession();
	}

	/**
	 * Logging out is the one moment the character behind the bank can change, so it is where the memo of what
	 * was last sent has to be dropped. Keying the memo on the name as well means a world hop, which does not
	 * pass through the login screen, cannot make us skip a sync for a different account either.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			forgetSession();
		}
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
	 * The bank is not read on WidgetLoaded, because the interface opening and the server having sent the
	 * container are two different things. BANKMAIN_FINISHBUILDING runs after the client has laid the bank out
	 * from that container, so by definition the container is present and current. This is also why
	 * ItemContainerChanged is not the trigger: it fires when a stack size changes, so a bank nobody has
	 * touched since logging in would never fire it, and that player would never sync at all.
	 * runelite-client's own BankPlugin computes its bank value on this same script for the same reason.
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

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"resetSyncKey".equals(event.getKey()))
		{
			return;
		}

		if (config.resetSyncKey())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, SECRET_KEY);
			forgetSession();
			// The toggle is really a button, so it puts itself back up rather than sitting on and looking
			// like a mode the plugin is now in.
			configManager.setConfiguration(CONFIG_GROUP, "resetSyncKey", false);
			say("Sync key reset. The next bank you open will claim this character for this install.");
		}
	}

	/**
	 * Runs on the client thread, as an event handler: ItemManager#canonicalize reaches through to
	 * Client#getItemDefinition, and the item containers and the local player are live game state, so none of
	 * it may be touched from anywhere else. Only the request leaves this thread.
	 */
	private void capture()
	{
		if (!config.sync() || nameClaimed)
		{
			return;
		}

		final Player local = client.getLocalPlayer();
		final String rsn = local == null ? null : local.getName();
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}

		final TreeSet<Integer> ids = new TreeSet<>();

		// Equipment and inventory are included alongside the bank because the question the site is asking is
		// "what do you own", and a player's best items are usually the ones they are wearing. A bank-only
		// read would tell the planner you do not own your own gear, which is the one thing it must not get
		// wrong. It costs nothing: same trigger, same pass, same request.
		collect(InventoryID.BANK, ids);
		collect(InventoryID.WORN, ids);
		collect(InventoryID.INV, ids);

		if (ids.isEmpty())
		{
			// The server rejects an empty list, so there is nothing to gain by asking it to.
			return;
		}

		if (ids.equals(lastSent) && rsn.equals(lastSentRsn))
		{
			// Idly reopening the bank is not a request.
			return;
		}

		send(rsn, ids);
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
			// "worn" variants some equipment has to the base id. Doing this by hand via getNote() and
			// getLinkedNoteId() would reimplement it and still miss the last case.
			into.add(itemManager.canonicalize(id));
		}
	}

	/**
	 * enqueue, not execute: OkHttp dispatches this on its own pool, so the game thread returns immediately
	 * and a slow or unreachable server costs the player nothing. Nothing is retried here. The next bank is
	 * the retry, and it arrives on its own.
	 */
	private void send(String rsn, Set<Integer> ids)
	{
		final Request request = new Request.Builder()
			.url(LoadoutLink.ENDPOINT)
			.post(RequestBody.create(JSON, LoadoutLink.json(gson, rsn, ids, secret())))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// Offline, or the server is down. Silent by design: a chat line every time someone banks
				// without a connection is worse than not syncing.
				log.debug("Bank sync failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					onResult(rsn, ids, r.code());
				}
			}
		});
	}

	/**
	 * On an OkHttp dispatcher thread. Everything touched here is either a field this class owns or
	 * ChatMessageManager#queue, which is an add to a ConcurrentLinkedQueue that the client thread drains.
	 */
	private void onResult(String rsn, Set<Integer> ids, int code)
	{
		if (code == 409)
		{
			nameClaimed = true;
			say("This character was first synced from another RuneLite install, so this one cannot update "
				+ "it. Reset the sync key in the OSRS Loadout settings, here or there, to move it.");
			return;
		}

		if (code < 200 || code >= 300)
		{
			log.debug("Bank sync rejected with {}", code);
			return;
		}

		lastSent = ids;
		lastSentRsn = rsn;

		if (!announced)
		{
			announced = true;
			say("Synced " + ids.size() + " items to osrsloadout.com.");
		}
	}

	/**
	 * There are no accounts: the first install to sync a name claims it, and proves itself afterwards with
	 * this key. It is generated once, never shown, and never asked for, which is the entire reason the player
	 * has nothing to set up.
	 */
	private String secret()
	{
		final String existing = configManager.getConfiguration(CONFIG_GROUP, SECRET_KEY);
		if (existing != null && !existing.isEmpty())
		{
			return existing;
		}

		// randomUUID is seeded from SecureRandom, which is what matters here; the hyphens are dropped only so
		// that what is stored is a plain 32-character token.
		final String generated = UUID.randomUUID().toString().replace("-", "");
		configManager.setConfiguration(CONFIG_GROUP, SECRET_KEY, generated);
		return generated;
	}

	private void forgetSession()
	{
		lastSent = null;
		lastSentRsn = null;
		nameClaimed = false;
		announced = false;
	}

	private void say(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("OSRS Loadout: ")
				.append(ChatColorType.NORMAL)
				.append(message)
				.build())
			.build());
	}
}
