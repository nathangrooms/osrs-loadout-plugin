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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Uploads the player's bank, worn equipment and inventory to osrsloadout.com when they press "Sync now" in the
 * panel, so the site can plan gear from what they own. Every request is behind the opt-in {@code sync} setting,
 * and nothing is uploaded automatically: opening a bank only reads it, locally.
 *
 * There are no accounts: the bank is stored against a random key generated on this install. The website is
 * linked to it by a one-time code shown in the panel and in chat.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Loadout",
	description = "Sends your bank, worn equipment and inventory to osrsloadout.com when you press Sync, so the "
		+ "site can plan gear from what you own. Off until you turn it on in the plugin's settings.",
	tags = {"bank", "gear", "loadout", "sync"}
)
public class OsrsLoadoutPlugin extends Plugin
{
	static final String CONFIG_GROUP = "osrsloadout";

	// Stored with ConfigManager but not declared in the config interface, so they never show in the settings.
	private static final String SECRET_KEY = "syncKey";
	private static final String LINKED_KEY = "linked";
	private static final String LINK_CODE_KEY = "linkCode";

	private static final MediaType JSON = MediaType.parse("application/json");
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	/** How long "Sync now" waits between uploads, to spare the server repeated presses. */
	private static final long SYNC_COOLDOWN_MS = 30_000;
	/** No real bank has this many slots; it bounds the request size if a container is ever malformed. */
	private static final int MAX_SLOTS = 2000;
	private static final int[] TAB_VARBITS = {
		VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4, VarbitID.BANK_TAB_5,
		VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9,
	};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

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

	@Inject
	private ClientToolbar clientToolbar;

	private OsrsLoadoutPanel panel;
	private NavigationButton navButton;

	/** Set when the bank opens; the bank is read once it has finished building. */
	private boolean pendingRead;
	/** The latest reading of the bank, kept so "Sync now" works with the bank closed. Client thread only. */
	private Snapshot lastSnapshot;
	/** When "Sync now" last sent an upload. Client thread only. */
	private long lastSyncPress;

	// Written from OkHttp callbacks, read on the client thread and the EDT.
	private volatile String lastSyncAt;
	private volatile int lastSyncItems;

