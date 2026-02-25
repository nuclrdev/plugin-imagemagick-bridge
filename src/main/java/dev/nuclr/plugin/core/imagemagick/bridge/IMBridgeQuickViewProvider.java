package dev.nuclr.plugin.core.imagemagick.bridge;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;
import dev.nuclr.plugin.core.imagemagick.bridge.config.IMBridgeConfig;
import dev.nuclr.plugin.core.imagemagick.bridge.service.DefaultMagickRunner;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link QuickViewProvider} entry point for the ImageMagick Bridge plugin.
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
 * <p>{@link #matches(QuickViewItem)} is always fast (no I/O) — it reads the
 * volatile extension set populated by the background thread.
 */
@Slf4j
public class IMBridgeQuickViewProvider implements QuickViewProvider {

    private final IMBridgeService service;
    private IMBridgeViewPanel panel;
    private volatile AtomicBoolean currentCancelled;

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

    // -------------------------------------------------------------------------
    // QuickViewProvider

    @Override
    public String getPluginClass() {
        return getClass().getName();
    }

    /**
     * Fast check — no I/O.  Returns {@code false} until the background init
     * populates the supported-extension set.
     */
    @Override
    public boolean matches(QuickViewItem item) {
        return service.getSupportedExtensions()
                .contains(item.extension().toLowerCase());
    }

    @Override
    public JComponent getPanel() {
        if (panel == null) {
            panel = new IMBridgeViewPanel(service);
        }
        return panel;
    }

    @Override
    public boolean open(QuickViewItem item, AtomicBoolean cancelled) {
        if (currentCancelled != null) currentCancelled.set(true);
        this.currentCancelled = cancelled;
        getPanel();
        return panel.load(item, cancelled);
    }

    @Override
    public void close() {
        if (currentCancelled != null) currentCancelled.set(true);
        if (panel != null) {
            panel.clear();
        }
    }

    @Override
    public void unload() {
        close();
        panel = null;
    }

	@Override
	public int priority() {
		return 50;
	}
}
