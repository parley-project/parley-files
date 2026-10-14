package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.browse.RecentFile;
import us.bringardner.parley.files.credentials.CommandCredentialProvider;
import us.bringardner.parley.files.credentials.CredentialException;
import us.bringardner.parley.files.credentials.CredentialRef;
import us.bringardner.parley.files.credentials.CredentialRequest;
import us.bringardner.parley.files.credentials.Credentials;
import us.bringardner.parley.files.credentials.EnvCredentialProvider;
import us.bringardner.parley.files.credentials.FileCredentialProvider;
import us.bringardner.parley.files.credentials.KeyStoreCredentialProvider;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** Credential references: parsing, each built-in provider, factories and recent files. */
public class CredentialsTests {

	private static final CredentialRequest REQ = new CredentialRequest("sftp", "password", "prod", "deploy");
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	@TempDir
	Path dir;

	@BeforeEach
	@AfterEach
	public void defaults() {
		Credentials.restoreDefaults();
		Credentials.setPrompter(null);
		System.clearProperty(CommandCredentialProvider.PROP_ENABLED);
		System.clearProperty(CommandCredentialProvider.PROP_TIMEOUT);
	}

	private static String str(char[] c) {
		return new String(c);
	}

	// ---------------------------------------------------------------- references

	@Test
	public void referencesAreParsedAndPlainValuesAreNot() {
		assertEquals(Optional.of(new CredentialRef("keychain", "parley/prod-db")), CredentialRef.parse("@keychain:parley/prod-db"));
		assertEquals("", CredentialRef.parse("@prompt:").get().locator());
		assertEquals("op read op://Dev/db/password", CredentialRef.parse("@cmd:op read op://Dev/db/password").get().locator());
		for(String plain : new String[] {null, "", "@", "secret", "@@keychain:x", "@:x", "@Key:x", "@1x:y", "@nocolon", "p@ss:word"}) {
			assertFalse(CredentialRef.isReference(plain), String.valueOf(plain));
		}
		assertEquals("@keychain:parley/prod-db", new CredentialRef("keychain", "parley/prod-db").toString());
		assertThrows(IllegalArgumentException.class, () -> new CredentialRef("Bad", "x"));
	}

	@Test
	public void aDoubledAtIsALiteralAt() throws IOException {
		assertEquals("@pw", CredentialRef.unescape("@@pw"));
		assertEquals("@@pw", CredentialRef.escape("@pw"));
		assertEquals("pw", CredentialRef.escape("pw"));
		assertEquals("@pw", str(Credentials.secret(CredentialRef.escape("@pw"), REQ)));
		assertEquals("plain", str(Credentials.secret("plain", REQ)));
		assertEquals(0, Credentials.secret(null, REQ).length);
	}

	@Test
	public void anUnknownSchemeFailsNamingTheReference() {
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@vault:prod", REQ));
		assertTrue(e.getMessage().startsWith("@vault:prod: "), e.getMessage());
		assertEquals("vault", e.getRef().scheme());
	}

	@Test
	public void registeredProvidersReplaceBuiltIns() throws IOException {
		assertTrue(Credentials.get("env").isPresent());
		Credentials.register(new EnvCredentialProvider(Map.of("PROD_PW", "from-env")::get));
		assertEquals("from-env", str(Credentials.secret("@env:PROD_PW", REQ)));
		assertThrows(CredentialException.class, () -> Credentials.secret("@env:MISSING", REQ));
		Credentials.unregister("env");
		assertFalse(Credentials.get("env").isPresent());
	}

	// ---------------------------------------------------------------- file

	@Test
	public void aFileReadableOnlyByItsOwnerIsRead() throws IOException {
		Path f = dir.resolve("pw");
		Files.write(f, "line1 pw\n".getBytes(StandardCharsets.UTF_8));
		if( !WINDOWS ) {
			Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
		}
		assertEquals("line1 pw", str(Credentials.secret("@file:"+f, REQ)), "one trailing line end dropped");
	}

	@Test
	public void aFileOthersCanReadIsRefused() throws IOException {
		assumeTrue(!WINDOWS);
		Path f = dir.resolve("pw");
		Files.write(f, "pw".getBytes(StandardCharsets.UTF_8));
		Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@file:"+f, REQ));
		assertTrue(e.getMessage().contains("chmod 600"), e.getMessage());
		assertFalse(e.getMessage().contains("pw\n"));
	}

