package undercroft.server;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.combat.CombatUtil;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import io.javalin.websocket.WsContext;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Bridge between Forge's PlayerController and the WebSocket client.
 *
 * When the Forge engine calls any method on this controller (requesting a player decision),
 * we serialize the choice as JSON, send it over WebSocket, and block until the client responds.
 *
 * The client sees these as "choice_request" messages and responds with "choice_response".
 */
public class BridgePlayerController extends PlayerController {
    private static final Logger log = LoggerFactory.getLogger(BridgePlayerController.class);
    private static final long CHOICE_TIMEOUT_SECONDS = 1800; // 30 minutes per decision

    private final WsContext wsContext;
    private final Gson gson;

    // Pending choice mechanism: engine thread blocks here, client thread unblocks
    private final Map<String, CompletableFuture<JsonObject>> pendingChoices = new ConcurrentHashMap<>();
    private int nextRequestId = 0;

    private volatile boolean shutdown = false;

    @Override
    public void autoPassCancel() {
        // No-op for headless server
    }

    @Override
    public void awaitNextInput() {
        // Called when engine is waiting for player input — no-op, we use WebSocket async
    }

    @Override
    public void cancelAwaitNextInput() {
        // Cancel any pending choice when the engine wants to interrupt
        for (CompletableFuture<JsonObject> future : pendingChoices.values()) {
            future.complete(new JsonObject());
        }
    }

    public BridgePlayerController(Game game, Player player, LobbyPlayer lobbyPlayer,
                                   WsContext wsContext, Gson gson) {
        super(game, player, lobbyPlayer);
        this.wsContext = wsContext;
        this.gson = gson;
    }

    // --- Core choice mechanism ---

    /**
     * Send a choice request to the client and block until they respond.
     * Always sends a game_state snapshot first so the client has current board state.
     */
    private JsonObject requestChoice(String choiceType, JsonObject data) {
        if (shutdown) return new JsonObject();

        // Send current game state before every choice so the client always has a fresh snapshot
        try {
            Game game = getGame();
            if (game != null) {
                JsonObject statePayload = GameStateSerializer.serialize(game, player, gson);
                ForgeServer.sendMessage(wsContext, "game_state", statePayload);
            }
        } catch (Exception e) {
            log.warn("Failed to send game state before choice: {}", e.getMessage());
        }

        String requestId = String.valueOf(nextRequestId++);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingChoices.put(requestId, future);

        // Build and send the request
        JsonObject request = new JsonObject();
        request.addProperty("requestId", requestId);
        request.addProperty("choiceType", choiceType);
        request.add("data", data);
        ForgeServer.sendMessage(wsContext, "choice_request", request);

        try {
            // Block until client responds or timeout
            JsonObject response = future.get(CHOICE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return response;
        } catch (TimeoutException e) {
            log.warn("Choice timeout for request {}", requestId);
            return new JsonObject(); // Return empty = default/skip
        } catch (Exception e) {
            log.error("Error waiting for choice {}: {}", requestId, e.getMessage());
            return new JsonObject();
        } finally {
            pendingChoices.remove(requestId);
        }
    }

    /**
     * Called by GameSession when the client sends a choice_response.
     */
    public void receiveChoiceResponse(JsonObject payload) {
        String requestId = payload.has("requestId") ? payload.get("requestId").getAsString() : null;
        if (requestId != null) {
            CompletableFuture<JsonObject> future = pendingChoices.get(requestId);
            if (future != null) {
                future.complete(payload);
            } else {
                log.warn("No pending choice for requestId: {}", requestId);
            }
        }
    }

    public void shutdown() {
        shutdown = true;
        // Complete all pending futures so threads unblock
        for (CompletableFuture<JsonObject> future : pendingChoices.values()) {
            future.complete(new JsonObject());
        }
    }

    // --- Helper: serialize cards to JSON ---

    private JsonArray serializeCards(Iterable<? extends GameEntity> entities) {
        JsonArray arr = new JsonArray();
        if (entities == null) return arr;
        for (GameEntity e : entities) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", e.getId());
            obj.addProperty("name", e.getName());
            if (e instanceof Card card) {
                obj.addProperty("type", card.getType().toString());
                if (card.getCurrentState() != null) {
                    obj.addProperty("power", card.getNetPower());
                    obj.addProperty("toughness", card.getNetToughness());
                }
                obj.addProperty("zone", card.getZone() != null ? card.getZone().getZoneType().name() : "unknown");
                obj.addProperty("owner", card.getOwner().getName());
                obj.addProperty("controller", card.getController().getName());
            } else if (e instanceof Player p) {
                obj.addProperty("type", "player");
                obj.addProperty("life", p.getLife());
            }
            arr.add(obj);
        }
        return arr;
    }

