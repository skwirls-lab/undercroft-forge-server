package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
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

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7000"));
        String forgeRes = System.getenv().getOrDefault("FORGE_RES",
                "../resource/forge-master/forge-gui/res");

        log.info("Starting Undercroft Forge Server on port {}", port);
        log.info("Forge resource path: {}", forgeRes);

        // Initialize Forge's static data (card definitions, etc.)
        ForgeInit.initialize(forgeRes);

        Javalin app = Javalin.create(config -> {
            config.enableCorsForAllOrigins();
        });

        // Health check endpoint
        app.get("/health", ctx -> ctx.result("ok"));

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
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("payload", gson.toJsonTree(payload));
        ctx.send(msg.toString());
    }

    static void sendError(WsContext ctx, String message) {
        sendMessage(ctx, "error", Map.of("message", message));
    }
}
