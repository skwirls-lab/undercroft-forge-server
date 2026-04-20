package undercroft.server;

import forge.CardStorageReader;
import forge.StaticData;
import forge.card.CardDb;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

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

            // Set Forge's resource path constants
            ForgeConstants.PROFILE_DIR = resDir.getAbsolutePath() + File.separator;
            ForgeConstants.CACHE_DIR = ForgeConstants.PROFILE_DIR + "cache" + File.separator;
            ForgeConstants.DB_DIR = ForgeConstants.PROFILE_DIR + "db" + File.separator;

            // Load card data
            // The cardsfolder/ contains individual .txt files for each card
            String editionsDir = resDir.getAbsolutePath() + File.separator + "editions" + File.separator;
            String cardDataDir = resDir.getAbsolutePath() + File.separator + "cardsfolder" + File.separator;
            String blockDataDir = resDir.getAbsolutePath() + File.separator + "blockdata" + File.separator;
            String tokenDataDir = resDir.getAbsolutePath() + File.separator + "token" + File.separator;

            // Initialize the static data singleton
            CardStorageReader.CardFileParser parser = new CardStorageReader.CardFileParser();
            StaticData data = new StaticData(parser, editionsDir, cardDataDir, blockDataDir, tokenDataDir, false);
            StaticData.setInstance(data);

            CardDb commonCards = StaticData.instance().getCommonCards();
            int cardCount = 0;
            for (PaperCard pc : commonCards.getAllCards()) {
                cardCount++;
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("Forge initialized: {} cards loaded in {}ms", cardCount, elapsed);
            initialized = true;

        } catch (Exception e) {
            log.error("Failed to initialize Forge: {}", e.getMessage(), e);
            throw new RuntimeException("Forge initialization failed", e);
        }
    }
}
