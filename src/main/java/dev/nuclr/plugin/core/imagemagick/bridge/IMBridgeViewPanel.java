package dev.nuclr.plugin.core.imagemagick.bridge;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Cursor;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.imagemagick.bridge.service.IMBridgeService;
import lombok.extern.slf4j.Slf4j;

/**
 * Swing panel that displays an image converted via ImageMagick.
 *
 * <p>Each call to {@link #load(NuclrResource, AtomicBoolean)} spawns a virtual thread that:
 * <ol>
 *   <li>Asks {@link IMBridgeService} to convert the item to a cached PNG.</li>
 *   <li>Reads the PNG with {@code ImageIO}.</li>
 *   <li>Posts the result back to the EDT via {@code SwingUtilities.invokeLater}.</li>
 * </ol>
 * Calling {@link #load(NuclrResource, AtomicBoolean)} again before the previous task finishes
 * cancels the previous task (thread interrupt).
 *
 * <p>On failure the panel renders a one-line error message instead of an image.
 */
@Slf4j
public class IMBridgeViewPanel extends JPanel {

	private static final int MESSAGE_WRAP_WIDTH = 56;

	/** Zoom multiplier applied on top of the fit-to-panel scale; 1.0 == fit. */
	private static final double MIN_ZOOM = 0.05;
	private static final double MAX_ZOOM = 16.0;
	private static final double ZOOM_STEP = 1.1;
	/** Effective scales within this band of 100% snap to exactly 100% (actual pixels). */
	private static final double SNAP_LOW = 0.95;
	private static final double SNAP_HIGH = 1.05;

	private Color backgroundColor = Color.BLACK;
	private Color errorColor = new Color(200, 80, 80);
	private Color loadingColor = new Color(140, 140, 140);
	private Color cardBackgroundColor = new Color(36, 36, 36);
	private Color cardBorderColor = new Color(85, 85, 85);
	private Color detailColor = new Color(210, 210, 210);

	private final IMBridgeService service;

	private volatile BufferedImage image;
	private volatile String statusMessage;
	private volatile boolean loading;
	private volatile Thread loadingThread;

	/** User zoom factor relative to the fit-to-panel scale; 1.0 == fit. */
	private double zoomMultiplier = 1.0;

	/** Pan offset (pixels) applied on top of the centered image position. */
	private int panX = 0;
	private int panY = 0;
	private int dragStartX;
	private int dragStartY;
	private boolean dragging;

	public IMBridgeViewPanel(IMBridgeService service) {
		this.service = service;
		setBackground(backgroundColor);
		setOpaque(true);
		addMouseWheelListener(this::onMouseWheel);
		installPanHandlers();
	}

