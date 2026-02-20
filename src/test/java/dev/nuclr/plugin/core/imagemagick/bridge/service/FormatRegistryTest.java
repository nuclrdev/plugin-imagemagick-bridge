package dev.nuclr.plugin.core.imagemagick.bridge.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormatRegistry#parse(String)}.
 * Tests run without any external process — purely against the static parser.
 */
class FormatRegistryTest {

    /**
     * 4-column layout: {@code Format  Module  Mode  Description}.
     * Common in IM builds before 7.1.0-19.
     */
    static final String SAMPLE_OUTPUT =
            "   Format  Module    Mode  Description\n" +
            "-------------------------------------------------------------------------------\n" +
            "      AAI* AAI        rw-  AAI Dune image\n" +
            "      AI   PDF        -w-  Adobe Illustrator CS2\n" +
            "      ARW  DNG        r--  Sony Alpha Raw Image Format\n" +
            "      AVI  MPEG       r--  Microsoft Audio/Visual Interleaved\n" +
            "      BMP  BMP        rw-  Microsoft Windows bitmap image\n" +
            "      BMP2 BMP        -w-  Microsoft Windows bitmap image v2\n" +
            "      GIF  GIF        rw+  CompuServe graphics interchange format\n" +
            "      JPEG JPEG       rw+  Joint Photographic Experts Group JFIF format\n" +
            "      JPEG* JPEG      rw+  same but with star suffix\n" +
            "      PDF  PDF        rw-  Portable Document Format\n" +
            "      PNG  PNG        rw-  Portable Network Graphics\n" +
            "      SVG  SVG        rw+  Scalable Vector Graphics\n" +
            "      3G2  VIDEO      ---  Media Container\n" +
            "      TIFF TIFF       rw+  Tagged Image File Format\n";

    /**
     * 3-column layout: {@code Format  Mode  Description} (Module column absent).
     * Observed in IM 7.1.0-19 on Windows.
     */
    static final String SAMPLE_OUTPUT_3COL =
            "   Format  Mode  Description\n" +
            "-------------------------------------------------------------------------------\n" +
            "      3FR  r--   Hasselblad CFV/H3D39II\n" +
            "      3G2  r--   Media Container\n" +
            "     ASHLAR* -w+   Image sequence laid out in continuous irregular courses\n" +
            "      AVI  r--   Microsoft Audio/Visual Interleaved\n" +
            "      BMP* rw-   Microsoft Windows bitmap image\n" +
            "      GIF* rw+   CompuServe graphics interchange format\n" +
            "     JPEG* rw+   Joint Photographic Experts Group JFIF format\n" +
            "      PNG* rw-   Portable Network Graphics\n" +
            "      SVG* rw+   Scalable Vector Graphics\n" +
            "     TIFF* rw+   Tagged Image File Format\n" +
            "      XCF  r--   GIMP image\n";

    @Test
    void readableFormatsAreIncluded() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);

        assertTrue(extensions.contains("aai"),  "AAI should be readable (rw-)");
        assertTrue(extensions.contains("arw"),  "ARW should be readable (r--)");
        assertTrue(extensions.contains("avi"),  "AVI should be readable (r--)");
        assertTrue(extensions.contains("bmp"),  "BMP should be readable (rw-)");
        assertTrue(extensions.contains("gif"),  "GIF should be readable (rw+)");
        assertTrue(extensions.contains("jpeg"), "JPEG should be readable (rw+)");
        assertTrue(extensions.contains("pdf"),  "PDF should be readable (rw-)");
        assertTrue(extensions.contains("png"),  "PNG should be readable (rw-)");
        assertTrue(extensions.contains("svg"),  "SVG should be readable (rw+)");
        assertTrue(extensions.contains("tiff"), "TIFF should be readable (rw+)");
    }

    @Test
    void writeOnlyFormatsAreExcluded() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);

        assertFalse(extensions.contains("ai"),   "AI is write-only (-w-), must be excluded");
        assertFalse(extensions.contains("bmp2"), "BMP2 is write-only (-w-), must be excluded");
    }

    @Test
    void neitherReadableNorWritableIsExcluded() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);

        assertFalse(extensions.contains("3g2"), "3G2 has mode ---, must be excluded");
    }

    @Test
    void starSuffixIsStripped() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);

        assertTrue(extensions.contains("aai"),
                "Extension with * suffix must be stored without the *");
        assertFalse(extensions.contains("aai*"),
                "Extension token must not contain *");
    }

    @Test
    void extensionsAreLowerCased() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);

        assertTrue(extensions.contains("png"), "Extensions must be lower-cased");
        assertFalse(extensions.contains("PNG"), "Upper-case extension must not appear");
    }

    // -------------------------------------------------------------------------
    // 3-column layout (Module column absent — observed in IM 7.1.0-19 on Windows)

    @Test
    void threeColumnLayoutReadableFormatsAreIncluded() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT_3COL);

        assertTrue(extensions.contains("3fr"),  "3FR r-- must be readable");
        assertTrue(extensions.contains("3g2"),  "3G2 r-- must be readable");
        assertTrue(extensions.contains("avi"),  "AVI r-- must be readable");
        assertTrue(extensions.contains("bmp"),  "BMP rw- must be readable");
        assertTrue(extensions.contains("gif"),  "GIF rw+ must be readable");
        assertTrue(extensions.contains("jpeg"), "JPEG rw+ must be readable");
        assertTrue(extensions.contains("png"),  "PNG rw- must be readable");
        assertTrue(extensions.contains("svg"),  "SVG rw+ must be readable");
        assertTrue(extensions.contains("tiff"), "TIFF rw+ must be readable");
        assertTrue(extensions.contains("xcf"),  "XCF r-- must be readable");
    }

    @Test
    void threeColumnLayoutWriteOnlyIsExcluded() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT_3COL);

        assertFalse(extensions.contains("ashlar"),
                "ASHLAR -w+ (write-only) must be excluded in 3-column layout");
    }

    @Test
    void threeColumnLayoutStarSuffixIsStripped() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT_3COL);

        assertTrue(extensions.contains("bmp"),
                "BMP* in 3-column layout must be stored as 'bmp'");
        assertFalse(extensions.contains("bmp*"),
                "Extension token must not contain * in 3-column layout");
    }

    // -------------------------------------------------------------------------

    @Test
    void emptyOutputProducesEmptySet() {
        assertTrue(FormatRegistry.parse("").isEmpty(),
                "Empty output should yield empty set");
    }

    @Test
    void outputWithOnlyHeaderProducesEmptySet() {
        String headerOnly =
                "   Format  Module    Mode  Description\n" +
                "-------------------------------------------------------------------------------\n";
        assertTrue(FormatRegistry.parse(headerOnly).isEmpty());
    }

    @Test
    void resultIsImmutable() {
        Set<String> extensions = FormatRegistry.parse(SAMPLE_OUTPUT);
        assertThrows(UnsupportedOperationException.class,
                () -> extensions.add("test"),
                "Returned set must be immutable");
    }
}
