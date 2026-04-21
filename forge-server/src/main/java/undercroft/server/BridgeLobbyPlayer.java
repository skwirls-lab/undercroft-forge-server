package undercroft.server;

import com.google.gson.Gson;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import io.javalin.websocket.WsContext;

/**
 * LobbyPlayer implementation for the bridge.
 * Implements IGameEntitiesFactory because Forge casts LobbyPlayer to it
 * during Game initialization to create in-game Player objects.
 */
public class BridgeLobbyPlayer extends LobbyPlayer implements IGameEntitiesFactory {
    private WsContext wsContext;
    private Gson gson;
    private BridgePlayerController lastController;

    public BridgeLobbyPlayer(String name) {
        super(name);
    }

    public void setWsContext(WsContext wsContext, Gson gson) {
        this.wsContext = wsContext;
        this.gson = gson;
    }

    public BridgePlayerController getLastController() {
        return lastController;
    }

    @Override
    public void hear(LobbyPlayer player, String message) {
        // No-op for headless server
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player p = new Player(getName(), game, id);
        BridgePlayerController ctrl = new BridgePlayerController(game, p, this, wsContext, gson);
        p.setFirstController(ctrl);
        lastController = ctrl;
        return p;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return slave.getController();
    }
}
