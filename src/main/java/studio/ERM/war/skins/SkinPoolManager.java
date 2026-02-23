package studio.ERM.war.skins;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central manager for the ERM Skin Pool System.
 * 
 * This class:
 * - Loads skin filenames from AW2's skin_pack.meta (or scans folders)
 * - Parses config to build named pools of skins
 * - Provides simple API for entities to get random skins from pools
 * - Handles NBT persistence for all entity types
 * 
 * USAGE:
 * <pre>
 * // Apply a random skin from a named pool
 * SkinPoolManager.applySkinFromPool(entity, "soldiers", random);
 * 
 * // Apply based on entity's default pool
 * SkinPoolManager.applySkinFromPool(entity, random);
 * 
 * // Apply based on rival level (legacy support)
 * SkinPoolManager.applySkinForRivalLevel(entity, 5, random);
 * 
 * // Get a skin reference without applying
 * SkinRef skin = SkinPoolManager.pickFromPool("pirates", random);
 * </pre>
 */
public final class SkinPoolManager {

    private static final Logger LOG = LogManager.getLogger("ERM-Skins");

    private SkinPoolManager() {}

    // NBT keys for skin persistence
    public static final String NBT_SKIN_KEY = "ermSkinKey";        // domain:pathNoExt
    public static final String NBT_SKIN_FULL = "ermSkinFull";      // domain:pathWithExt
    public static final String NBT_SKIN_POOL = "ermSkinPool";      // which pool it came from

    // Cached data
    private static volatile List<SkinEntry> ALL_SKINS = null;
    private static final Map<String, List<SkinEntry>> POOL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> ENTITY_POOL_MAP = new ConcurrentHashMap<>();
    private static final Map<Integer, String> RIVAL_LEVEL_MAP = new ConcurrentHashMap<>();

    private static final Pattern PNG_PATTERN = Pattern.compile("([A-Za-z0-9 _\\-]+\\.png)", Pattern.CASE_INSENSITIVE);

    /**
     * A single skin entry with domain and path info.
     */
    public static final class SkinEntry {
        public final String domain;
        public final String folder;
        public final String filename;      // e.g., "xoltec_woman_5.png"
        public final String filenameNoExt; // e.g., "xoltec_woman_5"

        public SkinEntry(String domain, String folder, String filename) {
            this.domain = domain;
            this.folder = folder;
            this.filename = filename;
            this.filenameNoExt = stripExtension(filename);
        }

        /** Returns "domain:folder/filenameNoExt" */
        public String getKey() {
            return domain + ":" + folder + "/" + filenameNoExt;
        }

        /** Returns "domain:folder/filename" (with .png) */
        public String getFullPath() {
            return domain + ":" + folder + "/" + filename;
        }

        /** Returns "folder/filenameNoExt" */
        public String getPathNoExt() {
            return folder + "/" + filenameNoExt;
        }

        /** Returns "folder/filename" */
        public String getPathWithExt() {
            return folder + "/" + filename;
        }

        private static String stripExtension(String name) {
            if (name == null) return "";
            int idx = name.toLowerCase(Locale.ROOT).lastIndexOf(".png");
            return (idx >= 0) ? name.substring(0, idx) : name;
        }
    }

