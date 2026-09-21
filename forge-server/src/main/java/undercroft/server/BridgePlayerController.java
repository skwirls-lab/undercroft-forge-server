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
import forge.StaticData;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
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
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPayment;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.card.mana.ManaAtom;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
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
            // An empty response is not a player decision - it means the prompt timed out, was
            // cancelled, or the client has no UI for this choiceType and replied with keys we
            // do not read. Every caller silently substitutes a default here, so without this
            // log a whole mechanic can no-op with no trace at all.
            if (response.size() == 0) {
                log.error("EMPTY choice response for '{}' (request {}) - the caller will now "
                        + "apply a hardcoded default. Either the client has no renderer for "
                        + "this choiceType, or it replied with an unexpected key.",
                        choiceType, requestId);
            }
            return response;
        } catch (TimeoutException e) {
            log.error("Choice TIMEOUT after {}s for '{}' (request {}) - applying default",
                    CHOICE_TIMEOUT_SECONDS, choiceType, requestId);
            return new JsonObject(); // Return empty = default/skip
        } catch (Exception e) {
            log.error("Error waiting for choice '{}' ({}): {}", choiceType, requestId, e.getMessage());
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
        if (requestId == null) {
            // Silently dropping this leaves the engine thread blocked for the full timeout.
            log.error("choice_response with no requestId - dropping. Payload: {}", payload);
            return;
        }
        CompletableFuture<JsonObject> future = pendingChoices.get(requestId);
        if (future != null) {
            future.complete(payload);
        } else {
            log.warn("No pending choice for requestId {} (already answered, timed out, or "
                    + "cancelled). Any currently blocked prompt is still waiting.", requestId);
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
                // Enough to read the card, not just recognise its name. Library cards never
                // reach the client as instances (the library is a count), so a tutor or scry
                // prompt was a list of names with nothing behind them — a player who did not
                // know every card by heart was choosing blind. Each guarded separately: a
                // face-down or split card can throw on one accessor and still name the rest.
                try { obj.addProperty("manaCost", card.getManaCost().toString()); } catch (Exception ignored) { }
                try { obj.addProperty("oracleText", card.getOracleText()); } catch (Exception ignored) { }
                try {
                    var cs = card.getColor();
                    StringBuilder colors = new StringBuilder();
                    if (cs.hasWhite()) colors.append('W');
                    if (cs.hasBlue()) colors.append('U');
                    if (cs.hasBlack()) colors.append('B');
                    if (cs.hasRed()) colors.append('R');
                    if (cs.hasGreen()) colors.append('G');
                    obj.addProperty("colors", colors.toString());
                } catch (Exception ignored) { }
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
        // Null is a real answer here: the card is simply not played. The client's "Never mind"
        // sends cancel:true (and index -1, for servers that only bounds-check).
        if (response.has("cancel") && response.get("cancel").getAsBoolean()) {
            return null;
        }
        int index = response.has("index") ? response.get("index").getAsInt() : 0;
        return (index >= 0 && index < abilities.size()) ? abilities.get(index) : null;
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        // Final resolution step for EVERY triggered ability (WrappedAbility.resolve), every
        // replacement effect (ReplacementHandler) and every opening-hand effect (GameAction).
        // An empty body here silently discards every trigger this player controls: the trigger
        // registers, fires, goes on the stack, "resolves", and produces no effect.
        //
        // Mirrors PlayerControllerAi.playSpellAbilityNoStack. ComputerUtil.playNoStack pays any
        // cost and then calls AbilityUtils.resolve(sa), which is what actually applies the effect.
        if (effectSA == null) {
            return;
        }

        // Callers that pass true have not chosen targets yet (WrappedAbility passes false,
        // because trigger targets are fixed when the trigger goes on the stack).
        if (mayChoseNewTargets && effectSA.usesTargeting()) {
            chooseTargetsFor(effectSA);
        }

        if (!ComputerUtil.playNoStack(player, effectSA, getGame(), true)) {
            log.warn("playSpellAbilityNoStack: {} did not resolve (cost could not be paid)", effectSA);
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (wrapperAbility == null) {
            return false;
        }

        if (!isMandatory) {
            JsonObject data = new JsonObject();
            data.addProperty("cardName", host.getName());
            data.addProperty("ability", wrapperAbility.toString());
            data.addProperty("mandatory", false);
            // The client renders data.prompt; without it the player sees a bare "Play trigger?"
            // with no indication of which trigger is being offered.
            data.addProperty("prompt", "Play the triggered ability of " + host.getName() + "?");

            JsonObject response = requestChoice("play_trigger", data);
            boolean play = response.has("play") ? response.get("play").getAsBoolean() : true;
            if (!play) {
                return false;
            }
        }

        // Answering the prompt is not the same as resolving the ability. TriggerHandler treats
        // a true return as "the trigger fired", so returning the answer without resolving makes
        // every static trigger a no-op that reports success.
        // Mirrors PlayerControllerAi.playTrigger (prepareSingleSa + playNoStack).
        if (!prepareTriggerAbility(host, wrapperAbility)) {
            return false;
        }
        return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
    }

    /**
     * Resolve the choices an ability needs before it can be played outside the stack:
     * modal (Charm) mode selection, and delegated targeting via TargetingPlayer.
     * Mirrors PlayerControllerAi.prepareSingleSa.
     */
    private boolean prepareTriggerAbility(Card host, SpellAbility sa) {
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return true;
            }
            sa = sa.getSubAbility();
            if (sa == null) {
                return false;
            }
        }
        if (sa.hasParam("TargetingPlayer")) {
            List<Player> targeting = AbilityUtils.getDefinedPlayers(host, sa.getParam("TargetingPlayer"), sa);
            if (targeting.isEmpty()) {
                return false;
            }
            Player targetingPlayer = targeting.get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        }
        return true;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        // Used by cascade/discover, "you may cast it without paying its mana cost", and
        // impulse-style play-from-exile. PlayEffect/ChangeZoneEffect/DiscoverEffect treat a
        // true return as "the spell was cast", so returning true without playing anything
        // makes all of those silently do nothing.
        // Mirrors PlayerControllerAi.playSaFromPlayEffect.
        if (tgtSA == null) {
            return false;
        }
        return ComputerUtil.playStack(tgtSA, player, getGame());
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

    /**
     * "Add X mana in any combination of {U} and/or {R}" — Vivi Ornitier, Cryptolith Rite's
     * cousins, every "in any combination" source. The engine asks once for the whole amount.
     * Returning all-colourless (the previous default) meant such mana could pay only generic
     * costs, so a {4}{U} ability paid four from Vivi and still wanted the {U}.
     *
     * Mirrors PlayerControllerHuman.specifyManaCombo: the player splits the amount across the
     * colours offered; "Different" caps each colour at one. An answer short of the amount is
     * topped up from the colours offered, never with colourless.
     */
    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        List<MagicColor.Color> options = new ArrayList<>();
        if (colorSet != null) {
            for (MagicColor.Color color : colorSet) {
                if (color != MagicColor.Color.COLORLESS && !options.contains(color)) {
                    options.add(color);
                }
            }
        }
        Map<Byte, Integer> result = new HashMap<>();
        if (options.isEmpty()) {
            result.put((byte) 0, manaAmount);
            return result;
        }
        if (options.size() == 1 && !different) {
            result.put(options.get(0).getColorMask(), manaAmount);
            return result;
        }

        String host = sa != null && sa.getHostCard() != null ? sa.getHostCard().getName() : "";
        JsonObject data = new JsonObject();
        data.addProperty("prompt", host.isEmpty() ? "Choose " + manaAmount + " mana" : host + " — choose " + manaAmount + " mana");
        data.addProperty("cardName", host);
        data.addProperty("amount", manaAmount);
        data.addProperty("different", different);
        JsonArray arr = new JsonArray();
        for (MagicColor.Color color : options) {
            JsonObject o = new JsonObject();
            o.addProperty("mask", color.getColorMask());
            o.addProperty("name", color.getName());
            o.addProperty("symbol", color.getShortName());
            arr.add(o);
        }
        data.add("colors", arr);

        JsonObject response = requestChoice("choose_mana_combo", data);

        int total = 0;
        if (response.has("counts") && response.get("counts").isJsonObject()) {
            JsonObject counts = response.getAsJsonObject("counts");
            for (MagicColor.Color color : options) {
                String key = String.valueOf(color.getColorMask());
                if (!counts.has(key)) continue;
                int n;
                try { n = Math.max(0, counts.get(key).getAsInt()); } catch (Exception e) { continue; }
                if (different) n = Math.min(n, 1);
                n = Math.min(n, manaAmount - total);
                if (n > 0) {
                    result.merge(color.getColorMask(), n, Integer::sum);
                    total += n;
                }
            }
        }
        for (int i = 0; total < manaAmount && i < manaAmount * options.size(); i++) {
            MagicColor.Color c = options.get(i % options.size());
            if (different && result.containsKey(c.getColorMask())) {
                if (i >= options.size()) break;
                continue;
            }
            result.merge(c.getColorMask(), 1, Integer::sum);
            total++;
        }
        if (total < manaAmount) {
            log.warn("specifyManaCombo: could only place {} of {} mana", total, manaAmount);
        }
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
        data.addProperty("prompt", "Choose " + announce + " for "
                + (ability.getHostCard() != null ? ability.getHostCard().getName() : "this spell"));
        data.addProperty("announce", announce);
        data.addProperty("abilityDescription", ability.toString());
        data.addProperty("min", 0);
        // Bound the stepper by what the player could actually pay, so the prompt is usable
        // instead of an unbounded counter.
        try {
            data.addProperty("max", ComputerUtilMana.getAvailableManaEstimate(player));
        } catch (Exception e) {
            log.debug("Could not estimate available mana for {}: {}", announce, e.getMessage());
        }

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
        String cardName = currentAbility.getHostCard() != null ? currentAbility.getHostCard().getName() : "spell";
        data.addProperty("prompt", "Choose target for " + cardName);
        data.addProperty("abilityDescription", currentAbility.toString());
        if (currentAbility.getHostCard() != null) {
            data.addProperty("cardName", cardName);
        }

        // Get valid targets
        List<GameEntity> validTargets = new ArrayList<>();
        TargetRestrictions restrictions = currentAbility.getTargetRestrictions();
        if (restrictions != null) {
            validTargets.addAll(restrictions.getAllCandidates(currentAbility, true));
        }

        int minTargets = restrictions != null ? restrictions.getMinTargets(currentAbility.getHostCard(), currentAbility) : 0;
        int maxTargets = restrictions != null ? restrictions.getMaxTargets(currentAbility.getHostCard(), currentAbility) : 1;

        // If targeting is optional and there are no valid targets, skip silently
        if (validTargets.isEmpty() && minTargets == 0) {
            log.info("chooseTargetsFor: no valid targets but min=0, skipping targeting for {}", currentAbility);
            return true;
        }

        // The same shape as every other card prompt: type line, P/T, zone, owner, controller,
        // text. Targets used to be bare id/name/type, so an "any target" prompt in a pod could
        // not say whose creature was whose.
        data.add("validTargets", serializeCards(validTargets));
        data.addProperty("minTargets", minTargets);
        data.addProperty("maxTargets", maxTargets);

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
            // Allow empty selection when targeting is optional (min=0)
            return currentAbility.getTargets().size() >= minTargets;
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
        // Only optional ("you may") triggers are worth asking about; mandatory ones always fire.
        if (sa == null || !sa.isOptionalTrigger()) {
            return true;
        }
        Card host = sa.getHostCard();
        return promptForConfirm("Use the triggered ability of "
                + (host != null ? host.getName() : "this permanent") + "?", true);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        if (attackers == null || attackers.isEmpty()) {
            return Collections.emptyList();
        }
        CardCollection options = new CardCollection(attackers);
        return new ArrayList<>(promptForCards("Choose attackers to exert (they won't untap next turn)",
                options, 0, options.size()));
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        if (attackers == null || attackers.isEmpty()) {
            return Collections.emptyList();
        }
        CardCollection options = new CardCollection(attackers);
        return new ArrayList<>(promptForCards("Choose attackers to enlist", options, 0, options.size()));
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Send list of possible attackers to client
        CardCollection possibleAttackers = new CardCollection();
        for (Card c : attacker.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) {
                boolean canAttack = CombatUtil.canAttack(c);
                boolean isSick = c.isSick();
                boolean hasHaste = c.hasKeyword("Haste");
                log.info("Creature {} - canAttack: {}, isSick: {}, hasHaste: {}, tapped: {}", 
                    c.getName(), canAttack, isSick, hasHaste, c.isTapped());
                if (canAttack) {
                    possibleAttackers.add(c);
                }
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
        // Damage assignment order is the player's choice, not source order.
        return promptForOrder("Damage assignment order for " + attacker.getName()
                + " (first blocker takes damage first)", blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection result = new CardCollection(oldBlockers);
        result.add(blocker);
        return result;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return promptForOrder("Damage assignment order for " + blocker.getName()
                + " (first attacker is dealt damage first)", attackers);
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
        if (cards == null || cards.size() <= 1) {
            return cards;
        }
        return promptForOrder("Choose the order cards are put into your "
                + destinationZone.name().toLowerCase(), cards);
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
        // Returning empty meant delve could never be used.
        if (genericAmount <= 0 || grave == null || grave.isEmpty()) {
            return CardCollection.EMPTY;
        }
        return promptForCards("Exile cards from your graveyard to pay for Delve (up to "
                + genericAmount + ")", grave, 0, Math.min(genericAmount, grave.size()));
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost,
            CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        // Returning an empty map meant convoke and improvise could never be used.
        Map<Card, ManaCostShard> result = new HashMap<>();
        if (untappedCards == null || untappedCards.isEmpty() || manaCost == null) {
            return result;
        }
        int limit = maxReduction != null ? Math.min(maxReduction, untappedCards.size()) : untappedCards.size();
        if (limit <= 0) {
            return result;
        }

        CardCollectionView chosen = promptForCards("Tap creatures/artifacts to help pay "
                + manaCost + " (" + (creatures ? "Convoke" : "Improvise") + ")",
                untappedCards, 0, limit);

        // Each tapped permanent pays one generic shard. Colored convoke reductions would need
        // a per-card color prompt; generic is always legal and never over-pays.
        for (Card c : chosen) {
            result.put(c, ManaCostShard.GENERIC);
        }
        return result;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        CardCollection options = new CardCollection(cards);
        return new ArrayList<>(promptForCards("Choose cards to splice onto "
                + (sa.getHostCard() != null ? sa.getHostCard().getName() : "this spell"),
                options, 0, options.size()));
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        // This previously passed a deliberately empty response so the helper auto-picked the
        // first `min` cards from the player's hand without ever asking.
        return promptForCards("Choose cards to reveal from your hand", valid, min,
                Math.min(max, valid == null ? 0 : valid.size()));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        // Returning empty meant Leyline, Chancellor and Gemstone Caverns style abilities were
        // never offered before the first turn.
        if (usableFromOpeningHand == null || usableFromOpeningHand.isEmpty()) {
            return Collections.emptyList();
        }
        List<SpellAbility> chosen = new ArrayList<>();
        for (SpellAbility sa : usableFromOpeningHand) {
            Card host = sa.getHostCard();
            if (promptForConfirm("Use " + (host != null ? host.getName() : "this ability")
                    + " from your opening hand?", false)) {
                chosen.add(sa);
            }
        }
        return chosen;
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
        if (options == null || options.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (Object o : options) {
            labels.add(String.valueOf(o));
        }
        String chosen = promptForString(prompt, labels, labels.get(0));
        for (Object o : options) {
            if (String.valueOf(o).equals(chosen)) {
                return o;
            }
        }
        return options.get(0);
    }

    @Override
    public boolean mulliganKeepHand(Player player, int cardsToReturn) {
        CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        log.info("Mulligan prompt for {} - cardsToReturn: {}, hand size: {}, cards: {}", 
            player.getName(), cardsToReturn, hand.size(), 
            hand.stream().map(Card::getName).collect(java.util.stream.Collectors.joining(", ")));
        
        JsonObject data = new JsonObject();
        data.addProperty("prompt", cardsToReturn > 0
                ? "Keep hand? (Return " + cardsToReturn + " card(s) to bottom)"
                : "Keep hand?");
        data.addProperty("cardsToReturn", cardsToReturn);
        data.add("hand", serializeCards(hand));

        JsonObject response = requestChoice("mulligan", data);
        boolean keep = response.has("keep") ? response.get("keep").getAsBoolean() : true;
        log.info("Mulligan response for {}: keep={}", player.getName(), keep);
        return keep;
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
        return promptForConfirm("Scry after mulligan?", true);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // This is the main "what do you want to do?" prompt
        // Forge calls this when the player has priority
        // We need to send the full list of legal actions
        // NOTE: Mana abilities are NOT included here - they are handled separately
        // during mana payment (see payManaCost method). This matches how Forge works:
        // you can't just tap lands for mana randomly, only when paying for something.

        // Zones a player can legally play from. Scanning only Hand/Battlefield/Command made
        // whole mechanics unreachable:
        //   Graveyard - flashback, escape, disturb, embalm, eternalize, unearth, aftermath
        //   Exile     - Adventure halves, foretell, suspend, cascade/impulse "play until EOT"
        //   Library   - Future Sight / Oracle of Mul Daya / Bolas's Citadel "play from the top"
        // canPlay() is still the authority on whether any individual ability is legal right
        // now, so widening the scan cannot make an illegal play available.
        final ZoneType[] playableZones = {
            ZoneType.Hand,
            ZoneType.Battlefield,
            ZoneType.Command,
            ZoneType.Graveyard,
            ZoneType.Exile,
            ZoneType.Library,
        };

        List<SpellAbility> legalPlays = new ArrayList<>();
        for (ZoneType zone : playableZones) {
            // Every "play from your library" effect in practice exposes only the top card
            // (Future Sight, Oracle of Mul Daya, Bolas's Citadel). Scanning the whole library
            // would walk ~99 cards on every priority check for no additional legal plays.
            Iterable<Card> candidates = zone == ZoneType.Library
                ? player.getCardsIn(ZoneType.Library, 1)
                : player.getCardsIn(zone);
            for (Card c : candidates) {
                for (SpellAbility sa : c.getAllPossibleAbilities(player, true)) {
                    // Mana abilities are excluded here; they are offered during mana payment.
                    if (sa.isManaAbility()) {
                        continue;
                    }
                    // Permanents on the battlefield only offer activated abilities.
                    if (zone == ZoneType.Battlefield && !sa.isActivatedAbility()) {
                        continue;
                    }
                    // canPlay() consults the activating player; set it first so that
                    // alternative-cost and zone-restricted abilities evaluate correctly.
                    sa.setActivatingPlayer(player);
                    if (sa.canPlay()) {
                        legalPlays.add(sa);
                    }
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

    // =====================================================================
    // payManaCost — Forge's built-in hook for interactive mana payment.
    // This is called by CostPartMana.payAsDecided() during spell casting.
    // Mirrors PlayerControllerHuman.payManaCost → HumanPlay.payManaCost.
    // =====================================================================
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
                               String prompt, ManaConversionMatrix matrix, boolean effect) {
        log.info("payManaCost called: toPay={} spell={}", toPay, sa.getHostCard().getName());

        ManaCostBeingPaid manaCost = new ManaCostBeingPaid(toPay);
        CostAdjustment.adjust(manaCost, sa, player, new CardCollection(), false, effect);

        if (manaCost.isPaid()) {
            log.info("payManaCost: cost already paid (0 or reduced to 0)");
            return true;
        }

        // Diagnostic: check what getManaAbilities returns for each battlefield permanent
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isTapped()) {
                log.info("  BF card: {} (id={}) type={} isLand={} subtypes={} manaAbilities={}",
                    c.getName(), c.getId(), c.getType(),
                    c.getType().isLand(),
                    c.getType().getSubtypes(),
                    c.getManaAbilities().size());
            }
        }

        // Life promised for Phyrexian shards ({U/P}: one blue or two life). Deferred until the
        // cost is fully paid, as InputPayManaOfCostPayment does, so a cancelled cast costs none.
        int lifeToPay = 0;

        int maxIterations = 20;
        for (int i = 0; i < maxIterations; i++) {
            if (manaCost.isPaid()) {
                log.info("payManaCost: fully paid after {} taps", i);
                settlePhyrexianLife(sa, lifeToPay);
                return true;
            }

            // Gather untapped cards with mana abilities (same as InputPayMana.getAllManaAbilities)
            List<Card> sources = new ArrayList<>();
            for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isTapped()) {
                    List<SpellAbility> manaAbs = new ArrayList<>();
                    for (SpellAbility ma : c.getManaAbilities()) {
                        ma.setActivatingPlayer(player);
                        if (ma.canPlay(true)) {
                            manaAbs.add(ma);
                        }
                    }
                    if (!manaAbs.isEmpty()) {
                        sources.add(c);
                    }
                }
            }

            boolean lifeOffer = manaCost.containsPhyrexianMana() && player.canPayLife(lifeToPay + 2, false, sa);

            if (sources.isEmpty() && !lifeOffer) {
                // Try paying from pool first (e.g. mana already floating)
                boolean paidFromPool = false;
                for (byte color : ManaAtom.MANATYPES) {
                    while (manaCost.isAnyPartPayableWith(color, player.getManaPool()) &&
                           player.getManaPool().tryPayCostWithColor(color, sa, manaCost, sa.getPayingMana())) {
                        paidFromPool = true;
                        if (manaCost.isPaid()) break;
                    }
                    if (manaCost.isPaid()) break;
                }
                if (manaCost.isPaid()) {
                    log.info("payManaCost: paid from pool");
                    settlePhyrexianLife(sa, lifeToPay);
                    return true;
                }
                log.info("payManaCost: no untapped mana sources and pool insufficient — cannot pay");
                return false;
            }

            // Send mana_payment choice to client with full card details
            JsonObject data = new JsonObject();
            data.addProperty("manaCost", manaCost.toString());
            data.addProperty("spellName", sa.getHostCard().getName());
            data.addProperty("canCancel", true);
            if (lifeOffer) {
                // {W/P}, {U/P}, ...: the player may pay two life instead of the mana. The
                // option was simply never offered before.
                data.addProperty("lifeForPhyrexian", 2);
                data.addProperty("lifePromised", lifeToPay);
            }
            // Send full card data so frontend can display clickable land buttons
            JsonArray sourcesArray = new JsonArray();
            for (Card c : sources) {
                JsonObject cardObj = new JsonObject();
                cardObj.addProperty("id", c.getId());
                cardObj.addProperty("name", c.getName());
                cardObj.addProperty("type", c.getType().toString());
                sourcesArray.add(cardObj);
            }
            data.add("sources", sourcesArray);

            JsonObject response = requestChoice("mana_payment", data);

            if (response.has("cancel") && response.get("cancel").getAsBoolean()) {
                log.info("payManaCost: player cancelled");
                return false;
            }

            if (response.has("payLife") && response.get("payLife").getAsBoolean()) {
                if (lifeOffer && manaCost.payPhyrexian()) {
                    sa.setSpendPhyrexianMana(true);
                    lifeToPay += 2;
                    log.info("payManaCost: two life promised for a Phyrexian shard — remaining={}", manaCost);
                }
                continue;
            }

            if (response.has("cardId")) {
                int cardId = response.get("cardId").getAsInt();
                Card chosen = null;
                for (Card c : sources) {
                    if (c.getId() == cardId) { chosen = c; break; }
                }
                if (chosen != null) {
                    // Activate mana ability — same as InputPayMana.activateManaAbility
                    for (SpellAbility ma : chosen.getManaAbilities()) {
                        ma.setActivatingPlayer(player);
                        if (!ma.canPlay(true)) continue;

                        // Pay tap cost and resolve (same as HumanPlay.playSpellAbility for mana)
                        CostPayment payment = new CostPayment(ma.getPayCosts(), ma);
                        if (payment.payComputerCosts(new AiCostDecision(player, ma, false))) {
                            // Through the stack, as HumanPlaySpellAbility does. A mana ability
                            // never waits there — MagicStack.add resolves it at once — but on
                            // the way it counts the activation (so "activate only once each
                            // turn" holds: Vivi Ornitier could be tapped for mana all turn) and
                            // fires the tapped-for-mana triggers. A bare ma.resolve() did neither.
                            getGame().getStack().add(ma);
                            // Apply produced mana to the cost being paid
                            player.getManaPool().payManaFromAbility(sa, manaCost, ma);
                            log.info("payManaCost: tapped {} — remaining={}", chosen.getName(), manaCost);
                        }
                        break;
                    }
                }
            }
        }

        boolean paid = manaCost.isPaid();
        if (paid) settlePhyrexianLife(sa, lifeToPay);
        return paid;
    }

    /** Pay the life promised for Phyrexian shards, once the rest of the cost is in. */
    private void settlePhyrexianLife(SpellAbility sa, int life) {
        if (life <= 0) return;
        if (!player.payLife(life, sa, false)) {
            log.warn("payManaCost: could not pay {} life promised for Phyrexian mana", life);
        }
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // Use the same flow as HumanPlay.playSpellAbility to ensure proper rollback
        // when mana payment fails or is cancelled
        sa.setActivatingPlayer(player);
        
        if (sa.isLandAbility()) {
            if (sa.canPlay()) {
                sa.resolve();
            }
            return true;
        }
        
        // For spells and abilities, use the full payment flow with rollback support
        // This mirrors HumanPlaySpellAbility.playAbility which handles rollback properly
        Card source = sa.getHostCard();
        Game game = player.getGame();

        // Store zone info for rollback
        forge.game.zone.Zone fromZone = game.getZoneOf(source);
        int zonePosition = fromZone != null ? fromZone.getCards().indexOf(source) : 0;

        // CR 401.5: freeze the top library cards while casting so the player cannot see the
        // next card. This matters now that the top of the library is a playable zone.
        boolean refreeze = game.getStack().isFrozen();
        if (!refreeze) {
            game.setTopLibsCast();
        }

        // needX starts true and is only narrowed for modal spells, as in HumanPlaySpellAbility.
        boolean needX = true;

        // CR 601.4 / 603.3c: modal spells choose their mode(s) BEFORE costs are computed.
        // CharmEffect.makeChoices is only ever called from the human/AI cast paths, so
        // omitting it here meant modal spells never prompted and then resolved to nothing.
        if (sa.getApi() == ApiType.Charm) {
            if (sa.isAnnouncing("X")) {
                needX = sa.costHasX();
                if (!announceValuesLikeX(sa, needX)) {
                    game.clearTopLibsCast(sa);
                    return false;
                }
                needX = false;
            }
            if (!CharmEffect.makeChoices(sa)) {
                game.clearTopLibsCast(sa);
                return false;
            }
        }

        sa = AbilityUtils.addSpliceEffects(sa);

        // Offer alternative and optional additional costs (kicker, buyback, entwine, ...).
        // chooseOptionalCosts has exactly one caller in Forge's human path
        // (HumanPlay.chooseOptionalAdditionalCosts), which the bridge never invoked, so these
        // costs were unreachable no matter what the player wanted.
        if (sa.isSpell() && !source.isCopiedSpell()) {
            SpellAbility withCosts = chooseOptionalAdditionalCosts(sa);
            if (withCosts == null) {
                log.info("playChosenSpellAbility: {} — player backed out at the cost prompt", source.getName());
                game.clearTopLibsCast(sa);
                return false;
            }
            sa = withCosts;
        }

        // Move spell to stack (will be rolled back if payment fails)
        if (sa.isSpell() && !source.isCopiedSpell()) {
            sa.setHostCard(game.getAction().moveToStack(source, sa));
            sa.changeText();
        }

        if (!sa.isCopied()) {
            sa.resetPaidHash();
            sa.setPaidLife(0);
        }

        // Attaches optional additional costs (kicker, buyback, entwine, ...) to the ability.
        // Without it those costs are never even offered.
        if (sa.isSpell() && !source.isCopiedSpell()) {
            sa = GameActionUtil.addExtraKeywordCost(sa);
        }

        Cost abCost = sa.getPayCosts();
        CostPayment payment = new CostPayment(abCost, sa);

        sa.clearManaPaid();
        sa.getPayingManaAbilities().clear();

        // announceType and announceValuesLikeX must run before the cost is paid: they set the
        // chosen type/number and, critically, setXManaCostPaid. Omitting them meant every X
        // spell was cast with X unset.
        boolean prerequisitesMet = announceType(sa) &&
            announceValuesLikeX(sa, needX) &&
            sa.checkRestrictions(player) &&
            sa.setupTargets() &&
            sa.canCastTiming(player) &&
            sa.isLegalAfterStack();

        game.getStack().freezeStack(sa);

        if (prerequisitesMet) {
            // NOTE: still AiCostDecision. Non-mana costs (sacrifice, discard, exile, tap,
            // remove counters) are therefore chosen by AI heuristics rather than by the
            // player. Fixing that needs a bridge ICostVisitor equivalent to HumanCostDecision.
            prerequisitesMet = payment.payCost(new AiCostDecision(player, sa, false));
        }

        game.clearTopLibsCast(sa);

        if (!prerequisitesMet) {
            // Rollback: move card back to original zone
            log.info("playChosenSpellAbility: rolling back {} to {}", source.getName(), fromZone);
            GameActionUtil.rollbackAbility(sa, fromZone, zonePosition, payment, source);
            game.getStack().unfreezeStack();
            return false;
        }
        
        if (payment.isFullyPaid()) {
            game.getStack().addAndUnfreeze(sa);
            return true;
        }
        
        // If we get here, something went wrong - rollback
        GameActionUtil.rollbackAbility(sa, fromZone, zonePosition, payment, source);
        game.getStack().unfreezeStack();
        return false;
    }

    /**
     * Offer alternative additional costs and optional additional costs for a spell.
     * Mirrors HumanPlay.chooseOptionalAdditionalCosts.
     */
    private SpellAbility chooseOptionalAdditionalCosts(SpellAbility original) {
        final List<SpellAbility> abilities = GameActionUtil.getAdditionalCostSpell(original);
        SpellAbility chosen;
        if (abilities.size() <= 1) {
            // Nothing to choose between. The prompt used to appear for every single spell
            // with one entry in it, and "Never mind" there fell through to the original —
            // straight into mana payment, with a second Cancel to press.
            chosen = abilities.isEmpty() ? original : abilities.get(0);
        } else {
            chosen = getAbilityToPlay(original.getHostCard(), abilities);
            if (chosen == null) {
                // The player backed out of casting. Null tells playChosenSpellAbility to stop
                // before the card moves to the stack; nothing has been paid yet.
                return null;
            }
        }

        List<OptionalCostValue> list = GameActionUtil.getOptionalCostValues(chosen);
        if (!list.isEmpty()) {
            list = chooseOptionalCosts(chosen, list);
        }
        return GameActionUtil.addOptionalCosts(chosen, list);
    }

    /**
     * Ask the player to put {@code cards} into an order, reusing the choose_order renderer.
     * Returns the original order if the answer is unusable.
     */
    private CardCollection promptForOrder(String prompt, CardCollectionView cards) {
        CardCollection original = new CardCollection(cards);
        if (original.size() <= 1) {
            return original;
        }
        JsonObject data = new JsonObject();
        data.addProperty("prompt", prompt);
        data.add("cards", serializeCards(original));

        JsonObject response = requestChoice("choose_order", data);
        if (!response.has("orderedIds")) {
            return original;
        }

        JsonArray ids = response.getAsJsonArray("orderedIds");
        CardCollection ordered = new CardCollection();
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.get(i).getAsInt();
            for (Card c : original) {
                if (c.getId() == id && !ordered.contains(c)) {
                    ordered.add(c);
                    break;
                }
            }
        }
        // Anything the client omitted keeps its original relative position at the end.
        for (Card c : original) {
            if (!ordered.contains(c)) {
                ordered.add(c);
            }
        }
        return ordered;
    }

    /**
     * Ask the player to pick up to {@code max} cards from {@code options} (selection may be
     * empty), reusing the generic choose_cards renderer.
     */
    private CardCollectionView promptForCards(String prompt, CardCollectionView options, int min, int max) {
        if (options == null || options.isEmpty() || max <= 0) {
            return CardCollection.EMPTY;
        }
        JsonObject data = new JsonObject();
        data.addProperty("prompt", prompt);
        data.addProperty("min", min);
        data.addProperty("max", max);
        data.addProperty("optional", min == 0);
        data.add("options", serializeCards(options));

        JsonObject response = requestChoice("choose_cards", data);
        return parseCardSelection(response, options, min);
    }

    /**
     * Ask the player to pick one string from a list, reusing the generic choose_type renderer.
     * Returns {@code fallback} if the answer is unusable.
     */
    private String promptForString(String prompt, Collection<String> options, String fallback) {
        if (options == null || options.isEmpty()) {
            return fallback;
        }
        if (options.size() == 1) {
            return options.iterator().next();
        }
        JsonObject data = new JsonObject();
        data.addProperty("prompt", prompt != null && !prompt.isEmpty() ? prompt : "Choose one");
        JsonArray arr = new JsonArray();
        options.forEach(arr::add);
        data.add("options", arr);

        JsonObject response = requestChoice("choose_type", data);
        if (response.has("chosen")) {
            String chosen = response.get("chosen").getAsString();
            if (options.contains(chosen)) {
                return chosen;
            }
        }
        return fallback;
    }

    /** Ask the player for a number in [min, max], reusing the announce_number renderer. */
    private int promptForNumber(String prompt, int min, int max) {
        if (min >= max) {
            return min;
        }
        JsonObject data = new JsonObject();
        data.addProperty("prompt", prompt != null && !prompt.isEmpty() ? prompt : "Choose a number");
        data.addProperty("min", min);
        data.addProperty("max", max);

        JsonObject response = requestChoice("announce_number", data);
        if (response.has("value")) {
            return Math.max(min, Math.min(max, response.get("value").getAsInt()));
        }
        return min;
    }

    /** Ask a yes/no question, reusing the confirm_action renderer. */
    private boolean promptForConfirm(String prompt, boolean fallback) {
        JsonObject data = new JsonObject();
        data.addProperty("prompt", prompt != null && !prompt.isEmpty() ? prompt : "Confirm?");
        JsonObject response = requestChoice("confirm_action", data);
        return response.has("confirmed") ? response.get("confirmed").getAsBoolean() : fallback;
    }

    /**
     * Announce X / multikicker style values before costs are paid.
     * Mirrors HumanPlaySpellAbility.announceValuesLikeX. Without this, setXManaCostPaid is
     * never called and every X spell is cast with X unset.
     */
    private boolean announceValuesLikeX(SpellAbility sa, boolean needX) {
        if (sa.isCopied() || sa.isWrapper()) {
            return true; // don't re-announce for spell copies
        }

        final Cost cost = sa.getPayCosts();
        final Card card = sa.getHostCard();

        final String announce = sa.getParam("Announce");
        if (announce != null && needX) {
            for (final String aVar : announce.split(",")) {
                final String varName = aVar.trim();
                final Integer value = announceRequirements(sa, varName);
                if (value == null) {
                    return false;
                }
                if ("X".equalsIgnoreCase(varName)) {
                    needX = false;
                    sa.setXManaCostPaid(value);
                } else {
                    sa.setSVar(varName, value.toString());
                    card.setSVar(varName, value.toString());
                }
            }
        }

        if (needX) {
            if (cost.hasXInAnyCostPart()) {
                final String sVar = sa.hasParam("XAlternative") ? sa.getParam("XAlternative") : sa.getSVar("X");
                if ("Count$xPaid".equals(sVar) || sVar == null || sVar.isEmpty()) {
                    final Integer value = announceRequirements(sa, "X");
                    if (value == null) {
                        return false;
                    }
                    sa.setXManaCostPaid(value);
                }
            } else {
                sa.setXManaCostPaid(null);
            }
        }
        return true;
    }

    /**
     * Resolve an AnnounceType parameter (creature type / number / opponent) on cast.
     * Mirrors HumanPlaySpellAbility.announceType.
     */
    private boolean announceType(SpellAbility sa) {
        if (sa.isCopied()) {
            return true;
        }
        final String announce = sa.getParam("AnnounceType");
        if (announce == null) {
            return true;
        }
        for (final String aVar : announce.split(",")) {
            final String varName = aVar.trim();
            if ("CreatureType".equals(varName)) {
                final String choice = chooseSomeType("Creature", sa, CardType.getAllCreatureTypes(), false);
                sa.getHostCard().setChosenType(choice);
            } else if ("ChooseNumber".equals(varName)) {
                final int min = Integer.parseInt(sa.getParam("Min"));
                final int max = Integer.parseInt(sa.getParam("Max"));
                sa.getHostCard().setChosenNumber(chooseNumber(sa, "Choose a number", min, max));
            } else if ("Opponent".equals(varName)) {
                final Player opp = chooseSingleEntityForEffect(
                        player.getOpponents(), sa, "Choose an opponent", null);
                sa.getHostCard().setChosenPlayer(opp);
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
        return promptForNumber("Choose a cost reduction amount", min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        // Returning 0 here meant multikicker and similar keyword costs were never paid.
        return promptForNumber(prompt != null ? prompt : "How many times?", 0, max);
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
        return promptForConfirm("Call heads? (No = tails)", true);
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return promptForColor(message, colors, false);
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return promptForColor(message, colors, true);
    }

    /**
     * Ask the player to pick a single color from {@code colors}.
     *
     * ColorSet.getColor() returns the whole bitmask (31 for WUBRG), NOT a single color, and
     * MagicColor.Color.fromByte maps anything that is not exactly one of W/U/B/R/G to
     * COLORLESS. Returning it unconditionally meant every "add one mana of any color" source
     * — Command Tower, Arcane Signet, Birds of Paradise — produced colorless mana.
     * Mirrors PlayerControllerHuman.chooseColor, which only short-circuits for a single color.
     */
    private byte promptForColor(String message, ColorSet colors, boolean allowColorless) {
        List<MagicColor.Color> options = new ArrayList<>();
        if (colors != null) {
            for (MagicColor.Color color : colors) {
                if (color != MagicColor.Color.COLORLESS && !options.contains(color)) {
                    options.add(color);
                }
            }
        }
        if (allowColorless) {
            options.add(MagicColor.Color.COLORLESS);
        }

        if (options.isEmpty()) {
            return MagicColor.COLORLESS;
        }
        if (options.size() == 1) {
            return options.get(0).getColorMask();
        }

        JsonObject data = new JsonObject();
        data.addProperty("prompt", message != null && !message.isEmpty() ? message : "Choose a color");
        JsonArray arr = new JsonArray();
        for (MagicColor.Color color : options) {
            JsonObject o = new JsonObject();
            o.addProperty("mask", color.getColorMask());
            o.addProperty("name", color.getName());
            o.addProperty("symbol", color.getShortName());
            arr.add(o);
        }
        data.add("colors", arr);

        JsonObject response = requestChoice("choose_color", data);
        if (response.has("mask")) {
            byte chosen = (byte) response.get("mask").getAsInt();
            for (MagicColor.Color color : options) {
                if (color.getColorMask() == chosen) {
                    return chosen;
                }
            }
        }

        // Fall back to a single real color, never the combined mask.
        log.warn("promptForColor: no usable answer for '{}', defaulting to {}", message, options.get(0).getName());
        return options.get(0).getColorMask();
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
        return promptForConfirm("Choose the first pile? (" + pile1.size() + " cards vs "
                + pile2.size() + " cards)", true);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (CounterType ct : options) {
            names.add(ct.getName());
        }
        String chosen = promptForString(prompt, names, names.get(0));
        for (CounterType ct : options) {
            if (ct.getName().equals(chosen)) {
                return ct;
            }
        }
        return options.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return promptForString(prompt, options, options.isEmpty() ? "" : options.get(0));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return promptForNumber(title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<String> labels = new ArrayList<>();
        for (Integer v : values) {
            labels.add(String.valueOf(v));
        }
        String chosen = promptForString(title, labels, labels.get(0));
        try {
            return Integer.parseInt(chosen);
        } catch (NumberFormatException e) {
            return values.get(0);
        }
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        // Returning empty meant kicker, buyback, entwine and every other optional additional
        // cost was declined without ever being offered.
        if (optionalCostValues == null || optionalCostValues.isEmpty()) {
            return Collections.emptyList();
        }
        List<OptionalCostValue> taken = new ArrayList<>();
        for (OptionalCostValue ocv : optionalCostValues) {
            if (promptForConfirm("Pay optional cost: " + ocv + "?", false)) {
                taken.add(ocv);
            }
        }
        return taken;
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        return promptForString(string, choices, choices.isEmpty() ? "" : choices.get(0));
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return promptForConfirm(string != null ? string : "Pay " + costPart + "?", true);
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.isEmpty()) {
            return null;
        }
        if (possibleReplacers.size() == 1) {
            return possibleReplacers.get(0);
        }
        // Which replacement applies first is the affected player's choice (CR 616.1).
        List<String> labels = new ArrayList<>();
        for (ReplacementEffect re : possibleReplacers) {
            labels.add(String.valueOf(re));
        }
        String chosen = promptForString("Choose which replacement effect to apply first",
                labels, labels.get(0));
        for (int i = 0; i < possibleReplacers.size(); i++) {
            if (labels.get(i).equals(chosen)) {
                return possibleReplacers.get(i);
            }
        }
        return possibleReplacers.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleReplacers) {
        return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        // Declining unconditionally meant "unless you pay/sacrifice..." was never offered.
        if (!promptForConfirm("Pay " + cost + " to prevent this effect?", false)) {
            return false;
        }
        return new CostPayment(cost, sa).payCost(new AiCostDecision(player, sa, true));
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        // Declining unconditionally made attacks silently fail against Propaganda-style taxes.
        if (!promptForConfirm(prompt != null ? prompt : "Pay " + cost + "?", false)) {
            return false;
        }
        return new CostPayment(cost, sa).payCost(new AiCostDecision(player, sa, true));
    }

    // payManaCost is defined earlier in this file (near line 1046)

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        // Returning "" named no card at all, which silently broke Cabal Therapy, Meddling Mage,
        // Pithing Needle and every other "choose a card name" effect.
        List<String> names = new ArrayList<>();
        try {
            for (ICardFace face : StaticData.instance().getCommonCards().getAllFaces()) {
                if (cpp == null || cpp.test(face)) {
                    names.add(face.getName());
                }
            }
        } catch (Exception e) {
            log.error("chooseCardName: could not enumerate card faces: {}", e.getMessage());
        }
        if (names.isEmpty()) {
            return "";
        }
        Collections.sort(names);
        return promptForString(message != null ? message : "Choose a card name", names, names.get(0));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces == null || faces.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (ICardFace f : faces) {
            names.add(f.getName());
        }
        return promptForString(message, names, names.get(0));
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
