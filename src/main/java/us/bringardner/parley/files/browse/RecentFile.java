/**
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.browse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * An entry in a recent files list: a file and the file system it's on, with what's needed to
 * connect to it again. It uses no UI toolkit; parley-files-swing's and parley-files-fx's
 * RecentFileMenu show a list of them, so both menus behave the same and read each other's lists.
 * <p>
 * An entry keeps its factory's connection properties, but secret values (the factory decides
 * which, see {@link FileSourceFactory#isSecretProperty(String)}) are never saved. A secret that
 * had a value is saved as its name alone; {@link #open(SecretPrompter)} asks for it, and the answer
 * is kept in memory for the rest of the session. Secrets that were empty are left out.
 * <p>
 * Entries are the same when they have the same file system type and path.
 */
public class RecentFile {

	/** Asks for the value of a secret connection property; returns null to cancel. */
	@FunctionalInterface
	public interface SecretPrompter {
		String prompt(RecentFile entry, String propertyName);
	}

	/** Where 1.0.1 and earlier kept the encrypted list; see {@link #migrateLegacyList(Preferences)}. */
	public static final String PREF_LEGACY_RECENT_LIST = "RecentList";
	/** Where the menus save the list (swing-widgets' RecentItems.PREF_RECENT_LIST). */
	public static final String PREF_RECENT_LIST = "RecentFiles";

	private static final String NL = "\n";
	private static final String AMP = "&";
	private static final String COMMA= ",";
	private static final String EQ= "=";
	private static final String TILDE= "~";
	private static final String VBAR= "|";

	public String id;
	public Properties prop = new Properties();
	public String path;
	public boolean isLocal=true;
	private FileSource filex;
	/** Properties the factory calls secret: never saved with a value. */
	private final Set<String> secrets = new TreeSet<>();
	/** Secrets this connection uses (they had a value): saved by name, asked for when opened. */
	private final Set<String> needed = new TreeSet<>();

	/** Parses the saved form (and the one 1.0.1 and earlier saved, which held secret values). */
	public RecentFile(String line) {
		// the path is last and may itself contain '|'
		String parts[] = line.split("\\"+VBAR, 3);
		if( parts.length < 3 || parts[0].isEmpty() ) {
			// the line isn't in the message: an old one may hold a password
			throw new IllegalArgumentException("Malformed recent file entry");
		}
		id = parts[0];
		path = parts[2];
		isLocal = FileProxyFactory.FACTORY_ID.equals(id);

		FileSourceFactory factory = null;
		for(String str : parts[1].split(COMMA)) {
			if( str.isEmpty() ) {
				continue;
			}
			int eq = str.indexOf(EQ);
			if( eq < 0 ) {
				// a name alone: a secret this connection needs
				String name = decode(str);
				secrets.add(name);
				needed.add(name);
			} else if( eq > 0 ) {
				String name = decode(str.substring(0, eq));
				String val = decode(str.substring(eq+1));
				prop.setProperty(name, val);
				// an old entry may hold a secret value: ask its factory which ones are secret
				if( factory == null ) {
					factory = FileSourceFactory.getFileSourceFactory(id);
				}
				if( factory != null ? factory.isSecretProperty(name) : FileSourceFactory.looksLikeSecret(name) ) {
					secrets.add(name);
					if( !val.isEmpty() ) {
						needed.add(name);
					}
				}
			}
		}
	}

	public RecentFile(FileSource file) throws IOException {
		this.filex = file;
		FileSourceFactory f = file.getFileSourceFactory();
		isLocal = FileProxyFactory.FACTORY_ID.equals(f.getTypeId());
		id = f.getTypeId();
		// a copy, so filling in a secret never changes the factory's own properties
		Properties p = f.getConnectProperties();
		if( p != null ) {
			prop.putAll(p);
		}
		for(String name : prop.stringPropertyNames()) {
			if( f.isSecretProperty(name) ) {
				secrets.add(name);
				if( !prop.getProperty(name).isEmpty() ) {
					needed.add(name);
				}
			}
		}
		path = file.getCanonicalPath();
	}

