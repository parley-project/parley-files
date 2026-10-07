package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.CommandLinePropertyEditor;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSetting.Kind;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.io.LFLineReader;
import us.bringardner.parley.io.LFLineWriter;

/** Connection settings described without UI types, and the text-mode editor that uses them. */
public class ConnectionSettingsTests {

	/** Like an SFTP connection: host, port, and either a password or a key file. */
	static final List<ConnectionSetting> SETTINGS = List.of(
			ConnectionSetting.text("host", "Host").asRequired(),
			ConnectionSetting.integer("port", "Port", 1L, 65535L).withDefault("22"),
			ConnectionSetting.choice("auth", "Authentication", "password", "keyFile"),
			ConnectionSetting.secret("password", "Password").visibleWhen("auth", "password"),
			ConnectionSetting.localFile("identityFile", "Key file").visibleWhen("auth", "keyFile").asRequired(),
			ConnectionSetting.integer("timeout", "Timeout", 0L, null).asAdvanced(),
			ConnectionSetting.hidden("sessionKey"));

	private static Properties props(String... kv) {
		Properties p = new Properties();
		for(int i=0; i < kv.length; i += 2) {
			p.setProperty(kv[i], kv[i+1]);
		}
		return p;
	}

	@Test
	public void checksValues() {
		ConnectionSetting port = ConnectionSettings.find(SETTINGS, "port");
		assertNull(port.check("22"));
		assertNull(port.check(""));
		assertEquals("Port must be a whole number", port.check("x"));
		assertEquals("Port must be between 1 and 65535", port.check("70000"));
		assertEquals("Timeout must be at least 0", ConnectionSettings.find(SETTINGS, "timeout").check("-1"));
		assertEquals("Host is required", ConnectionSettings.find(SETTINGS, "host").check("  "));
		assertEquals("Authentication must be one of password, keyFile", ConnectionSettings.find(SETTINGS, "auth").check("pin"));
		assertEquals("On must be true or false", ConnectionSetting.bool("on", "On").check("yes"));
		assertThrows(IllegalArgumentException.class, ()->ConnectionSetting.text("", "x"));
	}

	@Test
	public void defaults() {
		Properties start = ConnectionSettings.initialValues(SETTINGS, props("host", "example.com"));
		assertEquals("example.com", start.getProperty("host"));
		assertEquals("22", start.getProperty("port"));
		// a choice defaults to its first value
		assertEquals("password", start.getProperty("auth"));
		assertEquals("false", ConnectionSetting.bool("on", "On").defaultValue());
	}

	@Test
	public void onlyTheSettingsThatApply() {
		Properties values = props("host", "h", "auth", "password", "password", "pw", "identityFile", "/k");
		assertTrue(ConnectionSettings.isVisible(ConnectionSettings.find(SETTINGS, "password"), SETTINGS, values));
		assertFalse(ConnectionSettings.isVisible(ConnectionSettings.find(SETTINGS, "identityFile"), SETTINGS, values));
		assertFalse(ConnectionSettings.isVisible(ConnectionSettings.find(SETTINGS, "sessionKey"), SETTINGS, values));
		// the key file isn't required while a password is used
		assertEquals(List.of(), ConnectionSettings.validate(SETTINGS, values));

		values.setProperty("auth", "keyFile");
		values.setProperty("identityFile", "");
		assertEquals(List.of("Key file is required"), ConnectionSettings.validate(SETTINGS, values));
	}

	@Test
	public void settingsThatDontApplyAreNotSent() {
		Properties values = props("host", "h", "auth", "keyFile", "password", "old", "identityFile", "/k",
				"sessionKey", "abc", "other", "kept");
		Properties sent = ConnectionSettings.forConnect(SETTINGS, values);
		assertEquals("", sent.getProperty("password"));
		assertEquals("/k", sent.getProperty("identityFile"));
		assertEquals("abc", sent.getProperty("sessionKey"));
		assertEquals("kept", sent.getProperty("other"));
	}

	@Test
	public void describingPlainProperties() {
		List<ConnectionSetting> described = ConnectionSettings.describe(
				props("JdbcURL", "u", "jdbcPassword", "p", "user", "x"), "jdbc", n->n.toLowerCase().contains("password"));
		assertEquals(3, described.size());
		assertEquals("URL", ConnectionSettings.find(described, "JdbcURL").label());
		assertEquals(Kind.SECRET, ConnectionSettings.find(described, "jdbcPassword").kind());
		assertEquals("user", ConnectionSettings.find(described, "user").label());
	}

	@Test
	public void factoriesDescribeTheirPropertiesByDefault() throws IOException {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		List<ConnectionSetting> settings = factory.getConnectionSettings();
		assertEquals(factory.getConnectProperties() == null ? 0 : factory.getConnectProperties().size(), settings.size());
		assertEquals(List.of(), factory.validateConnection(factory.getConnectProperties()));
	}

	@Test
	public void listingWithoutProgress() throws IOException {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource dir = factory.createFileSource("/progress");
		dir.mkdirs();
		dir.getChild("a").createNewFile();
		assertEquals(1, dir.listFiles((us.bringardner.parley.files.FileSourceProgress) null).length);
	}

	// ---- the text-mode editor

	private String run(String typed, Properties values, boolean expectAccepted) throws IOException {
		ByteArrayOutputStream shown = new ByteArrayOutputStream();
		LFLineWriter out = new LFLineWriter(shown);
		CommandLinePropertyEditor editor = new CommandLinePropertyEditor(new LFLineReader(typed), out);
		assertEquals(expectAccepted, editor.editProperties("test", values, SETTINGS));
		out.flush();
		return shown.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void editorAsksForWhatApplies() throws IOException {
		Properties values = props("password", "hunter2");
		// host, port (keep), auth (keep), password (keep), timeout, then ok
		String shown = run("example.com\n\n\n\n5\nok\n", values, true);
		assertEquals("example.com", values.getProperty("host"));
		assertEquals("22", values.getProperty("port"));
		assertEquals("hunter2", values.getProperty("password"));
		assertEquals("5", values.getProperty("timeout"));
		assertTrue(shown.contains("Enter Password or enter to keep set"), shown);
		assertFalse(shown.contains("hunter2"), "a secret was shown: "+shown);
		assertFalse(shown.contains("Key file"), "a setting that doesn't apply was asked for");
	}

	@Test
	public void editorRejectsBadValues() throws IOException {
		Properties values = props("host", "h");
		String shown = run("\nx\n2222\nok\n", values, true);
		assertTrue(shown.contains("Port must be a whole number"), shown);
		assertEquals("2222", values.getProperty("port"));
	}

	@Test
	public void editorWontAcceptMissingValues() throws IOException {
		Properties values = new Properties();
		String shown = run("ok\nexample.com\nok\n", values, true);
		assertTrue(shown.contains("Host is required"), shown);
		assertEquals("example.com", values.getProperty("host"));
	}

	@Test
	public void editorCanBeCanceled() throws IOException {
		Properties values = props("host", "h");
		run("other\ncancel\n", values, false);
		assertEquals("h", values.getProperty("host"));
	}
}
