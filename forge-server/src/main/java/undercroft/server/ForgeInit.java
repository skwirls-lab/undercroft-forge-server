package undercroft.server;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardDb;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.gui.download.GuiDownloadService;
import forge.gamemodes.match.HostedMatch;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.Lang;
import forge.util.Localizer;
import org.jupnp.UpnpServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Initializes Forge's static card data so the engine can create games.
 * Must be called once at server startup before any game sessions.
 */
public class ForgeInit {
    private static final Logger log = LoggerFactory.getLogger(ForgeInit.class);
    private static boolean initialized = false;

    public static synchronized void initialize(String forgeResPath) {
        if (initialized) return;

        log.info("Initializing Forge card database from: {}", forgeResPath);
        long start = System.currentTimeMillis();

        try {
            File resDir = new File(forgeResPath);
            if (!resDir.exists()) {
                throw new RuntimeException("Forge resource directory not found: " + resDir.getAbsolutePath());
            }

            String assetsDir = resDir.getParentFile().getAbsolutePath() + File.separator;

            // Set up a stub IGuiBase so ForgeConstants can initialize
            GuiBase.setInterface(new StubGuiBase(assetsDir));

            // Initialize language
            Lang.createInstance("en-US");
            Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);

            // Create card storage readers
            CardStorageReader cardReader = new CardStorageReader(
                    ForgeConstants.CARD_DATA_DIR,
                    CardStorageReader.ProgressObserver.emptyObserver,
                    false);
            CardStorageReader tokenReader = new CardStorageReader(
                    ForgeConstants.TOKEN_DATA_DIR,
                    CardStorageReader.ProgressObserver.emptyObserver,
                    false);

            // Initialize static data (card database)
            StaticData data = new StaticData(
                    cardReader,
                    tokenReader,
                    null, // no custom cards
                    null, // no custom tokens
                    ForgeConstants.EDITIONS_DIR,
                    "", // no custom editions
                    ForgeConstants.BLOCK_DATA_DIR,
                    "", // no set lookup
                    "A", // card art preference
                    true, // enable unknown cards
                    true, // load non-legal cards
                    false, // no custom in conformance
                    false  // no smart art
            );

            CardDb commonCards = data.getCommonCards();
            int cardCount = commonCards.getAllCards().size();

            long elapsed = System.currentTimeMillis() - start;
            log.info("Forge initialized: {} cards loaded in {}ms", cardCount, elapsed);
            initialized = true;

        } catch (Exception e) {
            log.error("Failed to initialize Forge: {}", e.getMessage(), e);
            throw new RuntimeException("Forge initialization failed", e);
        }
    }

    /**
     * Minimal IGuiBase implementation for headless server mode.
     * Matches the exact IGuiBase interface from forge-gui.
     */
    private static class StubGuiBase implements IGuiBase {
        private final String assetsDir;

        StubGuiBase(String assetsDir) {
            this.assetsDir = assetsDir;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "server"; }
        @Override public String getAssetsDir() { return assetsDir; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public void invokeInEdtNow(Runnable r) { r.run(); }
        @Override public void invokeInEdtLater(Runnable r) { r.run(); }
        @Override public void invokeInEdtAndWait(Runnable r) { r.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public ISkinImage getSkinIcon(FSkinProp p) { return null; }
        @Override public ISkinImage getUnskinnedIcon(String p) { return null; }
        @Override public ISkinImage getCardArt(PaperCard c) { return null; }
        @Override public ISkinImage getCardArt(PaperCard c, boolean b) { return null; }
        @Override public ISkinImage createLayeredImage(PaperCard c, FSkinProp bg, String overlay, float opacity) { return null; }
        @Override public void showBugReportDialog(String t, String x, boolean b) {}
        @Override public void showImageDialog(ISkinImage img, String m, String t) {}
        @Override public int showOptionDialog(String m, String t, FSkinProp i, List<String> o, int d) { return d; }
        @Override public String showInputDialog(String m, String t, FSkinProp i, String init, List<String> opts, boolean num) { return init; }
        @Override public <T> List<T> getChoices(String m, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return Collections.emptyList(); }
        @Override public <T> List<T> order(String t, String top, int min, int max, List<T> src, List<T> dest) { return src; }
        @Override public String showFileDialog(String t, String d) { return null; }
        @Override public File getSaveFile(File def) { return def; }
        @Override public void download(GuiDownloadService svc, Consumer<Boolean> cb) { if (cb != null) cb.accept(true); }
        @Override public void refreshSkin() {}
        @Override public void showCardList(String t, String m, List<PaperCard> l) {}
        @Override public boolean showBoxedProduct(String t, String m, List<PaperCard> l) { return true; }
        @Override public PaperCard chooseCard(String t, String m, List<PaperCard> l) { return l.isEmpty() ? null : l.get(0); }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public void copyToClipboard(String s) {}
        @Override public void browseToUrl(String url) {}
        @Override public boolean isSupportedAudioFormat(File f) { return false; }
        @Override public IAudioClip createAudioClip(String f) { return null; }
        @Override public IAudioMusic createAudioMusic(String f) { return null; }
        @Override public void startAltSoundSystem(String f, boolean b) {}
        @Override public void clearImageCache() {}
        @Override public void showSpellShop() {}
        @Override public void showBazaar() {}
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public void runBackgroundTask(String m, Runnable r) { r.run(); }
        @Override public String encodeSymbols(String s, boolean fmt) { return s; }
        @Override public void preventSystemSleep(boolean b) {}
        @Override public float getScreenScale() { return 1.0f; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