	/**
	 * Connects to the entry's factory and returns its file. Secrets that weren't saved
	 * must be filled in first; {@link #open(SecretPrompter)} does that.
	 */
	public FileSource getFile() throws IOException {
		if( filex == null ) {
			FileSourceFactory f = FileSourceFactory.getFileSourceFactory(id);
			if( f == null ) {
				throw new IOException("No FileSource factory is registered for "+id);
			}
			// the properties aren't in the message: they may hold a password
			if( !f.connect(prop)) {
				throw new IOException("Can't connect to "+id+" for "+path);
			}
			filex = f.createFileSource(path);
		}

		return filex;
	}

	/**
	 * Opens the entry, first asking for any secret that wasn't saved.
	 * If the open fails, the secrets just entered are forgotten so they are asked for again.
	 *
	 * @return the file, or null if a prompt was cancelled
	 */
	public FileSource open(SecretPrompter prompter) throws IOException {
		List<String> asked = fillMissingSecrets(prompter);
		if( asked == null ) {
			return null;
		}
		try {
			return getFile();
		} catch (IOException | RuntimeException e) {
			forgetSecrets(asked);
			throw e;
		}
	}

	/**
	 * Asks for each secret this entry needs that has no value yet. For a UI that connects in
	 * the background: ask on the UI's thread, then call {@link #getFile()} elsewhere, and
	 * {@link #forgetSecrets(Collection)} if that fails.
	 *
	 * @return the names filled in, or null if a prompt was cancelled (then none are kept)
	 */
	public List<String> fillMissingSecrets(SecretPrompter prompter) {
		List<String> asked = new ArrayList<>();
		for(String name : getMissingSecrets()) {
			String value = prompter.prompt(this, name);
			if( value == null ) {
				forgetSecrets(asked);
				return null;
			}
			prop.setProperty(name, value);
			asked.add(name);
		}
		return asked;
	}

	/** Clears secrets entered this session, so they are asked for again. */
	public void forgetSecrets(Collection<String> names) {
		for(String name : names) {
			prop.setProperty(name, "");
		}
	}

	/** @return true if the named property is a secret for this entry's factory */
	public boolean isSecret(String name) {
		return secrets.contains(name);
	}

	/** @return the secrets this entry needs that have no value yet, sorted */
	public List<String> getMissingSecrets() {
		List<String> ret = new ArrayList<>();
		for(String name : needed) {
			if( prop.getProperty(name, "").isEmpty() ) {
				ret.add(name);
			}
		}
		return ret;
	}

	/** @return what a menu shows: the path for a local file, otherwise type:path */
	public String getLabel() {
		return isLocal ? path : id+":"+path;
	}

