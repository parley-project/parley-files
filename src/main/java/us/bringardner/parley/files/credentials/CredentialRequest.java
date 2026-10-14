package us.bringardner.parley.files.credentials;

/**
 * What a reference is being resolved for: the factory, the setting, and (when the factory
 * knows them) the host and user. Providers may use it to word a prompt or to cache an answer;
 * it never holds a secret.
 */
public final class CredentialRequest {

	private final String factoryId;
	private final String property;
	private final String host;
	private final String user;

	public CredentialRequest(String factoryId, String property, String host, String user) {
		this.factoryId = factoryId == null ? "" : factoryId;
		this.property = property == null ? "" : property;
		this.host = host == null ? "" : host;
		this.user = user == null ? "" : user;
	}

	/** A request with only a setting name, for tools that resolve outside a factory. */
	public static CredentialRequest forProperty(String property) {
		return new CredentialRequest("", property, "", "");
	}

	/** The factory's type id ("sftp", "jdbc"...), or "". */
	public String factoryId() { return factoryId; }
	/** The connection setting being filled ("password"...), or "". */
	public String property() { return property; }
	/** The host connected to, or "". */
	public String host() { return host; }
	/** The user logging in, or "". */
	public String user() { return user; }

	/** A short description for a prompt: "password for deploy@prod (sftp)". */
	public String describe() {
		StringBuilder buf = new StringBuilder(property.isEmpty() ? "secret" : property);
		if( !host.isEmpty() || !user.isEmpty()) {
			buf.append(" for ");
			if( !user.isEmpty()) {
				buf.append(user);
				if( !host.isEmpty()) {
					buf.append('@');
				}
			}
			buf.append(host);
		}
		if( !factoryId.isEmpty()) {
			buf.append(" (").append(factoryId).append(')');
		}
		return buf.toString();
	}

	@Override
	public String toString() {
		return describe();
	}
}
