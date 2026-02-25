package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Real {@link MagickRunner} implementation that executes a child process via
 * {@link ProcessBuilder}. No shell is involved — the command is passed directly
 * to the OS. Stdout and stderr are drained in separate virtual threads to
 * prevent deadlock on full pipe buffers.
 */
@Slf4j
public final class DefaultMagickRunner implements MagickRunner {

    @Override
    public RunResult run(List<String> command, Duration timeout) throws Exception {
        log.debug("Running: {}", command);
        long startMs = System.currentTimeMillis();

        Process proc = new ProcessBuilder(command).start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Drain streams asynchronously to prevent pipe-buffer deadlock.
        Thread outDrainer = Thread.ofVirtual().start(() -> drain(proc.getInputStream(), stdout));
        Thread errDrainer = Thread.ofVirtual().start(() -> drain(proc.getErrorStream(), stderr));

        try {
            boolean done = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                throw new IOException(
                        "ImageMagick process timed out after " + timeout.toSeconds() + "s");
            }
            outDrainer.join(500);
            errDrainer.join(500);
            return new RunResult(
                    proc.exitValue(),
                    stdout.toString(),
                    stderr.toString(),
                    System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            // throw e;
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
        return null;
    }

    private void drain(InputStream in, StringBuilder sb) {
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines().forEach(line -> sb.append(line).append('\n'));
        } catch (IOException ignored) {
            // stream closed by destroyForcibly — fine
        }
    }
}
