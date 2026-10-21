package us.bringardner.parley.files.credentials;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.spec.SecretKeySpec;

/**
 * {@code @keystore:NAME}: a secret in an encrypted PKCS12 file, {@code ~/.parley/credentials.p12}
 * by default ({@code -Dparley.files.credentials.keyStore=PATH} for another). Portable: it needs no
 * OS keychain. The file must be readable by its owner only.
 * <p>
 * The master password comes from {@code @keychain:parley/keystore} when a keychain provider
 * is installed and holds it; otherwise it is asked for once, through the
 * {@link Credentials.Prompter}, and kept in memory until {@link #lock()}. Names are not case
 * sensitive (PKCS12 lowercases them).
 */
public class KeyStoreCredentialProvider implements CredentialProvider {

	/** System property naming the keystore file. */
	public static final String PROP_PATH = "parley.files.credentials.keyStore";
	/** Where the master password is looked for first. */
	public static final CredentialRef MASTER_REF = new CredentialRef("keychain", "parley/keystore");

	private static final String TYPE = "PKCS12";

	private final Path fixedPath;
	private char[] master;

	public KeyStoreCredentialProvider() {
		this(null);
	}

	/** For tests and applications with their own file: the keystore at path. */
	public KeyStoreCredentialProvider(Path path) {
		this.fixedPath = path;
	}

	/** The keystore file this provider uses. */
	public Path path() {
		if( fixedPath != null ) {
			return fixedPath;
		}
		return SecretFiles.expand(System.getProperty(PROP_PATH, "~/.parley/credentials.p12"));
	}

	@Override
	public String scheme() {
		return "keystore";
	}

	@Override
	public String description() {
		return "an encrypted keystore, "+path();
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	/** Uses master as the master password from now on (a copy is kept). */
	public synchronized void unlock(char[] master) {
		lock();
		this.master = master.clone();
	}

	/**
	 * Gets the master password now (from the keychain, or asked for: twice for a new keystore),
	 * so a tool that is about to ask for a secret to store can unlock the keystore first and a
	 * mistyped master password doesn't waste the secret. Does nothing when already unlocked.
	 */
	public synchronized void prepare() throws CredentialException {
		load(null, true);
	}

	/** Forgets the master password, so it is asked for again. */
	public synchronized void lock() {
		if( master != null ) {
			Arrays.fill(master, '\0');
			master = null;
		}
	}

	@Override
	public synchronized char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		Path p = path();
		if( !Files.exists(p)) {
			throw new CredentialException(ref, "there is no keystore at "+p);
		}
		KeyStore ks = load(ref, false);
		try {
			Key key = ks.getKey(alias(ref), master);
			if( key == null ) {
				throw new CredentialException(ref, "not found in "+p);
			}
			byte[] bytes = key.getEncoded();
			try {
				return SecretFiles.decode(bytes, bytes.length);
			} finally {
				Arrays.fill(bytes, (byte) 0);
			}
		} catch (GeneralSecurityException e) {
			throw new CredentialException(ref, "can't read it from "+p, e);
		}
	}

	@Override
	public boolean canStore() {
		return true;
	}

