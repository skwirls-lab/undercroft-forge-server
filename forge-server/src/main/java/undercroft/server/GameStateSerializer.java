package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.Map;

/**
 * Serializes Forge game state to JSON for the WebSocket client.
 * Maps Forge's internal state to a format our React frontend can consume.
 */
public class GameStateSerializer {

    public static JsonObject serialize(Game game, Player perspective, Gson gson) {
        JsonObject state = new JsonObject();

        // Game metadata
        state.addProperty("gameId", game.getId());
        state.addProperty("isGameOver", game.isGameOver());

        // Phase/turn info — handle null values during mulligan/early game setup
        JsonObject turn = new JsonObject();
        var phaseHandler = game.getPhaseHandler();
        turn.addProperty("phase", phaseHandler.getPhase() != null ? phaseHandler.getPhase().name() : "MULLIGAN");
        turn.addProperty("activePlayer", phaseHandler.getPlayerTurn() != null ? phaseHandler.getPlayerTurn().getName() : perspective.getName());
        turn.addProperty("activePlayerId", phaseHandler.getPlayerTurn() != null ? phaseHandler.getPlayerTurn().getId() : perspective.getId());
        turn.addProperty("turnNumber", phaseHandler.getTurn());
        turn.addProperty("priorityPlayer", phaseHandler.getPriorityPlayer() != null
                ? phaseHandler.getPriorityPlayer().getName() : "");
        state.add("turn", turn);

        // Players
        JsonArray players = new JsonArray();
        for (Player p : game.getPlayers()) {
            players.add(serializePlayer(p, perspective, game));
        }
        state.add("players", players);

        // Stack
        JsonArray stack = new JsonArray();
        for (SpellAbilityStackInstance si : game.getStack()) {
            JsonObject stackItem = new JsonObject();
            stackItem.addProperty("description", si.getStackDescription());
            if (si.getSourceCard() != null) {
                stackItem.addProperty("cardName", si.getSourceCard().getName());
                stackItem.addProperty("cardId", si.getSourceCard().getId());
            }
            stackItem.addProperty("controller", si.getActivatingPlayer().getName());
            stack.add(stackItem);
        }
        state.add("stack", stack);

        // Combat
        Combat combat = game.getCombat();
        if (combat != null) {
            JsonObject combatData = new JsonObject();
            JsonArray attackers = new JsonArray();
            for (Card attacker : combat.getAttackers()) {
                JsonObject att = new JsonObject();
                att.addProperty("cardId", attacker.getId());
                att.addProperty("name", attacker.getName());

                JsonArray blockers = new JsonArray();
                CardCollectionView blockerCards = combat.getBlockers(attacker);
                if (blockerCards != null) {
                    for (Card blocker : blockerCards) {
                        JsonObject blk = new JsonObject();
                        blk.addProperty("cardId", blocker.getId());
                        blk.addProperty("name", blocker.getName());
                        blockers.add(blk);
                    }
                }
                att.add("blockers", blockers);
                attackers.add(att);
            }
            combatData.add("attackers", attackers);
            state.add("combat", combatData);
        }

        return state;
    }

    private static JsonObject serializePlayer(Player p, Player perspective, Game game) {
        JsonObject player = new JsonObject();
        player.addProperty("id", p.getId());
        player.addProperty("name", p.getName());
        player.addProperty("life", p.getLife());
        player.addProperty("poison", p.getPoisonCounters());
        player.addProperty("isAI", p.getController().isAI());
        player.addProperty("isActivePlayer", game.getPhaseHandler().getPlayerTurn() == p);
        player.addProperty("hasPriority", game.getPhaseHandler().getPriorityPlayer() == p);

        // Mana pool
        JsonObject manaPool = new JsonObject();
        manaPool.addProperty("white", p.getManaPool().getAmountOfColor((byte) 1));  // W
        manaPool.addProperty("blue", p.getManaPool().getAmountOfColor((byte) 2));   // U
        manaPool.addProperty("black", p.getManaPool().getAmountOfColor((byte) 4));  // B
        manaPool.addProperty("red", p.getManaPool().getAmountOfColor((byte) 8));    // R
        manaPool.addProperty("green", p.getManaPool().getAmountOfColor((byte) 16)); // G
        manaPool.addProperty("colorless", p.getManaPool().getAmountOfColor((byte) 0));
        player.add("manaPool", manaPool);

        // Commander damage
        JsonObject cmdDamage = new JsonObject();
        for (Player opponent : game.getPlayers()) {
            if (opponent != p) {
                // Track damage dealt TO this player BY each opponent's commander
                for (Card cmd : opponent.getCommanders()) {
                    int dmg = p.getCommanderDamage(cmd);
                    if (dmg > 0) {
                        cmdDamage.addProperty(cmd.getName(), dmg);
                    }
                }
            }
        }
        player.add("commanderDamage", cmdDamage);

        // Zones
        player.add("hand", serializeZone(p, ZoneType.Hand, p == perspective));
        player.add("battlefield", serializeZone(p, ZoneType.Battlefield, true));
        player.add("graveyard", serializeZone(p, ZoneType.Graveyard, true));
        player.add("exile", serializeZone(p, ZoneType.Exile, true));
        player.add("command", serializeZone(p, ZoneType.Command, true));

        // Library count only (don't reveal cards)
        player.addProperty("librarySize", p.getCardsIn(ZoneType.Library).size());

        return player;
    }

