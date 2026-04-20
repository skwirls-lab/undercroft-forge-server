package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import forge.game.event.*;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscribes to Forge game events and forwards them to the WebSocket client.
 * This allows the frontend to show animations, sounds, and log entries.
 */
public class GameEventForwarder implements IGameEventVisitor<Void> {
    private static final Logger log = LoggerFactory.getLogger(GameEventForwarder.class);

    private final WsContext wsContext;
    private final Gson gson;

    public GameEventForwarder(WsContext wsContext, Gson gson) {
        this.wsContext = wsContext;
        this.gson = gson;
    }

    private void sendEvent(String eventType, JsonObject data) {
        data.addProperty("eventType", eventType);
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }

    @Override
    public Void visit(GameEventTurnPhase event) {
        JsonObject data = new JsonObject();
        data.addProperty("phase", event.phase != null ? event.phase.name() : "");
        data.addProperty("playerTurn", event.playerTurn != null ? event.playerTurn.toString() : "");
        sendEvent("turn_phase", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardDamaged event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardId", event.card.getId());
        data.addProperty("cardName", event.card.getName());
        data.addProperty("damage", event.amount);
        data.addProperty("isCombat", event.isCombat);
        sendEvent("card_damaged", data);
        return null;
    }

    @Override
    public Void visit(GameEventSpellAbilityCast event) {
        JsonObject data = new JsonObject();
        if (event.sa != null && event.sa.getHostCard() != null) {
            data.addProperty("cardName", event.sa.getHostCard().getName());
            data.addProperty("cardId", event.sa.getHostCard().getId());
        }
        data.addProperty("description", event.sa != null ? event.sa.toString() : "");
        sendEvent("spell_cast", data);
        return null;
    }

    @Override
    public Void visit(GameEventSpellResolved event) {
        JsonObject data = new JsonObject();
        if (event.sa != null && event.sa.getHostCard() != null) {
            data.addProperty("cardName", event.sa.getHostCard().getName());
        }
        sendEvent("spell_resolved", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardChangeZone event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardId", event.card.getId());
        data.addProperty("cardName", event.card.getName());
        data.addProperty("from", event.from != null ? event.from.getZoneType().name() : "");
        data.addProperty("to", event.to != null ? event.to.getZoneType().name() : "");
        sendEvent("card_zone_change", data);
        return null;
    }

    @Override
    public Void visit(GameEventPlayerLivesChanged event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.player.getName());
        data.addProperty("oldLife", event.oldLives);
        data.addProperty("newLife", event.newLives);
        sendEvent("life_changed", data);
        return null;
    }

    @Override
    public Void visit(GameEventPlayerPoisoned event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.receiver.getName());
        data.addProperty("oldPoison", event.oldPoison);
        data.addProperty("newPoison", event.newPoison);
        sendEvent("poison_changed", data);
        return null;
    }

    @Override
    public Void visit(GameEventCombatChanged event) {
        sendEvent("combat_changed", new JsonObject());
        return null;
    }

    @Override
    public Void visit(GameEventGameOutcome event) {
        JsonObject data = new JsonObject();
        data.addProperty("winner", event.result.getWinningPlayer() != null
                ? event.result.getWinningPlayer().getName() : "draw");
        sendEvent("game_outcome", data);
        return null;
    }

    @Override
    public Void visit(GameEventGameFinished event) {
        sendEvent("game_finished", new JsonObject());
        return null;
    }

    @Override
    public Void visit(GameEventCardTapped event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardId", event.card.getId());
        data.addProperty("cardName", event.card.getName());
        data.addProperty("tapped", event.tapped);
        sendEvent("card_tapped", data);
        return null;
    }

    @Override
    public Void visit(GameEventTokenCreated event) {
        sendEvent("token_created", new JsonObject());
        return null;
    }

    @Override
    public Void visit(GameEventCardCounters event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardId", event.card.getId());
        data.addProperty("cardName", event.card.getName());
        data.addProperty("counterType", event.counterType.name());
        data.addProperty("oldValue", event.oldValue);
        data.addProperty("newValue", event.newValue);
        sendEvent("counters_changed", data);
        return null;
    }

    @Override
    public Void visit(GameEventAddLog event) {
        JsonObject data = new JsonObject();
        data.addProperty("message", event.message);
        sendEvent("log", data);
        return null;
    }

    // Default handler for events we don't specifically handle
    @Override
    public Void visit(GameEvent event) {
        // Silently ignore unhandled events
        return null;
    }
}
