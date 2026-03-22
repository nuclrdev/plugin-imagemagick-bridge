package dev.nuclr.plugin.core.imagemagick.bridge;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.PluginTheme;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import dev.nuclr.plugin.core.imagemagick.bridge.service.DefaultMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import dev.nuclr.plugin.event.PluginEvent;
import dev.nuclr.plugin.event.PluginThemeUpdatedEvent;
import dev.nuclr.plugin.event.bus.PluginEventListener;
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
 * <p>{@link #supports(PluginPathResource)} is always fast (no I/O) - it reads the
 * volatile extension set populated by the background thread.
 */
@Slf4j
public class IMBridgeQuickViewProvider implements QuickViewProviderPlugin, PluginEventListener {

    private final IMBridgeService service;
    private ApplicationPluginContext context;
    private IMBridgeViewPanel panel;
    private volatile AtomicBoolean currentCancelled;
    private PluginTheme theme;

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

    @Override
    public PluginManifest getPluginInfo() {
        ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
        try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
            if (is != null) {
                return objectMapper.readValue(is, PluginManifest.class);
            }
        } catch (Exception e) {
            log.error("Error reading /plugin.json for IMBridgeQuickViewProvider", e);
        }
        return null;
    }

    /**
     * Fast check — no I/O.  Returns {@code false} until the background init
     * populates the supported-extension set.
     */
    @Override
    public boolean supports(PluginPathResource resource) {
        if (resource == null || resource.getExtension() == null) {
            return false;
        }
        return service.getSupportedExtensions()
                .contains(resource.getExtension().toLowerCase());
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) {
            panel = new IMBridgeViewPanel(service);
            panel.applyTheme(theme);
        }
        return panel;
    }

    @Override
    public List<MenuResource> getMenuItems(PluginPathResource source) {
        return List.of();
    }

    @Override
    public void load(ApplicationPluginContext context) {
        this.context = context;
        context.getEventBus().subscribe(this);
        applyTheme(resolveTheme(context));
    }

    @Override
    public boolean openItem(PluginPathResource item, AtomicBoolean cancelled) {
        if (currentCancelled != null) {
            currentCancelled.set(true);
        }
        currentCancelled = cancelled;
        getPanel();
        return panel.load(item, cancelled);
    }

    @Override
    public void closeItem() {
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
        closeItem();
        if (context != null) {
            context.getEventBus().unsubscribe(this);
        }
        panel = null;
        context = null;
    }

	@Override
	public int getPriority() {
		return 50;
	}

    public void applyTheme(PluginTheme theme) {
        this.theme = theme;
        if (panel != null) {
            panel.applyTheme(theme);
        }
    }

    @Override
    public boolean isMessageSupported(PluginEvent msg) {
        return msg instanceof PluginThemeUpdatedEvent;
    }

    @Override
    public void handleMessage(PluginEvent e) {
        if (e instanceof PluginThemeUpdatedEvent) {
            applyTheme(resolveTheme(context));
        }
    }

    @Override
    public void onFocusGained() {
        // Quick view providers do not require focus-specific behavior.
    }

    @Override
    public void onFocusLost() {
        // Quick view providers do not require focus-specific behavior.
    }

    private static PluginTheme resolveTheme(ApplicationPluginContext context) {
        if (context == null) {
            return null;
        }
        Object theme = context.getGlobalData().get("pluginTheme");
        if (theme instanceof PluginTheme pluginTheme) {
            return pluginTheme;
        }
        return null;
    }
}
