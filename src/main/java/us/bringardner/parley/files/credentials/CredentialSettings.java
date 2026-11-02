package us.bringardner.parley.files.credentials;

/**
 * The credential providers' own settings, {@code parley.credentials.NAME}, which one caller can
 * override with its id: the factory's type id or the module's ({@link CredentialRequest#factoryId()}),
 * in lower case, such as {@code sftp}, {@code jdbc}, {@code smtp} or {@code acl.jdbc}. The nearest one set wins: for
 * the id {@code acl.jdbc} and the name {@code keyStore}, the first of
 * {@code parley.credentials.acl.jdbc.keyStore}, {@code parley.credentials.acl.keyStore} and
 * {@code parley.credentials.keyStore} that is set. They are system properties.
 *
 * @since 2.0.0
 */
public final class CredentialSettings {

	/** The family of these settings */
	public static final String PREFIX = "parley.credentials.";

	private CredentialSettings() {
	}

	/**
	 * @param name    the setting after {@value #PREFIX}, e.g. {@code keyStore}, {@code cmd.timeout}
	 * @param request who asks (its factory or module id), or null for the setting every caller gets
	 * @return the nearest value set, or null
	 */
	public static String get(String name, CredentialRequest request) {
		//  lower case, as every segment of a setting is (the JDBC factory's type id is Jdbc)
		String id = request == null ? "" : request.factoryId().trim().toLowerCase(java.util.Locale.ROOT);
		while( !id.isEmpty() ) {
			String v = System.getProperty(PREFIX + id + "." + name);
			if( v != null && !v.trim().isEmpty() ) {
				return v.trim();
			}
			int dot = id.lastIndexOf('.');
			id = dot < 0 ? "" : id.substring(0, dot);
		}
		String v = System.getProperty(PREFIX + name);
		return v == null || v.trim().isEmpty() ? null : v.trim();
	}
}
