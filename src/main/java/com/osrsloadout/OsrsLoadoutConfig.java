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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * One toggle and three actions, and each earns its place.
 *
 * The sync toggle is a Plugin Hub submission requirement rather than a feature: plugins which communicate
 * with third party servers must "have a warning either on the plugin, or on the configuration option
 * enabling the setting, explaining what data is being sent". It defaults to on so the disclosure exists
 * without standing between the player and a working plugin.
 *
 * The three actions are the only things in the whole system a player can ever be asked to do, which is
 * exactly why they have to be findable rather than clever.
 *
 * The secret and the linked flag are deliberately absent. They are written straight to the config store, so
 * they never render in the settings panel: the secret is the sole credential here, so there is nothing for a
 * player to read out to someone who asks nicely, and nothing to clear by accident.
 */
@ConfigGroup(OsrsLoadoutPlugin.CONFIG_GROUP)
public interface OsrsLoadoutConfig extends Config
{
	@ConfigItem(
		keyName = "sync",
		name = "Sync my bank to osrsloadout.com",
		description = "Uploads the item ids in your bank, worn equipment and inventory to osrsloadout.com "
			+ "every time you open a bank, along with how many of each you have and your character's "
			+ "display name as a label. Levels, location and chat are never sent. Your bank is stored under "
			+ "a key that only this RuneLite install holds, so it can only be read by a browser you have "
			+ "linked with a code. Turn this off to stop uploading.",
		position = 1
	)
	default boolean sync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "resync",
		name = "Re-sync my bank now",
		description = "Uploads your bank again immediately, even if nothing has changed since the last "
			+ "upload. Use this if the website is showing gear you no longer have, or you think a sync was "
			+ "missed. If no bank is open it re-sends the last one this plugin saw.",
		position = 2
	)
	default boolean resync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showLinkCode",
		name = "Show a new link code",
		description = "Prints a fresh code in your chat box to link another browser to your bank - a second "
			+ "computer, or the same one after clearing its site data. The code lasts ten minutes and can be "
			+ "used once, and asking for one cancels any code you have not used yet. You do not need this "
			+ "for a browser you have already linked.",
		position = 3
	)
	default boolean showLinkCode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "resetSyncKey",
		name = "Reset sync key",
		description = "Generates a brand new key for this install, which moves your bank to a new address "
			+ "and unlinks every browser you have ever linked. This is the real way to revoke access: "
			+ "unlinking inside the website only makes that browser forget, while this makes the old address "
			+ "stop working for everyone. You will need to link each browser again with a new code.",
		warning = "This unlinks every browser that can currently see your bank, on every device, and cannot "
			+ "be undone. You will need a new code for each one.",
		position = 4
	)
	default boolean resetSyncKey()
	{
		return false;
	}
}
