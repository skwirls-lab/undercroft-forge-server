package undercroft.server;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLogEntryType;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Subscribes to Forge game events and forwards them to the WebSocket client as the game log.
 *
 * Every event goes out fully described — who did it, to what, with what, and why — under the
 * client's own event names, marked {@code rich:true}. The client used to rebuild the log by
 * diffing state snapshots, which could say "drew a card" and "life 31 → 21" but never why; a
 * player who auto-passed to their next turn had no way to tell combat damage from four
 * separate effects. The engine knows, so the engine says.
 *
 * Hidden information is respected from the human's seat: an opponent's draw, tuck or library
 * search is logged without the card's name unless the card came from, or went to, a public
 * zone. Their casts, discards, exiles and everything on the battlefield are public.
 *
 * Extends IGameEventVisitor.Base so unhandled events return null by default. Forge events are
 * Java records — fields accessed via method calls (e.g. event.card()).
 */
public class GameEventForwarder extends IGameEventVisitor.Base<Void> {
    private static final Logger log = LoggerFactory.getLogger(GameEventForwarder.class);

    private static final Set<ZoneType> PUBLIC_ZONES = Set.of(
        ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack, ZoneType.Command);

    private final WsContext wsContext;
    private final Gson gson;
    private final Game game;
    private final String humanName;

    /** Seats already reported as out of the game. */
    private final Set<String> reportedLost = new HashSet<>();
    /** A sacrifice is logged as such; the zone change that follows it is not logged twice. */
    private String lastSacrificed = null;
    /** The last damage to a player, so the life change that follows can name its source. */
    private JsonObject lastPlayerDamage = null;

    public GameEventForwarder(WsContext wsContext, Gson gson, Game game, Player human) {
        this.wsContext = wsContext;
        this.gson = gson;
        this.game = game;
        this.humanName = human != null ? human.getName() : "";
    }

    private void sendEvent(String eventType, JsonObject data) {
        data.addProperty("eventType", eventType);
        data.addProperty("rich", true);
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }

