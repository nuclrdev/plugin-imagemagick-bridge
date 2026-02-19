package dev.nuclr.plugin.core.imagemagick.bridge;

import java.awt.Color;
import java.awt.Graphics;
import java.util.HashSet;
import java.util.Set;

import javax.swing.JPanel;

import dev.nuclr.plugin.QuickViewItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class IMBridgeViewPanel extends JPanel {

	static final Set<String> IMAGE_EXTENSIONS = new HashSet<>();

	public void load(QuickViewItem item) {
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		g.setColor(Color.black);
		g.fillRect(0, 0, getWidth(), getHeight());

	}

	public void clear() {
	}
}
