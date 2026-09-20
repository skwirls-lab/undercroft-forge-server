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

        // Parse deck from client payload — no padding for human (resolved at import time)
        Deck humanDeck = parseDeck(startPayload, false);
        String playerName = startPayload.has("playerName")
                ? startPayload.get("playerName").getAsString()
                : "Player";

        // Number of AI opponents (default 1, up to 3 for Commander)
        int aiCount = startPayload.has("aiCount")
                ? Math.max(1, Math.min(3, startPayload.get("aiCount").getAsInt()))
                : 1;

        // Create registered players
        BridgeLobbyPlayer humanLobby = new BridgeLobbyPlayer(playerName);
        humanLobby.setWsContext(wsContext, gson);

        // Use forCommander() to set 40 life and assign commander from deck's Commander section
        RegisteredPlayer humanReg = RegisteredPlayer.forCommander(humanDeck);
        humanReg.setPlayer(humanLobby);

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(humanReg);

        // Parse AI decks from payload (if provided by the client)
        List<Deck> aiDeckList = new ArrayList<>();
        List<String> aiDeckNames = new ArrayList<>();
        if (startPayload.has("aiDecks") && startPayload.get("aiDecks").isJsonArray()) {
            JsonArray aiDecksArray = startPayload.getAsJsonArray("aiDecks");
            for (int i = 0; i < aiDecksArray.size(); i++) {
                JsonObject aiDeckPayload = aiDecksArray.get(i).getAsJsonObject();
                aiDeckList.add(parseDeck(aiDeckPayload, true));
                aiDeckNames.add(aiDeckPayload.has("name") && aiDeckPayload.get("name").isJsonPrimitive()
                        ? aiDeckPayload.get("name").getAsString() : null);
            }
        }

        // Create AI opponents — use dedicated AI decks if available, otherwise fallback to humanDeck.
        // A seat is named after its deck when the client says so (the player chose what this
        // opponent plays, and the log should say "Krenko Goblins cast..." not "AI Opponent 2
        // cast..."). Names are de-duplicated: two players with one name cannot be told apart.
        String[] defaultNames = {"AI Opponent", "AI Opponent 2", "AI Opponent 3"};
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        usedNames.add(playerName);
        for (int i = 0; i < aiCount; i++) {
            String aiName = defaultNames[i];
            if (i < aiDeckNames.size() && aiDeckNames.get(i) != null && !aiDeckNames.get(i).isBlank()) {
                aiName = aiDeckNames.get(i).trim();
                if (aiName.length() > 40) aiName = aiName.substring(0, 40);
            }
            String candidate = aiName;
            for (int n = 2; usedNames.contains(candidate); n++) candidate = aiName + " " + n;
            usedNames.add(candidate);
            LobbyPlayerAi aiLobby = new LobbyPlayerAi(candidate, null);
            Deck aiDeck = (i < aiDeckList.size()) ? aiDeckList.get(i) : humanDeck;
            RegisteredPlayer aiReg = RegisteredPlayer.forCommander(aiDeck);
            aiReg.setPlayer(aiLobby);
            players.add(aiReg);
        }

        // Game rules — Commander format
        GameRules rules = new GameRules(GameType.Commander);
        rules.addAppliedVariant(GameType.Commander); // Required: hasCommander() checks appliedVariants, not gameType
        rules.setPlayForAnte(false);
        rules.setManaBurn(false);
        rules.setGamesPerMatch(1);

        // Create match and game
        // createGame() calls IGameEntitiesFactory.createIngamePlayer() on each LobbyPlayer
        // which creates the BridgePlayerController for the human player
        String matchTitle = playerName + " vs " + aiCount + " AI" + (aiCount > 1 ? "s" : "");
        match = new Match(rules, players, matchTitle);
        game = match.createGame();

        // Get the controller that was created during game initialization
        humanController = humanLobby.getLastController();

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
        }, "forge-game-" + ForgeServer.getSessionId(wsContext));
        gameThread.setDaemon(true);
        gameThread.start();

        log.info("Game started for session {}", ForgeServer.getSessionId(wsContext));
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
        RegisteredPlayer winner = game.getOutcome().getWinningPlayer();
        payload.addProperty("winner", winner != null ? winner.getPlayer().getName() : "draw");
        payload.addProperty("winnerIsHuman", winner != null && winner.getPlayer().getName().equals(
                humanController != null ? humanController.getPlayer().getName() : ""));
        ForgeServer.sendMessage(wsContext, "game_over", payload);
    }

    // --- Deck parsing ---

    /**
     * Parse a deck from the client's JSON payload.
     * Expected format: { "deckList": ["1 Lightning Bolt", "1 Mountain", ...], "commander": "Krenko, Mob Boss" }
     */
    private Deck parseDeck(JsonObject payload, boolean padMissing) {
        Deck deck = new Deck("Player Deck");
        int missingCount = 0;

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
                    missingCount += count;
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

        // Pad missing cards with basic lands (AI decks only — human decks resolve at import)
        if (padMissing && missingCount > 0) {
            String basicLandName = guessBasicLand(payload);
            PaperCard basicLand = StaticData.instance().getCommonCards().getCard(basicLandName);
            if (basicLand != null) {
                deck.getOrCreate(DeckSection.Main).add(basicLand, missingCount);
                log.info("Padded deck with {} {} to replace {} missing card(s)", missingCount, basicLandName, missingCount);
            }
        }

        return deck;
    }

    /**
     * Guess which basic land to use based on the commander name or deck color hints.
     * Falls back to "Wastes" if no color can be determined.
     */
    private String guessBasicLand(JsonObject payload) {
        // Check if there's a color hint (sent by AI deck data)
        if (payload.has("colors")) {
            String colors = payload.get("colors").getAsString().toUpperCase();
            if (colors.contains("W")) return "Plains";
            if (colors.contains("U")) return "Island";
            if (colors.contains("B")) return "Swamp";
            if (colors.contains("R")) return "Mountain";
            if (colors.contains("G")) return "Forest";
        }

        // Try to infer from the deck list — find the most common basic land already in the list
        if (payload.has("deckList")) {
            String[] basics = {"Plains", "Island", "Swamp", "Mountain", "Forest"};
            JsonArray deckList = payload.getAsJsonArray("deckList");
            for (String basic : basics) {
                for (int i = 0; i < deckList.size(); i++) {
                    if (deckList.get(i).getAsString().contains(basic)) {
                        return basic;
                    }
                }
            }
        }

        return "Mountain"; // Safe fallback
    }
}