	private void installPanHandlers() {
		MouseAdapter panHandler = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (SwingUtilities.isMiddleMouseButton(e) && image != null) {
					dragging = true;
					dragStartX = e.getX();
					dragStartY = e.getY();
					setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (!dragging) {
					return;
				}
				panX += e.getX() - dragStartX;
				panY += e.getY() - dragStartY;
				dragStartX = e.getX();
				dragStartY = e.getY();
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (dragging && SwingUtilities.isMiddleMouseButton(e)) {
					dragging = false;
					setCursor(Cursor.getDefaultCursor());
				}
			}
		};
		addMouseListener(panHandler);
		addMouseMotionListener(panHandler);
	}

	public void applyTheme(NuclrThemeScheme theme) {
		if (theme == null) {
			return;
		}

		backgroundColor = theme.color("Panel.background", backgroundColor);
		loadingColor = theme.color("Label.foreground", loadingColor);
		errorColor = theme.color("Component.error.focusedBorderColor", errorColor);
		cardBackgroundColor = theme.color("TextField.background", cardBackgroundColor);
		cardBorderColor = theme.color("Table.gridColor", cardBorderColor);
		detailColor = theme.color("Label.foreground", detailColor);
		setBackground(backgroundColor);
		repaint();
	}

	// -------------------------------------------------------------------------
	// Public API

	public boolean load(NuclrResource item, AtomicBoolean cancelled) {
		// Cancel any in-flight task
		Thread prev = loadingThread;
		if (prev != null) {
			prev.interrupt();
		}

		image = null;
		statusMessage = null;
		loading = true;
		zoomMultiplier = 1.0;
		panX = 0;
		panY = 0;
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
		zoomMultiplier = 1.0;
		panX = 0;
		panY = 0;
		dragging = false;
		repaint();
	}

	// -------------------------------------------------------------------------
	// Zoom

	private void onMouseWheel(MouseWheelEvent e) {
		if (!e.isControlDown() || image == null) {
			// Not a zoom gesture: let an ancestor (e.g. a scroll pane) handle it.
			java.awt.Container parent = getParent();
			if (parent != null) {
				parent.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, parent));
			}
			return;
		}
		double base = baseFitScale();
		if (base <= 0) {
			return;
		}
		// Negative rotation == wheel up == zoom in.
		double current = base * zoomMultiplier;
		double next = current * Math.pow(ZOOM_STEP, -e.getWheelRotation());
		next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, next));
		if (next >= SNAP_LOW && next <= SNAP_HIGH) {
			next = 1.0; // snap to actual pixels
		}
		zoomMultiplier = next / base;
		repaint();
	}

	/** Fit-to-panel scale (contain, never upscaling) used as the 1.0 zoom baseline. */
	private double baseFitScale() {
		BufferedImage img = image;
		if (img == null) {
			return 0;
		}
		int panelW = getWidth();
		int panelH = getHeight();
		int imgW = img.getWidth();
		int imgH = img.getHeight();
		if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0) {
			return 0;
		}
		return Math.min(1.0, Math.min((double) panelW / imgW, (double) panelH / imgH));
	}

	// -------------------------------------------------------------------------
	// Background loading

	private void doLoad(NuclrResource item, AtomicBoolean cancelled) {
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
			log.warn("ImageMagick Bridge: cannot load '{}': {}", item.getName(), e.getMessage());
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
		g.setColor(backgroundColor);
		g.fillRect(0, 0, getWidth(), getHeight());

		if (loading) {
			drawCenteredText(g, "Converting\u2026", loadingColor);
		} else if (image != null) {
			double scale = baseFitScale() * zoomMultiplier;
			drawImage((Graphics2D) g, scale);
			drawZoomBadge((Graphics2D) g.create(), scale);
		} else if (statusMessage != null) {
			drawErrorCard((Graphics2D) g.create(), statusMessage);
		}
	}

	private void drawImage(Graphics2D g2orig, double scale) {
		final int panelW = getWidth();
		final int panelH = getHeight();
		final int imgW = image.getWidth();
		final int imgH = image.getHeight();

		if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0 || scale <= 0) {
			return;
		}

		int drawW = (int) Math.round(imgW * scale);
		int drawH = (int) Math.round(imgH * scale);
		int x = (panelW - drawW) / 2 + panX;
		int y = (panelH - drawH) / 2 + panY;

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

	/** Draws the current zoom percentage as a small pill in the bottom-right corner. */
	private void drawZoomBadge(Graphics2D g2, double scale) {
		try {
			if (scale <= 0) {
				return;
			}
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			String text = Math.round(scale * 100) + "%";
			Font font = (getFont() != null ? getFont() : new Font(Font.DIALOG, Font.PLAIN, 12))
					.deriveFont(Font.BOLD, 12f);
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics(font);

			int padX = 8;
			int padY = 4;
			int pillW = fm.stringWidth(text) + padX * 2;
			int pillH = fm.getHeight() + padY * 2;
			int margin = 10;
			int pillX = getWidth() - pillW - margin;
			int pillY = getHeight() - pillH - margin;
			int arc = pillH;

			g2.setColor(cardBackgroundColor);
			g2.fillRoundRect(pillX, pillY, pillW, pillH, arc, arc);
			g2.setColor(cardBorderColor);
			g2.drawRoundRect(pillX, pillY, pillW, pillH, arc, arc);

			g2.setColor(detailColor);
			int textX = pillX + padX;
			int textY = pillY + padY + fm.getAscent();
			g2.drawString(text, textX, textY);
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

	private void drawErrorCard(Graphics2D g2, String message) {
		try {
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			Font baseFont = getFont() != null ? getFont() : new Font(Font.DIALOG, Font.PLAIN, 12);
			Font titleFont = baseFont.deriveFont(Font.BOLD, Math.max(15f, baseFont.getSize2D() + 2f));
			Font bodyFont = baseFont.deriveFont(Font.PLAIN, Math.max(12f, baseFont.getSize2D()));

			String title = classifyErrorTitle(message);
			List<String> lines = wrapMessage(message, MESSAGE_WRAP_WIDTH);

			FontMetrics titleMetrics = g2.getFontMetrics(titleFont);
			FontMetrics bodyMetrics = g2.getFontMetrics(bodyFont);

			int contentWidth = titleMetrics.stringWidth(title);
			for (String line : lines) {
				contentWidth = Math.max(contentWidth, bodyMetrics.stringWidth(line));
			}

			int horizontalPadding = 18;
			int verticalPadding = 16;
			int lineGap = 6;
			int titleGap = lines.isEmpty() ? 0 : 10;
			int cardWidth = Math.min(getWidth() - 24, contentWidth + horizontalPadding * 2);
			int cardHeight = verticalPadding * 2 + titleMetrics.getHeight();
			if (!lines.isEmpty()) {
				cardHeight += titleGap + (lines.size() * bodyMetrics.getHeight()) + ((lines.size() - 1) * lineGap);
			}

			int cardX = Math.max(12, (getWidth() - cardWidth) / 2);
			int cardY = Math.max(12, (getHeight() - cardHeight) / 2);
			int arc = 18;

			g2.setColor(cardBackgroundColor);
			g2.fillRoundRect(cardX, cardY, cardWidth, cardHeight, arc, arc);
			g2.setColor(cardBorderColor);
			g2.drawRoundRect(cardX, cardY, cardWidth, cardHeight, arc, arc);

			int textX = cardX + horizontalPadding;
			int y = cardY + verticalPadding + titleMetrics.getAscent();

			g2.setFont(titleFont);
			g2.setColor(errorColor);
			g2.drawString(title, textX, y);

			if (!lines.isEmpty()) {
				y += titleMetrics.getDescent() + titleGap + bodyMetrics.getAscent();
				g2.setFont(bodyFont);
				g2.setColor(detailColor);
				for (String line : lines) {
					g2.drawString(line, textX, y);
					y += bodyMetrics.getHeight() + lineGap;
				}
			}
		} finally {
			g2.dispose();
		}
	}

	private static String classifyErrorTitle(String message) {
		if (message == null || message.isBlank()) {
			return "Preview unavailable";
		}
		String normalized = message.toLowerCase();
		if (normalized.contains("not available") || normalized.contains("not found") || normalized.contains("initialised")) {
			return "ImageMagick unavailable";
		}
		if (normalized.contains("timed out")) {
			return "Conversion timed out";
		}
		if (normalized.contains("too large")) {
			return "File too large";
		}
		return "Preview failed";
	}

	private static List<String> wrapMessage(String message, int maxChars) {
		List<String> wrapped = new ArrayList<>();
		if (message == null || message.isBlank()) {
			return wrapped;
		}

		String normalized = message.replace('\r', ' ').replace('\n', ' ').trim();
		String[] words = normalized.split("\\s+");
		StringBuilder line = new StringBuilder();
		for (String word : words) {
			if (line.isEmpty()) {
				line.append(word);
				continue;
			}
			if (line.length() + 1 + word.length() <= maxChars) {
				line.append(' ').append(word);
				continue;
			}
			wrapped.add(line.toString());
			line = new StringBuilder(word);
		}
		if (!line.isEmpty()) {
			wrapped.add(line.toString());
		}

		if (wrapped.size() > 4) {
			List<String> shortened = new ArrayList<>(wrapped.subList(0, 4));
			int last = shortened.size() - 1;
			shortened.set(last, ellipsize(shortened.get(last), maxChars));
			return shortened;
		}
		return wrapped;
	}

	private static String ellipsize(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, Math.max(0, maxChars - 1)).trim() + "\u2026";
	}
}
