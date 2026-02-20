package dev.nuclr.plugin.core.imagemagick.bridge.config;

import java.io.InputStream;
import java.util.Properties;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin configuration loaded from {@code imagemagick-bridge.properties} on the classpath.
 * All fields are read-only after construction; fall back to safe defaults if the file is absent.
 */
@Slf4j
@Getter
public final class IMBridgeConfig {

    private static final String PROPS_RESOURCE = "imagemagick-bridge.properties";

    /** Full path to the {@code magick} executable, or {@code null} for auto-detection. */
    private final String executablePath;

    /** Seconds before an image-conversion process is forcibly killed. */
    private final int conversionTimeoutSeconds;

    /** Seconds before a detection / format-list query is forcibly killed. */
    private final int detectTimeoutSeconds;

    /** Maximum input file size in bytes; 0 means no limit. */
    private final long maxInputSizeBytes;

    /** Value passed to {@code -limit memory}. */
    private final String memoryLimit;

    /** Value passed to {@code -limit map}. */
    private final String mapLimit;

    /** Value passed to {@code -limit disk}. */
    private final String diskLimit;

    /** Value passed to {@code -limit thread}. */
    private final int threadLimit;

    /** Maximum pixel dimension (width and height) for {@code -resize NxN>}. */
    private final int maxPixelDimension;

    public IMBridgeConfig() {
        Properties p = loadProps();
        String rawPath = p.getProperty("executablePath", "").trim();
        executablePath        = rawPath.isEmpty() ? null : rawPath;
        conversionTimeoutSeconds = parseInt(p, "conversionTimeoutSeconds", 30);
        detectTimeoutSeconds     = parseInt(p, "detectTimeoutSeconds",     5);
        maxInputSizeBytes        = parseLong(p, "maxInputSizeBytes",  536_870_912L);
        memoryLimit              = p.getProperty("memoryLimit", "").trim();
        mapLimit                 = p.getProperty("mapLimit",    "").trim();
        diskLimit                = p.getProperty("diskLimit",   "").trim();
        threadLimit              = parseInt(p, "threadLimit",          1);
        maxPixelDimension        = parseInt(p, "maxPixelDimension",   2048);
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        try (InputStream in = IMBridgeConfig.class
                .getClassLoader()
                .getResourceAsStream(PROPS_RESOURCE)) {
            if (in != null) {
                p.load(in);
            } else {
                log.debug("{} not found on classpath, using built-in defaults", PROPS_RESOURCE);
            }
        } catch (Exception e) {
            log.warn("Could not load {}: {}", PROPS_RESOURCE, e.getMessage());
        }
        return p;
    }

    private static int parseInt(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}, using default {}", key, def);
            return def;
        }
    }

    private static long parseLong(Properties p, String key, long def) {
        try {
            return Long.parseLong(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}, using default {}", key, def);
            return def;
        }
    }
}
