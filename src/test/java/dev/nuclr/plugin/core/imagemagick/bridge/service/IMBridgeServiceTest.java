package dev.nuclr.plugin.core.imagemagick.bridge.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.imagemagick.bridge.MockMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;

/**
 * Integration tests for {@link IMBridgeService} with a {@link MockMagickRunner}.
 *
 * <p>These tests exercise the full service flow (init → convert) without
 * requiring a real ImageMagick installation.  The mock runner responds to
 * {@code -version} and {@code -list format} queries with canned output and
 * creates a minimal valid PNG for conversion commands.
 */
class IMBridgeServiceTest {

    private static final String IM_VERSION = "7.1.0-0";
    private static final Path FAKE_MAGICK  = Path.of("/fake/magick");

    private MockMagickRunner mockRunner;
    private IMBridgeService  service;

    @BeforeEach
    void setUp() {
        mockRunner = new MockMagickRunner();
        mockRunner.setVersionOutput(
                "Version: ImageMagick " + IM_VERSION + " Q16 x86_64 https://imagemagick.org\n");
        mockRunner.setFormatOutput(FormatRegistryTest.SAMPLE_OUTPUT);
        service = new IMBridgeService(new IMBridgeConfig(), mockRunner);
    }

    // -------------------------------------------------------------------------
    // init

    @Test
    void initWithPathSetsReadyState() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);

        assertTrue(service.isReady(), "Service must report ready after initWithPath");
        assertFalse(service.isInitFailed());
        assertNull(service.getInitError());
    }

    @Test
    void initWithPathPopulatesExtensions() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);

        assertFalse(service.getSupportedExtensions().isEmpty(),
                "Supported extensions must not be empty after init");
        assertTrue(service.getSupportedExtensions().contains("png"),
                "png must be in supported extensions from sample format output");
        assertTrue(service.getSupportedExtensions().contains("arw"),
                "arw must be in supported extensions from sample format output");
    }

    @Test
    void initFailsGracefullyWhenRunnerAlwaysFails() {
        mockRunner.setAlwaysFail(true);
        service.init(); // auto-detect — all candidates fail

        assertFalse(service.isReady());
        assertTrue(service.isInitFailed());
        assertNotNull(service.getInitError());
        assertTrue(service.getSupportedExtensions().isEmpty(),
                "No extensions should be available when init failed");
    }

    @Test
    void getSupportedExtensionsReturnsEmptyBeforeInit() {
        // Freshly created service — init not called yet
        assertTrue(service.getSupportedExtensions().isEmpty());
    }

    // -------------------------------------------------------------------------
    // convertToPng

    @Test
    void convertToPngReturnsImage() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);

        BufferedImage result = service.convertToPng(new StubItem("photo.arw", new byte[]{1, 2, 3}));

        assertNotNull(result, "convertToPng must return a non-null BufferedImage");
        assertTrue(result.getWidth() > 0 && result.getHeight() > 0,
                "Returned image must have positive dimensions");
    }

    @Test
    void convertToPngThrowsWhenNotReady() {
        assertThrows(IllegalStateException.class,
                () -> service.convertToPng(new StubItem("test.png", new byte[]{1})),
                "convertToPng must throw when service is not ready");
    }

    @Test
    void convertToPngThrowsWhenFileTooLarge() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);

        // Max input size is 512 MB by default; fake a file that reports 1 GB
        StubItem oversized = new StubItem("big.tiff", new byte[]{1}) {
            @Override
            public long sizeBytes() {
                return 1_073_741_824L; // 1 GB
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> service.convertToPng(oversized),
                "Must throw IllegalArgumentException for files exceeding maxInputSizeBytes");
    }

    @Test
    void conversionCommandContainsThreadLimit() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);
        service.convertToPng(new StubItem("test.png", new byte[]{5, 6, 7}));

        // Find the conversion command (contains -resize, not -version or -list)
        var conversionCmds = mockRunner.getRecordedCommands().stream()
                .filter(cmd -> cmd.contains("-resize"))
                .toList();

        assertFalse(conversionCmds.isEmpty(), "A conversion command must have been issued");

        var cmd = conversionCmds.get(0);
        // thread limit is always emitted; memory/map/disk are omitted when blank
        assertTrue(cmd.contains("-limit"), "Command must contain -limit flags");
        assertTrue(cmd.contains("thread"), "Command must specify thread limit");
        assertFalse(cmd.contains("memory"),
                "memory limit must be omitted when not configured (avoids CacheResourcesExhausted)");
        assertFalse(cmd.contains("disk"),
                "disk limit must be omitted when not configured (avoids CacheResourcesExhausted)");
    }

    @Test
    void conversionCommandOutputHasPngPrefix() throws Exception {
        service.initWithPath(FAKE_MAGICK, IM_VERSION);
        service.convertToPng(new StubItem("test.bmp", new byte[]{9}));

        var conversionCmd = mockRunner.getRecordedCommands().stream()
                .filter(cmd -> cmd.contains("-resize"))
                .findFirst()
                .orElseThrow();

        String outputArg = conversionCmd.get(conversionCmd.size() - 1);
        assertTrue(outputArg.startsWith("PNG:"),
                "Output argument must start with PNG: to force PNG format");

        // Input must have [0] suffix to select first frame/layer only
        // (prevents multi-output files for PSD, multi-frame TIFF, animated GIF, etc.)
        String inputArg = conversionCmd.stream()
                .filter(a -> a.endsWith("[0]"))
                .findFirst()
                .orElse(null);
        assertNotNull(inputArg, "Input argument must end with [0] to force single-frame output");
    }

    // -------------------------------------------------------------------------
    // Stub helper

    /**
     * Minimal {@link QuickViewItem} implementation backed by a byte array.
     */
    static class StubItem implements QuickViewItem {

        private final String name;
        private final byte[] content;

        StubItem(String name, byte[] content) {
            this.name    = name;
            this.content = content;
        }

        @Override
        public String name()      { return name; }

        @Override
        public long sizeBytes()   { return content.length; }

        @Override
        public String extension() {
            int dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : "";
        }

        @Override
        public String mimeType()  { return null; }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(content);
        }

		@Override
		public Path path() {
			return null;
		}
    }
}
