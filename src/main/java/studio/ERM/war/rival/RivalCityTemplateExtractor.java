package studio.ERM.war.rival;

import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Extracts .aws template files from the bundled MohawkyPack zip into AW2's
 * config directory so that AW2's TemplateLoader can discover them at startup.
 *
 * Call {@link #extractIfNeeded()} during FMLPreInitializationEvent — before AW2
 * scans its template directories.
 *
 * AW2 1.12 loads custom templates from:
 *   <minecraft>/config/ancientwarfare/structure/templates/
 * Town-gen pieces from:
 *   <minecraft>/config/ancientwarfare/structure/town_gen/
 *
 * This extractor handles multiple source strategies:
 *   1) A zip/jar on the classpath (mod dependency)
 *   2) A zip file co-located in the mods/ folder
 *   3) A zip file in config/ancientwarfare/
 *   4) An already-extracted directory
 */
public final class RivalCityTemplateExtractor {

    private static final Logger LOG = LogManager.getLogger("RivalCity-TemplateExtractor");

    /** Marker file we drop after a successful extraction so we don't redo it every launch. */
    private static final String MARKER_FILE = ".rivalcity_extracted_v72";

    /** The name/pattern of the MohawkyPack zip we're looking for. */
    private static final String PACK_ZIP_NAME = "MohawkyPack1_12_2V72.zip";
    private static final String PACK_ZIP_PATTERN = "MohawkyPack";

    private RivalCityTemplateExtractor() {}

    // ════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ════════════════════════════════════════════════════════════════════

    /**
     * Call this during FMLPreInitializationEvent.
     * Extracts MohawkyPack templates into AW2's config directory if not already done.
     *
     * @return the number of .aws files extracted, or 0 if already done / not needed.
     */
    public static int extractIfNeeded() {
        try {
            File mcDir = getMinecraftDir();
            File aw2TemplateDir = new File(mcDir, "config/ancientwarfare/structure/templates");
            File aw2TownDir     = new File(mcDir, "config/ancientwarfare/structure/town_gen");

            // Check marker
            File marker = new File(aw2TemplateDir, MARKER_FILE);
            if (marker.exists()) {
                LOG.info("[RivalCity] Templates already extracted (marker found). Skipping.");
                return 0;
            }

            // Find the pack zip
            File zipFile = locatePackZip(mcDir);
            if (zipFile == null) {
                LOG.warn("[RivalCity] Could not locate MohawkyPack zip. Tried:");
                LOG.warn("[RivalCity]   - mods/ folder");
                LOG.warn("[RivalCity]   - config/ancientwarfare/ folder");
                LOG.warn("[RivalCity]   - classpath resources");
                LOG.warn("[RivalCity] Templates will NOT be available unless manually installed.");
                return 0;
            }

            LOG.info("[RivalCity] Found MohawkyPack at: {}", zipFile.getAbsolutePath());

            int count = extractFromZip(zipFile, aw2TemplateDir, aw2TownDir);

            if (count > 0) {
                // Write marker
                aw2TemplateDir.mkdirs();
                marker.createNewFile();
                LOG.info("[RivalCity] Extracted {} .aws templates successfully.", count);
            }

            return count;

        } catch (Throwable t) {
            LOG.error("[RivalCity] Failed to extract templates", t);
            return 0;
        }
    }

    /**
     * Force re-extraction by deleting the marker file.
     * Call this if the user updates the pack and needs to refresh.
     */
    public static boolean forceReExtract() {
        try {
            File mcDir = getMinecraftDir();
            File marker = new File(mcDir, "config/ancientwarfare/structure/templates/" + MARKER_FILE);
            if (marker.exists()) {
                marker.delete();
                LOG.info("[RivalCity] Marker deleted. Templates will re-extract on next launch.");
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOG.error("[RivalCity] Failed to delete marker", t);
            return false;
        }
    }

    /**
     * Diagnostic: returns info about the extraction state.
     */
    public static List<String> diagnoseExtraction() {
        List<String> report = new ArrayList<>();
        try {
            File mcDir = getMinecraftDir();
            report.add("Minecraft dir: " + mcDir.getAbsolutePath());

            File aw2Dir = new File(mcDir, "config/ancientwarfare");
            report.add("AW2 config dir exists: " + aw2Dir.exists());

            File templateDir = new File(aw2Dir, "structure/templates");
            report.add("AW2 template dir exists: " + templateDir.exists());

            if (templateDir.exists()) {
                int awsCount = countAwsFiles(templateDir);
                report.add("  .aws files in template dir: " + awsCount);
            }

            File townDir = new File(aw2Dir, "structure/town_gen");
            report.add("AW2 town_gen dir exists: " + townDir.exists());

            if (townDir.exists()) {
                int awsCount = countAwsFiles(townDir);
                report.add("  .aws files in town_gen dir: " + awsCount);
            }

            File marker = new File(templateDir, MARKER_FILE);
            report.add("Extraction marker present: " + marker.exists());

            File zipFile = locatePackZip(mcDir);
            report.add("MohawkyPack zip found: " + (zipFile != null ? zipFile.getAbsolutePath() : "NOT FOUND"));

            if (zipFile != null) {
                try (ZipFile zf = new ZipFile(zipFile)) {
                    int total = 0;
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().endsWith(".aws")) total++;
                    }
                    report.add("  .aws files in zip: " + total);
                }
            }

        } catch (Throwable t) {
            report.add("ERROR: " + t.getMessage());
        }
        return report;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Zip location strategies
    // ════════════════════════════════════════════════════════════════════

