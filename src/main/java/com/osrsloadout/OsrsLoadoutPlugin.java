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
import javax.annotation.Nullable;
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
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
	 * Neither of these is a @ConfigItem. Keeping them out of the config interface keeps them out of the
	 * settings panel: the secret is the only thing that identifies this bank, so there should be nothing for
	 * a player to read out to someone who asks nicely, and nothing to clear by accident.
	 */
	private static final String SECRET_KEY = "syncKey";
	private static final String LINKED_KEY = "linked";

	private static final MediaType JSON = MediaType.parse("application/json");

	/** Stands in for the item count when the player asked for a code outright, with no upload behind it. */
	private static final int ON_REQUEST = -1;

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

	/**
	 * Set when the bank interface loads, cleared once we have read it. This is the whole debounce: the bank
	 * finishes building on every tab switch, every search keystroke and every withdrawal, and we want at most
	 * one request per visit to a banker rather than dozens.
	 */
	private boolean pendingCapture;

	/**
	 * The last set of ids accepted by the server, and the label that went with it. A hash would do, but the
	 * set itself is about four kilobytes for a full bank and comparing it is exact, so there is no reason to
	 * introduce a collision that would present as the plugin silently refusing to sync.
	 */
	private Set<Integer> lastSent;
	private String lastSentRsn;

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
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"showLinkCode".equals(event.getKey()))
		{
			return;
		}

		if (!config.showLinkCode())
		{
			return;
		}

		// The toggle is really a button, so it puts itself back up rather than sitting on and looking like a
		// mode the plugin is now in.
		configManager.setConfiguration(CONFIG_GROUP, "showLinkCode", false);

		// The display name is live game state, so it can only be read on the client thread - and this arrives
		// on Swing's, from the settings panel.
		clientThread.invoke(() -> requestCode(displayName(), ON_REQUEST));
	}

	/**
	 * Runs on the client thread, as an event handler: ItemManager#canonicalize reaches through to
	 * Client#getItemDefinition, and the item containers and the local player are live game state, so none of
	 * it may be touched from anywhere else. Only the request leaves this thread.
	 */
	private void capture()
	{
		if (!config.sync())
		{
			return;
		}

		final String rsn = displayName();
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

		if (ids.equals(lastSent) && equal(rsn, lastSentRsn))
		{
			// Idly reopening the bank is not a request.
			return;
		}

		upload(rsn, ids);
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
	private void upload(@Nullable String rsn, Set<Integer> ids)
	{
		final Request request = new Request.Builder()
			.url(LoadoutLink.UPLOAD_ENDPOINT)
			.post(RequestBody.create(JSON, LoadoutLink.uploadJson(gson, rsn, ids, secret())))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// Offline, or the server is down. Silent by design: a chat line every time someone banks
				// without a connection is worse than not syncing.
				log.debug("Bank upload failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Bank upload rejected with {}", r.code());
						return;
					}

					lastSent = ids;
					lastSentRsn = rsn;
					onUploaded(rsn, ids.size());
				}
			}
		});
	}

	/**
	 * On an OkHttp dispatcher thread. The bank is up; the only question left is whether the player still
	 * needs to be told how to let a browser read it.
	 */
	private void onUploaded(@Nullable String rsn, int count)
	{
		if (!linked())
		{
			// The code and the item count belong on one line, so the count is carried into the pairing call
			// rather than announced ahead of it.
			requestCode(rsn, count);
			return;
		}

		if (!announced)
		{
			announced = true;
			say("Synced " + count + " items.");
		}
	}

	/**
	 * Mints a single-use code the player types into the website, which is how a browser is granted the right
	 * to read this bank. Ten minutes, one use, and asking again replaces any unused code.
	 *
	 * @param count items just uploaded, so the code and the count can share one chat line, or
	 *              {@link #ON_REQUEST} when the player asked for a code outright and there is no count to
	 *              report
	 */
	private void requestCode(@Nullable String rsn, int count)
	{
		final Request request = new Request.Builder()
			.url(LoadoutLink.PAIR_ENDPOINT)
			.post(RequestBody.create(JSON, LoadoutLink.pairJson(gson, rsn, secret())))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Pair request failed", e);
				announceWithoutCode(count);
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
					else
					{
						log.debug("Pair request rejected with {}", r.code());
					}
				}
				catch (IOException e)
				{
					log.debug("Could not read pair response", e);
				}

				if (code == null)
				{
					announceWithoutCode(count);
					return;
				}

				// Printed exactly as returned, hyphen included. The server picks from an alphabet with no
				// O/0 and no I/1/l precisely because this gets read off a chat line and typed by hand, so
				// reformatting it here could only do harm.
				final String tail = "Type " + code + " at osrsloadout.com to link this browser.";
				say(count == ON_REQUEST ? tail : "Synced " + count + " items. " + tail);

				// Only now, with a code actually in front of the player. Setting this when the request was
				// merely sent would burn their one prompt on a failure they never saw.
				configManager.setConfiguration(CONFIG_GROUP, LINKED_KEY, true);
				announced = true;
			}
		});
	}

	/**
	 * The upload succeeded even though the pairing call did not, so the player is still told their bank
	 * synced. The linked flag stays down, which means the next bank tries for a code again.
	 */
	private void announceWithoutCode(int count)
	{
		if (count != ON_REQUEST && !announced)
		{
			announced = true;
			say("Synced " + count + " items.");
		}
	}

	/**
	 * There are no accounts. This key is the only thing that identifies this bank - the server stores it at
	 * sha-256 of this value - so it is generated once, never shown, and never asked for. That is the entire
	 * reason the player has nothing to set up, and the reason a character's name gets you nothing.
	 */
	private String secret()
	{
		final String existing = configManager.getConfiguration(CONFIG_GROUP, SECRET_KEY);
		if (existing != null && !existing.isEmpty())
		{
			return existing;
		}

		// randomUUID is seeded from SecureRandom, which is what matters for a value that is the sole
		// credential here; the hyphens are dropped only so that what is stored is a plain 32-character token.
		final String generated = UUID.randomUUID().toString().replace("-", "");
		configManager.setConfiguration(CONFIG_GROUP, SECRET_KEY, generated);
		return generated;
	}

	private boolean linked()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, LINKED_KEY));
	}

	@Nullable
	private String displayName()
	{
		final Player local = client.getLocalPlayer();
		final String name = local == null ? null : local.getName();
		return name == null || name.isEmpty() ? null : name;
	}

	private static boolean equal(@Nullable String a, @Nullable String b)
	{
		return a == null ? b == null : a.equals(b);
	}

	private void forgetSession()
	{
		lastSent = null;
		lastSentRsn = null;
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