	@Provides
	OsrsLoadoutConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsLoadoutConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new OsrsLoadoutPanel(new OsrsLoadoutPanel.Actions()
		{
			@Override
			public void syncNow()
			{
				clientThread.invoke(OsrsLoadoutPlugin.this::syncNow);
			}

			@Override
			public void newCode()
			{
				clientThread.invoke(() -> requestCode(-1));
			}

			@Override
			public void resetKey()
			{
				clientThread.invoke(OsrsLoadoutPlugin.this::rotateKey);
			}
		});
		navButton = NavigationButton.builder()
			.tooltip("OSRS Loadout")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		pendingRead = false;
		forget();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// the next login may be a different character
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			forget();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (CONFIG_GROUP.equals(event.getGroup()))
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			pendingRead = true;
		}
	}

	/** The bank container is only complete once the bank interface has finished building. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING && pendingRead)
		{
			pendingRead = false;
			remember();
		}
	}

	/** Closing the bank picks up whatever was deposited or withdrawn during the visit. */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			remember();
		}
	}

	/** Reads the bank and keeps the reading so "Sync now" has it. Nothing is uploaded here. */
	private void remember()
	{
		if (!config.sync())
		{
			return;
		}
		final Snapshot snapshot = read();
		if (snapshot != null)
		{
			lastSnapshot = snapshot;
			refreshPanel();
		}
	}

	/** The panel's "Sync now", the only thing that uploads the bank. */
	private void syncNow()
	{
		if (!config.sync())
		{
			say("Turn on \"Sync bank to osrsloadout.com\" in this plugin's settings first.");
			return;
		}
		final Snapshot fresh = read();
		if (fresh != null)
		{
			lastSnapshot = fresh;
		}
		if (lastSnapshot == null)
		{
			say("Open your bank once, then press Sync now.");
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastSyncPress < SYNC_COOLDOWN_MS)
		{
			say("Synced moments ago. Try again in " + ((SYNC_COOLDOWN_MS - (now - lastSyncPress)) / 1000 + 1) + " seconds.");
			return;
		}
		lastSyncPress = now;
		upload(lastSnapshot);
	}

	/**
	 * Reads the bank, worn equipment and inventory, or returns null if the bank has not been loaded this session
	 * (so a sync can never replace a bank with only what the player is carrying). Client thread only.
	 */
	@Nullable
	private Snapshot read()
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return null;
		}
		final TreeMap<Integer, Long> items = new TreeMap<>();
		collect(bank, items);
		collect(client.getItemContainer(InventoryID.WORN), items);
		collect(client.getItemContainer(InventoryID.INV), items);

		final int[] tabs = new int[TAB_VARBITS.length];
		for (int i = 0; i < tabs.length; i++)
		{
			tabs[i] = Math.max(0, client.getVarbitValue(TAB_VARBITS[i]));
		}
		return new Snapshot(items, layout(bank), tabs);
	}

	/** Adds each real item to the totals under its canonical id. Placeholders and bank fillers are skipped. */
	private void collect(@Nullable ItemContainer container, TreeMap<Integer, Long> into)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			final int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0 || id == ItemID.BANK_FILLER
				|| itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			into.merge(itemManager.canonicalize(id), (long) item.getQuantity(), Long::sum);
		}
	}

	/** The bank in slot order: the canonical id per slot, 0 for an empty slot, the negated id for a placeholder. */
	private int[] layout(ItemContainer bank)
	{
		final Item[] items = bank.getItems();
		final int[] out = new int[Math.min(items.length, MAX_SLOTS)];
		for (int i = 0; i < out.length; i++)
		{
			final int id = items[i].getId();
			if (id <= 0 || id == ItemID.BANK_FILLER)
			{
				continue;
			}
			final int canonical = itemManager.canonicalize(id);
			out[i] = itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1 ? -canonical : canonical;
		}
		return out;
	}

	private void upload(Snapshot snapshot)
	{
		if (!config.sync())
		{
			return;
		}
		final String body = LoadoutLink.uploadJson(gson, snapshot.items, snapshot.slots, snapshot.tabs, secret());
		final int count = snapshot.items.size();
		final Request request = new Request.Builder()
			.url(LoadoutLink.UPLOAD_ENDPOINT)
			.post(RequestBody.create(JSON, body))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bank upload failed", e);
				say("Could not reach osrsloadout.com. Try again in a moment.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Bank upload rejected with {}", r.code());
						say("osrsloadout.com did not accept the sync. Try again in a moment.");
						return;
					}
				}
				lastSyncItems = count;
				lastSyncAt = CLOCK.format(Instant.now());
				refreshPanel();
				if (!linked())
				{
					requestCode(count);
				}
				else
				{
					say("Synced " + count + " items.");
				}
			}
		});
	}

	/**
	 * Asks the server for a one-time code that links a browser to this bank.
	 *
	 * @param count items in the upload that prompted this, or -1 when the player asked for a code
	 */
	private void requestCode(int count)
	{
		if (!config.sync())
		{
			if (count < 0)
			{
				say("Turn on \"Sync bank to osrsloadout.com\" in this plugin's settings first.");
			}
			return;
		}
		final Request request = new Request.Builder()
			.url(LoadoutLink.PAIR_ENDPOINT)
			.post(RequestBody.create(JSON, LoadoutLink.pairJson(gson, secret())))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Link code request failed", e);
				if (count < 0)
				{
					say("Could not reach osrsloadout.com for a link code. Try again in a moment.");
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String code = null;
				try (Response r = response)
				{
					final ResponseBody body = r.body();
					if (r.isSuccessful() && body != null)
					{
						code = LoadoutLink.codeFrom(gson, body.string());
					}
				}
				catch (IOException e)
				{
					log.debug("Could not read link code response", e);
				}
				if (code == null)
				{
					if (count < 0)
					{
						say("Could not get a link code. Try again in a moment.");
					}
					return;
				}
				configManager.setConfiguration(CONFIG_GROUP, LINK_CODE_KEY, code);
				configManager.setConfiguration(CONFIG_GROUP, LINKED_KEY, true);
				refreshPanel();
				final String line = "Enter " + code + " at osrsloadout.com to link your browser.";
				say(count < 0 ? line : "Synced " + count + " items. " + line);
			}
		});
	}

	/** Moves the bank to a new random key, which unlinks every browser. */
	private void rotateKey()
	{
		if (!config.sync())
		{
			say("Turn on \"Sync bank to osrsloadout.com\" in this plugin's settings first.");
			return;
		}
		configManager.unsetConfiguration(CONFIG_GROUP, SECRET_KEY);
		configManager.unsetConfiguration(CONFIG_GROUP, LINKED_KEY);
		configManager.unsetConfiguration(CONFIG_GROUP, LINK_CODE_KEY);
		say("Sync key reset. Every linked browser is now unlinked.");
		refreshPanel();
		if (lastSnapshot != null)
		{
			upload(lastSnapshot);
		}
	}

	/** The random key this install's bank is stored under. Generated once with a secure random UUID. */
	private String secret()
	{
		final String existing = configManager.getConfiguration(CONFIG_GROUP, SECRET_KEY);
		if (existing != null && !existing.isEmpty())
		{
			return existing;
		}
		final String generated = UUID.randomUUID().toString().replace("-", "");
		configManager.setConfiguration(CONFIG_GROUP, SECRET_KEY, generated);
		return generated;
	}

	private boolean linked()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, LINKED_KEY));
	}

	private void forget()
	{
		lastSnapshot = null;
	}

	private void refreshPanel()
	{
		final OsrsLoadoutPanel p = panel;
		if (p != null)
		{
			p.update(config.sync(), linked(), lastSyncAt, lastSyncItems,
				configManager.getConfiguration(CONFIG_GROUP, LINK_CODE_KEY));
		}
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

	private static final class Snapshot
	{
		private final TreeMap<Integer, Long> items;
		private final int[] slots;
		private final int[] tabs;

		private Snapshot(TreeMap<Integer, Long> items, int[] slots, int[] tabs)
		{
			this.items = items;
			this.slots = slots;
			this.tabs = tabs;
		}
	}
}
