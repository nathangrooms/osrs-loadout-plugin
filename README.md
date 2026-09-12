# OSRS Loadout

A RuneLite plugin that copies a link to your gear onto your clipboard when you open a bank. Paste it into
your browser and [osrsloadout.com](https://www.osrsloadout.com/) opens with everything you own already
filled in.

That is the whole plugin. There is no overlay, no side panel, no configuration screen, and it watches
nothing except the bank interface opening.

## What happens, exactly

1. You open a bank.
2. The plugin reads your bank, your worn equipment and your inventory.
3. It builds a link and puts it on your clipboard.
4. A line appears in your chat box saying how many items it copied.

## Nothing is uploaded

osrsloadout.com is a static site with no backend, and its footer promises that nothing is uploaded. This
plugin does not change that. It opens no sockets, contacts no server, and writes nothing to disk.

The item list travels in the URL *fragment* — the part after the `#`:

```
https://www.osrsloadout.com/#v2~37b.93u.94o
```

Browsers never send the fragment to the server. It is not in the request line and not in the headers, so
even the act of opening the link tells the site's host nothing about what is in it. The site reads the
fragment with JavaScript in your own browser.

The clipboard is the whole transport. The link goes no further than wherever you paste it.

### The link format

`v2~` followed by each Grand Exchange item id in base 36, joined with `.`, ascending and de-duplicated.

The site's own share links use `v1~`, which encodes items as indexes into the site's internal item array. A
plugin cannot know those indexes and they move whenever the site's data is regenerated, so `v2~` exists for
this plugin and is keyed on Grand Exchange ids instead — the one name for an item that the game and the site
already agree on. The site skips ids it has no item for, so sending a whole bank is safe.

Noted items are sent as their unnoted id. Placeholders, bank fillers and empty slots are not sent at all.

## Judgement calls

**The trigger is `BANKMAIN_FINISHBUILDING`, not `ItemContainerChanged`.** The obvious hook is
`ItemContainerChanged` for the bank container, and it is wrong: that event fires when a stack size changes,
so on a bank nobody has touched since logging in there is nothing to fire and the player would open their
bank and get no link. `WidgetLoaded` for the bank group arrives when the interface opens, which is not the
same moment as the server having sent the container. The bank finishing its build is both: the client has
just laid the bank out from the container, so the container is present and current. This is also what
runelite-client's own `BankPlugin` uses to compute your bank value, which needs the same guarantee.

**`WidgetLoaded` still does something: it debounces.** The bank rebuilds on every tab switch, every search
keystroke and every withdrawal, so `BANKMAIN_FINISHBUILDING` on its own would copy dozens of times per visit.
`WidgetLoaded` fires once per bank opening, so it arms a flag that the first build after it consumes. One
copy per trip to a banker.

**Worn equipment and inventory are included, not just the bank.** The question the site is asking is "what
do you own", and a player's best items are usually the ones they are wearing. A bank-only read would tell
the planner you do not own your own gear, which is the one thing it must not get wrong. This adds no
user-visible surface: same trigger, same pass, same link.

**It copies on every bank opening, even if nothing changed.** The alternative is to skip the copy when the
item set is identical to last time, which would stop it clobbering a clipboard you were using for something
else. It is not the default because it breaks the mental model: open bank, get link. If you open your bank,
see the chat message, and find something else on your clipboard, that is worse than a redundant copy. If
this turns out to be annoying in practice, the fix is to keep the last packed string in a field and return
early when it matches.

**There is no config class.** A RuneLite plugin does not need one, and there is no decision here worth
handing to the player that is not better fixed in the code.

**The clipboard write happens off the client thread.** Reading the containers and resolving item ids must
happen on the client thread and does. The clipboard must not: on Windows and X11 a clipboard write means
negotiating with whatever process currently owns it, and a stall on the game thread is a visible freeze.
runelite-client's own `ImageCapture` moves off the client thread before writing the clipboard for exactly
this reason. The chat message is queued from that worker thread, which is safe because
`ChatMessageManager#queue` is an add to a `ConcurrentLinkedQueue` that the client thread drains.

## An alternative worth considering

`net.runelite.client.util.LinkBrowser.browse(url)` would open the player's browser directly, removing the
paste step entirely. It uploads nothing either — same fragment, same absence of a server — and it is a
materially nicer experience for a first-time user who does not know what to do with a clipboard.

It is not what this plugin does because opening a browser window every time you walk up to a banker is
intrusive in a way that a clipboard write is not, and a plugin that seizes focus mid-game will annoy people
into uninstalling it. The version of this that would be worth building is a bank-interface button that opens
the browser on demand, with the automatic clipboard copy left as it is. That is more UI than the brief asked
for, so it is written down here rather than built.

One practical note either way: a full bank packs to roughly five kilobytes of URL. That is fine in a
fragment for every current browser, and fine to paste, but it is not something to put in a chat message or a
QR code.

## Building

Requires a JDK 11 or newer. The Gradle wrapper is checked in, so:

```
./gradlew build
```

on Windows:

```
gradlew.bat build
```

This compiles against `net.runelite:client:latest.release` from `repo.runelite.net` and runs the unit tests
for the link format.

To run a development client with the plugin already loaded:

```
./gradlew run
```

## Installing it locally for testing

RuneLite side-loads plugins from a directory, but only when it is started in developer mode.

1. `./gradlew build`
2. Copy `build/libs/osrs-loadout-1.0.0.jar` into `~/.runelite/sideloaded-plugins/` (on Windows,
   `C:\Users\<you>\.runelite\sideloaded-plugins\`). Create the directory if it does not exist. Use the plain
   jar, not the shadow jar — the shadow jar bundles an entire client.
3. Start RuneLite with the `--developer-mode` flag. Side-loading is skipped entirely without it.
4. The plugin appears in the plugin list as "OSRS Loadout".

`./gradlew run` is the easier path for iterating, since it builds and launches in one step.

## What would be submitted to the Plugin Hub

Two things, in two repositories.

**This repository**, pushed to GitHub as a public repository under the owner's account. The Plugin Hub reads
it directly at a pinned commit. The files it cares about are `runelite-plugin.properties` (already written),
the `LICENSE` (BSD 2-Clause, which is the licence the Plugin Hub's own instructions tell authors to pick),
and optionally an `icon.png` of no more than 48x72 pixels at the repository root. There is no icon yet.

**A one-file pull request to [runelite/plugin-hub](https://github.com/runelite/plugin-hub)**, adding
`plugins/osrs-loadout` containing nothing but:

```
repository=https://github.com/<owner>/osrs-loadout-plugin.git
commit=<the full 40-character commit hash>
```

Then the PR's CI has to come back green, and a reviewer has to merge it. Updating the plugin later means
changing that `commit=` line and nothing else.

A few things about the submission that shaped the code:

- `build=standard` in `runelite-plugin.properties` means the Plugin Hub **replaces** `build.gradle` and
  `settings.gradle` with its own copies when it builds the plugin. Anything clever in the build file would
  work locally and then vanish. This is why the build file is deliberately kept in the template's shape.
- The Plugin Hub requires any dependency that is not already a transitive dependency of runelite-client to
  have its cryptographic hash added to a verification metadata file, reviewed by hand, and it warns that
  this "adds significantly to the amount of time it takes for a plugin submission or update to be reviewed".
  This plugin has no third-party dependencies at all.
- Review is about two things: that the plugin is not malicious, and that it does not break Jagex's
  third-party client rules. Reflection and native code are restricted, external programs may not be
  executed, and code may not be downloaded at runtime. This plugin does none of those things — the only
  non-RuneLite API it touches is `java.awt.datatransfer` for the clipboard.

### Next step

This repository has one local commit and no remote. Creating the GitHub repository and pushing is
deliberately left to the owner to do after review, since the Plugin Hub submission pins a commit hash from a
public repository and that should not happen before someone has read the code.

## Licence

BSD 2-Clause. See `LICENSE`.
