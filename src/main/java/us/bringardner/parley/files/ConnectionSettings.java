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
package us.bringardner.parley.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import us.bringardner.parley.files.credentials.CredentialProvider;
import us.bringardner.parley.files.credentials.CredentialRef;
import us.bringardner.parley.files.credentials.Credentials;

/**
 * Working with a factory's {@link ConnectionSetting}s, the same way in every UI: the
 * values a form starts with, which settings apply, what's wrong with the values, and the
 * properties to connect with.
 */
public class ConnectionSettings {

	private ConnectionSettings() {
	}

	/** The setting with key, or null. */
	public static ConnectionSetting find(List<ConnectionSetting> settings, String key) {
		for(ConnectionSetting s : settings) {
			if( s.key().equals(key)) {
				return s;
			}
		}
		return null;
	}

	/** The value of key in values, or its setting's default if there's none. */
	public static String value(List<ConnectionSetting> settings, Properties values, String key) {
		String ret = values == null ? null : values.getProperty(key);
		if( ret == null || ret.isEmpty()) {
			ConnectionSetting s = find(settings, key);
			if( s != null && !s.defaultValue().isEmpty()) {
				ret = s.defaultValue();
			}
		}
		return ret == null ? "" : ret;
	}

	/** The values a form starts with: current, else the default. */
	public static Properties initialValues(List<ConnectionSetting> settings, Properties current) {
		Properties ret = new Properties();
		for(ConnectionSetting s : settings) {
			ret.setProperty(s.key(), value(settings, current, s.key()));
		}
		return ret;
	}

	/**
	 * True if setting applies, given the other values: it isn't hidden, and the setting it
	 * depends on (if any) has the value it needs.
	 */
	public static boolean isVisible(ConnectionSetting setting, List<ConnectionSetting> settings, Properties values) {
		if( setting.kind() == ConnectionSetting.Kind.HIDDEN ) {
			return false;
		}
		if( setting.visibleWhenKey() == null ) {
			return true;
		}
		return setting.visibleWhenValue().equals(value(settings, values, setting.visibleWhenKey()));
	}

	/** What's wrong with values (one message per problem); empty if nothing is. Settings that don't apply aren't checked. */
	public static List<String> validate(List<ConnectionSetting> settings, Properties values) {
		List<String> ret = new ArrayList<>();
		for(ConnectionSetting s : settings) {
			if( isVisible(s, settings, values)) {
				String value = values == null ? null : values.getProperty(s.key());
				String problem = s.check(value);
				if( problem == null && isSecret(s)) {
					problem = checkReference(s, value);
				}
				if( problem != null ) {
					ret.add(problem);
				}
			}
		}
		return ret;
	}

	/** True for the settings that may hold a credential reference. */
	public static boolean isSecret(ConnectionSetting s) {
		return s.kind() == ConnectionSetting.Kind.SECRET || s.kind() == ConnectionSetting.Kind.MULTILINE_SECRET;
	}

	/**
	 * What's wrong with a secret setting's value as a reference (@keychain:...): no provider
	 * for its scheme, or the provider can't work here. Null for a plain value or a usable
	 * reference. Whether the secret exists isn't checked: that may ask for a password.
	 */
	public static String checkReference(ConnectionSetting s, String value) {
		Optional<CredentialRef> ref = CredentialRef.parse(value);
		if( ref.isEmpty()) {
			return null;
		}
		Optional<CredentialProvider> p = Credentials.get(ref.get().scheme());
		if( p.isEmpty()) {
			return s.label()+": there is no credential store called '"+ref.get().scheme()+"' ("+ref.get()+")";
		}
		if( !p.get().isAvailable()) {
			return s.label()+": the "+ref.get().scheme()+" store is unavailable here: "+p.get().unavailableReason();
		}
		return null;
	}

	/**
	 * The keychain entry to suggest when a typed password is saved there:
	 * {@code @keychain:parley/FACTORY/USER@HOST}, leaving out what's unknown.
	 */
	public static CredentialRef suggestedKeychainRef(String factoryId, String user, String host) {
		StringBuilder buf = new StringBuilder("parley/");
		buf.append(factoryId == null || factoryId.isBlank() ? "connection" : factoryId.trim());
		boolean hasUser = user != null && !user.isBlank();
		boolean hasHost = host != null && !host.isBlank();
		if( hasUser || hasHost ) {
			buf.append('/');
			if( hasUser ) {
				buf.append(user.trim());
				if( hasHost ) {
					buf.append('@');
				}
			}
			if( hasHost ) {
				buf.append(host.trim());
			}
		}
		return new CredentialRef("keychain", buf.toString());
	}

	/**
	 * The properties to connect with: values as given, except that settings that don't
	 * apply are emptied (so a password isn't sent when a key file was chosen, say). Hidden
	 * settings and properties no setting describes are passed on unchanged.
	 */
	public static Properties forConnect(List<ConnectionSetting> settings, Properties values) {
		Properties ret = new Properties();
		if( values != null ) {
			ret.putAll(values);
		}
		for(ConnectionSetting s : settings) {
			if( s.kind() != ConnectionSetting.Kind.HIDDEN && !isVisible(s, settings, values)) {
				ret.setProperty(s.key(), "");
			}
		}
		return ret;
	}

	/**
	 * A description of properties nobody described: one setting each, in key order, labelled
	 * without prefix (a factory's type id, say), secret if isSecret says so.
	 */
	public static List<ConnectionSetting> describe(Properties properties, String prefix, java.util.function.Predicate<String> isSecret) {
		List<ConnectionSetting> ret = new ArrayList<>();
		if( properties == null ) {
			return ret;
		}
		List<String> keys = new ArrayList<>(properties.stringPropertyNames());
		keys.sort(String.CASE_INSENSITIVE_ORDER);
		for(String key : keys) {
			String label = key;
			if( prefix != null && !prefix.isEmpty() && key.length() > prefix.length()
					&& key.toLowerCase().startsWith(prefix.toLowerCase())) {
				label = key.substring(prefix.length());
			}
			ret.add(isSecret.test(key) ? ConnectionSetting.secret(key, label) : ConnectionSetting.text(key, label));
		}
		return ret;
	}
}
