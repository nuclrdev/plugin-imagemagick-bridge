package dev.nuclr.plugin.core.imagemagick.bridge;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.apache.commons.io.FilenameUtils;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import dev.nuclr.plugin.core.imagemagick.bridge.service.DefaultMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link QuickViewProviderPlugin} entry point for the ImageMagick Bridge
 * plugin.
 *
 * <p>
 * Initialisation (background virtual thread):
 * <ol>
 * <li>Auto-detects the {@code magick} binary (saved preference â†’ config â†’ OS
 * PATH â†’ well-known dirs).</li>
 * <li>If detection fails, startup remains silent. Installation help and the
 * executable picker are only shown when this provider is asked to open a
 * preview.</li>
 * </ol>
 *
 * <p>
 * {@link #supports(NuclrResource)} is always fast (no I/O) - it reads the
 * volatile extension set populated by the background thread.
 */
@Slf4j
public class IMBridgeQuickViewProvider implements QuickViewNuclrPlugin {

	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";
	/**
	 * Conservative routing set used only while ImageMagick is unavailable. Without
	 * it the host would never select this provider, so the preview-time setup page
	 * could never be reached. The installed binary's queried format set remains the
	 * source of truth once detection succeeds.
	 */
	private static final Set<String> SETUP_TRIGGER_EXTENSIONS = Set.of(
			"3fr", "aai", "ai", "apng", "arw", "avi", "avs", "avif", "bgra", "bie", "bmp", "cin",
			"cmyk", "cmyka", "cr2", "cr3", "crw", "cut", "dcm", "dcr", "dcx", "dds",
			"dib", "djvu", "dng", "dpx", "epdf", "epi", "eps", "eps2", "eps3", "epsf",
			"epsi", "ept", "exr", "fax", "fff", "fits", "flif", "fpx", "fts", "g3",
			"gif", "hdr", "heic", "heif", "hrz", "ico", "iiq", "j2c", "j2k", "jng", "jp2",
			"jpc", "jpeg", "jpg", "jpt", "jxl", "mat", "miff", "mng", "mono", "mos", "mrw", "mtv",
			"nef", "nrw", "ora", "orf", "otb", "palm", "pam", "pbm", "pcd", "pcds",
			"pcl", "pcx", "pdb", "pdf", "pef", "pes", "pfa", "pfb", "pfm", "pgm", "picon", "png",
			"pict", "pix", "pnm", "ppm", "ps", "ps2", "ps3", "psb", "psd", "ptif",
			"pwp", "raf", "raw", "rgb", "rgba", "rgbo", "rla", "rle", "rw2", "sct",
			"sfw", "sgi", "six", "sixel", "sr2", "srf", "sun", "svg", "svgz", "tga",
			"tiff", "tim", "ttf", "ubrl", "uil", "vicar", "viff", "wbmp", "webp",
			"wmf", "wpg", "x3f", "xbm", "xcf", "xpm", "xwd");

	private final IMBridgeService service;
	private final Object setupLock = new Object();
	private NuclrPluginContext context;
	private IMBridgeViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrThemeScheme theme;
	private NuclrResource currentResource;

	/**
	 * Called by the host PluginLoader via reflection â€” zero-arg constructor
	 * required.
	 */
	public IMBridgeQuickViewProvider() {
		this.service = SharedServiceHolder.SERVICE;
	}

	/** Package-private: inject a pre-configured service for tests. */
	IMBridgeQuickViewProvider(IMBridgeService service) {
		this.service = service;
	}

	// -------------------------------------------------------------------------
	// Initialisation

	private static final class SharedServiceHolder {
		private static final IMBridgeService SERVICE = createService();

		private static IMBridgeService createService() {
			IMBridgeService shared = new IMBridgeService(new IMBridgeConfig(), new DefaultMagickRunner());
			// Detection and format discovery are safe at startup; only user-facing setup UI
			// must wait until an actual preview request.
			Thread.ofVirtual().name("imbridge-init").start(shared::init);
			return shared;
		}
	}

	private boolean ensureReadyForPreview() {
		if (service.isReady() || service.awaitInitialization()) {
			return true;
		}

		synchronized (setupLock) {
			if (service.isReady()) {
				return true;
			}

			log.info("ImageMagick is unavailable during preview; showing installation help");
			if (ImageMagickSetupDialog.show(panel) != ImageMagickSetupDialog.Action.LOCATE_EXECUTABLE) {
				return false;
			}

			Path chosen = ImageMagickSetupDialog.showLocateDialog(panel);
			if (chosen == null) {
				return false;
			}

			try {
				service.initWithUserSelectedPath(chosen);
				log.info("ImageMagick Bridge initialised with user-selected path: {}", chosen);
				return true;
			} catch (Exception e) {
				log.warn("User-selected path '{}' rejected: {}", chosen, e.getMessage());
				showError("Not a valid ImageMagick 7 executable:\n" + chosen + "\n\n" + e.getMessage());
				return false;
			}
		}
	}

	private static void showError(String message) {
		SwingUtilities.invokeLater(
				() -> JOptionPane.showMessageDialog(null, message, "ImageMagick Bridge", JOptionPane.ERROR_MESSAGE));
	}

	/**
	 * Fast check â€” no I/O. Uses the discovered extension set when ready and a
	 * conservative setup-trigger set while detection is pending or unavailable.
	 */
	@Override
	public boolean supports(NuclrResource resource) {

		String extension = extension(resource);

		if (extension == null) {
			var path = resource.getPath();
			extension = extension(path);
		}

		if (extension == null) {
			return false;
		}

		String normalized = extension.toLowerCase(Locale.ROOT);
		if (service.getSupportedExtensions().contains(normalized)) {
			return true;
		}

		// When detection has not completed or ImageMagick is missing, claim only
		// well-known bridge formats. This lets the host make a real openResource()
		// attempt, where setup UI is allowed, without hijacking arbitrary files.
		return !service.isReady() && isSetupTriggerExtension(normalized);
	}

	static boolean isSetupTriggerExtension(String extension) {
		return extension != null && SETUP_TRIGGER_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}

	private static String extension(Path path) {
		var name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		return FilenameUtils.getExtension(name);
	}

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new IMBridgeViewPanel(service);
			if (theme != null) {
				panel.applyTheme(theme);
			}
		}
		return panel;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		if (context != null) {
			updateTheme(context.getTheme());
		}
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public boolean openResource(NuclrResource item, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = item;
		currentCancelled = cancelled;
		panel();
		// This is deliberately the first point at which missing-installation UI may
		// appear. Merely loading or enabling the plugin never interrupts the user.
		ensureReadyForPreview();
		return panel.load(item, cancelled);
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (panel != null) {
			panel.clear();
		}
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}

	@Override
	public int priority() {
		return 50;
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	private String name = "ImageMagick Bridge";
	private String id = "dev.nuclr.plugin.core.imagemagick.bridge";
	private final String version = loadVersion();
	private String description = "'ImageMagick Bridge' provides QuickView for image formats supported by system-installed ImageMagick";
	private String author = "Nuclr Development Team";
	private String license = "Apache-2.0";
	private String website = "https://nuclr.dev";
	private String pageUrl = "https://nuclr.dev/plugins/core/imagemagick-bridge.html";
	private String docUrl = "https://nuclr.dev/plugins/core/imagemagick-bridge.html";

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String version() {
		return version;
	}
	private static String loadVersion() {
		try (var stream = IMBridgeQuickViewProvider.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) return "unknown";
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (java.io.IOException e) {
			return "unknown";
		}
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public String author() {
		return author;
	}

	@Override
	public String license() {
		return license;
	}

	@Override
	public String website() {
		return website;
	}

	@Override
	public String pageUrl() {
		return pageUrl;
	}

	@Override
	public String docUrl() {
		return docUrl;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		if (themeScheme == null) {
			return;
		}
		this.theme = themeScheme;
		if (panel != null) {
			SwingUtilities.invokeLater(() -> panel.applyTheme(themeScheme));
		}
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String uuid() {
		return UUID.randomUUID().toString();
	}
}
