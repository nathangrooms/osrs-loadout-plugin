# OSRS Loadout

A RuneLite plugin that syncs your bank to [osrsloadout.com](https://www.osrsloadout.com/). You open a bank,
it uploads your item ids, and the site knows what you own. There is nothing to paste, nothing to click and
nothing to set up.

That is the whole plugin. No overlay, no side panel, and it watches nothing except the bank interface
opening.

## What happens, exactly

1. You open a bank.
2. The plugin reads your bank, your worn equipment and your inventory, and reduces them to a list of item
   ids.
3. If that list differs from the last one it sent, it POSTs it to the server with your character's display
   name.
4. The first successful sync of a session says so in your chat box. Later ones are silent.

Reopening a bank you have not changed sends nothing at all.

## What is uploaded

**Your bank is uploaded.** This plugin sends data to a server. Specifically, on each bank open where
something changed, it sends:

- your character's display name,
- the list of distinct item ids in your bank, worn equipment and inventory,
- a random key generated on this install, used to prove that this install owns that character's data.

It does **not** send quantities, levels, location, chat, your IP beyond the ordinary fact of making an HTTPS
request, or anything about any other character.

The destination is a Supabase project run by the owner of osrsloadout.com:

```
POST https://yqdqbsbgowqjkjplkrzi.supabase.co/functions/v1/bank
```

**The result is readable by anyone who knows your character's display name.** There are no accounts and no
passwords on the site side. This is the same order of exposure as the public hiscores: your name is already
public, and now the list of items you own is attached to it. If that is not acceptable to you, turn the
plugin off — it is a real consideration, not a formality.

Earlier versions of this plugin put the item list in a URL fragment on your clipboard and uploaded nothing.
That was more private and more annoying, and it was dropped because a sync you have to perform by hand is a
sync that stops happening.

### How the character is claimed

There are no accounts, so the first install to sync a name claims it. The server stores a SHA-256 of the
random key that install generated, and every later write for that name has to present the same key.

The key is generated once, stored in your RuneLite config, never displayed and never asked for. That is the
entire reason there is nothing to set up.

If you sync the same character from a second RuneLite install, the server answers `409` and the plugin says
so once in chat and then stops trying. "Reset sync key" in the plugin settings clears the key on whichever
install you run it on, letting the other one take the character over.

## Judgement calls

**The trigger is `BANKMAIN_FINISHBUILDING`, not `ItemContainerChanged`.** The obvious hook is
`ItemContainerChanged` for the bank container, and it is wrong: it fires when a stack size changes, so on a
bank nobody has touched since logging in there is nothing to fire and that player would never sync at all.
`WidgetLoaded` for the bank group arrives when the interface opens, which is not the same moment as the
server having sent the container. The bank finishing its build is both — the client has just laid the bank
out from the container, so the container is present and current. runelite-client's own `BankPlugin` computes
your bank value on this same script for the same reason.

**`WidgetLoaded` still does something: it debounces.** The bank rebuilds on every tab switch, every search
keystroke and every withdrawal, so `BANKMAIN_FINISHBUILDING` alone would fire dozens of requests per visit.
`WidgetLoaded` fires once per opening, so it arms a flag that the first build after it consumes.

**Worn equipment and inventory are sent too, not just the bank.** This is the one place the plugin does more
than it was asked to, and it is deliberate: the question the site is asking is "what do you own", and a
player's best items are usually the ones they are wearing. A bank-only read would tell the planner you do
not own your own gear, which is the one thing it must not get wrong. It costs nothing — same trigger, same
pass, same request — and the server takes a list of ids without caring where they came from. Reverting it is
deleting two lines in `capture()`.

**The change check keeps the id set, not a hash of it.** A full bank is about four kilobytes of `Integer`,
comparing it is exact, and a hash would introduce a collision that presents as the plugin silently refusing
to sync — a bug nobody would ever diagnose from inside the game.

**A `409` latches.** If another install owns the name, that is still true on the next bank and the one after.
The plugin says so once and stops until you log out or reset the key, rather than generating a request and a
chat line every time you bank.

**Failures are silent.** A dropped connection produces a debug log line and nothing else. The next bank is
the retry, and it arrives on its own without a timer, a backoff or a queue.

**There is a config, reluctantly.** The Plugin Hub requires that "Plugins which communicate with third party
servers [...] have a warning either on the plugin, or on the configuration option enabling the setting,
explaining what data is being sent". The sync toggle exists to carry that disclosure, so it defaults to on.
The sync key is deliberately not a config item; it is written straight to the config store so it never
renders in the settings panel.

**The HTTP is `enqueue`, not `execute`.** Reading the containers and resolving ids happens on the client
thread because it must. The request does not: OkHttp dispatches it on its own pool, so an unreachable server
costs the player nothing.

## Running it on this machine

`gradlew.bat run` launches a RuneLite development client with this plugin already loaded and developer mode
on. It is the whole testing loop: edit, `run`, open a bank.

The task is called `run`, not `runClient`. It comes from RuneLite's own plugin template, where it is declared
as a `JavaExec` that starts the client with `--developer-mode --debug` and side-loads the plugin class
through `ExternalPluginManager.loadBuiltin`.

### You need a JDK first

RuneLite builds with **JDK 11** — its own instructions say "You can build RuneLite locally using JDK 11", and
the Plugin Hub recommends Java 11 and Eclipse Temurin. Anything from 11 upward works for this plugin, which
compiles with `--release 11` regardless of the JDK doing the compiling.

There is no `java` on this machine's PATH and no `gradle` either. Two Temurin JDKs are nonetheless sitting on
disk, bundled inside other applications:

- `C:\Program Files\RuneMate\jre` — Temurin 17.0.8
- `C:\Users\natha\AppData\Local\JDownloader 2\jre` — Temurin 21.0.6

Either can build this project, and the first one is what was used to verify it. Borrowing another
application's runtime is fine for a one-off but it disappears the day you uninstall that application, so
install a real JDK when you want this to keep working:

```
winget install EclipseAdoptium.Temurin.11.JDK
```

Close and reopen the terminal afterwards so the new PATH is picked up.

### The commands, in order

Open PowerShell in this directory.

If you installed Temurin with the winget line above, this is the whole list:

```
.\gradlew.bat build
.\gradlew.bat run
```

If you would rather not install anything yet, point the build at the JDK already on the machine first:

```
$env:JAVA_HOME = "C:\Program Files\RuneMate\jre"
.\gradlew.bat build
.\gradlew.bat run
```

`$env:JAVA_HOME` lasts only for that terminal window, so set it again each time you open a new one.

The first `build` downloads Gradle 8.10 and the RuneLite client jars and takes a couple of minutes. Later
runs are seconds. `build` also runs the unit tests; `run` builds first, so you can skip straight to it once
you trust it.

One gotcha that is not this plugin's fault: **if you use a Jagex account, the development client cannot log
in without extra setup.** RuneLite documents it at
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts). Worth reading before
you sit down to test, rather than after.

