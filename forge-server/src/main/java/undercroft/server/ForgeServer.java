package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.item.PaperCard;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Undercroft Forge Server — WebSocket bridge between Forge MTG engine and web frontend.
 *
 * Protocol:
 *   Client → Server:
 *     { "type": "start_game", "payload": { "deckList": [...], "commander": "...", "format": "commander" } }
 *     { "type": "choice_response", "payload": { "requestId": "...", ... } }
 *     { "type": "concede" }
 *
 *   Server → Client:
 *     { "type": "game_state", "payload": { ... full game state ... } }
 *     { "type": "choice_request", "payload": { "requestId": "...", "choiceType": "...", ... } }
 *     { "type": "game_event", "payload": { "eventType": "...", ... } }
 *     { "type": "game_over", "payload": { "winner": "...", "reason": "..." } }
 *     { "type": "error", "payload": { "message": "..." } }
 */
public class ForgeServer {
    private static final Logger log = LoggerFactory.getLogger(ForgeServer.class);
    private static final Gson gson = new Gson();

    // Active game sessions keyed by WebSocket session ID
    private static final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private static final Map<WsContext, String> contextToSession = new ConcurrentHashMap<>();

    static String getSessionId(WsContext ctx) {
        return contextToSession.computeIfAbsent(ctx, k -> UUID.randomUUID().toString());
    }

    /**
     * Below this, something is wrong with the card data even though the load reported success.
     * A healthy tree loads ~94,600 cards; a partial checkout or a wrong FORGE_RES path can
     * still produce a small, non-zero count, which is worse than an outright failure because
     * the server then starts and quietly cannot find half the cards in a decklist.
     */
    private static final int MIN_EXPECTED_CARDS = 30_000;

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7000"));
        String forgeRes = System.getenv().getOrDefault("FORGE_RES",
                "../resource/forge-master/forge-gui/res");

        boolean smokeTest = Arrays.asList(args).contains("--smoke-test");

        log.info("Starting Undercroft Forge Server on port {}", port);
        log.info("Forge resource path: {}", forgeRes);

        // Initialize Forge's static data (card definitions, etc.)
        ForgeInit.initialize(forgeRes);

        if (smokeTest) {
            System.exit(runSmokeTest());
        }

        Javalin app = Javalin.create(config -> {
            config.enableCorsForAllOrigins();
        });

        // Health check endpoint
        app.get("/health", ctx -> ctx.result("ok"));

        // Card verification endpoint — checks which card names exist in Forge's database
        app.post("/api/check-cards", ctx -> {
            try {
                JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
                JsonArray names = body.getAsJsonArray("cardNames");
                JsonObject result = new JsonObject();
                JsonArray found = new JsonArray();
                JsonArray notFound = new JsonArray();

                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i).getAsString();
                    PaperCard card = StaticData.instance().getCommonCards().getCard(name);
                    if (card != null) {
                        found.add(name);
                    } else {
                        notFound.add(name);
                    }
                }

                result.add("found", found);
                result.add("notFound", notFound);
                ctx.contentType("application/json");
                ctx.result(result.toString());
            } catch (Exception e) {
                log.error("Error checking cards: {}", e.getMessage(), e);
                ctx.status(400).result("{\"error\": \"" + e.getMessage() + "\"}");
            }
        });

        app.ws("/game", ws -> {
            ws.onConnect(ctx -> {
                String sessionId = getSessionId(ctx);
                log.info("Client connected: {}", sessionId);
                sendMessage(ctx, "connected", Map.of("sessionId", sessionId));
            });

            ws.onMessage(ctx -> {
                String sessionId = getSessionId(ctx);
                try {
                    JsonObject msg = gson.fromJson(ctx.message(), JsonObject.class);
                    String type = msg.get("type").getAsString();
                    JsonObject payload = msg.has("payload") ? msg.getAsJsonObject("payload") : new JsonObject();

                    switch (type) {
                        case "start_game" -> handleStartGame(ctx, sessionId, payload);
                        case "choice_response" -> handleChoiceResponse(sessionId, payload);
                        case "concede" -> handleConcede(sessionId);
                        case "ping" -> {} // Keepalive — no-op
                        default -> sendError(ctx, "Unknown message type: " + type);
                    }
                } catch (Exception e) {
                    log.error("Error processing message from {}: {}", sessionId, e.getMessage(), e);
                    sendError(ctx, "Server error: " + e.getMessage());
                }
            });

            ws.onClose(ctx -> {
                String sessionId = getSessionId(ctx);
                log.info("Client disconnected: {}", sessionId);
                GameSession session = sessions.remove(sessionId);
                if (session != null) {
                    session.shutdown();
                }
                contextToSession.remove(ctx);
            });

            ws.onError(ctx -> {
                String sessionId = getSessionId(ctx);
                log.error("WebSocket error for {}: {}", sessionId,
                        ctx.error() != null ? ctx.error().getMessage() : "unknown");
            });
        });

        app.start(port);
        log.info("Forge Server ready on ws://localhost:{}/game", port);
    }

    private static void handleStartGame(WsContext ctx, String sessionId, JsonObject payload) {
        // Clean up existing session if any
        GameSession existing = sessions.remove(sessionId);
        if (existing != null) {
            existing.shutdown();
        }

        try {
            GameSession session = new GameSession(ctx, payload, gson);
            sessions.put(sessionId, session);
            session.start();
        } catch (Exception e) {
            log.error("Failed to start game for {}: {}", sessionId, e.getMessage(), e);
            sendError(ctx, "Failed to start game: " + e.getMessage());
        }
    }

    private static void handleChoiceResponse(String sessionId, JsonObject payload) {
        GameSession session = sessions.get(sessionId);
        if (session != null) {
            session.handleChoiceResponse(payload);
        }
    }

    private static void handleConcede(String sessionId) {
        GameSession session = sessions.get(sessionId);
        if (session != null) {
            session.concede();
        }
    }

    // --- Utility ---

    static void sendMessage(WsContext ctx, String type, Object payload) {
        // Called from the engine thread (BridgePlayerController.requestChoice). If the socket
        // has closed, ctx.send throws; letting that propagate kills the game thread mid-turn
        // and leaks the pending-choice entry. Log and let the caller's timeout handle it.
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", type);
            msg.add("payload", gson.toJsonTree(payload));
            ctx.send(msg.toString());
        } catch (Exception e) {
            log.warn("Failed to send '{}' message: {}", type, e.getMessage());
        }
    }

    static void sendError(WsContext ctx, String message) {
        sendMessage(ctx, "error", Map.of("message", message));
    }

    /**
     * Load-and-exit check for CI. Returns a process exit code: 0 healthy, 1 not.
     *
     * This exists because of two real failures. The bridge did not compile for two months and
     * nobody noticed, and a card-data refresh from a newer upstream Forge aborts the entire
     * card database with "No enum constant forge.card.CardSplitType.Prepare" when a script
     * uses a mechanic this engine build does not know — leaving a server that starts, reports
     * ready, and has zero cards. A compile is not enough of a gate; the card database has to
     * be loaded and counted.
     */
    private static int runSmokeTest() {
        try {
            int cards = StaticData.instance().getCommonCards().getAllCards().size();
            if (cards < MIN_EXPECTED_CARDS) {
                log.error("SMOKE TEST FAILED: only {} cards loaded, expected at least {}",
                        cards, MIN_EXPECTED_CARDS);
                return 1;
            }
            log.info("SMOKE TEST PASSED: {} cards loaded", cards);
            return 0;
        } catch (Exception e) {
            log.error("SMOKE TEST FAILED: {}", e.getMessage(), e);
            return 1;
        }
    }
}
