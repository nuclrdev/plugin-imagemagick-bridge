package dev.nuclr.plugin.core.imagemagick.bridge.service;

/**
 * Immutable result of a {@link MagickRunner} invocation.
 *
 * @param exitCode  OS exit code (0 = success)
 * @param stdout    full standard output captured as a string
 * @param stderr    full standard error captured as a string
 * @param elapsedMs wall-clock milliseconds from process start to exit
 */
public record RunResult(int exitCode, String stdout, String stderr, long elapsedMs) {

    /** Returns {@code true} if the exit code is 0. */
    public boolean success() {
        return exitCode == 0;
    }

    /** Returns the first non-blank line of stderr, or an empty string. */
    public String firstStderrLine() {
        return stderr.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
    }
}
