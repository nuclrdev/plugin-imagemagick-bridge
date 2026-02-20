package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Locates and verifies the ImageMagick 7 {@code magick} executable.
 * Search order:
 * <ol>
 *   <li>Configured {@code executablePath} (if set)</li>
 *   <li>Entries in the {@code PATH} environment variable</li>
 *   <li>OS-specific common installation directories</li>
 * </ol>
 * Each candidate is verified by running {@code magick -version} and confirming
 * that the reported version starts with {@code 7}.
 */
@Slf4j
public final class MagickLocator {

    // Windows: official installer drops a directory under Program Files
    private static final List<String> WINDOWS_PARENT_DIRS = List.of(
            "C:\\Program Files",
            "C:\\Program Files (x86)"
    );
    private static final String WINDOWS_DIR_PREFIX = "ImageMagick";

    // Unix: common binary locations (Homebrew on macOS, distro packages on Linux)
    private static final List<String> UNIX_CANDIDATES = List.of(
            "/opt/homebrew/bin/magick",   // macOS – Apple Silicon
            "/usr/local/bin/magick",       // macOS – Intel / homebrew; Linux local
            "/usr/bin/magick",             // Linux – distro package
            "/snap/bin/magick"             // Linux – snap package
    );

    /** Result of a successful detection. */
    public record DetectedMagick(Path executable, String version) {}

    private final IMBridgeConfig config;
    private final MagickRunner runner;

    public MagickLocator(IMBridgeConfig config, MagickRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    /**
     * Searches for a usable {@code magick} binary and returns the first verified one.
     *
     * <p>Search order:
     * <ol>
     *   <li>Path saved by the user in a previous session (via {@link MagickPreferences})</li>
     *   <li>Configured {@code executablePath} from {@code imagemagick-bridge.properties}</li>
     *   <li>Unqualified {@code magick} / {@code magick.exe} — ProcessBuilder resolves it
     *       via the OS PATH; the most reliable approach for standard installations</li>
     *   <li>Manual scan of {@code PATH} entries (resolved absolute paths)</li>
     *   <li>OS-specific well-known installation directories</li>
     * </ol>
     */
    public Optional<DetectedMagick> locate() {
        // 0. User-saved preference from a previous manual selection
        Optional<Path> saved = MagickPreferences.loadSavedPath();
        if (saved.isPresent()) {
            Optional<String> v = verify(saved.get());
            if (v.isPresent()) {
                log.info("Using previously saved magick path: {}", saved.get());
                return Optional.of(new DetectedMagick(saved.get(), v.get()));
            }
            log.warn("Saved magick path '{}' is no longer valid, clearing preference",
                    saved.get());
            MagickPreferences.clearPath();
        }

        // 1. Configured path from properties file (absolute; file existence is checked)
        if (config.getExecutablePath() != null) {
            Path p = Path.of(config.getExecutablePath());
            Optional<String> v = verify(p);
            if (v.isPresent()) {
                return Optional.of(new DetectedMagick(p, v.get()));
            }
            log.warn("Configured executablePath '{}' is not a valid IM7 binary", p);
        }

        // 2. Unqualified name — ProcessBuilder / OS resolves via PATH.
        //    This is the common case for standard installations (Windows installer, Homebrew).
        //    Intentionally skips the Files.isRegularFile() guard.
        String unqualified = isWindows() ? "magick.exe" : "magick";
        Optional<String> vUnqualified = verifyCommand(unqualified);
        if (vUnqualified.isPresent()) {
            log.info("Found ImageMagick {} via OS PATH ('{}')", vUnqualified.get(), unqualified);
            return Optional.of(new DetectedMagick(Path.of(unqualified), vUnqualified.get()));
        }

        // 3. Manual PATH scan (resolved absolute paths, then file + version check)
        Optional<Path> fromPath = findOnPath();
        if (fromPath.isPresent()) {
            Optional<String> v = verify(fromPath.get());
            if (v.isPresent()) {
                return Optional.of(new DetectedMagick(fromPath.get(), v.get()));
            }
        }

        // 4. OS-specific well-known installation directories
        for (Path candidate : osSpecificCandidates()) {
            Optional<String> v = verify(candidate);
            if (v.isPresent()) {
                return Optional.of(new DetectedMagick(candidate, v.get()));
            }
        }

        return Optional.empty();
    }

    /**
     * Public entry point for verifying a specific path, e.g. one chosen by the user
     * via a file dialog.  Returns the IM version string if the path is a valid IM7
     * binary, or empty otherwise.
     */
    public Optional<String> verifyAndGetVersion(Path exe) {
        return verify(exe);
    }

    // -------------------------------------------------------------------------

    /**
     * Verifies an absolute {@code Path}: checks the file exists, runs
     * {@code <exe> -version}, and returns the version string if IM7.
     */
    private Optional<String> verify(Path exe) {
        if (!Files.isRegularFile(exe)) {
            return Optional.empty();
        }
        return verifyCommand(exe.toString());
    }

    /**
     * Runs {@code <command> -version} without a file-existence pre-check.
     * Used for unqualified names like {@code "magick"} whose resolution is
     * delegated to the OS via {@link ProcessBuilder}.
     * Returns the version string (e.g. {@code "7.1.1-38"}) if IM7, empty otherwise.
     */
    private Optional<String> verifyCommand(String command) {
        try {
            RunResult r = runner.run(
                    List.of(command, "-version"),
                    Duration.ofSeconds(config.getDetectTimeoutSeconds()));
            if (r.success()) {
                String version = parseVersion(r.stdout());
                if (version != null && version.startsWith("7")) {
                    return Optional.of(version);
                }
                log.debug("'{}' reports version '{}' (need 7+)", command, version);
            } else {
                log.debug("'{}' -version failed: {}", command, r.firstStderrLine());
            }
        } catch (Exception e) {
            log.debug("'{}' not usable: {}", command, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Parses the IM version string from the first line of {@code magick -version} output.
     * Example: {@code "Version: ImageMagick 7.1.1-38 Q16-HDRI …"} → {@code "7.1.1-38"}.
     */
    static String parseVersion(String versionOutput) {
        for (String line : versionOutput.lines().toList()) {
            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("ImageMagick".equals(parts[i])) {
                    return parts[i + 1];
                }
            }
        }
        return null;
    }

    private Optional<Path> findOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return Optional.empty();
        }
        String exeName = isWindows() ? "magick.exe" : "magick";
        return Arrays.stream(pathEnv.split(File.pathSeparator))
                .map(dir -> Path.of(dir.trim(), exeName))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private List<Path> osSpecificCandidates() {
        List<Path> candidates = new ArrayList<>();
        if (isWindows()) {
            for (String parent : WINDOWS_PARENT_DIRS) {
                Path parentPath = Path.of(parent);
                if (!Files.isDirectory(parentPath)) {
                    continue;
                }
                try (var stream = Files.list(parentPath)) {
                    stream.filter(p -> p.getFileName().toString().startsWith(WINDOWS_DIR_PREFIX))
                            // Prefer newest version (reverse lexicographic order)
                            .sorted(Comparator.comparing(Path::getFileName).reversed())
                            .map(p -> p.resolve("magick.exe"))
                            .forEach(candidates::add);
                } catch (IOException e) {
                    log.debug("Cannot list {}: {}", parent, e.getMessage());
                }
            }
        } else {
            for (String path : UNIX_CANDIDATES) {
                candidates.add(Path.of(path));
            }
        }
        return candidates;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }
}