### The other way: side-loading into the real client

If you would rather test in your normal RuneLite install than in the development client, it side-loads
plugins from a directory — but only when started in developer mode.

1. `.\gradlew.bat build`
2. Copy `build\libs\osrs-loadout-1.0.0.jar` into `C:\Users\<you>\.runelite\sideloaded-plugins\`, creating
   the directory if it does not exist. Use the plain jar, not the shadow jar — the shadow jar bundles an
   entire client.
3. Start RuneLite with `--developer-mode`. Side-loading is skipped entirely without it.
4. The plugin appears in the plugin list as "OSRS Loadout".

`.\gradlew.bat run` is the easier loop; this path is for testing against your real account and real bank.

### Does it build?

Yes, and it was verified here rather than assumed: `gradlew.bat build` completed with `BUILD SUCCESSFUL`
against `net.runelite:client` 1.12.38 resolved live from `repo.runelite.net`, with the four unit tests
passing. Because the build compiles against the real client jar, that result also confirms every RuneLite API
this plugin calls actually exists at the version it will ship against.

The Gradle wrapper is committed, so none of this needs a system Gradle install.

## What would be submitted to the Plugin Hub

Two things, in two repositories.

**This repository**, pushed to GitHub as a public repository under the owner's account. The Plugin Hub reads
it directly at a pinned commit. The files it cares about are `runelite-plugin.properties` (already written),
the `LICENSE` (BSD 2-Clause, which is the licence the Plugin Hub's instructions tell authors to pick), and
optionally an `icon.png` of no more than 48x72 pixels at the repository root. There is no icon yet.

**A one-file pull request to [runelite/plugin-hub](https://github.com/runelite/plugin-hub)**, adding
`plugins/osrs-loadout` containing nothing but:

```
repository=https://github.com/<owner>/osrs-loadout-plugin.git
commit=<the full 40-character commit hash>
```

Then the PR's CI has to come back green and a reviewer has to merge it. Updating later means changing that
`commit=` line and nothing else.

Things about the submission that shaped the code:

- **The third-party server disclosure is a hard requirement**, quoted above. It is met by the plugin
  description and by the sync option's description. This is the rule most likely to get the plugin sent back
  for changes, so the wording deserves a careful read before submitting.
- `build=standard` means the Plugin Hub **replaces** `build.gradle` and `settings.gradle` with its own copies
  when it builds. Anything clever in the build file would work locally and then vanish, which is why it is
  kept in the template's shape.
- Any dependency that is not already a transitive dependency of runelite-client needs its cryptographic hash
  added to a verification metadata file and reviewed by hand, and the Hub warns this "adds significantly to
  the amount of time it takes for a plugin submission or update to be reviewed". This plugin adds none:
  OkHttp, Gson and Guice all come from the client.
- Review covers two things — that the plugin is not malicious, and that it does not break Jagex's
  third-party client rules. Reflection and native code are restricted, external programs may not be executed,
  and code may not be downloaded at runtime. This plugin does none of those.

### Next step

This repository has local commits and no remote. Creating the GitHub repository and pushing is deliberately
left to the owner, since the Plugin Hub submission pins a commit hash from a public repository and that
should not happen before someone has read the code.

## Licence

BSD 2-Clause. See `LICENSE`.