    /**
     * Entry point for Forge's event bus.
     *
     * game.subscribeToEvents(...) is a Guava EventBus.register(), and Guava dispatches ONLY to
     * methods annotated with @Subscribe. Without this method, registering this class is a silent
     * no-op and not one of the visit(...) handlers below is ever called — no game_event message
     * ever reaches the client. Mirrors GameLogFormatter.recieve.
     */
    @Subscribe
    public void receive(GameEvent event) {
        if (event == null) {
            return;
        }
        try {
            // Forge fires no event when a player loses (Game.onPlayerLost only sets a flag),
            // so every event is a chance to notice a seat that has just gone out.
            checkEliminations();
            event.visit(this);
        } catch (Exception e) {
            // A forwarding failure must never propagate back into the engine's event dispatch.
            log.error("Failed to forward game event {}: {}", event.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String name(PlayerView p) { return p != null ? p.getName() : ""; }
    private static String name(CardView c) { return c != null ? c.getName() : ""; }
    private static String name(GameEntityView e) { return e != null ? e.getName() : ""; }
    private static String controller(CardView c) { return c != null ? name(c.getController()) : ""; }
    private static String owner(CardView c) { return c != null ? name(c.getOwner()) : ""; }

    private boolean isHuman(PlayerView p) { return p != null && humanName.equals(p.getName()); }

    /** True when the card's identity is public knowledge: a public zone at either end, or ours. */
    private boolean visible(CardView card, ZoneType from, ZoneType to) {
        if (card == null) return false;
        if (from != null && PUBLIC_ZONES.contains(from)) return true;
        if (to != null && PUBLIC_ZONES.contains(to)) return true;
        return isHuman(card.getOwner());
    }

    private int turn() {
        try { return game.getPhaseHandler().getTurn(); } catch (Exception e) { return 0; }
    }

    /** What is resolving right now, if anything: the cause of an effect's life loss or gain. */
    private JsonObject resolvingCause() {
        try {
            if (!game.getStack().isResolving()) return null;
            SpellAbility sa = game.getStack().peekAbility();
            if (sa == null || sa.getHostCard() == null) return null;
            JsonObject c = new JsonObject();
            c.addProperty("cardName", sa.getHostCard().getName());
            c.addProperty("playerName", sa.getActivatingPlayer() != null ? sa.getActivatingPlayer().getName() : "");
            c.addProperty("kind", "effect");
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private void checkEliminations() {
        for (Player p : game.getRegisteredPlayers()) {
            if (!p.hasLost() || reportedLost.contains(p.getName())) continue;
            reportedLost.add(p.getName());
            JsonObject data = new JsonObject();
            data.addProperty("playerName", p.getName());
            data.addProperty("turn", turn());
            if (p.getOutcome() != null && p.getOutcome().lossState != null) {
                data.addProperty("reason", p.getOutcome().lossState.name());
                if (p.getOutcome().loseConditionSpell != null) {
                    data.addProperty("spell", p.getOutcome().loseConditionSpell);
                }
            }
            sendEvent("PLAYER_LOST", data);
        }
    }

    // --- turn structure ----------------------------------------------------------------------

    @Override
    public Void visit(GameEventTurnBegan event) {
        JsonObject data = new JsonObject();
        data.addProperty("turnNumber", event.turnNumber());
        data.addProperty("activePlayer", name(event.turnOwner()));
        sendEvent("TURN_STARTED", data);
        return null;
    }

    @Override
    public Void visit(GameEventTurnPhase event) {
        JsonObject data = new JsonObject();
        data.addProperty("phase", event.phase() != null ? event.phase().name() : "");
        data.addProperty("activePlayer", name(event.playerTurn()));
        sendEvent("PHASE_CHANGED", data);
        return null;
    }

    @Override
    public Void visit(GameEventMulligan event) {
        JsonObject data = new JsonObject();
        data.addProperty("playerName", name(event.player()));
        sendEvent("CARD_MULLIGANED", data);
        return null;
    }

    // --- spells and abilities ----------------------------------------------------------------

    @Override
    public Void visit(GameEventSpellAbilityCast event) {
        JsonObject data = new JsonObject();
        boolean spell = event.sa() != null && event.sa().isSpell();
        if (event.si() != null) {
            data.addProperty("playerName", name(event.si().getActivatingPlayer()));
            data.addProperty("cardName", name(event.si().getSourceCard()));
            data.addProperty("description", event.si().getText());
            JsonArray targets = new JsonArray();
            if (event.si().getTargetCards() != null) {
                for (CardView c : event.si().getTargetCards()) targets.add(name(c));
            }
            if (event.si().getTargetPlayers() != null) {
                for (PlayerView p : event.si().getTargetPlayers()) targets.add(name(p));
            }
            data.add("targets", targets);
        } else if (event.sa() != null) {
            data.addProperty("cardName", name(event.sa().getHostCard()));
            data.addProperty("description", event.sa().toString());
        }
        if (event.targetDescription() != null && !event.targetDescription().isEmpty()) {
            data.addProperty("targetDescription", event.targetDescription());
        }
        data.addProperty("isAbility", !spell);
        sendEvent("SPELL_CAST", data);
        return null;
    }

    @Override
    public Void visit(GameEventSpellResolved event) {
        JsonObject data = new JsonObject();
        if (event.spell() != null) {
            data.addProperty("cardName", name(event.spell().getHostCard()));
            data.addProperty("isAbility", !event.spell().isSpell());
        }
        data.addProperty("description", event.stackDescription() != null ? event.stackDescription() : "");
        data.addProperty("fizzled", event.hasFizzled());
        sendEvent("SPELL_RESOLVED", data);
        return null;
    }

    /**
     * Mana abilities never reach the stack, so their only trace is Forge's own log line
     * ("Forest (12) - {T}: Add {G}."). Forwarded as a tap-for-mana so the log can say what a
     * permanent was tapped for, not just that it was tapped.
     */
    @Override
    public Void visit(GameEventAddLog event) {
        if (event.type() != GameLogEntryType.MANA) return null;
        JsonObject data = new JsonObject();
        String msg = event.message() != null ? event.message() : "";
        String card = name(event.sourceCard());
        if (card.isEmpty()) {
            int cut = msg.indexOf(" - ");
            card = cut > 0 ? msg.substring(0, cut).replaceAll(" \\(\\d+\\)$", "") : msg;
        }
        String ability = msg.contains(" - ") ? msg.substring(msg.indexOf(" - ") + 3) : "";
        data.addProperty("cardName", card);
        data.addProperty("ability", ability);
        data.addProperty("playerName", event.sourceCard() != null ? controller(event.sourceCard())
            : name(PlayerView.get(game.getPhaseHandler().getPriorityPlayer())));
        sendEvent("MANA_TAPPED", data);
        return null;
    }

    // --- damage, life, poison, counters ------------------------------------------------------

    @Override
    public Void visit(GameEventPlayerDamaged event) {
        JsonObject data = new JsonObject();
        data.addProperty("targetName", name(event.target()));
        data.addProperty("playerName", name(event.target()));
        data.addProperty("sourceName", name(event.source()));
        data.addProperty("sourceController", controller(event.source()));
        data.addProperty("sourceIsCommander", event.source() != null && event.source().isCommander());
        data.addProperty("amount", event.amount());
        data.addProperty("combat", event.combat());
        data.addProperty("infect", event.infect());
        lastPlayerDamage = data;
        sendEvent("DAMAGE_DEALT", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardDamaged event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardName", name(event.card()));
        data.addProperty("playerName", controller(event.card()));
        data.addProperty("sourceName", name(event.source()));
        data.addProperty("sourceController", controller(event.source()));
        data.addProperty("amount", event.amount());
        data.addProperty("damageType", event.type() != null ? event.type().name() : "Normal");
        sendEvent("CARD_DAMAGE_DEALT", data);
        return null;
    }

    @Override
    public Void visit(GameEventPlayerLivesChanged event) {
        JsonObject data = new JsonObject();
        String playerName = name(event.player());
        int delta = event.newLives() - event.oldLives();
        data.addProperty("playerName", playerName);
        data.addProperty("oldLife", event.oldLives());
        data.addProperty("newLife", event.newLives());
        data.addProperty("delta", delta);

        // Why: the damage just dealt to this player if it matches, else whatever is resolving,
        // else a cost the player chose to pay.
        JsonObject dmg = lastPlayerDamage;
        if (dmg != null && delta < 0 && playerName.equals(dmg.get("targetName").getAsString())
                && dmg.get("amount").getAsInt() == -delta && !dmg.get("infect").getAsBoolean()) {
            data.addProperty("cause", dmg.get("sourceName").getAsString());
            data.addProperty("causeController", dmg.get("sourceController").getAsString());
            data.addProperty("causeKind", dmg.get("combat").getAsBoolean() ? "combat" : "damage");
        } else {
            JsonObject cause = resolvingCause();
            if (cause != null) {
                data.addProperty("cause", cause.get("cardName").getAsString());
                data.addProperty("causeController", cause.get("playerName").getAsString());
                data.addProperty("causeKind", "effect");
            } else if (delta < 0) {
                data.addProperty("causeKind", "payment");
            }
        }
        lastPlayerDamage = null;
        sendEvent("LIFE_CHANGED", data);
        return null;
    }

    @Override
    public Void visit(GameEventPlayerPoisoned event) {
        JsonObject data = new JsonObject();
        data.addProperty("playerName", name(event.receiver()));
        data.addProperty("oldPoison", event.oldValue());
        data.addProperty("amount", event.amount());
        data.addProperty("newPoison", event.oldValue() + event.amount());
        JsonObject dmg = lastPlayerDamage;
        if (dmg != null && dmg.get("infect").getAsBoolean()) {
            data.addProperty("cause", dmg.get("sourceName").getAsString());
            data.addProperty("causeController", dmg.get("sourceController").getAsString());
        } else {
            JsonObject cause = resolvingCause();
            if (cause != null) data.addProperty("cause", cause.get("cardName").getAsString());
        }
        sendEvent("POISON_CHANGED", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardCounters event) {
        if (event.card() == null || event.oldValue() == event.newValue()) return null;
        JsonObject data = new JsonObject();
        data.addProperty("cardName", name(event.card()));
        data.addProperty("playerName", controller(event.card()));
        data.addProperty("counterType", event.type() != null ? event.type().toString() : "");
        data.addProperty("oldValue", event.oldValue());
        data.addProperty("newValue", event.newValue());
        data.addProperty("delta", event.newValue() - event.oldValue());
        sendEvent(event.newValue() > event.oldValue() ? "CARD_COUNTER_ADDED" : "CARD_COUNTER_REMOVED", data);
        return null;
    }

    // --- combat ------------------------------------------------------------------------------

    @Override
    public Void visit(GameEventAttackersDeclared event) {
        if (event.attackersMap() == null) return null;
        for (Map.Entry<GameEntityView, CardView> e : event.attackersMap().entries()) {
            JsonObject data = new JsonObject();
            data.addProperty("playerName", name(event.player()));
            data.addProperty("cardName", name(e.getValue()));
            data.addProperty("defender", name(e.getKey()));
            data.addProperty("isCommander", e.getValue() != null && e.getValue().isCommander());
            sendEvent("CREATURE_ATTACKED", data);
        }
        return null;
    }

    @Override
    public Void visit(GameEventBlockersDeclared event) {
        if (event.blockers() == null) return null;
        for (Map.Entry<GameEntityView, Multimap<CardView, CardView>> defender : event.blockers().entrySet()) {
            for (Map.Entry<CardView, CardView> block : defender.getValue().entries()) {
                JsonObject data = new JsonObject();
                data.addProperty("playerName", name(event.defendingPlayer()));
                data.addProperty("cardName", name(block.getKey()));
                data.addProperty("blockerName", name(block.getValue()));
                sendEvent("CREATURE_BLOCKED", data);
            }
        }
        return null;
    }

    // --- cards moving ------------------------------------------------------------------------

    @Override
    public Void visit(GameEventLandPlayed event) {
        JsonObject data = new JsonObject();
        data.addProperty("playerName", name(event.player()));
        data.addProperty("cardName", name(event.land()));
        sendEvent("CARD_PLAYED", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardSacrificed event) {
        if (event.card() == null) return null;
        lastSacrificed = name(event.card());
        JsonObject data = new JsonObject();
        data.addProperty("cardName", lastSacrificed);
        data.addProperty("playerName", controller(event.card()));
        JsonObject cause = resolvingCause();
        if (cause != null && !cause.get("cardName").getAsString().equals(lastSacrificed)) {
            data.addProperty("cause", cause.get("cardName").getAsString());
        }
        sendEvent("CARD_SACRIFICED", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardAttachment event) {
        JsonObject data = new JsonObject();
        data.addProperty("cardName", name(event.equipment()));
        data.addProperty("playerName", controller(event.equipment()));
        data.addProperty("targetName", name(event.newTarget()));
        data.addProperty("oldTargetName", name(event.oldEntity()));
        data.addProperty("detached", event.newTarget() == null);
        sendEvent("CARD_ATTACHED", data);
        return null;
    }

    @Override
    public Void visit(GameEventCardChangeZone event) {
        CardView card = event.card();
        if (card == null) return null;
        ZoneType from = event.from() != null ? event.from().zoneType() : null;
        ZoneType to = event.to() != null ? event.to().zoneType() : null;
        if (to == null || to == ZoneType.Stack || from == ZoneType.Stack) return null;
        if (from == to) return null;

        String type;
        if (from == null && to == ZoneType.Battlefield && card.isToken()) {
            type = "TOKEN_CREATED";
        } else if (from == ZoneType.Library && to == ZoneType.Hand) {
            type = "CARD_DRAWN";
        } else if (from == ZoneType.Battlefield && to == ZoneType.Graveyard) {
            if (lastSacrificed != null && lastSacrificed.equals(card.getName())) {
                lastSacrificed = null;
                return null;
            }
            type = "CARD_DESTROYED";
        } else if (to == ZoneType.Exile) {
            type = "CARD_EXILED";
        } else if (to == ZoneType.Graveyard && from == ZoneType.Hand) {
            type = "CARD_DISCARDED";
        } else if (to == ZoneType.Graveyard && from == ZoneType.Library) {
            type = "CARD_MILLED";
        } else if (to == ZoneType.Hand && from != null && from != ZoneType.Hand) {
            type = "CARD_RETURNED_TO_HAND";
        } else if (to == ZoneType.Battlefield && from != ZoneType.Hand && from != null) {
            type = "CARD_RETURNED_TO_BATTLEFIELD";
        } else if (to == ZoneType.Library && from != ZoneType.Library) {
            type = "CARD_RETURNED_TO_LIBRARY";
        } else if (to == ZoneType.Command && from != null) {
            type = "CARD_TO_COMMAND_ZONE";
        } else {
            return null;
        }

        JsonObject data = new JsonObject();
        boolean shown = visible(card, from, to);
        data.addProperty("cardName", shown ? card.getName() : "");
        data.addProperty("hidden", !shown);
        data.addProperty("playerName", type.equals("TOKEN_CREATED") ? controller(card) : owner(card));
        data.addProperty("isOwn", isHuman(card.getOwner()));
        data.addProperty("from", from != null ? from.name() : "");
        data.addProperty("to", to.name());
        data.addProperty("isCommander", card.isCommander());
        JsonObject cause = resolvingCause();
        if (cause != null && !cause.get("cardName").getAsString().equals(card.getName())
                && !type.equals("CARD_DRAWN") && !type.equals("TOKEN_CREATED")) {
            data.addProperty("cause", cause.get("cardName").getAsString());
        }
        sendEvent(type, data);
        return null;
    }

    // --- the end -----------------------------------------------------------------------------

    @Override
    public Void visit(GameEventGameOutcome event) {
        checkEliminations();
        JsonObject data = new JsonObject();
        data.addProperty("winner", event.winningPlayerName() != null ? event.winningPlayerName() : "draw");
        data.addProperty("lastTurn", event.lastTurnNumber());
        JsonArray outcomes = new JsonArray();
        if (event.outcomeStrings() != null) for (String s : event.outcomeStrings()) outcomes.add(s);
        data.add("outcomes", outcomes);
        sendEvent("GAME_OVER", data);
        return null;
    }

    @Override
    public Void visit(GameEventGameFinished event) {
        // GAME_OVER above carries everything; this only marks that the engine has stopped.
        return null;
    }
}