	@Test
	public void theFileProviderStoresOwnerOnlyAndRemoves() throws IOException {
		Path f = dir.resolve("sub/pw");
		CredentialRef ref = new CredentialRef("file", f.toString());
		Credentials.store(ref, "stored pä".toCharArray());
		if( !WINDOWS ) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
		}
		assertEquals("stored pä", str(new FileCredentialProvider().resolve(ref, REQ)));
		Credentials.remove(ref);
		assertFalse(Files.exists(f));
		assertThrows(CredentialException.class, () -> Credentials.resolve(ref, REQ));
	}

	// ---------------------------------------------------------------- cmd

	@Test
	public void theCommandProviderIsOffUntilEnabled() {
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:echo hi", REQ));
		assertTrue(e.getMessage().contains(CommandCredentialProvider.PROP_ENABLED), e.getMessage());
	}

	@Test
	public void aCommandsOutputIsTheSecret() throws IOException {
		assumeTrue(!WINDOWS);
		System.setProperty(CommandCredentialProvider.PROP_ENABLED, "true");
		assertEquals("a b  c", str(Credentials.secret("@cmd:printf '%s\\n' 'a b  c'", REQ)));
		assertEquals("x", str(Credentials.secret("@cmd:echo \"x\"", REQ)));
	}

	@Test
	public void aFailingSilentOrSlowCommandIsAnError() {
		assumeTrue(!WINDOWS);
		System.setProperty(CommandCredentialProvider.PROP_ENABLED, "true");
		// prints "secret", which the command's own text doesn't contain
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:sh -c 'printf sec; printf ret; exit 3'", REQ));
		assertTrue(e.getMessage().contains("exit status 3"), e.getMessage());
		assertFalse(e.getMessage().contains("secret"), e.getMessage());
		assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:true", REQ));
		assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:'unclosed", REQ));
		assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:no-such-command-xyz", REQ));
		System.setProperty(CommandCredentialProvider.PROP_TIMEOUT, "300");
		long start = System.currentTimeMillis();
		e = assertThrows(CredentialException.class, () -> Credentials.secret("@cmd:sleep 5", REQ));
		assertTrue(e.getMessage().contains("longer than 300"), e.getMessage());
		assertTrue(System.currentTimeMillis()-start < 4000);
	}

	// ---------------------------------------------------------------- prompt

	@Test
	public void withNobodyToAskAPromptFailsAtOnce() {
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@prompt:", REQ));
		assertTrue(e.getMessage().contains("password for deploy@prod (sftp)"), e.getMessage());
	}

	@Test
	public void aPromptIsAskedOnceAndCancellingFails() throws IOException {
		List<String> asked = new ArrayList<>();
		Credentials.setPrompter((label, req) -> { asked.add(label); return "typed".toCharArray(); });
		assertEquals("typed", str(Credentials.secret("@prompt:", REQ)));
		assertEquals("typed", str(Credentials.secret("@prompt:", REQ)));
		assertEquals(List.of("password for deploy@prod (sftp)"), asked);

		assertEquals("typed", str(Credentials.secret("@prompt:Prod DB password", REQ)));
		assertEquals("Prod DB password", asked.get(1), "the locator is the label");

		Credentials.setPrompter((label, req) -> null);
		CredentialException e = assertThrows(CredentialException.class,
				() -> Credentials.secret("@prompt:", new CredentialRequest("ftp", "password", "x", "y")));
		assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
	}

	// ---------------------------------------------------------------- keystore

	@Test
	public void theKeystoreStoresListsResolvesAndRemoves() throws IOException {
		Path ksPath = dir.resolve("creds.p12");
		KeyStoreCredentialProvider ks = new KeyStoreCredentialProvider(ksPath);
		Credentials.register(ks);
		List<String> asked = new ArrayList<>();
		Credentials.setPrompter((label, req) -> { asked.add(label); return "master".toCharArray(); });

		Credentials.store(new CredentialRef("keystore", "Prod-DB"), "db pä=ss".toCharArray());
		assertEquals(2, asked.size(), "a new keystore's master password is asked twice: "+asked);
		assertTrue(asked.get(0).startsWith("New master password"), asked.get(0));
		if( !WINDOWS ) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(ksPath)));
		}
		assertFalse(new String(Files.readAllBytes(ksPath), StandardCharsets.ISO_8859_1).contains("db p"));

		assertEquals("db pä=ss", str(Credentials.secret("@keystore:prod-db", REQ)), "names aren't case sensitive");
		assertEquals(List.of(new CredentialRef("keystore", "prod-db")), ks.list());
		assertEquals(2, asked.size(), "the master password is kept for the session");

		ks.lock();
		Credentials.setPrompter((label, req) -> "wrong".toCharArray());
		CredentialException e = assertThrows(CredentialException.class, () -> Credentials.secret("@keystore:prod-db", REQ));
		assertTrue(e.getMessage().contains("wrong master password"), e.getMessage());

		ks.unlock("master".toCharArray());
		Credentials.remove(new CredentialRef("keystore", "prod-db"));
		assertTrue(ks.list().isEmpty());
		assertThrows(CredentialException.class, () -> Credentials.secret("@keystore:prod-db", REQ));
	}

	@Test
	public void theKeystoreMasterPasswordComesFromTheKeychainFirst() throws IOException {
		Path ksPath = dir.resolve("creds.p12");
		KeyStoreCredentialProvider ks = new KeyStoreCredentialProvider(ksPath);
		Credentials.register(ks);
		Credentials.register(new FakeKeychain(Map.of("parley/keystore", "from-keychain")));
		Credentials.store(new CredentialRef("keystore", "a"), "A".toCharArray());
		ks.lock();
		assertEquals("A", str(Credentials.secret("@keystore:a", REQ)), "no prompter, so the keychain gave the master password");
	}

	/** A keychain stand-in, since the real provider lives in its own artifact. */
	static class FakeKeychain extends EnvCredentialProvider {
		FakeKeychain(Map<String, String> values) {
			super(values::get);
		}

		@Override
		public String scheme() {
			return "keychain";
		}
	}

	// ---------------------------------------------------------------- factories and recent files

	/** A factory that logs in with a password setting, through secret(). */
	public static class LoginFactory extends MemoryFileSourceFactory {
		private static final long serialVersionUID = 1L;
		static String expected = "right";
		String password = "";
		String lastSecret;

		@Override
		public void setConnectionProperties(Properties prop) {
			super.setConnectionProperties(prop);
			if( prop != null ) {
				password = prop.getProperty("password", "");
			}
		}

		@Override
		public Properties getConnectProperties() {
			Properties p = super.getConnectProperties();
			p.setProperty("host", "prod");
			p.setProperty("user", "deploy");
			p.setProperty("password", password);
			return p;
		}

		@Override
		public boolean isSecretProperty(String name) {
			return "password".equals(name);
		}

		@Override
		protected boolean connectImpl() throws IOException {
			char[] pw = secret("password", password);
			lastSecret = new String(pw);
			java.util.Arrays.fill(pw, '\0');
			if( !lastSecret.equals(expected)) {
				return false;
			}
			return super.connectImpl();
		}
	}

	@Test
	public void aFactoryKeepsTheReferenceAndResolvesItToLogIn() throws IOException {
		Credentials.register(new EnvCredentialProvider(Map.of("PW", "right")::get));
		LoginFactory f = new LoginFactory();
		Properties p = new Properties();
		p.setProperty("password", "@env:PW");
		assertTrue(f.connect(p));
		assertEquals("right", f.lastSecret);
		assertEquals("@env:PW", f.getConnectProperties().getProperty("password"), "the factory holds the reference");
	}

	@Test
	public void aRejectedPromptAnswerIsAskedForAgain() throws IOException {
		List<String> answers = new ArrayList<>(List.of("wrong", "right"));
		Credentials.setPrompter((label, req) -> answers.remove(0).toCharArray());
		LoginFactory f = new LoginFactory();
		Properties p = new Properties();
		p.setProperty("password", "@prompt:");
		assertFalse(f.connect(p), "the first answer is wrong");
		assertTrue(f.connect(p), "asked again, not the kept wrong answer");
		assertTrue(answers.isEmpty());
	}

	@Test
	public void aRecentFileSavesAReferenceAndNeedsNoPrompt() throws IOException {
		Credentials.register(new EnvCredentialProvider(Map.of("PW", "right")::get));
		LoginFactory f = new LoginFactory();
		Properties p = new Properties();
		p.setProperty("password", "@env:PW");
		f.connect(p);
		FileSource file = f.createFileSource("/a.txt");

		String saved = new RecentFile(file).toString();
		assertTrue(saved.contains("password=@env:PW"), saved);
		RecentFile back = new RecentFile(saved);
		assertTrue(back.isSecret("password"));
		assertTrue(back.getMissingSecrets().isEmpty(), "a reference is never asked for");

		// a typed password is still never saved
		LoginFactory g = new LoginFactory();
		p.setProperty("password", "right");
		g.connect(p);
		saved = new RecentFile(g.createFileSource("/a.txt")).toString();
		assertFalse(saved.contains("right"), saved);
	}
}
