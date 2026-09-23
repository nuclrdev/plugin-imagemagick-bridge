package dev.nuclr.plugin.core.imagemagick.bridge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.imagemagick.bridge.MockMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;

class IMBridgeServiceThumbnailTest {

	@Test
	void thumbnailAsksImageMagickToShrinkIntoTheBox() throws Exception {
		MockMagickRunner runner = new MockMagickRunner();
		IMBridgeService service = readyService(new IMBridgeConfig(), runner);

		BufferedImage image = service.thumbnail(resource("photo.psd"), 64, 48);

		assertNotNull(image);
		assertEquals("64x48>", resizeArgument(runner.getRecordedCommands().getLast()));
	}

	@Test
	void thumbnailNeverAsksForMoreThanThePreviewLimit() throws Exception {
		IMBridgeConfig config = new IMBridgeConfig();
		MockMagickRunner runner = new MockMagickRunner();
		IMBridgeService service = readyService(config, runner);

		int limit = config.getMaxPixelDimension();
		service.thumbnail(resource("poster.tiff"), limit * 4, limit * 4);

		assertEquals(limit + "x" + limit + ">", resizeArgument(runner.getRecordedCommands().getLast()));
	}

	private static IMBridgeService readyService(IMBridgeConfig config, MockMagickRunner runner) throws Exception {
		runner.setFormatOutput(FormatRegistryTest.SAMPLE_OUTPUT);
		IMBridgeService service = new IMBridgeService(config, runner);
		service.initWithPath(Path.of("magick"), "7.1.0");
		return service;
	}

	private static String resizeArgument(List<String> command) {
		return command.get(command.indexOf("-resize") + 1);
	}

	private static NuclrResource resource(String name) {
		byte[] content = { 1, 2, 3 };
		NuclrResource resource = new NuclrResource(null) {
			private static final long serialVersionUID = 1L;

			@Override
			public InputStream openInputStream(OpenOption... options) {
				return new ByteArrayInputStream(content);
			}
		};
		resource.setUuid(name);
		resource.setName(name);
		resource.setLength(content.length);
		return resource;
	}
}
