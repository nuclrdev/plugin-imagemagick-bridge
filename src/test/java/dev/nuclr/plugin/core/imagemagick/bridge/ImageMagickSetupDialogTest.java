package dev.nuclr.plugin.core.imagemagick.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageMagickSetupDialogTest {

	@Test
	void windowsHelpUsesOfficialWingetPackage() {
		var help = ImageMagickSetupDialog.installationHelp("Windows 11");

		assertEquals("Windows", help.platform());
		assertEquals("winget install ImageMagick.Q16", help.command());
		assertTrue(help.linksHtml().contains("imagemagick.org/download"));
	}

	@Test
	void macHelpUsesHomebrew() {
		var help = ImageMagickSetupDialog.installationHelp("Mac OS X");

		assertEquals("macOS", help.platform());
		assertEquals("brew install imagemagick", help.command());
	}

	@Test
	void linuxHelpDoesNotRecommendPotentialImageMagickSixPackage() {
		var help = ImageMagickSetupDialog.installationHelp("Linux");

		assertEquals("Linux", help.platform());
		assertNull(help.command());
		assertTrue(help.explanation().contains("AppImage"));
		assertTrue(help.explanation().contains("ImageMagick 6"));
	}

	@Test
	void helpPageContainsPlatformSpecificContent() {
		var help = ImageMagickSetupDialog.installationHelp("Windows 11");
		String html = ImageMagickSetupDialog.createMessage(help).getText();

		assertTrue(html.contains("Windows setup"));
		assertTrue(html.contains("winget install ImageMagick.Q16"));
		assertTrue(html.contains("Locate executable"));
	}

	@Test
	void onlyKnownBridgeFormatsCanTriggerSetupBeforeDetection() {
		assertTrue(IMBridgeQuickViewProvider.isSetupTriggerExtension("PSD"));
		assertTrue(IMBridgeQuickViewProvider.isSetupTriggerExtension("xcf"));
		assertTrue(IMBridgeQuickViewProvider.isSetupTriggerExtension("dng"));
		assertFalse(IMBridgeQuickViewProvider.isSetupTriggerExtension("totally-unknown"));
	}
}
