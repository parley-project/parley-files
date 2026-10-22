package us.bringardner.parley.files.credentials;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import us.bringardner.parley.plugin.Plugins;

/**
 * The credential providers this JVM knows, by scheme, and the one place a secret setting's
 * value is turned into a secret ({@link #secret(String, CredentialRequest)}).
 * <p>
 * Built in: {@code env}, {@code file}, {@code cmd} (off until enabled, see
 * {@link CommandCredentialProvider}), {@code prompt} and {@code keystore}. Providers found with
 * {@link ServiceLoader} are added after them and replace a built-in with the same scheme, which
 * is how the separate keychain artifact plugs in.
 */
public final class Credentials {

	/**
	 * Asks a person for a secret. Each application supplies its own (a terminal, a Swing or
	 * JavaFX dialog) with {@link Credentials#setPrompter(Prompter)}.
	 */
	@FunctionalInterface
	public interface Prompter {
		/**
		 * @param label what to ask for, such as "password for deploy@prod (sftp)"
		 * @return the answer (the caller zeroes it), or null if the person cancelled
		 */
		char[] ask(String label, CredentialRequest request);

		/**
		 * Whether anyone can be asked now: false in a session nobody is watching (a script
		 * run by cron, say), so a prompt fails at once with that reason instead of waiting
		 * or reporting a cancel. True by default.
		 */
		default boolean canAsk(CredentialRequest request) {
			return true;
		}
	}

	private static final Map<String, CredentialProvider> providers = new LinkedHashMap<>();
	private static volatile Prompter prompter;

	static {
		restoreDefaults();
		// (providers in plugin jars loaded later: added then)
		Plugins.onLoad(() -> {
			for(CredentialProvider p : Plugins.providers(CredentialProvider.class, null)) {
				if( p.getClass().getClassLoader() == Plugins.classLoader() ) {
					register(p);
				}
			}
		});
	}

	private Credentials() {
	}

	/** Forgets added providers and the prompter; registers the built-ins and the ServiceLoader ones again. */
	public static void restoreDefaults() {
		synchronized (providers) {
			providers.clear();
			register(new EnvCredentialProvider());
			register(new FileCredentialProvider());
			register(new CommandCredentialProvider());
			register(new PromptCredentialProvider());
			register(new KeyStoreCredentialProvider());
			// (the application's and its plugins'; a broken plug-in must not take the built-ins down with it)
			for(CredentialProvider p : Plugins.providers(CredentialProvider.class, null)) {
				register(p);
			}
		}
		prompter = ConsolePrompter.forThisJvm();
	}

	/** Adds provider, replacing any provider with the same scheme. */
	public static void register(CredentialProvider provider) {
		synchronized (providers) {
			providers.put(provider.scheme(), provider);
		}
	}

	/** Removes the provider for scheme, if there is one. */
	public static void unregister(String scheme) {
		synchronized (providers) {
			providers.remove(scheme);
		}
	}

	/** The provider for scheme. */
	public static Optional<CredentialProvider> get(String scheme) {
		synchronized (providers) {
			return Optional.ofNullable(providers.get(scheme));
		}
	}

	/** All providers, in the order they were registered. */
	public static List<CredentialProvider> providers() {
		synchronized (providers) {
			return new ArrayList<>(providers.values());
		}
	}

	/** Sets who asks a person for secrets; null means nobody can be asked. */
	public static void setPrompter(Prompter p) {
		prompter = p;
	}

	/** Who asks a person for secrets, or null if nobody can be asked. */
	public static Prompter getPrompter() {
		return prompter;
	}

	/**
	 * The secret ref names, from its provider.
	 *
	 * @return a new array the caller zeroes when done
	 */
	public static char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		CredentialProvider p = provider(ref);
		char[] ret = p.resolve(ref, request == null ? CredentialRequest.forProperty("") : request);
		if( ret == null || ret.length == 0 ) {
			throw new CredentialException(ref, "is empty");
		}
		return ret;
	}

	/**
	 * The secret a setting's value stands for: a reference is resolved, a plain value is
	 * used as it is (with a leading {@code @@} read as {@code @}).
	 *
	 * @return a new array the caller zeroes when done; empty for a null or empty value
	 */
	public static char[] secret(String value, CredentialRequest request) throws CredentialException {
		if( value == null || value.isEmpty()) {
			return new char[0];
		}
		Optional<CredentialRef> ref = CredentialRef.parse(value);
		if( ref.isPresent()) {
			return resolve(ref.get(), request);
		}
		return CredentialRef.unescape(value).toCharArray();
	}

	/** Tells ref's provider that the secret it gave for request didn't work. */
	public static void rejected(CredentialRef ref, CredentialRequest request) {
		get(ref.scheme()).ifPresent(p -> p.rejected(ref, request));
	}

	/** Stores secret under ref with its provider. */
	public static void store(CredentialRef ref, char[] secret) throws CredentialException {
		CredentialProvider p = provider(ref);
		if( !p.canStore()) {
			throw new CredentialException(ref, "the "+ref.scheme()+" provider can't store secrets");
		}
		p.store(ref, secret);
	}

	/** Removes the secret ref names from its provider. */
	public static void remove(CredentialRef ref) throws CredentialException {
		CredentialProvider p = provider(ref);
		if( !p.canStore()) {
			throw new CredentialException(ref, "the "+ref.scheme()+" provider can't remove secrets");
		}
		p.remove(ref);
	}

	private static CredentialProvider provider(CredentialRef ref) throws CredentialException {
		CredentialProvider p = get(ref.scheme()).orElse(null);
		if( p == null ) {
			throw new CredentialException(ref, "no credential provider for '"+ref.scheme()+"'");
		}
		if( !p.isAvailable()) {
			throw new CredentialException(ref, "the "+ref.scheme()+" provider is unavailable: "+p.unavailableReason());
		}
		return p;
	}
}
