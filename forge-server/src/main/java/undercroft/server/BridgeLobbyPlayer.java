package undercroft.server;

import forge.LobbyPlayer;

/**
 * Minimal LobbyPlayer implementation for the bridge.
 * Forge requires a LobbyPlayer for each player in the game.
 */
public class BridgeLobbyPlayer extends LobbyPlayer {
    public BridgeLobbyPlayer(String name) {
        super(name);
    }

    @Override
    public boolean isAI() {
        return false;
    }
}
