package dev.nuclr.plugin.core.imagemagick.bridge;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JPanel;

/**
 * Swing panel that displays an image converted via ImageMagick.
 *
 * <p>Each call to {@link #load(QuickViewItem)} spawns a virtual thread that:
 * <ol>
 *   <li>Asks {@link IMBridgeService} to convert the item to a cached PNG.</li>
 *   <li>Reads the PNG with {@code ImageIO}.</li>
 *   <li>Posts the result back to the EDT via {@code SwingUtilities.invokeLater}.</li>
 * </ol>
 * Calling {@link #load(QuickViewItem)} again before the previous task finishes
 * cancels the previous task (thread interrupt).
 *
 * <p>On failure the panel renders a one-line error message instead of an image.
 */
@Slf4j
public class IMBridgeViewPanel extends JPanel {

	private static final Color BG = Color.BLACK;
	private static final Color ERROR = new Color(200, 80, 80);
	private static final Color LOADING = new Color(140, 140, 140);

	private final IMBridgeService service;

	private volatile BufferedImage image;
	private volatile String statusMessage;
	private volatile boolean loading;
	private volatile Thread loadingThread;

	public IMBridgeViewPanel(IMBridgeService service) {
		this.service = service;
		setBackground(BG);
		setOpaque(true);
	}

	// -------------------------------------------------------------------------
	// Public API

	public boolean load(QuickViewItem item, AtomicBoolean cancelled) {
		// Cancel any in-flight task
		Thread prev = loadingThread;
		if (prev != null) {
			prev.interrupt();
		}

		image = null;
		statusMessage = null;
		loading = true;
		repaint();

		loadingThread = Thread.ofVirtual()
				.name("imbridge-load")
				.start(() -> doLoad(item, cancelled));
		return true;
	}

	public void clear() {
		Thread prev = loadingThread;
		if (prev != null) {
			prev.interrupt();
		}
		loadingThread = null;
		image = null;
		statusMessage = null;
		loading = false;
		repaint();
	}

	// -------------------------------------------------------------------------
	// Background loading

	private void doLoad(QuickViewItem item, AtomicBoolean cancelled) {
		try {
			BufferedImage img = service.convertToPng(item);
			if (cancelled.get()) return;
			SwingUtilities.invokeLater(() -> {
				image = img;
				statusMessage = null;
				loading = false;
				repaint();
			});
		} catch (Exception e) {
			if (cancelled.get()) return;
			String msg = toFriendlyMessage(e);
			log.warn("ImageMagick Bridge: cannot load '{}': {}", item.name(), e.getMessage());
			log.debug("ImageMagick Bridge load error detail", e);
			SwingUtilities.invokeLater(() -> {
				image = null;
				statusMessage = msg;
				loading = false;
				repaint();
			});
		}
	}

	private static String toFriendlyMessage(Exception e) {
		String msg = e.getMessage();
		if (msg == null) {
			return "Preview failed (unknown error).";
		}
		if (msg.contains("not found") || msg.contains("not yet initialised")) {
			return "ImageMagick not available. Install ImageMagick 7+.";
		}
		if (msg.contains("timed out")) {
			return "Preview timed out. Try increasing conversionTimeoutSeconds.";
		}
		if (msg.contains("too large")) {
			return msg; // already user-friendly from IMBridgeService
		}
		// Strip to first line to keep the message brief
		return "Preview failed: " + msg.lines().findFirst().orElse(msg);
	}

	// -------------------------------------------------------------------------
	// Rendering

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		// Background
		g.setColor(BG);
		g.fillRect(0, 0, getWidth(), getHeight());

		if (loading) {
			drawCenteredText(g, "Converting\u2026", LOADING);
		} else if (image != null) {
			drawImage((Graphics2D) g);
		} else if (statusMessage != null) {
			drawCenteredText(g, statusMessage, ERROR);
		}
	}

	private void drawImage(Graphics2D g2orig) {
		final int panelW = getWidth();
		final int panelH = getHeight();
		final int imgW = image.getWidth();
		final int imgH = image.getHeight();

		if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0) {
			return;
		}

		// Fit-inside (contain) scaling — never upscale
		double scale = Math
				.min(
						1.0,
						Math
								.min(
										(double) panelW / imgW,
										(double) panelH / imgH));

		int drawW = (int) Math.round(imgW * scale);
		int drawH = (int) Math.round(imgH * scale);
		int x = (panelW - drawW) / 2;
		int y = (panelH - drawH) / 2;

		Graphics2D g2 = (Graphics2D) g2orig.create();
		try {
			g2
					.setRenderingHint(
							RenderingHints.KEY_INTERPOLATION,
							scale < 1.0
									? RenderingHints.VALUE_INTERPOLATION_BILINEAR
									: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2
					.setRenderingHint(
							RenderingHints.KEY_RENDERING,
							RenderingHints.VALUE_RENDER_QUALITY);
			g2
					.setRenderingHint(
							RenderingHints.KEY_ANTIALIASING,
							RenderingHints.VALUE_ANTIALIAS_ON);
			g2.drawImage(image, x, y, drawW, drawH, null);
		} finally {
			g2.dispose();
		}
	}

	private void drawCenteredText(Graphics g, String text, Color color) {
		g.setColor(color);
		Font font = g.getFont().deriveFont(Font.PLAIN, 13f);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics(font);
		int x = Math.max(8, (getWidth() - fm.stringWidth(text)) / 2);
		int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString(text, x, y);
	}
}
