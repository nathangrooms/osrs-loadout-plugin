# OSRS Loadout

A RuneLite plugin that sends your bank to [osrsloadout.com](https://www.osrsloadout.com/) when you press a
button, so the site can work out the best gear you can field from what you own.

**It sends data to a third-party server, and it is off until you turn it on.** Enable *Sync bank to
osrsloadout.com* in the plugin's settings.

## How it works

1. Turn on *Sync bank to osrsloadout.com* in the plugin's settings.
2. Open your bank in game. The plugin reads it locally; **nothing is sent**.
3. Press **Sync now** in the OSRS Loadout side panel. That is the only thing that uploads your bank.
4. The first time, a code appears in the panel and in chat. Enter it at osrsloadout.com once to link your
   browser.

Nothing is ever uploaded automatically.

## What is sent

When you press **Sync now**, one request to `https://www.osrsloadout.com/api/v1/bank` containing:

- the item ids and quantities in your bank, worn equipment and inventory
- your bank's layout: which item is in each slot, and the sizes of your nine tabs
- a random key the plugin generated on this install

**Get a link code** sends only that key, to `https://www.osrsloadout.com/api/v1/bank/pair`.

The plugin never sends your character name, levels, location, chat, or anything about other players. With
the setting off it makes no requests at all.

## Privacy

Your bank is stored against the random key, not against your character. **Reset sync key** in the panel moves
it to a new key and unlinks every browser. See the [privacy page](https://www.osrsloadout.com/privacy).

## Building

Requires JDK 11 or newer. `./gradlew build` compiles and runs the tests; `./gradlew run` starts a development
client with the plugin loaded.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
