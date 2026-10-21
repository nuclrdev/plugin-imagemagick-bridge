package dev.nuclr.plugin.core.imagemagick.bridge;

import static dev.nuclr.plugin.core.imagemagick.bridge.Messages.tr;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.filechooser.FileNameExtensionFilter;

import lombok.extern.slf4j.Slf4j;

/** OS-specific installation help shown only when a preview needs ImageMagick. */
@Slf4j
final class ImageMagickSetupDialog {

	private static final String DOWNLOAD_URL = "https://imagemagick.org/download/";
	private static final String SOURCE_URL = "https://imagemagick.org/install-source/";
	private static final String HOMEBREW_URL = "https://brew.sh/";

	enum Action {
		LOCATE_EXECUTABLE,
		DISMISS
	}

	private ImageMagickSetupDialog() {
	}

	static Action show(Component parent) {
		if (GraphicsEnvironment.isHeadless()) {
			return Action.DISMISS;
		}

		Action[] result = { Action.DISMISS };
		Runnable showDialog = () -> result[0] = showOnEdt(parent, installationHelp());
		if (SwingUtilities.isEventDispatchThread()) {
			showDialog.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(showDialog);
			} catch (InvocationTargetException e) {
				log.error("Could not show ImageMagick installation help", e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return result[0];
	}

	private static Action showOnEdt(Component parent, InstallationHelp help) {
		JEditorPane message = createMessage(help);
		Object[] options = help.command() == null
				? new Object[] { tr("setup.locate"), tr("setup.not.now") }
				: new Object[] { tr("setup.copy.command"), tr("setup.locate"), tr("setup.not.now") };

		int choice = JOptionPane.showOptionDialog(
				visibleParent(parent),
				message,
				tr("setup.title"),
				JOptionPane.DEFAULT_OPTION,
				JOptionPane.WARNING_MESSAGE,
				null,
				options,
				options[options.length - 1]);

		if (help.command() != null && choice == 0) {
			copyToClipboard(help.command());
			return Action.DISMISS;
		}
		int locateChoice = help.command() == null ? 0 : 1;
		return choice == locateChoice ? Action.LOCATE_EXECUTABLE : Action.DISMISS;
	}

	static Path showLocateDialog(Component parent) {
		if (GraphicsEnvironment.isHeadless()) {
			return null;
		}

		Path[] result = { null };
		Runnable showChooser = () -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle(tr("locate.title"));
			chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
			chooser.setApproveButtonText(tr("locate.approve"));
			setInitialDirectory(chooser);

			if (isWindows(System.getProperty("os.name", ""))) {
				chooser.setFileFilter(new FileNameExtensionFilter(tr("locate.filter.exe"), "exe"));
			}
			if (chooser.showOpenDialog(visibleParent(parent)) == JFileChooser.APPROVE_OPTION) {
				result[0] = chooser.getSelectedFile().toPath();
			}
		};

		if (SwingUtilities.isEventDispatchThread()) {
			showChooser.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(showChooser);
			} catch (InvocationTargetException e) {
				log.error("Could not show ImageMagick executable picker", e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return result[0];
	}

	private static void setInitialDirectory(JFileChooser chooser) {
		String osName = System.getProperty("os.name", "");
		File directory;
		if (isWindows(osName)) {
			directory = new File(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"));
		} else if (isMac(osName)) {
			directory = new File("/opt/homebrew/bin");
			if (!directory.isDirectory()) {
				directory = new File("/usr/local/bin");
			}
		} else {
			directory = new File("/usr/local/bin");
		}
		if (directory.isDirectory()) {
			chooser.setCurrentDirectory(directory);
		}
	}

	static InstallationHelp installationHelp() {
		return installationHelp(System.getProperty("os.name", ""));
	}

	static InstallationHelp installationHelp(String osName) {
		if (isWindows(osName)) {
			return new InstallationHelp(
					"Windows",
					tr("setup.windows.explanation"),
					"winget install ImageMagick.Q16",
					links(link(DOWNLOAD_URL, tr("setup.windows.download"))));
		}
		if (isMac(osName)) {
			return new InstallationHelp(
					"macOS",
					tr("setup.mac.explanation"),
					"brew install imagemagick",
					links(link(HOMEBREW_URL, tr("setup.mac.homebrew")), link(DOWNLOAD_URL, tr("setup.mac.download"))));
		}
		if (isLinux(osName)) {
			return new InstallationHelp(
					"Linux",
					tr("setup.linux.explanation"),
					null,
					links(link(DOWNLOAD_URL, tr("setup.linux.appimage")), link(SOURCE_URL, tr("setup.linux.source"))));
		}
		return new InstallationHelp(
				tr("setup.other.platform"),
				tr("setup.other.explanation"),
				null,
				links(link(DOWNLOAD_URL, tr("setup.other.downloads")), link(SOURCE_URL, tr("setup.other.source"))));
	}

	static JEditorPane createMessage(InstallationHelp help) {
		String command = help.command() == null ? "" : "<p>" + html(tr("setup.run.in.terminal")) + "</p>"
				+ "<p style='margin-left:12px;font-family:monospace;'><b>" + html(help.command()) + "</b></p>";
		String content = "<html><body style='font-family:sans-serif;width:540px;'>"
				+ "<h2>" + html(tr("setup.heading", help.platform())) + "</h2>"
				+ "<p>" + tr("setup.not.found.html") + "</p>"
				+ "<p>" + html(help.explanation()) + "</p>"
				+ command
				+ "<p>" + help.linksHtml() + "</p>"
				+ "<p>" + tr("setup.after.install.html", html(tr("setup.locate"))) + "</p>"
				+ "</body></html>";

		JEditorPane message = new JEditorPane("text/html", content);
		message.setEditable(false);
		message.setOpaque(false);
		message.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		message.addHyperlinkListener(event -> {
			if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
				openLink(event.getURL().toExternalForm());
			}
		});
		return message;
	}

	private static Component visibleParent(Component parent) {
		return parent != null && parent.isShowing() ? parent : null;
	}

	private static String link(String url, String label) {
		return "<a href='" + url + "'>" + html(label) + "</a>";
	}

	private static String links(String... values) {
		return String.join(" &nbsp;|&nbsp; ", values);
	}

	private static String html(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static void copyToClipboard(String text) {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		} catch (Exception e) {
			log.warn("Could not copy ImageMagick installation text: {}", e.getMessage());
		}
	}

	private static void openLink(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
			} else {
				copyToClipboard(url);
			}
		} catch (Exception e) {
			log.warn("Could not open ImageMagick help link [{}]: {}", url, e.getMessage());
			copyToClipboard(url);
		}
	}

	private static boolean isWindows(String osName) {
		return osName.toLowerCase(Locale.ROOT).startsWith("win");
	}

	private static boolean isMac(String osName) {
		return osName.toLowerCase(Locale.ROOT).contains("mac");
	}

	private static boolean isLinux(String osName) {
		return osName.toLowerCase(Locale.ROOT).contains("linux");
	}

	record InstallationHelp(String platform, String explanation, String command, String linksHtml) {
	}
}
