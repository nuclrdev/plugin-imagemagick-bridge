package dev.nuclr.plugin.core.imagemagick.bridge;

import javax.swing.JComponent;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;

public class IMBridgeQuickViewProvider implements QuickViewProvider {

	private IMBridgeViewPanel panel;

	@Override
	public String getPluginClass() {
		return getClass().getName();
	}

	@Override
	public boolean matches(QuickViewItem item) {
		return IMBridgeViewPanel.IMAGE_EXTENSIONS.contains(item.extension().toLowerCase());
	}

	@Override
	public JComponent getPanel() {
		if (this.panel == null) {
			this.panel = new IMBridgeViewPanel();
		}
		return panel;
	}

	@Override
	public void open(QuickViewItem item) {
		getPanel(); // ensure panel exists
		this.panel.load(item);
	}

	@Override
	public void close() {
		if (this.panel != null) {
			this.panel.clear();
		}
	}

	@Override
	public void unload() {
		close();
		this.panel = null;
	}

}
