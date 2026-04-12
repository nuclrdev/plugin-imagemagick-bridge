package dev.nuclr.plugin.core.imagemagick.bridge;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import dev.nuclr.plugin.core.imagemagick.bridge.service.DefaultMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link QuickViewProviderPlugin} entry point for the ImageMagick Bridge plugin.
 *
 * <p>Initialisation (background virtual thread):
 * <ol>
 *   <li>Auto-detects the {@code magick} binary (saved preference → config →
 *       OS PATH → well-known dirs).</li>
 *   <li>If detection fails, shows a {@link JFileChooser} on the EDT so the user
 *       can locate the executable manually.  A valid selection is persisted via
 *       {@code java.util.prefs.Preferences} and used on every subsequent launch.</li>
 *   <li>If the user cancels or the chosen file is invalid the plugin stays
 *       disabled silently.</li>
 * </ol>
 *
 * <p>{@link #supports(NuclrResourcePath)} is always fast (no I/O) - it reads the
 * volatile extension set populated by the background thread.
 */
@Slf4j
public class IMBridgeQuickViewProvider implements NuclrPlugin {

    private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";

    private final IMBridgeService service;
    private NuclrPluginContext context;
    private IMBridgeViewPanel panel;
    private volatile AtomicBoolean currentCancelled;
    private NuclrThemeScheme theme;

    /** Called by the host PluginLoader via reflection — zero-arg constructor required. */
    public IMBridgeQuickViewProvider() {
        IMBridgeConfig config = new IMBridgeConfig();
        this.service = new IMBridgeService(config, new DefaultMagickRunner());
        Thread.ofVirtual()
                .name("imbridge-init")
                .start(this::initWithFallback);
    }

    /** Package-private: inject a pre-configured service for tests. */
    IMBridgeQuickViewProvider(IMBridgeService service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Initialisation

    private void initWithFallback() {
        service.init();
        if (service.isReady()) {
            return;
        }

        // Auto-detection failed — ask the user to locate the executable
        log.info("ImageMagick not found automatically; showing file picker");
        Path chosen = showLocateDialog();
        if (chosen == null) {
            log.info("User cancelled the ImageMagick file picker; plugin disabled");
            return;
        }

        try {
            service.initWithUserSelectedPath(chosen);
            log.info("ImageMagick Bridge initialised with user-selected path: {}", chosen);
        } catch (Exception e) {
            log.warn("User-selected path '{}' rejected: {}", chosen, e.getMessage());
            showError("Not a valid ImageMagick 7 executable:\n" + chosen + "\n\n" + e.getMessage());
        }
    }

    /**
     * Blocks the calling (virtual) thread while the file chooser runs on the EDT.
     * Returns the chosen {@link Path}, or {@code null} if the user cancelled.
     */
    private static Path showLocateDialog() {
        Path[] result = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Locate ImageMagick 7 executable (magick / magick.exe)");
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setApproveButtonText("Use this executable");

                if (isWindows()) {
                    // Start the user in Program Files where IM7 typically installs
                    File programFiles = new File("C:\\Program Files");
                    if (programFiles.isDirectory()) {
                        chooser.setCurrentDirectory(programFiles);
                    }
                    chooser.setFileFilter(
                            new FileNameExtensionFilter("Executable (*.exe)", "exe"));
                } else {
                    chooser.setCurrentDirectory(new File("/usr/local/bin"));
                }

                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    result[0] = chooser.getSelectedFile().toPath();
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Exception inside file-picker dialog", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private static void showError(String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(
                        null, message,
                        "ImageMagick Bridge",
                        JOptionPane.ERROR_MESSAGE));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }

    /**
     * Fast check — no I/O.  Returns {@code false} until the background init
     * populates the supported-extension set.
     */
    @Override
    public boolean supports(NuclrResourcePath resource) {
        if (resource == null || resource.getExtension() == null) {
            return false;
        }
        return service.getSupportedExtensions()
                .contains(resource.getExtension().toLowerCase());
    }

    @Override
    public JComponent panel() {
        if (panel == null) {
            panel = new IMBridgeViewPanel(service);
        }
        return panel;
    }

    @Override
    public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
        return List.of();
    }

    @Override
    public void load(NuclrPluginContext context, boolean template) {
        this.context = context;
    }

    @Override
    public boolean openResource(NuclrResourcePath item, AtomicBoolean cancelled) {
        if (currentCancelled != null) {
            currentCancelled.set(true);
        }
        currentCancelled = cancelled;
        panel();
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
	private String version = "1.0.0";
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
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		
	}
}