    /**
     * Force rebuild of all pools from current config.
     * Called automatically when config changes.
     */
    public static void rebuildPools() {
        ALL_SKINS = null; // Force reload
        POOL_CACHE.clear();
        ENTITY_POOL_MAP.clear();
        RIVAL_LEVEL_MAP.clear();

        // Parse entity pool mappings
        for (String entry : SkinPoolConfig.entityPools) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                ENTITY_POOL_MAP.put(parts[0].trim(), parts[1].trim());
            }
        }

        // Parse rival level mappings
        for (String entry : SkinPoolConfig.rivalLevelPools) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                try {
                    int level = Integer.parseInt(parts[0].trim());
                    RIVAL_LEVEL_MAP.put(level, parts[1].trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        // Pre-build pools
        for (String entry : SkinPoolConfig.pools) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2) {
                String poolName = parts[0].trim().toLowerCase(Locale.ROOT);
                buildPool(poolName, parts[1].trim());
            }
        }

        if (SkinPoolConfig.debugLogging) {
            LOG.info("[ERM-Skins] Rebuilt pools. Total skins: {}, Pools: {}", 
                getAllSkins().size(), POOL_CACHE.keySet());
        }
    }

    private static void buildPool(String poolName, String prefixesRaw) {
        List<SkinEntry> all = getAllSkins();
        List<SkinEntry> matching = new ArrayList<>();

        String[] prefixes = prefixesRaw.split(",");
        for (String prefix : prefixes) {
            String p = prefix.trim();
            if (p.isEmpty()) continue;

            // Wildcard: include all skins
            if ("*".equals(p)) {
                matching.addAll(all);
                break;
            }

            // Exact match: "exact:filename.png"
            if (p.toLowerCase(Locale.ROOT).startsWith("exact:")) {
                String exactName = p.substring(6).trim();
                for (SkinEntry e : all) {
                    if (e.filename.equalsIgnoreCase(exactName)) {
                        matching.add(e);
                    }
                }
                continue;
            }

            // Prefix match
            String lowerPrefix = p.toLowerCase(Locale.ROOT);
            for (SkinEntry e : all) {
                if (e.filename.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    matching.add(e);
                }
            }
        }

        // De-duplicate while preserving order
        LinkedHashSet<SkinEntry> unique = new LinkedHashSet<>(matching);
        POOL_CACHE.put(poolName, new ArrayList<>(unique));

        if (SkinPoolConfig.debugLogging) {
            LOG.info("[ERM-Skins] Pool '{}': {} skins from prefixes '{}'", 
                poolName, unique.size(), prefixesRaw);
        }
    }

    /**
     * Get all available skins (loads from meta file or scans on first call).
     */
    public static List<SkinEntry> getAllSkins() {
        List<SkinEntry> cached = ALL_SKINS;
        if (cached != null) return cached;

        List<SkinEntry> skins = new ArrayList<>();

        // Load from main source
        skins.addAll(loadSkinsFromMeta(
            SkinPoolConfig.metaFilePath,
            SkinPoolConfig.skinDomain,
            SkinPoolConfig.skinFolder
        ));

        // Load from additional sources
        for (String source : SkinPoolConfig.additionalSkinSources) {
            String[] parts = source.split(":", 2);
            if (parts.length == 2) {
                String domain = parts[0].trim();
                String folder = parts[1].trim();
                String metaPath = "/assets/" + domain + "/" + folder + "/skin_pack.meta";
                skins.addAll(loadSkinsFromMeta(metaPath, domain, folder));
            }
        }

        ALL_SKINS = skins;

        if (SkinPoolConfig.debugLogging) {
            LOG.info("[ERM-Skins] Loaded {} total skins", skins.size());
        }

        return skins;
    }

    private static List<SkinEntry> loadSkinsFromMeta(String metaPath, String domain, String folder) {
        List<SkinEntry> result = new ArrayList<>();

        if (metaPath == null || metaPath.isEmpty()) {
            return result;
        }

        try {
            InputStream is = SkinPoolManager.class.getResourceAsStream(metaPath);
            if (is == null) {
                if (SkinPoolConfig.debugLogging) {
                    LOG.warn("[ERM-Skins] Meta file not found: {}", metaPath);
                }
                return result;
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Matcher m = PNG_PATTERN.matcher(line);
                    while (m.find()) {
                        String filename = m.group(1);
                        if (filename != null && !filename.trim().isEmpty()) {
                            result.add(new SkinEntry(domain, folder, filename.trim()));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (SkinPoolConfig.debugLogging) {
                LOG.error("[ERM-Skins] Error loading meta file: {}", metaPath, t);
            }
        }

        return result;
    }

    // =========================================================================
    // PUBLIC API - Skin Application
    // =========================================================================

    /**
     * Apply a random skin from the named pool to an entity.
     * Works with both ISkinnable entities and any Entity (via NBT).
     * 
     * @param entity The entity to skin
     * @param poolName The pool name (from config)
     * @param rng Random source (nullable, will create new if null)
     * @return true if skin was applied
     */
    public static boolean applySkinFromPool(Entity entity, String poolName, @Nullable Random rng) {
        if (entity == null) return false;
        if (rng == null) rng = new Random();

        SkinEntry skin = pickFromPool(poolName, rng);
        if (skin == null) {
            skin = pickFromPool(SkinPoolConfig.fallbackPool, rng);
        }
        if (skin == null) return false;

        return applySkin(entity, skin, poolName);
    }

    /**
     * Apply a random skin using the entity's default pool.
     * For ISkinnable entities, uses getDefaultPoolName().
     * For other entities, looks up in config entityPools mapping.
     */
    public static boolean applySkinFromPool(Entity entity, @Nullable Random rng) {
        if (entity == null) return false;

        String poolName = getDefaultPoolForEntity(entity);
        return applySkinFromPool(entity, poolName, rng);
    }

    /**
     * Apply a skin based on rival level (legacy support).
     */
    public static boolean applySkinForRivalLevel(Entity entity, int rivalLevel, @Nullable Random rng) {
        if (entity == null) return false;

        // Ensure maps are loaded
        if (RIVAL_LEVEL_MAP.isEmpty()) {
            rebuildPools();
        }

        int level = Math.max(1, Math.min(10, rivalLevel));
        String poolName = RIVAL_LEVEL_MAP.get(level);

        if (poolName == null) {
            poolName = SkinPoolConfig.fallbackPool;
        }

        return applySkinFromPool(entity, poolName, rng);
    }

    /**
     * Pick a random skin from a pool without applying it.
     * 
     * @param poolName The pool name
     * @param rng Random source
     * @return SkinEntry or null if pool is empty/missing
     */
    @Nullable
    public static SkinEntry pickFromPool(String poolName, Random rng) {
        if (poolName == null || poolName.isEmpty()) return null;

        String key = poolName.toLowerCase(Locale.ROOT);

        // Build pool on demand if not cached
        if (!POOL_CACHE.containsKey(key)) {
            for (String entry : SkinPoolConfig.pools) {
                String[] parts = entry.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(poolName)) {
                    buildPool(key, parts[1].trim());
                    break;
                }
            }
        }

        List<SkinEntry> pool = POOL_CACHE.get(key);
        if (pool == null || pool.isEmpty()) return null;

        return pool.get(rng.nextInt(pool.size()));
    }

    /**
     * Get a list of all skins in a pool (for UI display, etc.)
     */
    public static List<SkinEntry> getPoolContents(String poolName) {
        if (poolName == null) return Collections.emptyList();

        String key = poolName.toLowerCase(Locale.ROOT);
        List<SkinEntry> pool = POOL_CACHE.get(key);

        return pool != null ? Collections.unmodifiableList(pool) : Collections.emptyList();
    }

    /**
     * Get names of all defined pools.
     */
    public static Set<String> getPoolNames() {
        // Ensure pools are built
        if (POOL_CACHE.isEmpty()) {
            rebuildPools();
        }
        return Collections.unmodifiableSet(POOL_CACHE.keySet());
    }

    // =========================================================================
    // INTERNAL - Apply skin to entity
    // =========================================================================

    private static boolean applySkin(Entity entity, SkinEntry skin, String poolName) {
        String key = skin.getKey();

        // If entity implements ISkinnable, use that interface
        if (entity instanceof ISkinnable) {
            ISkinnable skinnable = (ISkinnable) entity;
            skinnable.setSkinKey(key);
            skinnable.onSkinApplied(key);
        }

        // Also try reflection for EntityModularCitizen compatibility
        trySetTexturePathReflection(entity, key);

        // Always write to NBT for universal access
        NBTTagCompound data = entity.getEntityData();
        data.setString(NBT_SKIN_KEY, key);
        data.setString(NBT_SKIN_FULL, skin.getFullPath());
        data.setString(NBT_SKIN_POOL, poolName != null ? poolName : "");

        if (SkinPoolConfig.debugLogging) {
            LOG.info("[ERM-Skins] Applied skin '{}' to {} (pool: {})", 
                key, entity.getClass().getSimpleName(), poolName);
        }

        return true;
    }

    private static void trySetTexturePathReflection(Entity entity, String key) {
        // Try setTexturePathNoExt first (new method name)
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("setTexturePathNoExt", String.class);
            m.invoke(entity, key);
            return;
        } catch (Throwable ignored) {}

        // Try setTexturePath (old method name)
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("setTexturePath", String.class);
            m.invoke(entity, key);
        } catch (Throwable ignored) {}
    }

    private static String getDefaultPoolForEntity(Entity entity) {
        // If implements ISkinnable, ask it
        if (entity instanceof ISkinnable) {
            String pool = ((ISkinnable) entity).getDefaultPoolName();
            if (pool != null && !pool.isEmpty()) {
                return pool;
            }
        }

        // Ensure map is loaded
        if (ENTITY_POOL_MAP.isEmpty()) {
            rebuildPools();
        }

        // Look up by class name
        String className = entity.getClass().getName();
        String pool = ENTITY_POOL_MAP.get(className);

        if (pool != null) {
            return pool;
        }

        // Try superclasses
        Class<?> clazz = entity.getClass().getSuperclass();
        while (clazz != null && clazz != Entity.class) {
            pool = ENTITY_POOL_MAP.get(clazz.getName());
            if (pool != null) {
                return pool;
            }
            clazz = clazz.getSuperclass();
        }

        return SkinPoolConfig.fallbackPool;
    }

    // =========================================================================
    // UTILITY - Read skin from entity
    // =========================================================================

    /**
     * Get the skin key from an entity (reads ISkinnable or NBT).
     */
    @Nullable
    public static String getSkinKey(Entity entity) {
        if (entity == null) return null;

        // Try ISkinnable first
        if (entity instanceof ISkinnable) {
            String key = ((ISkinnable) entity).getSkinKey();
            if (key != null && !key.isEmpty()) {
                return key;
            }
        }

        // Fall back to NBT
        NBTTagCompound data = entity.getEntityData();
        if (data.hasKey(NBT_SKIN_KEY)) {
            return data.getString(NBT_SKIN_KEY);
        }

        return null;
    }

    /**
     * Check if an entity has a skin assigned.
     */
    public static boolean hasSkin(Entity entity) {
        String key = getSkinKey(entity);
        return key != null && !key.isEmpty();
    }

    /**
     * Parse a skin key into a SkinEntry.
     * 
     * @param key Format: "domain:folder/filename" (no extension)
     * @return SkinEntry or null if invalid
     */
    @Nullable
    public static SkinEntry parseKey(String key) {
        if (key == null || key.isEmpty()) return null;

        String[] domainPath = key.split(":", 2);
        if (domainPath.length != 2) return null;

        String domain = domainPath[0];
        String path = domainPath[1];

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return null;

        String folder = path.substring(0, lastSlash);
        String filenameNoExt = path.substring(lastSlash + 1);

        return new SkinEntry(domain, folder, filenameNoExt + ".png");
    }
}
