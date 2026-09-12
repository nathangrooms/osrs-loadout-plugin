# OSRS Loadout

A RuneLite plugin that sends your bank to [osrsloadout.com](https://www.osrsloadout.com/), so the site can
work out the best gear you can actually field from what you own.

**This plugin sends data to a third-party server.** What it sends, when, and what can be done with it is set
out in full below.

## What it does

1. You open a bank. The plugin reads your bank, your worn equipment and your inventory and keeps the
   reading in memory. **Nothing is sent at this point.**
2. You press **Sync my bank now** in the plugin's panel. That uploads the reading.
3. The first upload prints a code in your chat box:

   > OSRS Loadout: Synced 879 items. Type KVVK-SQB8 at osrsloadout.com to link this browser.

4. You type that code into the website once. From then on the site can read your bank whenever you press
   Sync again, and the code is never needed again for that browser.

Reading on bank-open and uploading on a button press are deliberately separate: opening a bank is not a
request to send your gear anywhere, and keeping the reading means the button works instantly and works with
the bank closed.

## What is uploaded

On each press of Sync, to `https://yqdqbsbgowqjkjplkrzi.supabase.co/functions/v1/bank`:

- the distinct item ids in your bank, worn equipment and inventory
- how many of each you have
- a random key generated once on this install
- your character's display name, as a label so the site has something to show beside the bank

Requesting a link code additionally POSTs the random key and display name to
`.../functions/v1/bank/pair`, which returns the code.

It does **not** send levels, location, chat, or anything about any other character.

Quantities are sent so the site can tell five thousand yew logs from one; that difference is most of what
makes a pile of loot worth valuing. What counts as loot, as a supply, or as gear worth keeping is decided
entirely on the website — the plugin reports what is in the bank and expresses no opinion about it.

Three kinds of bank entry are never sent: empty slots, the bank filler used to pad a tab, and
**placeholders**. A placeholder is a slot holding the shape of an item you have already spent, and the
plugin identifies them by `ItemComposition#getPlaceholderTemplateId`, not by quantity — see the comment in
`collect()` for why that distinction matters.

### Knowing your character's name gets you nothing

Your bank is stored under the SHA-256 of the random key, and **only** under that. The display name is a
label, not an address: the server has no endpoint that takes a name and returns a bank, so somebody who
knows what you are called cannot look you up.

The key is generated once from `UUID.randomUUID()`, which Java specifies to use a cryptographically strong
generator, giving 122 random bits stored as 32 hex characters. It is kept in your RuneLite config, never
displayed and never asked for.

### One key per install, not per character

The key is stored in your RuneLite config, not against a character, so every character you play on
this install shares one bank on the server: syncing an alt replaces what your main last uploaded. That
is a deliberate simplification for a first release and not a good one — per-character keys are the
next thing to change — but while it is true it is worth knowing, both because it loses the previous
bank and because it means the server can see that those characters share an install.

### What each secret is worth if it leaks

| | what it is | what it lets someone do |
| --- | --- | --- |
| the link code | 8 characters, single use, 10 minutes | claim your bank into one browser, once, if they use it before you do |
| the bank id | SHA-256 of your key, held by linked browsers | read your bank; it cannot write one |
| the sync key | the credential, never leaves this install | read and overwrite your bank |

Claiming a code returns the **read** id and never the key, so a linked browser can read your bank and can
never write one. **Reset sync key** in the panel generates a new key, which moves your bank to a new address
and unlinks every browser at once; it then re-uploads to the new address and prints a fresh code.

## The controls

Everything you can *do* is in the plugin's side panel, reachable from the icon on RuneLite's toolbar:

- **Sync my bank now** — uploads the last reading. If no bank has been opened this session it re-sends the
  last one the plugin saw.
- **Get a link code / Link another browser** — prints a fresh code. Asking for one cancels any unused code.
- **Reset sync key** — the revoke, described above. It asks for confirmation first.

The panel also shows whether a browser is linked, what the last upload contained, and the current code with
a Copy button.

The settings screen holds the one thing that is a genuine preference — whether to sync at all — plus an item
that opens the panel. RuneLite renders a config item as whatever its return type suggests, so an action
there has to be a checkbox that unticks itself; that is why the actions are buttons in a panel instead.

## Building

Requires a JDK 11 or newer. RuneLite itself builds with JDK 11 and the Plugin Hub recommends Eclipse
Temurin; this plugin compiles with `--release 11` regardless of the JDK doing the compiling.

```
./gradlew build
```

The Gradle wrapper is committed, so no system Gradle install is needed. `build` also runs the unit tests.

`build.gradle` and `settings.gradle` are kept in the shape of
[runelite/example-plugin](https://github.com/runelite/example-plugin), because the Plugin Hub builds
`build=standard` plugins with its own copies of both files — anything clever in them would work locally and
vanish at submission. The same reasoning rules out adding dependencies: the Hub requires cryptographic
verification metadata for anything that is not already a transitive dependency of `runelite-client`.

## Running it in a client

`./gradlew run` starts a development client with the plugin loaded.

To test against your own account in your own client, `run-dev.ps1` (Windows) launches the installed client
jar directly with the plugin side-loaded from `~/.runelite/sideloaded-plugins`. It has to launch the jar
itself rather than go through the launcher, because RuneLite computes

```java
final boolean developerMode = options.has("developer-mode")
    && RuneLiteProperties.getLauncherVersion() == null;
```

and the official launcher always sets that property — so `--developer-mode` passed through the launcher, its
configure dialog, or `RUNELITE_ARGS` is parsed and then discarded, and side-loading never happens. A Jagex
account additionally needs `--insecure-write-credentials` once, through the launcher, to write
`~/.runelite/credentials.properties` for the directly-launched client to read.

## Tests

`LoadoutLinkTest` covers the JSON the plugin sends and the code it parses back.
`OsrsLoadoutPluginTest` is the standard Plugin Hub test harness entry point.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