    private static JsonArray serializeZone(Player p, ZoneType zone, boolean showDetails) {
        JsonArray cards = new JsonArray();
        for (Card c : p.getCardsIn(zone)) {
            cards.add(serializeCard(c, showDetails));
        }
        return cards;
    }

    static JsonObject serializeCard(Card c, boolean showDetails) {
        JsonObject card = new JsonObject();
        card.addProperty("id", c.getId());

        if (showDetails) {
            card.addProperty("name", c.getName());
            card.addProperty("typeLine", c.getType().toString());
            card.addProperty("manaCost", c.getManaCost().toString());
            card.addProperty("oracleText", c.getOracleText());

            if (c.isCreature()) {
                card.addProperty("power", c.getNetPower());
                card.addProperty("toughness", c.getNetToughness());
                card.addProperty("basePower", c.getCurrentState().getBasePower());
                card.addProperty("baseToughness", c.getCurrentState().getBaseToughness());
            }

            if (c.isPlaneswalker()) {
                card.addProperty("loyalty", c.getCurrentLoyalty());
            }

            card.addProperty("tapped", c.isTapped());
            card.addProperty("flipped", c.isFlipped());
            card.addProperty("faceDown", c.isFaceDown());
            card.addProperty("sick", c.isSick()); // summoning sickness

            // Counters
            if (c.hasCounters()) {
                JsonObject counters = new JsonObject();
                for (Map.Entry<CounterType, Integer> entry : c.getCounters().entrySet()) {
                    if (entry.getValue() > 0) {
                        counters.addProperty(entry.getKey().toString(), entry.getValue());
                    }
                }
                card.add("counters", counters);
            }

            // Attachments
            if (c.isEquipped()) {
                JsonArray equipment = new JsonArray();
                for (Card eq : c.getEquippedBy()) {
                    JsonObject eqObj = new JsonObject();
                    eqObj.addProperty("id", eq.getId());
                    eqObj.addProperty("name", eq.getName());
                    equipment.add(eqObj);
                }
                card.add("equippedBy", equipment);
            }

            if (c.isEnchanted()) {
                JsonArray auras = new JsonArray();
                for (Card aura : c.getEnchantedBy()) {
                    JsonObject auraObj = new JsonObject();
                    auraObj.addProperty("id", aura.getId());
                    auraObj.addProperty("name", aura.getName());
                    auras.add(auraObj);
                }
                card.add("enchantedBy", auras);
            }

            // Keywords (visible ones)
            JsonArray keywords = new JsonArray();
            for (String kw : c.getHiddenExtrinsicKeywords()) {
                keywords.add(kw);
            }
            for (var kw : c.getKeywords()) {
                keywords.add(kw.getOriginal());
            }
            card.add("keywords", keywords);

            // Owner/controller
            card.addProperty("owner", c.getOwner().getName());
            card.addProperty("ownerId", c.getOwner().getId());
            card.addProperty("controller", c.getController().getName());
            card.addProperty("controllerId", c.getController().getId());

            // Is this a token?
            card.addProperty("isToken", c.isToken());

            // Damage marked
            card.addProperty("damage", c.getDamage());
        } else {
            // Hidden card (opponent's hand) — just show card back
            card.addProperty("name", "???");
            card.addProperty("faceDown", true);
        }

        return card;
    }
}
