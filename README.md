# OSRS Loadout

A RuneLite plugin that syncs your bank to [osrsloadout.com](https://www.osrsloadout.com/). You open a bank,
it uploads your item ids, and the site knows what you own. You type one short code into the website the first
time; after that there is nothing to paste, nothing to click and nothing to keep doing.

That is the whole plugin. No overlay, no side panel, and it watches nothing except the bank interface
opening.

## What happens, exactly

1. You open a bank.
2. The plugin reads your bank, your worn equipment and your inventory, and reduces them to a list of item
   ids.
3. If that list differs from the last one it sent, it uploads it.
4. The first time, it prints one line in your chat box with a code:

   > OSRS Loadout: synced 812 items. Type 3XQQ-W3EM at osrsloadout.com to link this browser.

5. You type that code into the website once. After that the site reads your bank on its own, for ever, and
   the plugin never mentions the code again.

Reopening a bank you have not changed sends nothing at all. Typing the code is the only thing you are ever
asked to do.

## What is uploaded

**Your bank is uploaded.** This plugin sends data to a server. On each bank open where something changed, it
sends:

- the list of distinct item ids in your bank, worn equipment and inventory,
- how many of each you have,
- a random key generated once on this install,
- your character's display name, as a label so the site has something to show next to the bank.

It does **not** send levels, location, chat, or anything about any other character.

Quantities are there so the site can tell five thousand yew logs from one. Without them every row looks the
same size, and the difference is most of what makes a pile of loot worth selling to fund an upgrade. What
counts as loot, as a supply, or as gear you should keep is decided entirely on the site — the plugin reports
what is in the bank and expresses no opinion about any of it.

The destination is a Supabase project run by the owner of osrsloadout.com:

```
POST https://yqdqbsbgowqjkjplkrzi.supabase.co/functions/v1/bank
```

### Knowing your character's name gets you nothing

Your bank is stored under the SHA-256 of the random key, and **only** under that. The display name is a
label, not an address: there is no endpoint that takes a name and returns a bank, so a stranger who knows
what you are called cannot look you up. That is a deliberate change from an earlier version of this plugin,
where the name *was* the key and anyone who could type it could read a stranger's gear.

The key is generated once from `UUID.randomUUID()`, which Java specifies to use a cryptographically strong
generator — `java.security.SecureRandom` — giving 122 random bits stored as 32 hex characters. It is kept in
your RuneLite config, never displayed and never asked for. It is the entire identity of your bank, which is
both why there is nothing to set up and why the only way a browser gets to read your bank is you typing a
code into it.

There is no account, no email and no password anywhere in this system — not on the site, not in the plugin,
not on the server.

An earlier version put the item list in a URL fragment on your clipboard and uploaded nothing at all. That
was more private and more annoying, and it was dropped because a sync you have to perform by hand is a sync
that stops happening.

### A linked browser can read your bank, never write one

Typing a code in gives that browser a **read** id, not the secret. The secret never leaves your RuneLite
install. So a browser you linked and later lost — a stolen phone, a shared machine — can look at your gear
and can never overwrite it, and cannot be used to touch anybody else's.

Codes are eight characters from a 31-letter alphabet, single use, ten minutes. Guessing is rate limited at
the server: twelve wrong attempts from one address in fifteen minutes and it stops answering with `429`, with
a success clearing the count.

### Linking more than one browser

Ask for a code once per browser, one after another: link the first, then tick the option again for the
second. There is deliberately no way to have two live codes at once — asking for a code retires any unused
one, so only ever one door is open.

## The three buttons

Everything else is automatic. These are in the plugin's settings for the three moments it isn't.

**Re-sync my bank now** uploads again immediately even if nothing changed. It is for when you can see the
site is wrong — stale gear, a sync that failed while you were offline. Without it the only way to force an
upload would be to go and change your bank, which is the kind of workaround that makes people stop trusting a
tool. If a bank is open it re-reads it; if not it re-sends the last bank it saw this session, and if it has
not seen one it says so rather than doing nothing quietly.

**Show a new link code** prints a fresh code, for a second computer or a browser whose site data you cleared.

**Reset sync key** is the real revoke. Unlinking inside the website only makes that browser forget the id it
is holding — the id itself keeps working for anyone who has it. Resetting the key generates a new secret,
which moves your bank to a different address and makes every id ever handed out against the old one stop
resolving. It unlinks every browser on every device at once and cannot be undone, so each one needs a new
code afterwards.

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

**The change check keeps the reading itself, not a hash of it.** A full bank is a few kilobytes of boxed
numbers, comparing it is exact, and a hash would introduce a collision that presents as the plugin silently
refusing to sync — a bug nobody would ever diagnose from inside the game. It compares quantities as well as
ids, because otherwise selling half a stack of logs would never reach the site: the row is still there, and
only the number moved.

**An item found in more than one place has its quantities summed.** Runes part-carried and part-banked, a
stack of logs in the inventory on top of the pile in the bank, noted and unnoted copies that `canonicalize`
has just folded into one id — all of these are genuinely one holding split across containers. Taking a single
container's figure would under-report every one of them.

**The sum is accumulated in a `long` and clamped to `Integer.MAX_VALUE` on the way out.** No real bank gets
close, but a wrapped total would arrive at the server as a negative number and be read as nonsense, whereas a
clamped one is merely the largest amount the wire can express.

**The "linked" flag is only set once a code has actually reached the player.** Setting it when the pairing
request was merely sent would burn their one prompt on a failure they never saw, leaving them with a synced
bank and no way to discover the code short of finding the settings item. So a failed pairing call leaves the
flag down, prints the plain "synced N items" line, and tries again at the next bank.

**Failures are silent.** A dropped connection produces a debug log line and nothing else. The next bank is
the retry, and it arrives on its own without a timer, a backoff or a queue.

**The code is printed exactly as the server returns it.** Its alphabet deliberately has no `O`/`0` and no
`I`/`1`/`l` because it is read off a chat line and typed into a browser, so reformatting, upper-casing or
re-hyphenating it here could only introduce a character the server will not accept back.

**A read with no bank container returns nothing, rather than what it found.** Worn equipment and the
inventory are readable the moment you log in, but the bank is not. Had the read simply returned whatever it
could see, pressing "Re-sync my bank now" before opening a bank would have uploaded the thirty items the
player happens to be carrying *as* their bank, silently replacing a real one on the server. So the bank
container is a precondition for the whole read, not one of three optional sources.

**Re-sync re-reads instead of trusting the memo.** If the bank is open the player may be several withdrawals
past the last capture, and re-sending a set they can see is stale would be worse than useless given the
button exists precisely for people who doubt the sync. The memo is the fallback for a closed bank, not the
source.

**Re-sync and the automatic sync differ on silence.** A failed upload nobody asked for stays silent, because
a chat line every time someone banks offline is worse than not syncing. A failed upload they pressed a button
for says so, because they are waiting for an answer.

**There is a config, reluctantly, and it has four items.** The Plugin Hub requires that "Plugins which
communicate with third party servers [...] have a warning either on the plugin, or on the configuration
option enabling the setting, explaining what data is being sent". The sync toggle carries that disclosure, so
it defaults to on. The other three are actions rather than settings, and each exists for a moment the
automatic path cannot cover: the site looks wrong, another browser needs linking, or access needs revoking.
The secret and the linked flag are deliberately *not* config items — they are written straight to the config
store so they never render in the settings panel, because the secret is the sole credential and there should
be nothing to read out or clear by accident.

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

## Testing it in game

The short version: **you cannot test this in the RuneLite you launch from the Jagex Launcher.** Not because
of anything in this plugin — RuneLite refuses on purpose, and it is worth knowing exactly how, because the
refusal is silent.

Side-loading is gated on developer mode (`PluginManager.loadSideLoadPlugins` opens with
`if (!developerMode) { return; }`), and developer mode is gated on not having been started by the launcher:

```java
final boolean developerMode = options.has("developer-mode")
    && RuneLiteProperties.getLauncherVersion() == null;
```

The launcher always sets that property. So `--developer-mode` typed into **RuneLite (configure)**, or set in
`RUNELITE_ARGS`, is parsed, accepted, and then quietly AND-ed away — no error, no log line, the plugin simply
never appears. The maintainers declined a request to change this ("We aren't interested in this due to the
potential abuse"), so it is not a bug to route around.

What works is running the client jar yourself, which is what `run-dev.ps1` does — using the JRE, the client
jars and the plugin directory the launcher has already set up, so there is nothing extra to install:

```powershell
.\gradlew.bat build
.\run-dev.ps1
```

Two things that script has to get right and that cost an afternoon to find:

- **`-ea` is mandatory.** With developer mode on and assertions off, `RuneLite.main()` puts up "Developers
  should enable assertions" and *returns* — before the injector is built, before any plugin loads.
- **`repository2` holds two versions.** `client-1.12.37.jar` and `runelite-api-1.12.37-runtime.jar` sit
  beside the 1.12.38 pair, and whichever the classpath hits first wins.

### Once, for a Jagex account

A directly-launched client has no Jagex session, and a migrated account cannot use the old username and
password screen. RuneLite's answer is a flag that dumps the launcher's tokens to a file — and that flag,
unlike `--developer-mode`, is *not* gated, so it works through the launcher:

1. Start menu → **RuneLite (configure)** (needs launcher 2.6.3+; ours reports 2.6.10).
2. In **Client arguments**, put `--insecure-write-credentials`, and Save.
3. Launch OSRS through the Jagex Launcher as normal, log in, then close it. This writes
   `C:\Users\<you>\.runelite\credentials.properties`.
4. Go back into **RuneLite (configure)** and clear that box again.

After that `run-dev.ps1` logs in on its own. That file is a password-equivalent, non-expiring token: do not
share it, delete it when you are done, and you can revoke it with "End sessions" in your account settings.

`.\gradlew.bat run` is the other route — RuneLite's own template task, which starts a client with
`--developer-mode --debug` and loads the plugin through `ExternalPluginManager.loadBuiltin`. It needs the
same credentials step, and `-ea` in its VM options.

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
