package dev.nuclr.plugin.core.imagemagick.bridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import dev.nuclr.plugin.core.imagemagick.bridge.service.MagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.service.RunResult;

/**
 * Test double for {@link MagickRunner}.
 *
 * <p>Behaviour by command type:
 * <ul>
 *   <li>{@code -version} → returns {@link #versionOutput}</li>
 *   <li>{@code -list format} → returns {@link #formatOutput}</li>
 *   <li>Anything else (conversion) → creates a 1×1 black PNG at the path indicated
 *       by the last argument (must start with {@code PNG:})</li>
 * </ul>
 *
 * <p>Set {@link #setAlwaysFail(boolean)} to {@code true} to make every call return
 * exit code 1 (simulates IM not found / broken).
 */
public class MockMagickRunner implements MagickRunner {

    // -- Configurable outputs -------------------------------------------------

    private String versionOutput =
            "Version: ImageMagick 7.1.0-0 Q16 x86_64 https://imagemagick.org\n" +
            "Copyright: (C) 1999 ImageMagick Studio LLC\n";

    private String formatOutput = "";  // empty → no readable formats

    private boolean alwaysFail = false;

    // -- Introspection --------------------------------------------------------

    private int callCount = 0;
    private final List<List<String>> recordedCommands = new ArrayList<>();

    // -- MagickRunner ---------------------------------------------------------

    @Override
    public RunResult run(List<String> command, Duration timeout) throws Exception {
        callCount++;
        recordedCommands.add(List.copyOf(command));

        if (alwaysFail) {
            return new RunResult(1, "", "mock failure", 1L);
        }

        if (command.contains("-version")) {
            return new RunResult(0, versionOutput, "", 1L);
        }
        if (command.contains("-list")) {
            return new RunResult(0, formatOutput, "", 1L);
        }

        // Conversion: find "PNG:<outputPath>" argument and write a minimal PNG there
        for (String arg : command) {
            if (arg.startsWith("PNG:")) {
                Path outputPath = Path.of(arg.substring(4));
                Files.createDirectories(outputPath.getParent());
                Files.write(outputPath, createMinimalPng());
                return new RunResult(0, "", "", 10L);
            }
        }

        return new RunResult(0, "", "", 1L);
    }

    // -- Helpers --------------------------------------------------------------

    /** Produces a valid 1×1 black PNG using javax.imageio — no external deps. */
    private static byte[] createMinimalPng() throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x000000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    // -- Setters / getters for test assertions --------------------------------

    public void setVersionOutput(String versionOutput) {
        this.versionOutput = versionOutput;
    }

    public void setFormatOutput(String formatOutput) {
        this.formatOutput = formatOutput;
    }

    public void setAlwaysFail(boolean alwaysFail) {
        this.alwaysFail = alwaysFail;
    }

    public int getCallCount() {
        return callCount;
    }

    public List<List<String>> getRecordedCommands() {
        return Collections.unmodifiableList(recordedCommands);
    }
}
