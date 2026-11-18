# Undercroft Forge Server

WebSocket bridge server that exposes the [Forge MTG engine](https://github.com/Card-Forge/forge) for the [Undercroft](https://github.com/skwirls-lab/undercroft) web frontend.

## Architecture

```
Browser (Undercroft UI)  ←WebSocket→  This Server  ←Java calls→  Forge Engine
```

The server wraps Forge's game engine behind a JSON/WebSocket protocol. The frontend sends player decisions, and the server sends back game state updates and choice prompts.

## Project Structure

```
├── forge-engine/          # Stripped Forge modules (GPL v3)
│   ├── forge-core/        # Base utilities
│   ├── forge-game/        # Rules engine
│   ├── forge-ai/          # AI opponent logic
│   └── forge-gui/         # Interfaces (IGuiGame, PlayerController)
├── forge-server/          # Our WebSocket bridge code
│   └── src/main/java/undercroft/server/
├── forge-res/             # Card definitions & game data
│   ├── cardsfolder/       # 25,000+ card scripts
│   ├── editions/          # Set/edition data
│   └── ...
├── Dockerfile             # Multi-stage build for deployment
└── pom.xml                # Root Maven build
```

## Configuration

Set these as environment variables on the host (Railway: the service's Variables tab). None is required.

| Variable | Default | What it does |
|---|---|---|
| `MAX_HEAP_MB` | `512` | Java heap in MB. Every running game lives in this heap. The container needs roughly 300 MB on top of it. |
| `MAX_GAMES` | from the heap | Games allowed at once. Default is `(heap - 160) / 40`, which is 8 at 512 MB and 21 at 1024 MB. Past the limit a new game is refused with `server_full`. |
| `FIREBASE_PROJECT_ID` | unset | When set, `start_game` must carry a valid Firebase ID token for this project (`idToken`), so only signed-in users of the app can start games. Use the same value as the web app's `NEXT_PUBLIC_FIREBASE_PROJECT_ID`. Unset, anyone who can reach the server can start a game. |
| `PORT` | `7000` | Listening port. |

Measured against a 512 MB heap: the card database takes about 160 MB, a four-player game about 12 MB early and about 35 MB by turn 25.

`GET /status` reports how full the server is: `{"activeGames":2,"maxGames":8,"heapUsedMb":231,"heapMaxMb":512,"signInRequired":true}`.

### Errors the client acts on

`error` messages carry a `code` when the client should do more than log them:

| Code | Meaning |
|---|---|
| `server_full` | The game limit is reached; nothing was started. Also carries `activeGames` and `maxGames`. |
| `auth_required` | No valid sign-in token came with `start_game`. |
| `auth_unavailable` | The token could not be checked (Google's signing keys were unreachable). |
| `start_failed` | The game could not be created from the payload. |
| `game_crashed` | The game's engine thread died (out of memory, or an engine error). The game is over. |

A game ends on the server when its socket closes. The engine is stopped, not left to play itself out.

## License

The Forge engine modules (`forge-engine/`) and card data (`forge-res/`) are from the
[Forge project](https://github.com/Card-Forge/forge) and licensed under **GPL v3**.

The bridge server code (`forge-server/`) is also licensed under **GPL v3** for compatibility.