	@Override
	public synchronized void store(CredentialRef ref, char[] secret) throws CredentialException {
		if( secret == null || secret.length == 0 ) {
			throw new CredentialException(ref, "can't store an empty secret");
		}
		KeyStore ks = load(ref, true);
		byte[] bytes = SecretFiles.encode(secret);
		try {
			ks.setEntry(alias(ref), new KeyStore.SecretKeyEntry(new SecretKeySpec(bytes, "AES")),
					new KeyStore.PasswordProtection(master));
		} catch (GeneralSecurityException e) {
			throw new CredentialException(ref, "can't add it to "+path(), e);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
		save(ref, ks);
	}

	@Override
	public synchronized void remove(CredentialRef ref) throws CredentialException {
		if( !Files.exists(path())) {
			throw new CredentialException(ref, "there is no keystore at "+path());
		}
		KeyStore ks = load(ref, false);
		try {
			if( !ks.containsAlias(alias(ref))) {
				throw new CredentialException(ref, "not found in "+path());
			}
			ks.deleteEntry(alias(ref));
		} catch (GeneralSecurityException e) {
			throw new CredentialException(ref, "can't remove it from "+path(), e);
		}
		save(ref, ks);
	}

	@Override
	public synchronized List<CredentialRef> list() throws CredentialException {
		if( !Files.exists(path())) {
			return List.of();
		}
		KeyStore ks = load(null, false);
		List<CredentialRef> ret = new ArrayList<>();
		try {
			for(String a : Collections.list(ks.aliases())) {
				ret.add(new CredentialRef(scheme(), a));
			}
		} catch (GeneralSecurityException e) {
			throw new CredentialException(null, "can't list "+path(), e);
		}
		return ret;
	}

	private static String alias(CredentialRef ref) throws CredentialException {
		if( ref.locator().isEmpty()) {
			throw new CredentialException(ref, "names no entry");
		}
		return ref.locator().toLowerCase(Locale.ROOT);
	}

	/** The keystore, unlocked; a new empty one if create and there's no file yet. */
	private KeyStore load(CredentialRef ref, boolean create) throws CredentialException {
		Path p = path();
		boolean exists = Files.exists(p);
		if( exists ) {
			SecretFiles.checkOwnerOnly(ref, p);
		} else if( !create ) {
			throw new CredentialException(ref, "there is no keystore at "+p);
		}
		ensureMaster(ref, !exists);
		try {
			KeyStore ks = KeyStore.getInstance(TYPE);
			if( exists ) {
				ks.load(new ByteArrayInputStream(Files.readAllBytes(p)), master);
			} else {
				ks.load(null, master);
			}
			return ks;
		} catch (IOException e) {
			if( e.getCause() instanceof UnrecoverableKeyException ) {
				// a wrong master password: ask again next time
				lock();
				throw new CredentialException(ref, "wrong master password for "+p);
			}
			throw new CredentialException(ref, "can't read "+p, e);
		} catch (GeneralSecurityException e) {
			throw new CredentialException(ref, "can't read "+p, e);
		}
	}

	private void save(CredentialRef ref, KeyStore ks) throws CredentialException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ks.store(out, master);
			SecretFiles.writeOwnerOnly(path(), out.toByteArray());
		} catch (IOException | GeneralSecurityException e) {
			throw new CredentialException(ref, "can't write "+path(), e);
		}
	}

	private void ensureMaster(CredentialRef ref, boolean creating) throws CredentialException {
		if( master != null ) {
			return;
		}
		CredentialProvider keychain = Credentials.get(MASTER_REF.scheme()).orElse(null);
		if( keychain != null && keychain.isAvailable()) {
			try {
				master = keychain.resolve(MASTER_REF, CredentialRequest.forProperty("keystore master password"));
				return;
			} catch (CredentialException e) {
				// not kept there: ask
			}
		}
		Credentials.Prompter p = Credentials.getPrompter();
		if( p == null || !p.canAsk(CredentialRequest.forProperty("keystore master password"))) {
			throw new CredentialException(ref, "the keystore is locked and this session has no way to ask for its master password");
		}
		CredentialRequest req = CredentialRequest.forProperty("keystore master password");
		char[] first = p.ask((creating ? "New master password for " : "Master password for ")+path(), req);
		if( first == null || first.length == 0 ) {
			throw new CredentialException(ref, "no master password given");
		}
		if( creating ) {
			char[] again = p.ask("Master password again", req);
			boolean same = again != null && Arrays.equals(first, again);
			if( again != null ) {
				Arrays.fill(again, '\0');
			}
			if( !same ) {
				Arrays.fill(first, '\0');
				throw new CredentialException(ref, "the master passwords didn't match");
			}
		}
		master = first;
	}
}
