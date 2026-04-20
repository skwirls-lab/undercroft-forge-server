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

## License

The Forge engine modules (`forge-engine/`) and card data (`forge-res/`) are from the
[Forge project](https://github.com/Card-Forge/forge) and licensed under **GPL v3**.

The bridge server code (`forge-server/`) is also licensed under **GPL v3** for compatibility.
