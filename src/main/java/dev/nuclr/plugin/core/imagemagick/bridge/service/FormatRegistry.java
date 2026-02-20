package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Queries {@code magick -list format} and builds the set of file extensions
 * that ImageMagick can <em>read</em> on the current installation.
 *
 * <p>Typical output of {@code magick -list format}:
 * <pre>
 *    Format  Module    Mode  Description
 * -------------------------------------------------------------------------------
 *       AAI* AAI        rw-  AAI Dune image
 *       AI   PDF        -w-  Adobe Illustrator CS2
 *       ARW  DNG        r--  Sony Alpha Raw Image Format
 *       …
 * </pre>
 * The Mode column (third whitespace-separated token) encodes read ({@code r}),
 * write ({@code w}), and multi-image ({@code +/-}) capabilities.
 *
 * <p><b>Parsing strategy:</b> rather than relying on the {@code ---} separator
 * line to find the data section (which varies across IM builds and may be
 * preceded by path or warning messages), every non-empty line is tested against
 * the pattern of a valid Mode token ({@code [r-][w-][+-]}).  Only lines whose
 * third token matches that pattern and whose first character is {@code 'r'} are
 * accepted.  This makes the parser resilient to any preamble text.
 */
@Slf4j
public final class FormatRegistry {


    private final IMBridgeConfig config;
    private final MagickRunner runner;

    public FormatRegistry(IMBridgeConfig config, MagickRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    /**
     * Runs {@code magick -list format} against the given executable and returns
     * the set of readable extensions (lower-cased, decoration stripped).
     */
    public Set<String> loadFormats(Path magickExe) throws Exception {
        RunResult result = runner.run(
                List.of(magickExe.toString(), "-list", "format"),
                Duration.ofSeconds(config.getDetectTimeoutSeconds()));

        if (!result.success()) {
            throw new RuntimeException(
                    "magick -list format failed (exit " + result.exitCode() + "): "
                    + result.firstStderrLine());
        }

        Set<String> formats = parse(result.stdout());

        if (formats.isEmpty()) {
            // Log the raw output so the cause can be diagnosed without re-running
            log.warn("FormatRegistry: parsed 0 readable extensions. "
                    + "Raw output (stdout, first 2000 chars):\n{}",
                    result.stdout().length() > 2000
                            ? result.stdout().substring(0, 2000) + "…"
                            : result.stdout());
            if (!result.stderr().isBlank()) {
                log.warn("FormatRegistry: stderr:\n{}", result.stderr());
            }
        } else {
            log.debug("FormatRegistry: {} readable extensions loaded", formats.size());
        }

        return formats;
    }

    /**
     * Pure parser — package-private for direct unit testing.
     *
     * <p>IM7 has produced at least two column layouts across builds:
     * <ul>
     *   <li>4-column: {@code Format  Module  Mode  Description}</li>
     *   <li>3-column: {@code Format  Mode  Description} (Module column dropped)</li>
     * </ul>
     * Rather than assuming a fixed column index for Mode, the parser scans each
     * line left-to-right from index 1 to find the first token that matches the
     * Mode pattern {@code [r-][w-][+-]}.  This makes it resilient to any column
     * count variation or preamble text.
     *
     * <p>A line is accepted as a format entry only when:
     * <ol>
     *   <li>A Mode token is found (starting from index 1).</li>
     *   <li>The Mode token's first character is {@code 'r'} (readable).</li>
     * </ol>
     */
    static Set<String> parse(String output) {
        Set<String> extensions = new HashSet<>();

        for (String line : output.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }

            // Scan from index 1 to find the Mode token (layout-independent).
            // Mode is exactly 3 chars: read=[r-], write=[w-], multi=[+-]
            String mode = null;
            for (int i = 1; i < parts.length; i++) {
                if (isModeToken(parts[i])) {
                    mode = parts[i];
                    break;
                }
            }
            if (mode == null || mode.charAt(0) != 'r') {
                continue;
            }

            // Strip IM decoration characters (*, !, +, @) from the extension token
            String ext = parts[0].replaceAll("[*!+@]", "").toLowerCase();
            if (!ext.isEmpty()) {
                extensions.add(ext);
            }
        }

        return Collections.unmodifiableSet(extensions);
    }

    /**
     * Returns {@code true} if {@code s} looks like an IM Mode token:
     * exactly 3 characters, read-flag ∈ {r,-}, write-flag ∈ {w,-}, multi-flag ∈ {+,-}.
     */
    private static boolean isModeToken(String s) {
        if (s.length() != 3) {
            return false;
        }
        char r = s.charAt(0), w = s.charAt(1), m = s.charAt(2);
        return (r == 'r' || r == '-')
                && (w == 'w' || w == '-')
                && (m == '+' || m == '-');
    }
}