    private static File locatePackZip(File mcDir) {
        // Strategy 1: Look in mods/ folder
        File modsDir = new File(mcDir, "mods");
        File found = searchDirForZip(modsDir);
        if (found != null) return found;

        // Strategy 2: Look in config/ancientwarfare/
        File aw2Config = new File(mcDir, "config/ancientwarfare");
        found = searchDirForZip(aw2Config);
        if (found != null) return found;

        // Strategy 3: Look in minecraft root
        found = searchDirForZip(mcDir);
        if (found != null) return found;

        // Strategy 4: Try to find it as a classpath resource
        found = findOnClasspath();
        if (found != null) return found;

        // Strategy 5: Look inside jar files in mods/ for the embedded zip
        found = findInsideModJars(modsDir);
        if (found != null) return found;

        return null;
    }

    private static File searchDirForZip(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        // Exact name first
        for (File f : files) {
            if (f.getName().equals(PACK_ZIP_NAME)) return f;
        }
        // Pattern match
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.contains("mohawky") && (name.endsWith(".zip") || name.endsWith(".jar"))) {
                return f;
            }
        }
        return null;
    }

    private static File findOnClasspath() {
        try {
            // Try loading the zip as a resource
            URL url = RivalCityTemplateExtractor.class.getResource("/" + PACK_ZIP_NAME);
            if (url != null) {
                File f = new File(url.toURI());
                if (f.exists()) return f;
            }

            // Try via classloader
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = RivalCityTemplateExtractor.class.getClassLoader();
            url = cl.getResource(PACK_ZIP_NAME);
            if (url != null && "file".equals(url.getProtocol())) {
                File f = new File(url.toURI());
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * If the MohawkyPack zip is embedded inside the mod's own jar, extract it to a temp location.
     */
    private static File findInsideModJars(File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) return null;
        File[] jars = modsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return null;

        for (File jar : jars) {
            try (ZipFile zf = new ZipFile(jar)) {
                // Look for the zip inside the jar
                ZipEntry entry = zf.getEntry(PACK_ZIP_NAME);
                if (entry == null) {
                    // Search more broadly
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().contains("MohawkyPack") && e.getName().endsWith(".zip")) {
                            entry = e;
                            break;
                        }
                    }
                }
                if (entry == null) {
                    // Also check if .aws files are directly in the jar
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    boolean hasAws = false;
                    while (entries.hasMoreElements()) {
                        if (entries.nextElement().getName().endsWith(".aws")) {
                            hasAws = true;
                            break;
                        }
                    }
                    if (hasAws) {
                        LOG.info("[RivalCity] Found .aws files directly inside jar: {}", jar.getName());
                        return jar; // The jar itself contains .aws files
                    }
                    continue;
                }

                // Extract the embedded zip to temp
                File tempZip = File.createTempFile("mohawkypack_", ".zip");
                tempZip.deleteOnExit();
                try (InputStream is = zf.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(tempZip)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                }
                LOG.info("[RivalCity] Extracted embedded MohawkyPack from jar: {}", jar.getName());
                return tempZip;

            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Extraction
    // ════════════════════════════════════════════════════════════════════

    private static int extractFromZip(File zipFile, File templateDir, File townDir) throws IOException {
        int count = 0;

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                if (!name.endsWith(".aws")) continue;

                // Determine destination based on path
                // normal/ → templates directory (world gen structures)
                // towns/  → town_gen directory (AW2 town building pieces)
                // structures/ → templates directory

                File destDir;
                String relativePath;

                if (name.contains("/normal/")) {
                    destDir = templateDir;
                    // Preserve subfolder structure after "normal/"
                    int idx = name.indexOf("/normal/");
                    relativePath = name.substring(idx + "/normal/".length());
                } else if (name.contains("/towns/")) {
                    destDir = townDir;
                    int idx = name.indexOf("/towns/");
                    relativePath = name.substring(idx + "/towns/".length());
                } else if (name.contains("/structures/")) {
                    destDir = templateDir;
                    int idx = name.indexOf("/structures/");
                    relativePath = name.substring(idx + "/structures/".length());
                } else {
                    // Flat .aws file — just put it in templates
                    destDir = templateDir;
                    relativePath = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                }

                File destFile = new File(destDir, relativePath);

                // Skip if already exists (don't overwrite user customizations)
                if (destFile.exists()) continue;

                destFile.getParentFile().mkdirs();

                try (InputStream is = zf.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                }

                count++;
            }
        }

        return count;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════════════════════════════

    private static File getMinecraftDir() {
        // Forge sets the minecraft home directory; try multiple approaches
        try {
            // Forge 1.12 standard approach
            File mcDir = Launch.minecraftHome;
            if (mcDir != null) return mcDir;
        } catch (Throwable ignored) {}

        // Fallback: use current working directory
        File cwd = new File(System.getProperty("user.dir", "."));

        // Verify it looks like a MC directory
        if (new File(cwd, "mods").exists() || new File(cwd, "config").exists()) {
            return cwd;
        }

        return cwd;
    }

    private static int countAwsFiles(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += countAwsFiles(f);
            } else if (f.getName().endsWith(".aws")) {
                count++;
            }
        }
        return count;
    }
}
