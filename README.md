# OSRS Loadout

A RuneLite plugin that sends your bank to [osrsloadout.com](https://www.osrsloadout.com/), so the site can
work out the best gear you can actually field from what you own.

**This plugin sends data to a third-party server, and is off until you turn it on.** Tick *Sync my bank to
osrsloadout.com* in its settings to enable it.

## How it works

1. You open a bank. The plugin reads it and keeps the reading in memory. **Nothing is sent.**
2. You press **Sync my bank now** in the plugin's panel. That uploads it.
3. The first upload prints a code in your chat box. You type it into the website once, and that browser
   stays linked.

Reading and uploading are deliberately separate: opening a bank is not a request to send your gear
anywhere.

## What is uploaded

On each press of Sync, to `https://www.osrsloadout.com/api/v1/bank`:

- the distinct item ids in your bank, worn equipment and inventory
- how many of each you have
- how your bank is arranged: which item is in which slot, and the sizes of your nine tabs, so the site
  can show your bank back to you as you actually keep it
- a random key generated once on this install
- your character's display name, **only if you also tick *Send my character name as a label***, which is
  off by default

Asking for a link code posts the key (and the name, under the same opt-in) to `.../api/v1/bank/pair`.

With *Sync* off the plugin makes no requests at all. It does **not** send levels, location, chat, or
anything about any other character.

Quantities are sent so the site can tell five thousand yew logs from one. Empty slots, the bank filler and
**placeholders** are never sent - placeholders are identified by `ItemComposition#getPlaceholderTemplateId`
rather than by quantity, for the reason given in `collect()`.

### Knowing your character's name gets you nothing

Your bank is stored under the SHA-256 of the random key, and only under that. The name is a label, not an
address: there is no endpoint that takes a name and returns a bank.

The key comes from `UUID.randomUUID()` - 122 random bits from a cryptographically strong generator - and is
kept in your RuneLite config, never displayed and never asked for. Claiming a code hands back the **read**
id and never the key, so a linked browser can read your bank and can never write one. **Reset sync key** in
the panel moves your bank to a new address and unlinks every browser at once.

### One key per install, not per character

The key is stored against the install rather than the character, so every character you play here shares
one bank on the server: syncing an alt replaces what your main uploaded. That is a deliberate
simplification for a first release and not a good one - per-character keys are the next thing to change.

## The controls

Everything you can *do* is in the plugin's side panel, reached from the toolbar icon: **Sync my bank now**,
**Get a link code**, and **Reset sync key**, which asks first. The settings screen holds the one thing that
is a genuine preference - whether to sync at all - plus an item that opens the panel.

## Building

Requires JDK 11 or newer. `./gradlew build` compiles and runs the tests; `./gradlew run` starts a
development client with the plugin loaded. `build.gradle` and `settings.gradle` are kept in the shape of
[runelite/example-plugin](https://github.com/runelite/example-plugin), because the Plugin Hub builds
`build=standard` plugins with its own copies of both.

On Windows, `run-dev.ps1` side-loads the plugin into your installed client instead; its header explains why
that cannot go through the launcher.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
