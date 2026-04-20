package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages a single game session between the WebSocket client (human player)
 * and the Forge engine (with AI opponent).
 */
public class GameSession {
    private static final Logger log = LoggerFactory.getLogger(GameSession.class);

    private final WsContext wsContext;
    private final JsonObject startPayload;
    private final Gson gson;

    private Game game;
    private Match match;
    private BridgePlayerController humanController;
    private Thread gameThread;
    private volatile boolean running = false;

    public GameSession(WsContext wsContext, JsonObject startPayload, Gson gson) {
        this.wsContext = wsContext;
        this.startPayload = startPayload;
        this.gson = gson;
    }

    /**
     * Start the game in a background thread.
     * Forge's game loop is synchronous and blocking — it runs in its own thread
     * and calls PlayerController methods when it needs decisions.
     */
    public void start() {
        running = true;

        // Parse deck from client payload
        Deck humanDeck = parseDeck(startPayload);
        String playerName = startPayload.has("playerName")
                ? startPayload.get("playerName").getAsString()
                : "Player";

        // Create registered players
        BridgeLobbyPlayer humanLobby = new BridgeLobbyPlayer(playerName);
        LobbyPlayerAi aiLobby = new LobbyPlayerAi("AI Opponent", null);

        RegisteredPlayer humanReg = new RegisteredPlayer(humanDeck);
        humanReg.setPlayer(humanLobby);

        // AI gets a random preconstructed deck or the same deck
        Deck aiDeck = humanDeck; // TODO: Generate proper AI deck
        RegisteredPlayer aiReg = new RegisteredPlayer(aiDeck);
        aiReg.setPlayer(aiLobby);

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(humanReg);
        players.add(aiReg);

        // Game rules — Commander format
        GameRules rules = new GameRules(GameType.Commander);
        rules.setPlayForAnte(false);
        rules.setManaBurn(false);
        rules.setGamesPerMatch(1);

        // Create match and game
        match = new Match(rules, players, playerName + " vs AI");
        game = match.createGame();

        // Find the human player and attach our bridge controller
        for (Player p : game.getPlayers()) {
            if (p.getLobbyPlayer() == humanLobby) {
                // Replace the default controller with our bridge
                humanController = new BridgePlayerController(game, p, humanLobby, wsContext, gson);
                p.setController(humanController);
            }
            p.updateOpponentsForView();
        }

        // Create the bridge GUI that pushes state to WebSocket
        BridgeGuiGame guiGame = new BridgeGuiGame(wsContext, gson);
        guiGame.setGameView(game.getView());

        // Subscribe to game events
        game.subscribeToEvents(new GameEventForwarder(wsContext, gson));

        // Run game loop in background thread
        gameThread = new Thread(() -> {
            try {
                log.info("Starting Forge game for session");
                sendGameState();
                match.startGame(game, null);

                // Game is over
                if (running) {
                    sendGameOver();
                }
            } catch (Exception e) {
                log.error("Game error: {}", e.getMessage(), e);
                if (running) {
                    ForgeServer.sendError(wsContext, "Game error: " + e.getMessage());
                }
            }
        }, "forge-game-" + wsContext.getSessionId());
        gameThread.setDaemon(true);
        gameThread.start();

        log.info("Game started for session {}", wsContext.getSessionId());
    }

    /**
     * Handle a choice response from the client.
     * This unblocks the BridgePlayerController which is waiting for input.
     */
    public void handleChoiceResponse(JsonObject payload) {
        if (humanController != null) {
            humanController.receiveChoiceResponse(payload);
        }
    }

    /**
     * Player concedes the game.
     */
    public void concede() {
        if (game != null && !game.isGameOver()) {
            for (Player p : game.getPlayers()) {
                if (p.getController() == humanController) {
                    p.concede();
                    break;
                }
            }
        }
    }

    /**
     * Clean up when the session ends.
     */
    public void shutdown() {
        running = false;
        if (humanController != null) {
            humanController.shutdown();
        }
        if (gameThread != null) {
            gameThread.interrupt();
        }
        log.info("Session shut down");
    }

    // --- State serialization ---

    void sendGameState() {
        try {
            JsonObject state = GameStateSerializer.serialize(game, humanController.getPlayer(), gson);
            ForgeServer.sendMessage(wsContext, "game_state", state);
        } catch (Exception e) {
            log.error("Error serializing game state: {}", e.getMessage(), e);
        }
    }

    private void sendGameOver() {
        JsonObject payload = new JsonObject();
        Player winner = game.getOutcome().getWinningPlayer();
        payload.addProperty("winner", winner != null ? winner.getName() : "draw");
        payload.addProperty("winnerIsHuman", winner != null && winner.getController() == humanController);
        ForgeServer.sendMessage(wsContext, "game_over", payload);
    }

    // --- Deck parsing ---

    /**
     * Parse a deck from the client's JSON payload.
     * Expected format: { "deckList": ["1 Lightning Bolt", "1 Mountain", ...], "commander": "Krenko, Mob Boss" }
     */
    private Deck parseDeck(JsonObject payload) {
        Deck deck = new Deck("Player Deck");

        if (payload.has("deckList")) {
            JsonArray deckList = payload.getAsJsonArray("deckList");
            for (int i = 0; i < deckList.size(); i++) {
                String line = deckList.get(i).getAsString().trim();
                if (line.isEmpty()) continue;

                // Parse "N CardName" format
                String[] parts = line.split("\\s+", 2);
                int count = 1;
                String cardName = line;
                try {
                    count = Integer.parseInt(parts[0]);
                    cardName = parts[1];
                } catch (NumberFormatException e) {
                    // Entire line is the card name
                }

                PaperCard card = StaticData.instance().getCommonCards().getCard(cardName);
                if (card != null) {
                    deck.getOrCreate(DeckSection.Main).add(card, count);
                } else {
                    log.warn("Card not found in Forge database: {}", cardName);
                }
            }
        }

        // Handle commander
        if (payload.has("commander")) {
            String cmdName = payload.get("commander").getAsString();
            PaperCard cmdCard = StaticData.instance().getCommonCards().getCard(cmdName);
            if (cmdCard != null) {
                deck.getOrCreate(DeckSection.Commander).add(cmdCard);
                // Remove from main if it was also listed there
                deck.getOrCreate(DeckSection.Main).remove(cmdCard);
            } else {
                log.warn("Commander not found in Forge database: {}", cmdName);
            }
        }

        return deck;
    }
}
