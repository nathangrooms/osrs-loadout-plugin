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
 * This plugin would rather have had no configuration at all, but the Plugin Hub requires that "Plugins which
 * communicate with third party servers [...] have a warning either on the plugin, or on the configuration
 * option enabling the setting, explaining what data is being sent". That makes the sync toggle below a
 * submission requirement rather than a feature, which is also why it defaults to on: the disclosure has to
 * exist, but the player should not have to go and find it before the plugin does anything.
 *
 * The per-install sync key is deliberately not here. It is written straight to the config store under
 * {@link OsrsLoadoutPlugin#CONFIG_GROUP}, so it never renders in the panel and there is nothing for a player
 * to copy, paste or leak.
 */
@ConfigGroup(OsrsLoadoutPlugin.CONFIG_GROUP)
public interface OsrsLoadoutConfig extends Config
{
	@ConfigItem(
		keyName = "sync",
		name = "Sync my bank to osrsloadout.com",
		description = "Uploads the item ids in your bank, worn equipment and inventory to osrsloadout.com "
			+ "every time you open a bank, along with your character's display name. Quantities, levels, "
			+ "location and chat are never sent. Anyone who knows your character name can read the result, "
			+ "the same way they can read your hiscores. Turn this off to stop uploading.",
		position = 1
	)
	default boolean sync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "resetSyncKey",
		name = "Reset sync key",
		description = "Forgets the key that proves this RuneLite install owns your character's data, and "
			+ "generates a new one on the next bank you open. Only useful if you sync the same character "
			+ "from more than one install and want this one to take over.",
		warning = "The next install to sync this character claims it. If you do this on the wrong machine "
			+ "you will have to reset the key on the other one to hand it back.",
		position = 2
	)
	default boolean resetSyncKey()
	{
		return false;
	}
}
