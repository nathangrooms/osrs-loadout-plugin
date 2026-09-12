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
 * One toggle, and nothing else.
 *
 * Everything you can DO is in the panel; the one thing you can SET is here. Splitting them this way is
 * deliberate: RuneLite renders a config item as whatever its return type suggests, so an action has to ship
 * as a checkbox that unticks itself, and three of those stacked up read as settings somebody forgot to turn
 * on. But controls in two places is worse than controls in the wrong one, so this file keeps exactly the
 * item that is genuinely a preference.
 *
 * The sync toggle is a Plugin Hub submission requirement rather than a feature: plugins which communicate
 * with third party servers must "have a warning either on the plugin, or on the configuration option
 * enabling the setting, explaining what data is being sent". It defaults to on so the disclosure exists
 * without standing between the player and a working plugin.
 *
 * The secret, the linked flag and the current link code are written straight to the config store, so they
 * never render here: the secret is the sole credential in this system, so there is nothing for a player to
 * read out to somebody who asks nicely, and nothing to clear by accident.
 */
@ConfigGroup(OsrsLoadoutPlugin.CONFIG_GROUP)
public interface OsrsLoadoutConfig extends Config
{
	@ConfigItem(
		keyName = "sync",
		name = "Sync my bank to osrsloadout.com",
		description = "Lets this plugin talk to osrsloadout.com. With it on, pressing 'Sync my bank now' "
			+ "in the OSRS Loadout panel uploads every item id and quantity in your bank, worn equipment "
			+ "and inventory, your character's display name as a label, and a random key this plugin "
			+ "generated. Levels, location and chat are never sent. Nothing is uploaded until you press "
			+ "that button. Turn this off and the plugin makes no requests at all.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified "
			+ "by RuneLite developers, along with the contents of your bank and your character's display "
			+ "name.",
		position = 1
	)
	default boolean sync()
	{
		return true;
	}

	@ConfigItem(
		keyName = "openPanel",
		name = "Tick to open the OSRS Loadout panel",
		description = "Opens the panel where the link code, the sync button and the rest live. It is also "
			+ "the icon on the toolbar to the right of the screen - this is here because a settings screen "
			+ "that mentions a panel and cannot take you to it is a dead end.",
		position = 2
	)
	default boolean openPanel()
	{
		return false;
	}
}
