package dev.nuclr.plugin.core.imagemagick.bridge.service;

import java.time.Duration;
import java.util.List;

/**
 * Abstraction over executing a child process.
 * Swap out the default implementation in tests via {@link dev.nuclr.plugin.core.imagemagick.bridge.MockMagickRunner}.
 */
public interface MagickRunner {

    /**
     * Runs the given command and waits up to {@code timeout} for it to finish.
     *
     * @param command full argument list (no shell expansion)
     * @param timeout maximum wall-clock time to wait
     * @return captured stdout, stderr, exit code, and elapsed time
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws java.io.IOException  if the process cannot be started or times out
     */
    RunResult run(List<String> command, Duration timeout) throws Exception;
}
