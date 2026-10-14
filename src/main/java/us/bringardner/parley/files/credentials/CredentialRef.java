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
package us.bringardner.parley.files.credentials;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A reference to a secret held somewhere else: {@code @scheme:locator}, for example
 * {@code @keychain:parley/prod-db} or {@code @env:PROD_DB_PW}. A secret connection setting
 * may hold a reference instead of the secret; the factory resolves it with {@link Credentials}
 * only while it logs in.
 * <p>
 * A reference is not a secret: it may be shown, logged and saved. {@code @@} at the start of
 * a value is a literal {@code @}, for a password that begins with one.
 */
public final class CredentialRef {

	/** The character that starts a reference. */
	public static final char MARK = '@';

	private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*");

	private final String scheme;
	private final String locator;

	public CredentialRef(String scheme, String locator) {
		if( scheme == null || !SCHEME.matcher(scheme).matches()) {
			throw new IllegalArgumentException("Invalid credential scheme: "+scheme);
		}
		this.scheme = scheme;
		this.locator = locator == null ? "" : locator;
	}

	/**
	 * The reference in value, or empty if value is a plain value: null, empty, not
	 * starting with {@code @}, starting with {@code @@}, or with no valid scheme before a ':'.
	 */
	public static Optional<CredentialRef> parse(String value) {
		if( value == null || value.length() < 2 || value.charAt(0) != MARK || value.charAt(1) == MARK ) {
			return Optional.empty();
		}
		int colon = value.indexOf(':');
		if( colon < 2 ) {
			return Optional.empty();
		}
		String scheme = value.substring(1, colon);
		if( !SCHEME.matcher(scheme).matches()) {
			return Optional.empty();
		}
		return Optional.of(new CredentialRef(scheme, value.substring(colon+1)));
	}

	/** True if value is a reference (see {@link #parse(String)}). */
	public static boolean isReference(String value) {
		return parse(value).isPresent();
	}

	/** A plain value as meant: a leading {@code @@} becomes {@code @}. */
	public static String unescape(String value) {
		if( value != null && value.length() >= 2 && value.charAt(0) == MARK && value.charAt(1) == MARK ) {
			return value.substring(1);
		}
		return value;
	}

	/** A plain value written so it can't be read as a reference (a leading {@code @} doubled). */
	public static String escape(String value) {
		if( value != null && !value.isEmpty() && value.charAt(0) == MARK ) {
			return MARK+value;
		}
		return value;
	}

	/** The provider's name, such as "keychain" or "env". */
	public String scheme() {
		return scheme;
	}

	/** What the provider looks up: a name, a path, a command... ("" for none). */
	public String locator() {
		return locator;
	}

	/** {@code @scheme:locator}: safe to show, log and save. */
	@Override
	public String toString() {
		return MARK+scheme+":"+locator;
	}

	@Override
	public boolean equals(Object o) {
		if( this == o ) {
			return true;
		}
		if( !(o instanceof CredentialRef)) {
			return false;
		}
		CredentialRef r = (CredentialRef) o;
		return scheme.equals(r.scheme) && locator.equals(r.locator);
	}

	@Override
	public int hashCode() {
		return Objects.hash(scheme, locator);
	}
}
