package us.bringardner.parley.files.credentials;

import java.util.List;

/**
 * Turns a {@link CredentialRef} with this provider's scheme into the secret it names. Built-in
 * providers are registered by {@link Credentials}; others are found with
 * {@link java.util.ServiceLoader} (list the class in
 * {@code META-INF/services/us.bringardner.parley.files.credentials.CredentialProvider}) or
 * added with {@link Credentials#register(CredentialProvider)}.
 * <p>
 * Rules for implementations: return a new array the caller may zero; never put a secret in an
 * exception message, a log line or {@code toString()}.
 */
public interface CredentialProvider {

	/** The scheme this provider answers, such as "keychain" ({@code @keychain:...}). */
	String scheme();

	/** One line for a list of providers: where the secrets are kept. */
	default String description() {
		return scheme();
	}

	/** False if the provider can't work here (no keychain on this OS, turned off...). */
	boolean isAvailable();

	/** Why {@link #isAvailable()} is false, for an error message; "" when it's available. */
	default String unavailableReason() {
		return isAvailable() ? "" : "not available here";
	}

	/**
	 * The secret ref names.
	 *
	 * @return a new array the caller zeroes when done
	 * @throws CredentialException if there's no such secret or it can't be read
	 */
	char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException;

	/**
	 * The secret resolved for request didn't work (the login failed). A provider that keeps
	 * answers, such as prompt, drops this one so it is asked for again. Does nothing by default.
	 */
	default void rejected(CredentialRef ref, CredentialRequest request) {
	}

	/** True if {@link #store} and {@link #remove} work. */
	default boolean canStore() {
		return false;
	}

	/** Saves secret under ref, replacing any value it had. The caller zeroes secret afterwards. */
	default void store(CredentialRef ref, char[] secret) throws CredentialException {
		throw new CredentialException(ref, "the "+scheme()+" provider can't store secrets");
	}

	/**
	 * Saves secret under ref for a caller (its id, as {@link #resolve} gets it), so it is kept where
	 * that caller's secrets are read from; a null request is every caller's place. The caller zeroes
	 * secret afterwards. A provider that keeps one place for every caller needs only
	 * {@link #store(CredentialRef, char[])}, which this calls by default.
	 * @since 2.0.0
	 */
	default void store(CredentialRef ref, char[] secret, CredentialRequest request) throws CredentialException {
		store(ref, secret);
	}

	/** Removes the secret ref names. */
	default void remove(CredentialRef ref) throws CredentialException {
		throw new CredentialException(ref, "the "+scheme()+" provider can't remove secrets");
	}

	/**
	 * Removes the secret ref names from where a caller's secrets are read (a null request: every caller's).
	 * @since 2.0.0
	 */
	default void remove(CredentialRef ref, CredentialRequest request) throws CredentialException {
		remove(ref);
	}

	/** The references this provider holds, if it can list them; never their values. */
	default List<CredentialRef> list() throws CredentialException {
		return List.of();
	}

	/**
	 * The references a caller can read from this provider (a null request: every caller's), if it can
	 * list them; never their values.
	 * @since 2.0.0
	 */
	default List<CredentialRef> list(CredentialRequest request) throws CredentialException {
		return list();
	}
}
