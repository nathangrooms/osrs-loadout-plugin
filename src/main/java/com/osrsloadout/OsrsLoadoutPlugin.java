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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
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
	description = "Sends your bank, worn equipment and inventory to osrsloadout.com when you press "
		+ "Sync, so the site can plan gear from what you actually own. Uploads item ids and quantities "
		+ "to a third-party server, and your display name only if you separately opt in.",
	tags = {"bank", "gear", "loadout", "sync", "export"}
)
public class OsrsLoadoutPlugin extends Plugin
{
	static final String CONFIG_GROUP = "osrsloadout";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm")
		.withZone(ZoneId.systemDefault());

	/**
	 * Neither of these is a @ConfigItem. Keeping them out of the config interface keeps them out of the
	 * settings panel: the secret is the only thing that identifies this bank, so there should be nothing for
	 * a player to read out to someone who asks nicely, and nothing to clear by accident.
	 */
	private static final String SECRET_KEY = "syncKey";
	private static final String LINKED_KEY = "linked";
	private static final String LINK_CODE_KEY = "linkCode";

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

	@Inject
	private ClientToolbar clientToolbar;

	/**
	 * Set when the bank interface loads, cleared once we have read it. This is the whole debounce: the bank
	 * finishes building on every tab switch, every search keystroke and every withdrawal, and we want at most
	 * one request per visit to a banker rather than dozens.
	 */
	private boolean pendingCapture;

	/**
	 * The most recent reading of a bank, whether or not the upload that followed it succeeded. This is what a
	 * manual re-sync falls back on when no bank is open, and it is deliberately separate from lastSent: an
	 * upload that failed still leaves us something to re-send.
	 */
	private SortedMap<Integer, Long> lastCaptured;
	private String lastCapturedRsn;

	private OsrsLoadoutPanel panel;
	private NavigationButton navButton;

	/**
	 * These three are volatile because they are written on OkHttp dispatcher threads and read on the client
	 * thread and the EDT. {@code announced} is the once-per-session guard on the chat line; the other two
	 * are what the last successful upload contained, for the panel to report rather than the player to
	 * guess.
	 */
	private volatile boolean announced;
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
		// Actions rather than checkboxes, because the settings panel has no type that is a button, and
		// three ticks that untick themselves is not a control surface.
		panel = new OsrsLoadoutPanel(new OsrsLoadoutPanel.Actions()
		{
			@Override
			public void syncNow()
			{
				clientThread.invoke(OsrsLoadoutPlugin.this::resync);
			}

			@Override
			public void newCode()
			{
				clientThread.invoke(() -> requestCode(labelName(), ON_REQUEST));
			}

			@Override
			public void resetKey()
			{
				rotateKey();
			}
		});
		navButton = NavigationButton.builder()
			.tooltip("OSRS Loadout")
			.icon(icon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();
	}

	private BufferedImage icon()
	{
		return ImageUtil.loadImageResource(getClass(), "icon.png");
	}

