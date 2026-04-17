package village.automation.mod.entity;

/**
 * Describes what job a {@link VillagerWorkerEntity} currently holds.
 *
 * <p>Each entry carries:
 * <ul>
 *   <li>{@code displayPrefix} — prepended to the worker's base name when they
 *       are employed (e.g. "Alice" → "Farmer Alice").
 *   <li>{@code title} — short label used in GUIs and name badges.
 * </ul>
 *
 * <p>Jobs are intentionally stub entries for now. Work-loop AI, required
 * workplace block, and tool requirements will be wired up per-job later.
 */
public enum JobType {

    // ── Unemployed ────────────────────────────────────────────────────────────
    UNEMPLOYED    ("",              "Unemployed"   ),

    // ── Production / gathering ────────────────────────────────────────────────
    FARMER        ("Farmer ",       "Farmer"       ),
    MINER         ("Miner ",        "Miner"        ),
    LUMBERJACK    ("Lumberjack ",   "Lumberjack"   ),
    FISHERMAN     ("Fisherman ",    "Fisherman"    ),
    SHEPHERD      ("Shepherd ",     "Shepherd"     ),
    CHEF          ("Chef ",         "Chef"         ),

    // ── Crafting / specialisation ─────────────────────────────────────────────
    BLACKSMITH    ("Blacksmith ",   "Blacksmith"   ),
    SMELTER       ("Smelter ",      "Smelter"      ),
    ENCHANTER     ("Enchanter ",    "Enchanter"    ),
    POTION_BREWER ("Brewer ",       "Potion Brewer"),

    // ── Animal care ───────────────────────────────────────────────────────────
    BEEKEEPER     ("Beekeeper ",    "Beekeeper"    );

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String displayPrefix;
    private final String title;

    JobType(String displayPrefix, String title) {
        this.displayPrefix = displayPrefix;
        this.title         = title;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Prefix prepended to the worker's base name, e.g. {@code "Miner "}. */
    public String getDisplayPrefix() { return displayPrefix; }

    /** Human-readable label for GUIs and name badges, e.g. {@code "Miner"}. */
    public String getTitle()         { return title; }
}
