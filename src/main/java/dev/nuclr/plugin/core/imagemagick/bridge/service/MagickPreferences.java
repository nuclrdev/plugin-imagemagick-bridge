package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.nio.file.Path;
import java.util.Optional;
import java.util.prefs.Preferences;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists the user-selected ImageMagick executable path across sessions using
 * {@link java.util.prefs.Preferences} (Windows registry / Unix user-prefs file).
 *
 * <p>The stored value is checked by {@link MagickLocator} as the first step so
 * that a manually chosen path is tried before the auto-detection scan.
 */
@Slf4j
public final class MagickPreferences {

    private static final Preferences PREFS =
            Preferences.userNodeForPackage(MagickPreferences.class);

    private static final String KEY = "executablePath";

    private MagickPreferences() {}

    /** Returns the previously saved executable path, or empty if none is stored. */
    public static Optional<Path> loadSavedPath() {
        String value = PREFS.get(KEY, null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value));
    }

    /** Persists the given path so future sessions skip auto-detection. */
    public static void savePath(Path path) {
        PREFS.put(KEY, path.toString());
        log.debug("Saved magick path preference: {}", path);
    }

    /** Removes any stored path (e.g. when a saved path is found to be invalid). */
    public static void clearPath() {
        PREFS.remove(KEY);
        log.debug("Cleared magick path preference");
    }
}
