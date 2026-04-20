package undercroft.server;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardDb;
import forge.card.CardType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.util.ImageFetcher;
import forge.util.Lang;
import forge.util.Localizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
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

            // Load dynamic game data (card types, keywords, etc.)
            CardType.CoreType.isCornerCaseType(null); // trigger static init

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
     * Only getAssetsDir() is truly needed for ForgeConstants initialization.
     */
    private static class StubGuiBase implements IGuiBase {
        private final String assetsDir;

        StubGuiBase(String assetsDir) {
            this.assetsDir = assetsDir;
        }

        @Override public String getAssetsDir() { return assetsDir; }
        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "server"; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public void invokeInEdtNow(Runnable r) { r.run(); }
        @Override public void invokeInEdtLater(Runnable r) { r.run(); }
        @Override public void invokeInEdtAndWait(Runnable r) { r.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public ISkinImage getSkinIcon(FSkinProp p) { return null; }
        @Override public ISkinImage getUnskinnedIcon(String s) { return null; }
        @Override public ISkinImage getCardArt(forge.item.PaperCard c) { return null; }
        @Override public ISkinImage getCardArt(forge.item.PaperCard c, boolean b) { return null; }
        @Override public ISkinImage createLayeredImage(forge.item.PaperCard c, FSkinProp p, String s, float f) { return null; }
        @Override public void showBugReportDialog(String t, String x, boolean b) {}
        @Override public void showImageDialog(ISkinImage img, String m, String t) {}
        @Override public int showOptionDialog(String m, String t, FSkinProp i, List<String> o, int d) { return d; }
        @Override public String showInputDialog(String m, String t, FSkinProp i, String init, List<String> opts, boolean num) { return init; }
        @Override public void showCardList(String t, String m, List<forge.item.PaperCard> l) {}
        @Override public boolean showBoxedProduct(String t, String m, List<forge.item.PaperCard> l) { return true; }
        @Override public PaperCard chooseCard(String t, String m, List<PaperCard> l) { return l.isEmpty() ? null : l.get(0); }
        @Override public int showConfirmDialog(String m, String t, String y, String n, boolean def) { return 0; }
        @Override public forge.gamemodes.match.HostedMatch hostMatch() { return null; }
        @Override public void runBackgroundTask(String m, Consumer<forge.gui.interfaces.IProgressBar> c) {}
        @Override public void copyToClipboard(String s) {}
        @Override public void browseToUrl(String url) {}
        @Override public forge.sound.IAudioClip createAudioClip(String f) { return null; }
        @Override public forge.sound.IAudioMusic createAudioMusic(String f) { return null; }
        @Override public void startAltSoundSystem(String f, boolean b) {}
        @Override public void clearImageCache() {}
        @Override public void showSpellShop() {}
        @Override public void enableOverlay() {}
        @Override public void disableOverlay() {}
        @Override public forge.gui.download.GuiDownloadService getGuiDownload() { return null; }
        @Override public org.jupnp.UpnpServiceConfiguration getUPnPServiceConfiguration() { return null; }
    }
}
