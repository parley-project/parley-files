package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.browse.RecentFile;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** A recent files entry never saves secrets, and asks for them when it's opened. */
public class RecentFileTests {

	private static final String BASE = "us/bringardner/parley/files/test/RecentFileTests";
	private static final String PASSWORD = "s3cret|,=&~";
	private static final String USER = "b|o,b=&~";

	/**
	 * A memory factory whose connection properties include secrets, like a remote one.
	 * It names its secrets exactly: "pin" isn't something the name guess would catch,
	 * and "passphrase" is a secret it reports empty (not used by this connection).
	 */
	public static class SecretFactory extends MemoryFileSourceFactory {
		private static final long serialVersionUID = 1L;
		private static final List<String> SECRETS = Arrays.asList("password", "jdbcPassword", "pin", "passphrase");

		@Override
		public Properties getConnectProperties() {
			Properties p = super.getConnectProperties();
			p.setProperty("user", USER);
			p.setProperty("password", PASSWORD);
			p.setProperty("jdbcPassword", "pw2");
			p.setProperty("pin", "4321");
			p.setProperty("passphrase", "");
			return p;
		}

		@Override
		public boolean isSecretProperty(String name) {
			return SECRETS.contains(name);
		}
	}

	@AfterAll
	public static void removeBase() throws BackingStoreException {
		Preferences.userRoot().node(BASE).removeNode();
		Preferences.userRoot().flush();
	}

	private static FileSource secretFile(String path) throws IOException {
		return new SecretFactory().createFileSource(path);
	}

	@Test
	public void theFactoryDecidesWhatIsSecretAndSecretsAreNeverSaved() throws IOException {
		RecentFile entry = new RecentFile(secretFile("/dir|x/a,b.txt"));
		assertTrue(entry.isSecret("pin"));
		assertTrue(entry.isSecret("passphrase"));
		assertFalse(entry.isSecret("user"));
		String saved = entry.toString();
		assertTrue(saved.startsWith("memory|"), saved);
		// needed secrets are saved by name only; the unused one not at all
		assertTrue(saved.endsWith(",jdbcPassword,password,pin|/dir|x/a,b.txt"), saved);
		assertFalse(saved.contains("password="), saved);
		assertFalse(saved.contains("passphrase"), saved);
		assertFalse(saved.contains("4321") || saved.contains("pw2") || saved.contains("s3cret"), saved);
		// kept in memory for this session
		assertEquals(PASSWORD, entry.prop.getProperty("password"));
	}

	@Test
	public void theSavedFormIsReadBackAndAsksForSecrets() throws IOException {
		RecentFile entry = new RecentFile(new RecentFile(secretFile("/dir|x/a,b.txt")).toString());
		assertEquals("/dir|x/a,b.txt", entry.path);
		assertEquals(USER, entry.prop.getProperty("user"));
		assertFalse(entry.isLocal);
		assertEquals("memory:/dir|x/a,b.txt", entry.getLabel());
		assertEquals(Arrays.asList("jdbcPassword", "password", "pin"), entry.getMissingSecrets());

		List<String> asked = new ArrayList<>();
		FileSource file = entry.open((e, name) -> { asked.add(name); return "typed"; });
		assertNotNull(file);
		assertEquals("/dir|x/a,b.txt", file.getCanonicalPath());
		assertEquals(Arrays.asList("jdbcPassword", "password", "pin"), asked);

		// asked once per session, and still never saved
		entry.open((e, name) -> { asked.add(name); return "again"; });
		assertEquals(3, asked.size());
		assertFalse(entry.toString().contains("typed"));
	}

	@Test
	public void cancellingAPromptOpensNothingAndKeepsNothing() throws IOException {
		RecentFile entry = new RecentFile(new RecentFile(secretFile("/a.txt")).toString());
		List<String> asked = new ArrayList<>();
		assertNull(entry.open((e, name) -> { asked.add(name); return asked.size() < 2 ? "first" : null; }));
		assertEquals(2, asked.size());
		assertEquals(Arrays.asList("jdbcPassword", "password", "pin"), entry.getMissingSecrets());
	}

	@Test
	public void aFailedOpenForgetsTheSecretsEntered() {
		RecentFile entry = new RecentFile("nosuchfactory|host=h,password|/x");
		assertThrows(IOException.class, () -> entry.open((e, name) -> "wrong"));
		assertEquals(Arrays.asList("password"), entry.getMissingSecrets());
	}

	@Test
	public void secretsCanBeFilledHereAndTheConnectionMadeElsewhere() throws IOException {
		RecentFile entry = new RecentFile(new RecentFile(secretFile("/a.txt")).toString());
		List<String> filled = entry.fillMissingSecrets((e, name) -> "typed");
		assertEquals(Arrays.asList("jdbcPassword", "password", "pin"), filled);
		assertTrue(entry.getMissingSecrets().isEmpty());
		entry.forgetSecrets(filled);
		assertEquals(filled, entry.getMissingSecrets());
	}

