# MassMsg

A client-side Fabric mod that sends one private message to every online player,
one at a time, except the players on your exempt list.

- Minecraft 1.21.11, Fabric Loader 0.16.14 or newer, Fabric API, Java 21
- Client only. The server does not need it.

**Download:** [releases/massmsg-1.0.6.jar](releases/massmsg-1.0.6.jar). Put it in your
`.minecraft/mods` folder with Fabric API.

## Commands

| Command | What it does |
|---|---|
| `/massmsg start` / `stop` | Start or stop a run. The **M** key also toggles it. |
| `/massmsg status` | Show progress, or the current settings when idle |
| `/massmsg message <text>` | Set the message. `{player}` is replaced with each name. |
| `/massmsg delay <ticks>` | Wait between messages. 20 ticks = 1 second, max 1200. |
| `/massmsg command <name>` | Which command to use: `msg`, `tell`, `w`, `whisper`, `message`, `m`, `pm` |
| `/massmsg shuffle on\|off` | Message players in random order |
| `/massmsg listed on\|off` | On: only players shown in tab. Off: the full player list (default). |
| `/massmsg ignore on\|off` | Keep going after a server error. Off by default. |
| `/massmsg exempt add\|remove\|list` | Players who never get a message |
| `/massmsg reload` | Reload the config file |
| `/massmsg help` | Show the command list in game |

Settings are saved in `.minecraft/config/massmsg.json`.

## How it behaves

- Skips you, NPC entries, invalid names, exempt players and anyone who left.
- Stops on its own if the server says the command is unknown or not allowed.
- Only private-message commands are allowed, and `§` codes and line breaks are
  removed from the message.

## Build

```
./gradlew build
```

The jar is in `build/libs/`.

## License

MIT. See [LICENSE](LICENSE).