	/**
	 * @return true if the entry is a local file that's gone, so a list should drop it. Remote
	 * entries aren't checked: that would connect to every server (and ask for passwords) just to
	 * draw a menu.
	 */
	public boolean isGone() {
		if( !isLocal ) {
			return false;
		}
		try {
			return !getFile().exists();
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * For a list replacing an older entry for the same file with this one: carries over the
	 * secrets entered this session that the factory no longer reports.
	 *
	 * @return this entry
	 */
	public RecentFile keepSecretsFrom(RecentFile old) {
		for(String name : old.needed) {
			String val = old.prop.getProperty(name, "");
			if( !val.isEmpty() && prop.getProperty(name, "").isEmpty() ) {
				prop.setProperty(name, val);
				secrets.add(name);
				needed.add(name);
			}
		}
		return this;
	}

	/**
	 * The saved form: id|name=value,...,secretName,...|path. Secret values are never
	 * written; a secret the connection needs is written as its name alone.
	 */
	@Override
	public String toString() {
		StringBuilder buf = new StringBuilder();
		for(String name : new TreeSet<>(prop.stringPropertyNames())) {
			if( secrets.contains(name) ) {
				continue;
			}
			if(buf.length()>0) {
				buf.append(COMMA);
			}
			buf.append(encode(name)).append(EQ).append(encode(prop.getProperty(name)));
		}
		for(String name : needed) {
			if(buf.length()>0) {
				buf.append(COMMA);
			}
			buf.append(encode(name));
		}

		return id+VBAR+buf+VBAR+path;
	}

	static final String illegalChar =""+ AMP+NL+COMMA+EQ+TILDE+VBAR;

	static String encode(String str) {
		StringBuilder ret = new StringBuilder();
		for(char c : str.toCharArray()) {
			if( illegalChar.indexOf(c)>=0) {
				String tmp = (Integer.toHexString(c).toUpperCase());
				if( tmp.length()==1) {
					tmp = "0"+tmp;
				}
				ret.append(AMP+tmp);
			} else {
				ret.append(c);
			}
		}

		return ret.toString();
	}

	static String decode(String str) {
		StringBuilder ret = new StringBuilder();
		char array [] = str.toCharArray();
		for (int idx = 0; idx < array.length; idx++) {
			if( array[idx] == AMP.charAt(0) && idx+2 < array.length ) {
				ret.append((char)Integer.parseInt(""+array[++idx]+array[++idx], 16));
			} else {
				ret.append(array[idx]);
			}
		}

		return ret.toString();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, path);
	}

	@Override
	public boolean equals(Object obj) {
		boolean ret = false;
		if (obj instanceof RecentFile) {
			RecentFile le = (RecentFile) obj;
			ret = Objects.equals(le.id, id) && Objects.equals(le.path, path);
		}
		return ret;
	}

	/**
	 * Call before a menu reads the list: replaces the list 1.0.1 and earlier saved (encrypted,
	 * secrets included) with one without secrets, and removes it.
	 *
	 * @return prefs
	 */
	public static Preferences migrateLegacyList(Preferences prefs) throws IOException {
		Objects.requireNonNull(prefs, "prefs");
		String legacy = prefs.get(PREF_LEGACY_RECENT_LIST, null);
		if( legacy != null ) {
			if( prefs.get(PREF_RECENT_LIST, "").isEmpty() && !legacy.isEmpty() ) {
				try {
					prefs.put(PREF_RECENT_LIST, withoutSecrets(legacyDecrypt(legacy)));
				} catch (GeneralSecurityException | RuntimeException e) {
					// unreadable (e.g. user.name changed): the old list is dropped
				}
			}
			// save without secrets first, then remove the old value
			flush(prefs);
			prefs.remove(PREF_LEGACY_RECENT_LIST);
			flush(prefs);
		}

		return prefs;
	}

	/** Parses an old list and writes it again: toString() leaves the secret values out. */
	private static String withoutSecrets(String text) {
		List<RecentFile> list = new ArrayList<>();
		for(String line : text.split(NL)) {
			if( line.isEmpty() ) {
				continue;
			}
			try {
				RecentFile e = new RecentFile(line);
				if( !list.contains(e) ) {
					list.add(e);
				}
			} catch (RuntimeException e) {
				// skip a malformed line rather than lose the list
			}
		}
		StringBuilder buf = new StringBuilder();
		for(RecentFile e : list) {
			buf.append(e).append(NL);
		}

		return buf.toString();
	}

	private static void flush(Preferences prefs) throws IOException {
		try {
			prefs.flush();
		} catch (BackingStoreException e) {
			throw new IOException("Can't save preferences", e);
		}
	}

	/** Reads the list 1.0.1 and earlier saved (AES/CBC, key = SHA-256 of user.name, fixed IV). Migration only. */
	private static String legacyDecrypt(String cipherText) throws GeneralSecurityException {
		byte[] key = MessageDigest.getInstance("SHA-256").digest(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new IvParameterSpec("1234567812345678".getBytes(StandardCharsets.US_ASCII)));

		return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
	}
}
