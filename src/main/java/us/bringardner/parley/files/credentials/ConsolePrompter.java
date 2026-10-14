package us.bringardner.parley.files.credentials;

import java.io.Console;

/**
 * The default {@link Credentials.Prompter}: asks on the JVM's terminal without echo. There is
 * none when the JVM has no terminal (a GUI, a service, input piped in), so a prompt there
 * fails at once instead of waiting for an answer nobody can give.
 */
final class ConsolePrompter implements Credentials.Prompter {

	private final Console console;

	private ConsolePrompter(Console console) {
		this.console = console;
	}

	/** A prompter on this JVM's terminal, or null if it has none. */
	static Credentials.Prompter forThisJvm() {
		Console c = System.console();
		return c == null ? null : new ConsolePrompter(c);
	}

	@Override
	public char[] ask(String label, CredentialRequest request) {
		return console.readPassword("%s: ", label);
	}
}