	@Test
	public void keepSecretsFromCarriesOverWhatWasTyped() throws IOException {
		RecentFile older = new RecentFile(new RecentFile(secretFile("/a.txt")).toString());
		older.fillMissingSecrets((e, name) -> "typed-"+name);
		RecentFile newer = new RecentFile("memory|user=bob|/a.txt");
		assertSame(newer, newer.keepSecretsFrom(older));
		assertEquals("typed-password", newer.prop.getProperty("password"));
		assertTrue(newer.isSecret("pin"));
		assertFalse(newer.toString().contains("typed"));
	}

	@Test
	public void sameFileSameEntry() {
		assertEquals(new RecentFile("memory|a=1|/x"), new RecentFile("memory|b=2|/x"));
		assertFalse(new RecentFile("memory||/x").equals(new RecentFile("other||/x")));
		assertThrows(IllegalArgumentException.class, () -> new RecentFile("no bars here"));
	}

	@Test
	public void onlyMissingLocalFilesAreGone() throws IOException {
		File tmp = Files.createTempFile("recent", ".txt").toFile();
		try {
			RecentFile local = new RecentFile(new RecentFile(new FileProxyFactory().createFileSource(tmp.getAbsolutePath())).toString());
			assertTrue(local.isLocal);
			assertEquals(local.path, local.getLabel());
			assertFalse(local.isGone());
			assertTrue(tmp.delete());
			assertTrue(new RecentFile(local.toString()).isGone());
			// remote entries are never checked
			assertFalse(new RecentFile("nosuchfactory|host=h|/x").isGone());
		} finally {
			tmp.delete();
		}
	}

	@Test
	public void legacyListIsMigratedWithoutSecrets() throws Exception {
		Preferences node = Preferences.userRoot().node(BASE+"/"+UUID.randomUUID());
		node.put(RecentFile.PREF_LEGACY_RECENT_LIST,
				legacyEncrypt("memory|name=,password=hunter2,user=bob|/docs/a.txt\nmemory||/docs/a.txt\n"));
		node.flush();

		assertSame(node, RecentFile.migrateLegacyList(node));
		assertNull(node.get(RecentFile.PREF_LEGACY_RECENT_LIST, null));
		String saved = node.get(RecentFile.PREF_RECENT_LIST, "");
		assertEquals(1, saved.split("\n").length, saved);
		assertTrue(saved.contains("user=bob"), saved);
		assertTrue(saved.endsWith("|/docs/a.txt\n"), saved);
		assertFalse(saved.contains("hunter2"), saved);
		// the memory factory doesn't declare secrets, so the name guess decides
		assertTrue(saved.contains(",password|") || saved.contains("|password,") || saved.contains(",password,"), saved);
	}

	@Test
	public void unreadableLegacyListIsRemovedAndANewListKept() throws Exception {
		Preferences node = Preferences.userRoot().node(BASE+"/"+UUID.randomUUID());
		node.put(RecentFile.PREF_LEGACY_RECENT_LIST, "not base64 !!");
		RecentFile.migrateLegacyList(node);
		assertNull(node.get(RecentFile.PREF_LEGACY_RECENT_LIST, null));
		assertEquals("", node.get(RecentFile.PREF_RECENT_LIST, ""));

		node.put(RecentFile.PREF_RECENT_LIST, "memory||/new\n");
		node.put(RecentFile.PREF_LEGACY_RECENT_LIST, legacyEncrypt("memory||/old\n"));
		RecentFile.migrateLegacyList(node);
		assertEquals("memory||/new\n", node.get(RecentFile.PREF_RECENT_LIST, ""));
		assertNull(node.get(RecentFile.PREF_LEGACY_RECENT_LIST, null));
	}

	@Test
	public void theNameGuessIsOnlyADefault() {
		for(String s : new String[] {"password", "PASSWORD", "jdbcPassword", "passwd", "keyPassphrase",
				"privateKey", "sessionKey", "apiToken", "clientSecret", "credentials"}) {
			assertTrue(FileSourceFactory.looksLikeSecret(s), s);
		}
		for(String s : new String[] {"user", "host", "port", "name", "privateKeyFileName", "", null}) {
			assertFalse(FileSourceFactory.looksLikeSecret(s), String.valueOf(s));
		}
		// a factory that doesn't say uses the guess; one that does is exact
		assertTrue(new MemoryFileSourceFactory().isSecretProperty("password"));
		assertTrue(new SecretFactory().isSecretProperty("pin"));
		assertFalse(new SecretFactory().isSecretProperty("clientSecret"));
	}

	/** How 1.0.1 and earlier saved the list. */
	static String legacyEncrypt(String text) throws Exception {
		byte[] key = MessageDigest.getInstance("SHA-256").digest(System.getProperty("user.name").getBytes(StandardCharsets.UTF_8));
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
				new IvParameterSpec("1234567812345678".getBytes(StandardCharsets.US_ASCII)));
		return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
	}
}
