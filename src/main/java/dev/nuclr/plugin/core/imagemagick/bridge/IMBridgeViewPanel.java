package dev.nuclr.plugin.core.imagemagick.bridge;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Cursor;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

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
	private volatile ScaledImage scaledCache;
	private volatile String statusMessage;
	private volatile boolean loading;
	private volatile Thread loadingThread;

	private record ScaledImage(BufferedImage source, int width, int height, BufferedImage bitmap) {
	}

	/** User zoom factor relative to the fit-to-panel scale; 1.0 == fit. */
	private double zoomMultiplier = 1.0;

	/** Pan offset (pixels) applied on top of the centered image position. */
	private int panX = 0;
	private int panY = 0;
	private int dragStartX;
	private int dragStartY;
	private boolean dragging;

	private final JPopupMenu contextMenu = new JPopupMenu();
	private final JMenuItem copyImageItem = new JMenuItem("Copy image to clipboard");

	public IMBridgeViewPanel(IMBridgeService service) {
		this.service = service;
		setBackground(backgroundColor);
		setOpaque(true);
		copyImageItem.addActionListener(e -> copyImageToClipboard());
		contextMenu.add(copyImageItem);
		addMouseWheelListener(this::onMouseWheel);
		installPanHandlers();
	}

	private void installPanHandlers() {
		MouseAdapter panHandler = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (maybeShowPopup(e)) {
					return;
				}
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
				if (maybeShowPopup(e)) {
					return;
				}
				if (dragging && SwingUtilities.isMiddleMouseButton(e)) {
					dragging = false;
					setCursor(Cursor.getDefaultCursor());
				}
			}
		};
		addMouseListener(panHandler);
		addMouseMotionListener(panHandler);
	}

	/** Shows the context menu if {@code e} is the platform popup trigger. */
	private boolean maybeShowPopup(MouseEvent e) {
		if (!e.isPopupTrigger()) {
			return false;
		}
		copyImageItem.setEnabled(image != null);
		contextMenu.show(this, e.getX(), e.getY());
		return true;
	}

	// -------------------------------------------------------------------------
	// Clipboard

	private void copyImageToClipboard() {
		BufferedImage img = image;
		if (img == null) {
			return;
		}
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(new ImageTransferable(img), null);
		} catch (Exception ex) {
			log.warn("ImageMagick Bridge: failed to copy image to clipboard: {}", ex.getMessage());
			log.debug("ImageMagick Bridge clipboard error detail", ex);
		}
	}

	/** Minimal {@link Transferable} exposing a single image via {@link DataFlavor#imageFlavor}. */
	private static final class ImageTransferable implements Transferable {
		private final Image image;

		ImageTransferable(Image image) {
			this.image = image;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.imageFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!DataFlavor.imageFlavor.equals(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return image;
		}
	}

	public void applyTheme(NuclrThemeScheme theme) {
		// The active FlatLaf colors live in the shared UIManager (the host installs the
		// look-and-feel and any scheme overrides there before notifying plugins). The
		// scheme's own palette is sparse and rarely carries standard keys like
		// "Panel.background", so UIManager is the source of truth; the scheme is only
		// an optional override on top of it, and the current values are the last resort.
		backgroundColor = resolve(theme, "Panel.background", backgroundColor);
		loadingColor = resolve(theme, "Label.foreground", loadingColor);
		errorColor = resolve(theme, "Component.error.focusedBorderColor", errorColor);
		cardBackgroundColor = resolve(theme, "TextField.background", cardBackgroundColor);
		cardBorderColor = resolve(theme, "Table.gridColor", cardBorderColor);
		detailColor = resolve(theme, "Label.foreground", detailColor);
		setBackground(backgroundColor);
		repaint();
	}

	/** Resolves a color key: scheme override → active UIManager LaF → current default. */
	private static Color resolve(NuclrThemeScheme theme, String key, Color fallback) {
		Color uiColor = UIManager.getColor(key);
		Color base = uiColor != null ? uiColor : fallback;
		return theme != null ? theme.color(key, base) : base;
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
		scaledCache = null;
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
		scaledCache = null;
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
		return baseFitScale(img, getWidth(), getHeight());
	}

	private static double baseFitScale(BufferedImage img, int panelW, int panelH) {
		if (img == null) {
			return 0;
		}
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
		Thread thisThread = Thread.currentThread();
		try {
			BufferedImage img = service.convertToPng(item);
			if (cancelled.get() || loadingThread != thisThread) return;
			ScaledImage fitCache = buildInitialScaledCache(img);
			if (cancelled.get() || loadingThread != thisThread) return;
			SwingUtilities.invokeLater(() -> {
				if (cancelled.get() || loadingThread != thisThread) return;
				image = img;
				scaledCache = fitCache;
				statusMessage = null;
				loading = false;
				repaint();
			});
		} catch (Exception e) {
			if (cancelled.get() || loadingThread != thisThread) return;
			String msg = toFriendlyMessage(e);
			log.warn("ImageMagick Bridge: cannot load '{}': {}", item.getName(), e.getMessage());
			log.debug("ImageMagick Bridge load error detail", e);
			SwingUtilities.invokeLater(() -> {
				if (cancelled.get() || loadingThread != thisThread) return;
				image = null;
				scaledCache = null;
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
		BufferedImage img = image;
		if (img == null) {
			return;
		}
		final int panelW = getWidth();
		final int panelH = getHeight();
		final int imgW = img.getWidth();
		final int imgH = img.getHeight();

		if (panelW <= 0 || panelH <= 0 || imgW <= 0 || imgH <= 0 || scale <= 0) {
			return;
		}

		int drawW = scaledDimension(imgW, scale);
		int drawH = scaledDimension(imgH, scale);
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

			if (scale < 1.0) {
				BufferedImage prescaled = getOrBuildScaled(img, drawW, drawH);
				if (prescaled != null && prescaled.getWidth() == drawW && prescaled.getHeight() == drawH) {
					g2.drawImage(prescaled, x, y, null);
					return;
				}
			}

			drawVisibleImageRegion(g2, img, x, y, drawW, drawH, scale, panelW, panelH);
		} finally {
			g2.dispose();
		}
	}

	private ScaledImage buildInitialScaledCache(BufferedImage img) {
		try {
			int panelW = getWidth();
			int panelH = getHeight();
			double scale = baseFitScale(img, panelW, panelH);
			if (scale <= 0 || scale >= 1.0) {
				return null;
			}
			int drawW = scaledDimension(img.getWidth(), scale);
			int drawH = scaledDimension(img.getHeight(), scale);
			BufferedImage bitmap = createScaled(img, drawW, drawH);
			return new ScaledImage(img, drawW, drawH, bitmap);
		} catch (Exception e) {
			log.debug("Could not pre-build ImageMagick Bridge scaled preview", e);
			return null;
		}
	}

	private BufferedImage getOrBuildScaled(BufferedImage src, int w, int h) {
		if (src == null || w <= 0 || h <= 0) {
			return null;
		}
		if (w >= src.getWidth() && h >= src.getHeight()) {
			return src;
		}

		ScaledImage cached = scaledCache;
		if (cached != null && cached.source() == src && cached.width() == w && cached.height() == h) {
			return cached.bitmap();
		}

		BufferedImage scaled = createScaled(src, w, h);
		scaledCache = new ScaledImage(src, w, h, scaled);
		return scaled;
	}

	private BufferedImage createScaled(BufferedImage src, int targetW, int targetH) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage current = src;

		while ((long) w > (long) targetW * 2 || (long) h > (long) targetH * 2) {
			w = Math.max(targetW, w / 2);
			h = Math.max(targetH, h / 2);
			current = renderResized(current, w, h);
		}

		return renderResized(current, targetW, targetH);
	}

	private BufferedImage renderResized(BufferedImage src, int w, int h) {
		BufferedImage dst = newCompatibleImage(w, h, src.getColorModel().hasAlpha());
		Graphics2D g = dst.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return dst;
	}

	private static BufferedImage newCompatibleImage(int w, int h, boolean hasAlpha) {
		int transparency = hasAlpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE;
		GraphicsConfiguration gc = defaultConfiguration();
		if (gc != null) {
			return gc.createCompatibleImage(w, h, transparency);
		}
		return new BufferedImage(w, h, hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
	}

	private static GraphicsConfiguration defaultConfiguration() {
		try {
			if (GraphicsEnvironment.isHeadless()) {
				return null;
			}
			return GraphicsEnvironment.getLocalGraphicsEnvironment()
					.getDefaultScreenDevice()
					.getDefaultConfiguration();
		} catch (Exception e) {
			return null;
		}
	}

	private static void drawVisibleImageRegion(
			Graphics2D g2,
			BufferedImage img,
			int x,
			int y,
			int drawW,
			int drawH,
			double scale,
			int panelW,
			int panelH) {
		Rectangle clip = g2.getClipBounds();
		int clipX1 = 0;
		int clipY1 = 0;
		int clipX2 = panelW;
		int clipY2 = panelH;
		if (clip != null) {
			clipX1 = Math.max(0, clip.x);
			clipY1 = Math.max(0, clip.y);
			clipX2 = Math.min(panelW, (int) Math.min(Integer.MAX_VALUE, (long) clip.x + clip.width));
			clipY2 = Math.min(panelH, (int) Math.min(Integer.MAX_VALUE, (long) clip.y + clip.height));
		}

		long destX1 = x;
		long destY1 = y;
		long destX2 = destX1 + drawW;
		long destY2 = destY1 + drawH;

		int dx1 = (int) Math.max(clipX1, destX1);
		int dy1 = (int) Math.max(clipY1, destY1);
		int dx2 = (int) Math.min(clipX2, destX2);
		int dy2 = (int) Math.min(clipY2, destY2);
		if (dx1 >= dx2 || dy1 >= dy2) {
			return;
		}

		int imgW = img.getWidth();
		int imgH = img.getHeight();
		int sx1 = clamp((int) Math.floor((dx1 - destX1) / scale), 0, imgW - 1);
		int sy1 = clamp((int) Math.floor((dy1 - destY1) / scale), 0, imgH - 1);
		int sx2 = clamp((int) Math.ceil((dx2 - destX1) / scale), sx1 + 1, imgW);
		int sy2 = clamp((int) Math.ceil((dy2 - destY1) / scale), sy1 + 1, imgH);

		g2.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
	}

	private static int scaledDimension(int value, double scale) {
		double scaled = value * scale;
		if (!Double.isFinite(scaled) || scaled >= Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return Math.max(1, (int) Math.round(scaled));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
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
