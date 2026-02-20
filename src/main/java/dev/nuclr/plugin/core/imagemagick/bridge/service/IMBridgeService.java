package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Central service that orchestrates ImageMagick detection, format discovery,
 * caching, and image conversion.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Construct with a {@link IMBridgeConfig} and a {@link MagickRunner}.</li>
 *   <li>Call {@link #init()} (typically on a background thread) — locates the
 *       {@code magick} binary and populates the supported-extension set.</li>
 *   <li>Call {@link #convertToPng(QuickViewItem)} from any thread for each preview
 *       request; the result is a path to a cached PNG that can be read with
 *       {@code ImageIO.read()}.</li>
 * </ol>
 *
 * <p>All public fields that are written by {@link #init()} are {@code volatile}
 * so callers on other threads always see the final values once init completes.
 */
@Slf4j
public final class IMBridgeService {

    private final IMBridgeConfig config;
    private final MagickRunner runner;
    private final MagickLocator locator;
    private final FormatRegistry formatRegistry;

    // Written by init(), read from any thread
    private volatile Path magickExe;
    private volatile String imVersion;
    private volatile Set<String> supportedExtensions = Set.of();
    private volatile boolean initFailed = false;
    private volatile String initError = null;

    public IMBridgeService(IMBridgeConfig config, MagickRunner runner) {
        this.config = config;
        this.runner = runner;
        this.locator = new MagickLocator(config, runner);
        this.formatRegistry = new FormatRegistry(config, runner);
    }

    // -------------------------------------------------------------------------
    // Initialisation

    /**
     * Locates the {@code magick} binary and builds the supported-extension set.
     * Safe to call from a background thread. Idempotent — a second call is a no-op
     * if already successfully initialised.
     */
    public void init() {
        if (magickExe != null) {
            return; // already ready
        }
        try {
            log.info("ImageMagick Bridge: initialising...");
            Optional<MagickLocator.DetectedMagick> detected = locator.locate();
            if (detected.isEmpty()) {
                setFailed("ImageMagick 7 not found. "
                        + "Install ImageMagick 7+ or set executablePath in "
                        + "imagemagick-bridge.properties.");
                return;
            }
            initWithPath(detected.get().executable(), detected.get().version());
        } catch (Exception e) {
            setFailed("ImageMagick Bridge init error: " + e.getMessage());
            log.error("ImageMagick Bridge init failed", e);
        }
    }

    /**
     * Verifies the user-supplied path, persists it to {@link MagickPreferences},
     * and completes initialisation.  Called after the user picks a file via the
     * file-chooser dialog.
     *
     * @throws IllegalArgumentException if the path is not a valid IM7 binary
     */
    public void initWithUserSelectedPath(Path path) throws Exception {
        if (isReady()) {
            return;
        }
        Optional<String> version = locator.verifyAndGetVersion(path);
        if (version.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + path.getFileName() + "' is not a valid ImageMagick 7 executable.");
        }
        MagickPreferences.savePath(path);
        initWithPath(path, version.get());
    }

    /**
     * Package-private: initialises with a known executable path, bypassing
     * the auto-detection step.  Used by integration tests with a mock runner.
     */
    void initWithPath(Path exe, String version) throws Exception {
        this.magickExe = exe;
        this.imVersion = version;
        Set<String> formats = formatRegistry.loadFormats(exe);
        this.supportedExtensions = formats;
        log.info("ImageMagick Bridge ready: {} formats, IM {}", formats.size(), version);
    }

    private void setFailed(String message) {
        initFailed = true;
        initError = message;
        log.warn("{}", message);
    }

    // -------------------------------------------------------------------------
    // Queries

    public boolean isReady()      { return magickExe != null; }
    public boolean isInitFailed() { return initFailed; }
    public String  getInitError() { return initError; }

    /** Returns the (possibly empty) set of supported extensions; never {@code null}. */
    public Set<String> getSupportedExtensions() {
        return supportedExtensions;
    }

    // -------------------------------------------------------------------------
    // Conversion

    /**
     * Converts the item's content to a PNG using ImageMagick and returns the
     * decoded image.  A temporary input file and a temporary output file are
     * created for the duration of the call and deleted before returning.
     *
     * @throws IllegalStateException    if the service is not yet ready
     * @throws IllegalArgumentException if the file exceeds the configured size limit
     * @throws IOException              on I/O or conversion failure
     */
    public BufferedImage convertToPng(QuickViewItem item) throws Exception {
        if (!isReady()) {
            throw new IllegalStateException(
                    initError != null ? initError : "ImageMagick not yet initialised");
        }

        // Enforce input-size guard (best-effort — sizeBytes may be 0 if unknown)
        long reportedSize = item.sizeBytes();
        if (config.getMaxInputSizeBytes() > 0
                && reportedSize > 0
                && reportedSize > config.getMaxInputSizeBytes()) {
            throw new IllegalArgumentException(String.format(
                    "File too large: %.1f MB (limit %.1f MB)",
                    reportedSize / 1_048_576.0,
                    config.getMaxInputSizeBytes() / 1_048_576.0));
        }

        Path tempInput  = Files.createTempFile("imbridge-in-",  "." + item.extension());
        Path tempOutput = Files.createTempFile("imbridge-out-", ".png");
        try {
            // Write item stream to the temp input file
            try (InputStream in = item.openStream()) {
                Files.copy(in, tempInput, StandardCopyOption.REPLACE_EXISTING);
            }

            // Enforce post-write size guard if sizeBytes was unreported
            if (config.getMaxInputSizeBytes() > 0) {
                long actualSize = Files.size(tempInput);
                if (actualSize > config.getMaxInputSizeBytes()) {
                    throw new IllegalArgumentException(String.format(
                            "File too large: %.1f MB (limit %.1f MB)",
                            actualSize / 1_048_576.0,
                            config.getMaxInputSizeBytes() / 1_048_576.0));
                }
            }

            runConversion(tempInput, tempOutput, item.name());

            BufferedImage img = ImageIO.read(tempOutput.toFile());
            if (img == null) {
                throw new IOException(
                        "ImageIO could not decode the converted PNG for: " + item.name());
            }
            return img;

        } finally {
            deleteSilently(tempInput);
            deleteSilently(tempOutput);
        }
    }

    // -------------------------------------------------------------------------
    // Internals

    private static void deleteSilently(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // best-effort; temp files are cleaned up by the OS on reboot anyway
        }
    }

    private void runConversion(Path input, Path output, String itemName) throws Exception {
        List<String> cmd = buildConversionCommand(input, output);
        log.debug("Converting {}: {}", itemName, cmd);

        RunResult result = runner.run(
                cmd, Duration.ofSeconds(config.getConversionTimeoutSeconds()));

        if (!result.success()) {
            log.error("IM conversion failed for '{}'. Exit={}, stderr={}",
                    itemName, result.exitCode(), result.stderr());
            throw new IOException("Conversion failed: " + result.firstStderrLine());
        }

        if (!Files.exists(output) || Files.size(output) == 0) {
            throw new IOException("ImageMagick produced no output for: " + itemName);
        }
    }

    private List<String> buildConversionCommand(Path input, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(magickExe.toString());
        // Resource limits — placed before the input file per IM7 convention.
        // Only emit a -limit flag when the configured value is non-blank; omitting
        // a flag lets ImageMagick use its own defaults (and policy.xml), which avoids
        // CacheResourcesExhausted when our value would be lower than policy.xml allows.
        addLimitIfSet(cmd, "memory", config.getMemoryLimit());
        addLimitIfSet(cmd, "map",    config.getMapLimit());
        addLimitIfSet(cmd, "disk",   config.getDiskLimit());
        cmd.addAll(List.of("-limit", "thread", String.valueOf(config.getThreadLimit())));
        // Input — [0] selects the first frame/layer only, which prevents ImageMagick from
        // producing multiple output files (e.g. output-0.png, output-1.png) for multi-layer
        // formats like PSD, TIFF, or animated GIF.  For single-frame images it is a no-op.
        cmd.add(input + "[0]");
        // Resize: only shrink, never enlarge (the > modifier)
        cmd.addAll(List.of("-resize",
                config.getMaxPixelDimension() + "x" + config.getMaxPixelDimension() + ">"));
        // Output with explicit PNG: prefix so IM doesn't guess the format
        cmd.add("PNG:" + output);
        return cmd;
    }

    private static void addLimitIfSet(List<String> cmd, String resource, String value) {
        if (value != null && !value.isBlank()) {
            cmd.addAll(List.of("-limit", resource, value));
        }
    }
}
