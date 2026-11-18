package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.YieldUpdate;
import forge.gui.control.PlaybackSpeed;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.interfaces.IMacroSystem;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements Forge's IGuiGame interface, forwarding UI update calls
 * to the WebSocket client as JSON messages.
 *
 * Most of the interface is display bookkeeping for a real screen (zones to open, cards to
 * highlight, yield markers), which a headless bridge has no use for; since 2.0.14 those carry
 * defaults upstream and are left alone here. Every decision a player makes goes through
 * BridgePlayerController, never through these dialog methods, which answer with safe defaults
 * only so an unexpected caller cannot hang the engine.
 */
public class BridgeGuiGame implements IGuiGame {
    private final WsContext wsContext;
    private final Gson gson;
    private GameView gameView;
    private boolean paused = false;

    public BridgeGuiGame(WsContext wsContext, Gson gson) {
        this.wsContext = wsContext;
        this.gson = gson;
    }

    @Override
    public void setGameView(GameView gameView) {
        this.gameView = gameView;
    }

    @Override
    public GameView getGameView() {
        return gameView;
    }

    @Override
    public void setOriginalGameController(PlayerView view, IGameController gameController) {}

    @Override
    public void setGameController(PlayerView player, IGameController gameController) {}

    @Override
    public void setSpectator(IGameController spectator) {}

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {}

    @Override
    public void afterGameEnd() {}

    @Override
    public void showCombat() {
        sendUiEvent("show_combat");
    }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        JsonObject data = new JsonObject();
        data.addProperty("player", playerView != null ? playerView.getName() : "");
        data.addProperty("message", message);
        sendUiEvent("prompt", data);
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {}

    @Override
    public void flashIncorrectAction() {}

    @Override
    public void alertUser() {}

    @Override
    public void updatePhase(boolean saveState) {
        sendUiEvent("phase_update");
    }

    @Override
    public void updateTurn(PlayerView player) {
        sendUiEvent("turn_update");
    }

    @Override
    public void finishGame() {
        sendUiEvent("game_finished");
    }

    @Override
    public void updateStack() {
        sendUiEvent("stack_update");
    }

    @Override
    public void handleGameEvent(GameEvent event) {
        // Events are handled by GameEventForwarder separately
    }

    @Override
    public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) {
        sendUiEvent("zones_update");
    }

    @Override
    public void refreshField() {
        sendUiEvent("field_refresh");
    }

    @Override
    public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) {
        sendUiEvent("mana_update");
    }

    @Override
    public void updateLives(Iterable<PlayerView> livesUpdate) {
        sendUiEvent("lives_update");
    }

    @Override
    public void setPanelSelection(CardView hostCard) {}

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        return abilities.isEmpty() ? null : abilities.get(0);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers,
            int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        // Default: assign all to first blocker
        Map<CardView, Integer> result = new HashMap<>();
        if (!blockers.isEmpty()) {
            result.put(blockers.get(0), damage);
        }
        return result;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target,
            int amount, boolean atLeastOne, String amountLabel) {
        return target;
    }

    @Override
    public void message(String message, String title) {}

    @Override
    public void showErrorDialog(String message, String title) {}

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) { return defaultYes; }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return inputOptions != null && !inputOptions.isEmpty() ? inputOptions.get(0) : initialInput;
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) { return defaultIsYes; }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        return choices.subList(0, Math.min(min, choices.size()));
    }

    @Override
    public Integer getInteger(String message, int min, int max, boolean sortDesc) { return min; }

    @Override
    public Integer getInteger(String message, int min, int max, int cutoff) { return min; }

    @Override
    public <T> T oneOrNone(String message, List<T> choices) {
        return choices.isEmpty() ? null : choices.get(0);
    }

    @Override
    public <T> T one(String message, List<T> choices, FSerializableFunction<T, String> display) {
        return choices.isEmpty() ? null : choices.get(0);
    }

    @Override
    public <T> void reveal(String message, List<T> items) {}

    @Override
    public <T> List<T> many(String title, String topCaption, int min, int max, List<T> sourceChoices, List<T> destChoices, CardView c) {
        return sourceChoices.subList(0, Math.min(min, sourceChoices.size()));
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
            List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        return new OrderResult<>(sourceChoices, false);
    }

    @Override
    public <T> List<T> insertInList(String title, T newItem, List<T> oldItems) {
        List<T> result = new ArrayList<>(oldItems);
        result.add(0, newItem);
        return result;
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return Collections.emptyList();
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
            DelayedReveal delayedReveal, boolean isOptional) {
        return optionList.isEmpty() ? null : optionList.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
            int min, int max, DelayedReveal delayedReveal) {
        return new ArrayList<>(optionList.subList(0, Math.min(min, optionList.size())));
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
            boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> result = new ArrayList<>();
        cards.forEach(result::add);
        return result;
    }

    @Override
    public void setCard(CardView card) {}

    @Override
    public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) {}

    @Override
    public void setSelection(Selection selection) {}

    @Override
    public boolean isSelecting() { return false; }

    @Override
    public boolean isGamePaused() { return paused; }

    @Override
    public void setGamePause(boolean pause) { this.paused = pause; }

    @Override
    public PlaybackSpeed getGameSpeed() { return PlaybackSpeed.NORMAL; }

    @Override
    public void setGameSpeed(PlaybackSpeed gameSpeed) {}

    @Override
    public String getDayTime() { return "day"; }

    @Override
    public void updateDayTime(String daytime) {}

    @Override
    public void awaitNextInput() {}

    @Override
    public void cancelAwaitNextInput() {}

    @Override
    public void updateAutoPassPrompt() {}

    @Override
    public void setCurrentPlayer(PlayerView player) {}

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) { return false; }

    @Override
    public void applyYieldUpdate(YieldUpdate update) {}

    @Override
    public void applyMacroState(IMacroSystem.State state) {}

    @Override
    public boolean isNetGame() { return false; }

    @Override
    public void setNetGame() {}

    // --- Utility ---

    private void sendUiEvent(String eventType) {
        sendUiEvent(eventType, new JsonObject());
    }

    /** Set when the session ends, so a game being torn down sends nothing more. */
    private volatile boolean muted = false;

    void mute() {
        muted = true;
    }

    private void sendUiEvent(String eventType, JsonObject data) {
        if (muted) return;
        data.addProperty("eventType", eventType);
        ForgeServer.sendMessage(wsContext, "game_event", data);
    }
}
