package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.List;
import java.util.Properties;

import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Swing form drawn from connection settings (headless: no window is shown). */
public class ConnectionSettingsPanelTests {

	static final List<ConnectionSetting> SETTINGS = List.of(
			ConnectionSetting.text("host", "Host").asRequired(),
			ConnectionSetting.integer("port", "Port", 1L, 65535L).withDefault("22"),
			ConnectionSetting.choice("auth", "Authentication", "password", "keyFile"),
			ConnectionSetting.secret("password", "Password").visibleWhen("auth", "password"),
			ConnectionSetting.localFile("identityFile", "Key file").visibleWhen("auth", "keyFile"),
			ConnectionSetting.integer("timeout", "Timeout", 0L, null).asAdvanced(),
			ConnectionSetting.hidden("sessionKey"));

	@BeforeAll
	public static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private static void onEdt(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}

	@Test
	public void aFieldOfTheRightKindForEachSetting() throws Exception {
		onEdt(()->{
			ConnectionSettingsPanel panel = new ConnectionSettingsPanel();
			Properties values = new Properties();
			values.setProperty("host", "example.com");
			values.setProperty("password", "pw");
			values.setProperty("sessionKey", "abc");
			panel.setSettings(SETTINGS, values);
			assertTrue(panel.fieldFor("password") instanceof JPasswordField);
			assertTrue(panel.fieldFor("auth") instanceof JComboBox);
			assertEquals("22", ((JTextField) panel.fieldFor("port")).getText());
			assertEquals(null, panel.fieldFor("sessionKey"));

			Properties got = panel.getProperties();
			assertEquals("example.com", got.getProperty("host"));
			assertEquals("pw", got.getProperty("password"));
			// hidden settings are carried through
			assertEquals("abc", got.getProperty("sessionKey"));
		});
	}

	@Test
	public void choosingShowsAndHidesSettings() throws Exception {
		onEdt(()->{
			ConnectionSettingsPanel panel = new ConnectionSettingsPanel();
			Properties values = new Properties();
			values.setProperty("password", "pw");
			panel.setSettings(SETTINGS, values);
			Component password = panel.fieldFor("password");
			Component keyFile = ((JTextField) panel.fieldFor("identityFile")).getParent();
			assertTrue(password.isVisible());
			assertFalse(keyFile.isVisible());
			// advanced settings stay out of the way
			assertFalse(panel.fieldFor("timeout").isVisible());

			@SuppressWarnings("unchecked")
			JComboBox<String> auth = (JComboBox<String>) panel.fieldFor("auth");
			auth.setSelectedItem("keyFile");
			assertFalse(password.isVisible());
			assertTrue(keyFile.isVisible());
			((JTextField) panel.fieldFor("identityFile")).setText("/home/me/.ssh/id");
			((JTextField) panel.fieldFor("host")).setText("h");

			Properties got = panel.getProperties();
			// the password no longer applies, so it isn't sent
			assertEquals("", got.getProperty("password"));
			assertEquals("/home/me/.ssh/id", got.getProperty("identityFile"));
			assertEquals(List.of(), panel.validateValues());
		});
	}

	@Test
	public void problemsAreReported() throws Exception {
		onEdt(()->{
			ConnectionSettingsPanel panel = new ConnectionSettingsPanel();
			panel.setSettings(SETTINGS, new Properties());
			((JTextField) panel.fieldFor("port")).setText("abc");
			assertEquals(List.of("Host is required", "Port must be a whole number"), panel.validateValues());
		});
	}

	@Test
	public void propertiesNobodyDescribed() throws Exception {
		onEdt(()->{
			ConnectionSettingsPanel panel = new ConnectionSettingsPanel();
			Properties values = new Properties();
			values.setProperty("url", "jdbc:x");
			values.setProperty("password", "pw");
			panel.setProperties(values);
			assertTrue(panel.fieldFor("password") instanceof JPasswordField);
			assertEquals("jdbc:x", panel.getProperties().getProperty("url"));
		});
	}
}
