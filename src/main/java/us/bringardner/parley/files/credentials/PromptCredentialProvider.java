package us.bringardner.parley.files.credentials;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code @prompt:} or {@code @prompt:LABEL}: asks a person, through the application's
 * {@link Credentials.Prompter} (the terminal by default). The answer is kept in memory for the
 * rest of the JVM's life, per factory, setting, host and user, so a reconnect doesn't ask
 * again; {@link #forget()} drops the answers.
 * <p>
 * With no prompter (no terminal, nobody to ask) it fails at once, so a script run by cron
 * reports an error instead of waiting.
 */
public class PromptCredentialProvider implements CredentialProvider {

	private final Map<String, char[]> answers = new HashMap<>();

	@Override
	public String scheme() {
		return "prompt";
	}

	@Override
	public String description() {
		return "asks each session, nothing saved";
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		String key = key(ref, request);
		synchronized (answers) {
			char[] known = answers.get(key);
			if( known != null ) {
				return known.clone();
			}
		}
		Credentials.Prompter p = Credentials.getPrompter();
		if( p == null ) {
			throw new CredentialException(ref, "can't ask for "+request.describe()+": this session has no way to ask");
		}
		String label = ref.locator().isEmpty() ? request.describe() : ref.locator();
		char[] answer = p.ask(label, request);
		if( answer == null ) {
			throw new CredentialException(ref, "cancelled");
		}
		if( answer.length == 0 ) {
			throw new CredentialException(ref, "no answer");
		}
		synchronized (answers) {
			char[] old = answers.put(key, answer.clone());
			if( old != null ) {
				Arrays.fill(old, '\0');
			}
		}
		return answer;
	}

	/** A wrong answer is dropped, so the next connect asks again. */
	@Override
	public void rejected(CredentialRef ref, CredentialRequest request) {
		synchronized (answers) {
			char[] old = answers.remove(key(ref, request));
			if( old != null ) {
				Arrays.fill(old, '\0');
			}
		}
	}

	private static String key(CredentialRef ref, CredentialRequest request) {
		return request.factoryId()+"\n"+request.property()+"\n"+request.host()+"\n"+request.user()+"\n"+ref.locator();
	}

	/** Drops the answers kept from earlier prompts, so they are asked for again. */
	public void forget() {
		synchronized (answers) {
			for(char[] a : answers.values()) {
				Arrays.fill(a, '\0');
			}
			answers.clear();
		}
	}
}