    private JsonArray serializeSpellAbilities(List<SpellAbility> abilities) {
        JsonArray arr = new JsonArray();
        if (abilities == null) return arr;
        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("index", i);
            obj.addProperty("description", sa.toString());
            if (sa.getHostCard() != null) {
                obj.addProperty("cardName", sa.getHostCard().getName());
                obj.addProperty("cardId", sa.getHostCard().getId());
            }
            obj.addProperty("isSpell", sa.isSpell());
            obj.addProperty("isAbility", sa.isAbility());
            arr.add(obj);
        }
        return arr;
    }

    // ===================================================================
    // PlayerController abstract method implementations
    // Each method sends a choice_request and waits for the client response
    // ===================================================================

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        JsonObject data = new JsonObject();
        data.addProperty("cardName", hostCard.getName());
        data.addProperty("cardId", hostCard.getId());
        data.add("abilities", serializeSpellAbilities(abilities));

        JsonObject response = requestChoice("choose_ability", data);
        int index = response.has("index") ? response.get("index").getAsInt() : 0;
        return (index >= 0 && index < abilities.size()) ? abilities.get(index) : null;
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        // This is called for things like mana abilities that don't use the stack
        // Just let it resolve automatically
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory) return true;

        JsonObject data = new JsonObject();
        data.addProperty("cardName", host.getName());
        data.addProperty("ability", wrapperAbility.toString());
        data.addProperty("mandatory", false);

        JsonObject response = requestChoice("play_trigger", data);
        return response.has("play") ? response.get("play").getAsBoolean() : true;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return true; // Auto-play effects from other effects
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // For simplicity, play them in order
        for (SpellAbility sa : activePlayerSAs) {
            if (sa.isTrigger()) {
                player.getGame().getStack().add(sa);
            }
        }
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return Collections.emptyList(); // No sideboarding for Commander
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return Collections.emptyList();
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        // Send damage assignment choice to client
        JsonObject data = new JsonObject();
        data.addProperty("attackerName", attacker.getName());
        data.addProperty("attackerId", attacker.getId());
        data.addProperty("totalDamage", damageDealt);
        data.add("blockers", serializeCards(blockers));
        data.addProperty("defenderName", defender.getName());

        // If only one blocker, auto-assign all damage
        if (blockers.size() <= 1) {
            Map<Card, Integer> result = new HashMap<>();
            if (blockers.size() == 1) {
                result.put(blockers.get(0), damageDealt);
            }
            return result;
        }

        JsonObject response = requestChoice("assign_combat_damage", data);

        // Parse response: { "assignments": { "cardId": amount, ... } }
        Map<Card, Integer> result = new HashMap<>();
        if (response.has("assignments")) {
            JsonObject assignments = response.getAsJsonObject("assignments");
            for (String key : assignments.keySet()) {
                int cardId = Integer.parseInt(key);
                int amount = assignments.get(key).getAsInt();
                for (Card blocker : blockers) {
                    if (blocker.getId() == cardId) {
                        result.put(blocker, amount);
                        break;
                    }
                }
            }
        }

        // Default: distribute damage in order
        if (result.isEmpty()) {
            int remaining2 = damageDealt;
            for (Card blocker : blockers) {
                int need = blocker.getNetToughness() - blocker.getDamage();
                int assign = Math.min(need, remaining2);
                result.put(blocker, assign);
                remaining2 -= assign;
                if (remaining2 <= 0) break;
            }
        }
        return result;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return affected; // Auto-distribute
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        // Default: all colorless
        Map<Byte, Integer> result = new HashMap<>();
        result.put((byte) 0, manaAmount);
        return result;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", message);
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.add("options", serializeCards(validTargets));

        JsonObject response = requestChoice("choose_permanents_sacrifice", data);
        return parseCardSelection(response, validTargets, min);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
            CardCollectionView validTargets, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", message);
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.add("options", serializeCards(validTargets));

        JsonObject response = requestChoice("choose_permanents_destroy", data);
        return parseCardSelection(response, validTargets, min);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, String announce) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", announce);
        data.addProperty("abilityDescription", ability.toString());

        JsonObject response = requestChoice("announce_number", data);
        return response.has("value") ? response.get("value").getAsInt() : 0;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return null; // Let Forge use default targeting
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        // Send targeting choice to client
        JsonObject data = new JsonObject();
        data.addProperty("abilityDescription", currentAbility.toString());
        if (currentAbility.getHostCard() != null) {
            data.addProperty("cardName", currentAbility.getHostCard().getName());
        }

        // Get valid targets
        List<GameEntity> validTargets = new ArrayList<>();
        TargetRestrictions restrictions = currentAbility.getTargetRestrictions();
        if (restrictions != null) {
            validTargets.addAll(restrictions.getAllCandidates(currentAbility, true));
        }

        JsonArray targetsArr = new JsonArray();
        for (GameEntity ge : validTargets) {
            JsonObject t = new JsonObject();
            t.addProperty("id", ge.getId());
            t.addProperty("name", ge.getName());
            t.addProperty("type", ge instanceof Card ? "card" : "player");
            targetsArr.add(t);
        }
        data.add("validTargets", targetsArr);
        data.addProperty("minTargets", restrictions != null ? restrictions.getMinTargets(currentAbility.getHostCard(), currentAbility) : 0);
        data.addProperty("maxTargets", restrictions != null ? restrictions.getMaxTargets(currentAbility.getHostCard(), currentAbility) : 1);

        JsonObject response = requestChoice("choose_targets", data);

        // Parse selected target IDs
        if (response.has("targetIds")) {
            JsonArray ids = response.getAsJsonArray("targetIds");
            for (int i = 0; i < ids.size(); i++) {
                int targetId = ids.get(i).getAsInt();
                for (GameEntity entity : validTargets) {
                    if (entity.getId() == targetId) {
                        currentAbility.getTargets().add(entity);
                        break;
                    }
                }
            }
            return !currentAbility.getTargets().isEmpty();
        }
        return false;
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return null;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
            String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", title);
        data.addProperty("min", isOptional ? 0 : min);
        data.addProperty("max", max);
        data.add("options", serializeCards(sourceList));

        JsonObject response = requestChoice("choose_cards", data);
        return parseCardSelection(response, sourceList, isOptional ? 0 : min);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa,
            String title, boolean isOptional) {
        // Flatten and let player choose
        CardCollection all = new CardCollection();
        for (CardCollection cc : validMap.values()) {
            all.addAll(cc);
        }
        return (CardCollection) chooseCardsForEffect(all, sa, title, isOptional ? 0 : 1, all.size(), isOptional, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
            DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional,
            Player relatedPlayer, Map<String, Object> params) {

        if (optionList.size() == 1 && !isOptional) {
            return optionList.get(0);
        }

        JsonObject data = new JsonObject();
        data.addProperty("prompt", title);
        data.addProperty("optional", isOptional);
        data.add("options", serializeCards(optionList));

        JsonObject response = requestChoice("choose_single_entity", data);

        if (response.has("entityId")) {
            int entityId = response.get("entityId").getAsInt();
            for (T entity : optionList) {
                if (entity.getId() == entityId) {
                    return entity;
                }
            }
        }
        return isOptional ? null : (optionList.isEmpty() ? null : optionList.get(0));
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max,
            DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {

        JsonObject data = new JsonObject();
        data.addProperty("prompt", title);
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.add("options", serializeCards(optionList));

        JsonObject response = requestChoice("choose_entities", data);

        List<T> result = new ArrayList<>();
        if (response.has("entityIds")) {
            JsonArray ids = response.getAsJsonArray("entityIds");
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i).getAsInt();
                for (T entity : optionList) {
                    if (entity.getId() == id) {
                        result.add(entity);
                        break;
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, int num, Map<String, Object> params) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", title);
        data.addProperty("count", num);
        data.add("abilities", serializeSpellAbilities(spells));

        JsonObject response = requestChoice("choose_spell_abilities", data);

        List<SpellAbility> result = new ArrayList<>();
        if (response.has("indices")) {
            JsonArray indices = response.getAsJsonArray("indices");
            for (int i = 0; i < indices.size(); i++) {
                int idx = indices.get(i).getAsInt();
                if (idx >= 0 && idx < spells.size()) {
                    result.add(spells.get(idx));
                }
            }
        }
        return result.isEmpty() ? spells.subList(0, Math.min(num, spells.size())) : result;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
            String title, Map<String, Object> params) {
        if (spells.size() == 1) return spells.get(0);

        JsonObject data = new JsonObject();
        data.addProperty("prompt", title);
        data.add("abilities", serializeSpellAbilities(spells));

        JsonObject response = requestChoice("choose_single_spell", data);
        int index = response.has("index") ? response.get("index").getAsInt() : 0;
        return (index >= 0 && index < spells.size()) ? spells.get(index) : spells.get(0);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", message);
        data.addProperty("mode", mode != null ? mode.name() : "");
        if (sa != null && sa.getHostCard() != null) {
            data.addProperty("cardName", sa.getHostCard().getName());
        }
        JsonArray opts = new JsonArray();
        if (options != null) options.forEach(opts::add);
        data.add("options", opts);

        JsonObject response = requestChoice("confirm_action", data);
        return response.has("confirmed") ? response.get("confirmed").getAsBoolean() : true;
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        return true;
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", question);
        JsonObject response = requestChoice("confirm_replacement", data);
        return response.has("confirmed") ? response.get("confirmed").getAsBoolean() : true;
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return true;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return true; // Auto-accept triggers for now
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return Collections.emptyList();
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return Collections.emptyList();
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Send list of possible attackers to client
        CardCollection possibleAttackers = new CardCollection();
        for (Card c : attacker.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && CombatUtil.canAttack(c)) {
                possibleAttackers.add(c);
            }
        }

        if (possibleAttackers.isEmpty()) return;

        // Get possible defenders
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());

        JsonObject data = new JsonObject();
        data.add("possibleAttackers", serializeCards(possibleAttackers));
        data.add("defenders", serializeCards(defenders));

        JsonObject response = requestChoice("declare_attackers", data);

        // Parse: { "attackers": [{ "cardId": N, "defenderId": N }, ...] }
        if (response.has("attackers")) {
            JsonArray attackerDecls = response.getAsJsonArray("attackers");
            for (int i = 0; i < attackerDecls.size(); i++) {
                JsonObject decl = attackerDecls.get(i).getAsJsonObject();
                int cardId = decl.get("cardId").getAsInt();
                int defenderId = decl.has("defenderId") ? decl.get("defenderId").getAsInt() : -1;

                Card attackCard = null;
                for (Card c : possibleAttackers) {
                    if (c.getId() == cardId) { attackCard = c; break; }
                }

                GameEntity defender = null;
                if (defenderId >= 0) {
                    for (GameEntity d : defenders) {
                        if (d.getId() == defenderId) { defender = d; break; }
                    }
                }
                if (defender == null && !defenders.isEmpty()) {
                    defender = defenders.get(0);
                }

                if (attackCard != null && defender != null) {
                    combat.addAttacker(attackCard, defender);
                }
            }
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // Get possible blockers and attacking creatures
        CardCollection possibleBlockers = new CardCollection();
        for (Card c : defender.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !c.isTapped()) {
                possibleBlockers.add(c);
            }
        }

        if (possibleBlockers.isEmpty()) return;

        CardCollection attackers = combat.getAttackers();
        if (attackers.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.add("possibleBlockers", serializeCards(possibleBlockers));
        data.add("attackers", serializeCards(attackers));

        JsonObject response = requestChoice("declare_blockers", data);

        // Parse: { "blocks": [{ "blockerId": N, "attackerId": N }, ...] }
        if (response.has("blocks")) {
            JsonArray blocks = response.getAsJsonArray("blocks");
            for (int i = 0; i < blocks.size(); i++) {
                JsonObject block = blocks.get(i).getAsJsonObject();
                int blockerId = block.get("blockerId").getAsInt();
                int attackerId = block.get("attackerId").getAsInt();

                Card blocker = null;
                for (Card c : possibleBlockers) {
                    if (c.getId() == blockerId) { blocker = c; break; }
                }
                Card attacker = null;
                for (Card c : attackers) {
                    if (c.getId() == attackerId) { attacker = c; break; }
                }

                if (blocker != null && attacker != null) {
                    combat.addBlocker(attacker, blocker);
                }
            }
        }
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return blockers; // Default order
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection result = new CardCollection(oldBlockers);
        result.add(blocker);
        return result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return attackers; // Default order
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        JsonObject data = new JsonObject();
        data.addProperty("message", messagePrefix != null ? messagePrefix : "Revealed cards");
        data.addProperty("zone", zone.name());
        data.addProperty("owner", owner.getName());
        data.add("cards", serializeCards(cards));
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) {
        // CardView variant - just send event
        JsonObject data = new JsonObject();
        data.addProperty("eventType", "reveal");
        data.addProperty("message", messagePrefix != null ? messagePrefix : "Revealed cards");
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        JsonObject data = new JsonObject();
        data.addProperty("eventType", "notify_value");
        data.addProperty("value", value);
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        // Send scry choice to client
        JsonObject data = new JsonObject();
        data.add("cards", serializeCards(topN));
        data.addProperty("prompt", "Scry: Choose cards to put on bottom");

        JsonObject response = requestChoice("scry", data);

        CardCollection top = new CardCollection();
        CardCollection bottom = new CardCollection();

        if (response.has("bottomIds")) {
            Set<Integer> bottomIds = new HashSet<>();
            JsonArray arr = response.getAsJsonArray("bottomIds");
            for (int i = 0; i < arr.size(); i++) {
                bottomIds.add(arr.get(i).getAsInt());
            }
            for (Card c : topN) {
                if (bottomIds.contains(c.getId())) {
                    bottom.add(c);
                } else {
                    top.add(c);
                }
            }
        } else {
            top.addAll(topN); // Default: keep all on top
        }

        return ImmutablePair.of(top, bottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return arrangeForScry(topN); // Same UI as scry
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Put " + c.getName() + " on top of library?");
        data.add("card", serializeCards(List.of(c)));
        JsonObject response = requestChoice("put_on_top", data);
        return response.has("onTop") ? response.get("onTop").getAsBoolean() : true;
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, int min, int max,
            DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        if (fetchList.isEmpty()) return Collections.emptyList();

        JsonObject data = new JsonObject();
        data.addProperty("prompt", selectPrompt != null ? selectPrompt : "Choose cards");
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.addProperty("destination", destination.name());
        data.add("options", serializeCards(fetchList));

        JsonObject response = requestChoice("choose_cards_zone", data);
        CardCollectionView selected = parseCardSelection(response, fetchList, min);
        return new ArrayList<>(selected);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return cards; // Default order
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
            CardCollection validCards, int min, int max) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Choose cards to discard");
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.add("options", serializeCards(validCards));

        JsonObject response = requestChoice("choose_discard", data);
        return parseCardSelection(response, validCards, min);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String param, SpellAbility sa) {
        return chooseCardsToDiscardFrom(player, sa, new CardCollection(hand), min, min);
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Discard to hand size");
        data.addProperty("min", numDiscard);
        data.addProperty("max", numDiscard);
        data.add("options", serializeCards(hand));

        JsonObject response = requestChoice("choose_discard", data);
        CardCollectionView result = parseCardSelection(response, hand, numDiscard);
        return new CardCollection(result);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return CardCollection.EMPTY; // Don't delve by default
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
            CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        return new HashMap<>(); // Don't convoke/improvise by default
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return Collections.emptyList();
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return parseCardSelection(new JsonObject(), valid, min);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return Collections.emptyList();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return player; // Human always goes first for now
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return zones.get(0);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return manaChoices.get(0); // Auto-pick first available
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Choose a " + kindOfType);
        JsonArray types = new JsonArray();
        validTypes.forEach(types::add);
        data.add("options", types);

        JsonObject response = requestChoice("choose_type", data);
        return response.has("chosen") ? response.get("chosen").getAsString()
                : validTypes.iterator().next();
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return sectors.get(0);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return contraptions;
    }

    @Override
    public int chooseSprocket(Card assignee, boolean forceDifferent) {
        return 1;
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return rolls.get(0);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return rolls.get(0);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return Collections.emptyList();
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return rolls.get(0);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return rolls.get(0);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return swapChoices.get(0);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes,
            Player forPlayer, boolean optional) {
        return options.get(0);
    }

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", cardsToReturn > 0
                ? "Keep hand? (Return " + cardsToReturn + " card(s) to bottom)"
                : "Keep hand?");
        data.addProperty("cardsToReturn", cardsToReturn);
        data.add("hand", serializeCards(player.getCardsIn(ZoneType.Hand)));

        JsonObject response = requestChoice("mulligan", data);
        return response.has("keep") ? response.get("keep").getAsBoolean() : true;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        if (cardsToReturn <= 0) return CardCollection.EMPTY;

        CardCollection hand = new CardCollection(mulliganingPlayer.getCardsIn(ZoneType.Hand));
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Choose " + cardsToReturn + " card(s) to put on the bottom of your library");
        data.addProperty("min", cardsToReturn);
        data.addProperty("max", cardsToReturn);
        data.add("options", serializeCards(hand));

        JsonObject response = requestChoice("mulligan_tuck", data);
        return parseCardSelection(response, hand, cardsToReturn);
    }

    @Override
    public boolean confirmMulliganScry(Player p) {
        return true; // Always scry after mulligan
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // This is the main "what do you want to do?" prompt
        // Forge calls this when the player has priority
        // We need to send the full list of legal actions

        List<SpellAbility> legalPlays = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(player, true)) {
                if (sa.canPlay()) {
                    legalPlays.add(sa);
                }
            }
        }
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(player, true)) {
                if (sa.isActivatedAbility() && sa.canPlay()) {
                    legalPlays.add(sa);
                }
            }
        }
        // Command zone (commander)
        for (Card c : player.getCardsIn(ZoneType.Command)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(player, true)) {
                if (sa.canPlay()) {
                    legalPlays.add(sa);
                }
            }
        }

        // Also send current game state
        JsonObject data = new JsonObject();
        data.add("legalPlays", serializeSpellAbilities(legalPlays));
        data.addProperty("canPassPriority", true);
        data.addProperty("phase", getGame().getPhaseHandler().getPhase().name());
        data.addProperty("step", getGame().getPhaseHandler().getPhase().name());
        data.addProperty("activePlayer", getGame().getPhaseHandler().getPlayerTurn().getName());
        data.addProperty("isMainPhase", getGame().getPhaseHandler().getPhase().isMain());

        JsonObject response = requestChoice("choose_action", data);

        if (response.has("pass") && response.get("pass").getAsBoolean()) {
            return null; // Pass priority
        }

        if (response.has("abilityIndex")) {
            int index = response.get("abilityIndex").getAsInt();
            if (index >= 0 && index < legalPlays.size()) {
                return Collections.singletonList(legalPlays.get(index));
            }
        }

        return null; // Pass priority by default
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // Must actually execute the ability — matching Forge AI's PlayerControllerAi logic
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
            }
        } else if (sa.isManaAbility()) {
            // Mana abilities don't use the stack — pay costs (tap), then resolve immediately
            sa.setActivatingPlayer(player);
            final Cost cost = sa.getPayCosts();
            if (cost != null) {
                CostPayment payment = new CostPayment(cost, sa);
                if (payment.payComputerCosts(new AiCostDecision(player, sa, false))) {
                    sa.resolve();
                }
            } else {
                sa.resolve();
            }
        } else {
            // Remember original zone so we can recover on failure
            final Card source = sa.getHostCard();
            final ZoneType origZone = (source != null && source.getZone() != null)
                    ? source.getZone().getZoneType() : ZoneType.Hand;

            boolean success = ComputerUtil.handlePlayingSpellAbility(player, sa, null);
            if (!success) {
                // handlePlayingSpellAbility moves card to Stack zone BEFORE paying costs.
                // If payment fails, the card is orphaned (Forge bug: FIXME in source).
                // Move it back to the original zone so it doesn't vanish.
                Card card = sa.getHostCard();
                if (card != null && card.getZone() != null && card.getZone().is(ZoneType.Stack)) {
                    log.info("Spell payment failed for {} — returning to {}", card.getName(), origZone);
                    player.getGame().getAction().moveTo(origZone, card, null);
                }
            }
        }
        return true;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", "Choose mode(s) for " + sa.getHostCard().getName());
        data.addProperty("min", min);
        data.addProperty("max", num);
        JsonArray modes = new JsonArray();
        for (int i = 0; i < possible.size(); i++) {
            JsonObject mode = new JsonObject();
            mode.addProperty("index", i);
            mode.addProperty("description", possible.get(i).toString());
            modes.add(mode);
        }
        data.add("modes", modes);

        JsonObject response = requestChoice("choose_modes", data);

        List<AbilitySub> result = new ArrayList<>();
        if (response.has("indices")) {
            JsonArray indices = response.getAsJsonArray("indices");
            for (int i = 0; i < indices.size(); i++) {
                int idx = indices.get(i).getAsInt();
                if (idx >= 0 && idx < possible.size()) {
                    result.add(possible.get(idx));
                }
            }
        }
        return result.isEmpty() ? possible.subList(0, Math.min(min, possible.size())) : result;
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return 0;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa,
            List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return allTargets.isEmpty() ? null : allTargets.get(0);
    }

    // --- All remaining abstract method stubs ---

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal,
            String selectPrompt, boolean isOptional, Player decider) {
        if (fetchList.isEmpty()) return null;
        JsonObject data = new JsonObject();
        data.addProperty("prompt", selectPrompt != null ? selectPrompt : "Choose a card");
        data.addProperty("destination", destination.name());
        data.add("options", serializeCards(fetchList));
        JsonObject response = requestChoice("choose_single_card_zone", data);
        if (response.has("selectedIds")) {
            int id = response.getAsJsonArray("selectedIds").get(0).getAsInt();
            for (Card c : fetchList) { if (c.getId() == id) return c; }
        }
        return isOptional ? null : fetchList.get(0);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", question);
        data.addProperty("choiceType", kindOfChoice.name());
        JsonObject response = requestChoice("choose_binary", data);
        return response.has("result") ? response.get("result").getAsBoolean()
                : (defaultChoice != null ? defaultChoice : true);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean[] results, boolean call) {
        return true; // Call heads
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return colors.getColor();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return colors.getColor();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return options;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return null;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? null : faces.get(0);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return true; // Choose pile 1
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        return options.isEmpty() ? null : options.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return options.isEmpty() ? "" : options.get(0);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return values.isEmpty() ? 0 : values.get(0);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        return Collections.emptyList();
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        return choices.isEmpty() ? "" : choices.get(0);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return true;
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return false;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        // Use AI mana payment — auto-taps lands to pay costs
        return ComputerUtilMana.payManaCost(new Cost(toPay, effect), player, sa, effect);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return "";
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return faces.isEmpty() ? "" : faces.get(0).getName();
    }

    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {}

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {}

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {}

    @Override
    public void resetAtEndOfTurn() {}

    // --- Helper to parse card selections from client response ---

    private CardCollectionView parseCardSelection(JsonObject response, CardCollectionView options, int minRequired) {
        CardCollection result = new CardCollection();
        if (response.has("selectedIds")) {
            // Handle both array [1,2] and single number 1 from the client
            var element = response.get("selectedIds");
            List<Integer> ids = new ArrayList<>();
            if (element.isJsonArray()) {
                JsonArray arr = element.getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    ids.add(arr.get(i).getAsInt());
                }
            } else {
                ids.add(element.getAsInt());
            }
            for (int id : ids) {
                for (Card c : options) {
                    if (c.getId() == id) {
                        result.add(c);
                        break;
                    }
                }
            }
        }
        // If not enough selected, auto-pick from options
        if (result.size() < minRequired) {
            for (Card c : options) {
                if (!result.contains(c)) {
                    result.add(c);
                    if (result.size() >= minRequired) break;
                }
            }
        }
        return result;
    }
}