	/** Everything the panel shows, in one place, so no caller has to remember which parts to update. */
	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		final String linked = configManager.getConfiguration(CONFIG_GROUP, LINKED_KEY);
		panel.setLinked(Boolean.parseBoolean(linked), lastSyncAt, lastSyncItems, lastBreakdown[3],
			config.sync());
		panel.setCode(configManager.getConfiguration(CONFIG_GROUP, LINK_CODE_KEY));
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
			panel = null;
		}
		pendingCapture = false;
		lastCaptured = null;
		lastCapturedRsn = null;
		forgetUploads();
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
			lastCaptured = null;
			lastCapturedRsn = null;
			forgetUploads();
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

		// Read it, do not send it. Opening a bank is not a request to upload one - the player decides
		// when their gear leaves the client, with the button. What this buys is that the button then
		// works instantly and works with the bank closed, because the reading is already in hand.
		readOnly();
	}

	/**
	 * Takes a reading and keeps it, without uploading. Every bank you open refreshes what "Sync my bank
	 * now" will send, so pressing it never has to ask you to go and open a bank first.
	 */
	private void readOnly()
	{
		if (!config.sync())
		{
			return;
		}

		final String rsn = labelName();
		final TreeMap<Integer, Long> items = new TreeMap<>();
		if (!read(items))
		{
			return;
		}

		lastCaptured = items;
		lastCapturedRsn = rsn;
		refreshPanel();
	}

	/**
	 * One key, because the actions live in the panel where a button can be a button. This is the door to
	 * that panel, and a settings screen has no type that is a button either, so it arrives as a toggle
	 * pretending to be one: it puts itself straight back up rather than sitting on and looking like a mode
	 * the plugin is now in. Writing the key back re-enters this method, which is why the branch is guarded
	 * on the value still being true.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("openPanel".equals(event.getKey()) && config.openPanel())
		{
			configManager.setConfiguration(CONFIG_GROUP, "openPanel", false);
			if (navButton != null)
			{
				clientToolbar.openPanel(navButton);
			}
		}
	}

	/**
	 * The manual re-sync, for when the player can see that the site is wrong and changing their bank to force
	 * an upload would be an absurd thing to have to do.
	 *
	 * It re-reads rather than trusting the memo, because the bank may well be open and a few withdrawals
	 * further on than the last capture. The memo is the fallback, not the source.
	 *
	 * Runs on the client thread, because ItemManager#canonicalize reaches through to
	 * Client#getItemDefinition and the item containers and the local player are live game state, so none of
	 * it may be touched from anywhere else. Only the request leaves this thread.
	 */
	private void resync()
	{
		if (!config.sync())
		{
			say("Syncing is turned off, so there is nothing to re-send.");
			return;
		}

		String rsn = displayName();
		final TreeMap<Integer, Long> fresh = new TreeMap<>();
		SortedMap<Integer, Long> items = read(fresh) ? fresh : null;

		if (items == null)
		{
			items = lastCaptured;
			rsn = lastCapturedRsn;
		}

		if (items == null || items.isEmpty())
		{
			// Never silently do nothing: the player pressed a button and is owed an answer.
			say("No bank seen yet this session, open one first.");
			return;
		}

		lastCaptured = items;
		lastCapturedRsn = rsn;

		say(breakdown());

		// Deliberately skips the unchanged check. Re-sending an identical reading is the entire point: the
		// player is telling us they do not believe the server has it.
		upload(rsn, items, true);
	}

	/**
	 * Fills {@code into} from the bank, worn equipment and inventory.
	 *
	 * Returns false when there is no bank container, and importantly does not fill anything in that case.
	 * Worn equipment and the inventory are readable the moment you log in, so a read that quietly succeeded
	 * with only those would let a manual re-sync replace a real bank with the thirty items the player happens
	 * to be carrying.
	 */
	private boolean read(Map<Integer, Long> into)
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return false;
		}

		lastBreakdown = new int[4];
		collect(bank, into, 0);

		// Equipment and inventory are included alongside the bank because the question the site is asking is
		// "what do you own", and a player's best items are usually the ones they are wearing. A bank-only
		// read would tell the planner you do not own your own gear, which is the one thing it must not get
		// wrong. It costs nothing: same trigger, same pass, same request.
		collect(client.getItemContainer(InventoryID.WORN), into, 1);
		collect(client.getItemContainer(InventoryID.INV), into, 2);

		return !into.isEmpty();
	}

	/**
	 * Bank, worn, inventory, skipped: what the last read found. Kept because "is it counting
	 * placeholders?" is a question one line of counts settles and an argument otherwise.
	 */
	private int[] lastBreakdown = new int[4];

	private void collect(@Nullable ItemContainer container, Map<Integer, Long> into, int slot)
	{
		if (container == null)
		{
			return;
		}

		for (Item item : container.getItems())
		{
			final int id = item.getId();
			final int quantity = item.getQuantity();

			// Empty slots, and the filler the bank uses to pad a tab.
			if (id <= 0 || quantity <= 0 || id == ItemID.BANK_FILLER)
			{
				if (id > 0 && id != ItemID.BANK_FILLER)
				{
					lastBreakdown[3]++;
				}
				continue;
			}

			// A placeholder is a bank slot holding the SHAPE of an item you no longer have. This used to be
			// filtered on quantity, on the reasoning that a placeholder is stored with zero of the item -
			// and that filter demonstrably did not catch them: a player with an Armadyl crossbow placeholder
			// and no Armadyl crossbow was credited with one on the website.
			//
			// The composition says so outright, and it is the same field canonicalize() reads to fold a
			// placeholder onto the real item. That is exactly why this was invisible rather than obvious:
			// by the time the id leaves this loop it is the genuine article's id, indistinguishable from
			// owning one. So the question has to be asked before that, of the id the game actually gave us.
			final ItemComposition comp = itemManager.getItemComposition(id);
			if (comp.getPlaceholderTemplateId() != -1)
			{
				lastBreakdown[3]++;
				continue;
			}
			lastBreakdown[slot]++;

			// canonicalize resolves a noted id to its unnoted one, a placeholder to the real item, and the
			// "worn" variants some equipment has to the base id. Doing this by hand via getNote() and
			// getLinkedNoteId() would reimplement it and still miss the last case.
			final int canonical = itemManager.canonicalize(id);

			// Summed rather than replaced, because the site is being asked how much of a thing the player
			// owns in total and one id genuinely occurs in several places at once: runes part-carried and
			// part-banked, a stack of logs in the inventory on top of the pile in the bank, noted and unnoted
			// copies of the same item that canonicalize has just folded together. Taking one container's
			// figure would under-report every one of those.
			into.merge(canonical, (long) quantity, Long::sum);
		}
	}

	/**
	 * Where the last reading came from, in one line. Placeholders are the first thing anybody suspects
	 * when the site shows gear they do not own, so the skipped count is said out loud.
	 */
	private String breakdown()
	{
		return "Read " + lastBreakdown[0] + " from the bank, " + lastBreakdown[1] + " worn, "
			+ lastBreakdown[2] + " in the inventory; ignored " + lastBreakdown[3]
			+ " placeholders and empty slots.";
	}

	/**
	 * enqueue, not execute: OkHttp dispatches this on its own pool, so the game thread returns immediately
	 * and a slow or unreachable server costs the player nothing. Nothing is retried here. The next bank is
	 * the retry, and it arrives on its own.
	 */
	private void upload(@Nullable String rsn, SortedMap<Integer, Long> items, boolean manual)
	{
		final Request request = new Request.Builder()
			.url(LoadoutLink.UPLOAD_ENDPOINT)
			.post(RequestBody.create(JSON, LoadoutLink.uploadJson(gson, rsn, items, secret())))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bank upload failed", e);
				failed(manual);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Bank upload rejected with {}", r.code());
						failed(manual);
						return;
					}

					lastSyncItems = items.size();
					lastSyncAt = CLOCK.format(Instant.now());
					refreshPanel();
					onUploaded(rsn, items.size(), manual);
				}
			}
		});
	}

	/**
	 * Silence is right for an upload nobody asked for - a chat line every time someone banks without a
	 * connection is worse than not syncing - but wrong for one they pressed a button for.
	 */
	private void failed(boolean manual)
	{
		if (manual)
		{
			say("Could not reach osrsloadout.com. Press Sync again in a moment.");
		}
	}

	/**
	 * On an OkHttp dispatcher thread. The bank is up; the only question left is whether the player still
	 * needs to be told how to let a browser read it.
	 */
	private void onUploaded(@Nullable String rsn, int count, boolean manual)
	{
		if (!linked())
		{
			// A code is more use than a confirmation, and the count rides along on the same line, so this
			// takes precedence over the manual wording.
			requestCode(rsn, count);
			return;
		}

		if (manual)
		{
			say("Re-sent " + count + " items.");
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
	 * to read this bank. Ten minutes, one use, and asking again retires any unused code, so only one door is
	 * ever open.
	 *
	 * @param count items just uploaded, so the code and the count can share one chat line, or
	 *              {@link #ON_REQUEST} when the player asked for a code outright and there is no count to
	 *              report
	 */
	private void requestCode(@Nullable String rsn, int count)
	{
		// Gated like every other request. The toggle is the plugin's disclosure of talking to a third
		// party at all, so leaving one endpoint reachable with it off would make that disclosure false.
		if (!config.sync())
		{
			say("Syncing is turned off, so there is no bank to link to.");
			return;
		}

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

				// Stored as well as said, because a chat line scrolls away while you are finding the
				// website. The panel reads it back out of config, so it survives a client restart.
				configManager.setConfiguration(CONFIG_GROUP, LINK_CODE_KEY, code);
				refreshPanel();

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
		if (count == ON_REQUEST)
		{
			say("Could not reach osrsloadout.com for a link code. Try again in a moment.");
			return;
		}

		if (!announced)
		{
			announced = true;
			say("Synced " + count + " items.");
		}
	}

	/**
	 * The real revoke. Unlinking inside the website only makes that browser forget the id it holds; the id
	 * itself keeps working for anyone who has it. Rotating the secret moves the bank to a different address
	 * altogether, so every id handed out against the old one stops resolving at once.
	 */
	private void rotateKey()
	{
		if (!config.sync())
		{
			say("Syncing is turned off, so there is no key in use to reset.");
			return;
		}

		configManager.unsetConfiguration(CONFIG_GROUP, SECRET_KEY);
		configManager.unsetConfiguration(CONFIG_GROUP, LINKED_KEY);
		configManager.unsetConfiguration(CONFIG_GROUP, LINK_CODE_KEY);

		// The new address holds nothing, so the memo of what the old one already had would otherwise suppress
		// the very upload that fills it.
		forgetUploads();

		say("Sync key reset. Every linked browser is now unlinked.");
		refreshPanel();

		// One action, not two. Resetting and then being told to go and do a second thing is how a control
		// ends up feeling half-connected: if there is a bank to re-send, this finishes the job and comes
		// back with the new code already on screen.
		clientThread.invoke(() -> {
			if (lastCaptured != null && !lastCaptured.isEmpty())
			{
				upload(lastCapturedRsn, lastCaptured, true);
			}
			else
			{
				say("Open a bank to upload it to the new address and get a fresh code.");
			}
		});
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

		// UUID.randomUUID is specified to use a cryptographically strong pseudo random number generator,
		// which is java.security.SecureRandom. That matters more than the format for a value that is the sole
		// credential in this system: it carries 122 random bits, and the hyphens are dropped only so that
		// what is stored is a plain 32-character token.
		final String generated = UUID.randomUUID().toString().replace("-", "");
		configManager.setConfiguration(CONFIG_GROUP, SECRET_KEY, generated);
		return generated;
	}

	private boolean linked()
	{
		return Boolean.parseBoolean(configManager.getConfiguration(CONFIG_GROUP, LINKED_KEY));
	}

	/**
	 * The label, or nothing. "No exposing player information over HTTP" is a Plugin Hub rule, and the name
	 * is only ever a caption on a web page here - the bank is addressed by the key, never by the name - so
	 * it is a second, separate opt-in rather than a passenger on the first.
	 */
	@Nullable
	private String labelName()
	{
		return config.sendName() ? displayName() : null;
	}

	@Nullable
	private String displayName()
	{
		final Player local = client.getLocalPlayer();
		final String name = local == null ? null : local.getName();
		return name == null || name.isEmpty() ? null : name;
	}

	private void forgetUploads()
	{
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
