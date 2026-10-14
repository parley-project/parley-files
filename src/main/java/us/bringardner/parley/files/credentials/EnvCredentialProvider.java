package us.bringardner.parley.files.credentials;

import java.util.function.UnaryOperator;

/**
 * {@code @env:NAME}: the value of an environment variable. For CI and containers, where the
 * platform injects secrets that way. Read only.
 */
public class EnvCredentialProvider implements CredentialProvider {

	private final UnaryOperator<String> env;

	public EnvCredentialProvider() {
		this(System::getenv);
	}

	/** For tests: env looks a name up (returns null when it isn't set). */
	public EnvCredentialProvider(UnaryOperator<String> env) {
		this.env = env;
	}

	@Override
	public String scheme() {
		return "env";
	}

	@Override
	public String description() {
		return "environment variables (read only)";
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		if( ref.locator().isEmpty()) {
			throw new CredentialException(ref, "names no environment variable");
		}
		String val = env.apply(ref.locator());
		if( val == null || val.isEmpty()) {
			throw new CredentialException(ref, "the environment variable isn't set");
		}
		return val.toCharArray();
	}
}
