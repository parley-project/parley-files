package us.bringardner.parley.files.credentials;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code @cmd:COMMAND ARGS}: what a command prints, such as a password manager's CLI
 * ({@code @cmd:op read op://Dev/db/password}, {@code @cmd:pass show prod/db}). One trailing
 * line end is dropped.
 * <p>
 * Off until enabled, because it runs whatever a settings file names: call
 * {@link #setEnabled(boolean)} or start the JVM with {@code -Dparley.files.credentials.command.enabled=true}.
 * <p>
 * The command runs directly, not through a shell: arguments are split on spaces, and single
 * or double quotes keep spaces in one. Its standard error is discarded (it might echo what
 * it was given), standard input is empty, and it is stopped after
 * {@code parley.files.credentials.command.timeout} milliseconds (10 seconds by default). A failing
 * exit status or empty output is an error.
 */
public class CommandCredentialProvider implements CredentialProvider {

	/** System property that enables the provider when it's "true". */
	public static final String PROP_ENABLED = "parley.files.credentials.command.enabled";
	/** System property: how long a command may run, in milliseconds. */
	public static final String PROP_TIMEOUT = "parley.files.credentials.command.timeout";
	public static final long DEFAULT_TIMEOUT = 10_000;
	/** More output than this is not a password. */
	private static final int MAX_OUTPUT = 64*1024;

	private static volatile Boolean enabled;

	/** Turns the provider on or off for this JVM (overrides the system property). */
	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/** Whether the provider is on. */
	public static boolean isEnabled() {
		Boolean e = enabled;
		return e != null ? e : Boolean.getBoolean(PROP_ENABLED);
	}

	@Override
	public String scheme() {
		return "cmd";
	}

	@Override
	public String description() {
		return "the output of a command, such as a password manager's CLI (read only)";
	}

	@Override
	public boolean isAvailable() {
		return isEnabled();
	}

	@Override
	public String unavailableReason() {
		return isEnabled() ? "" : "it is off; enable it with -D"+PROP_ENABLED+"=true";
	}

	@Override
	public char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		List<String> args = split(ref);
		Process p;
		try {
			p = new ProcessBuilder(args)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.redirectInput(ProcessBuilder.Redirect.PIPE)
					.start();
			p.getOutputStream().close();
		} catch (IOException e) {
			throw new CredentialException(ref, "can't run "+args.get(0), e);
		}
		Reader reader = new Reader(p.getInputStream());
		Thread t = new Thread(reader, "cmd credential");
		t.setDaemon(true);
		t.start();
		try {
			long timeout = Long.getLong(PROP_TIMEOUT, DEFAULT_TIMEOUT);
			if( !p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				reader.clear();
				throw new CredentialException(ref, "the command took longer than "+timeout+" ms");
			}
			t.join(timeout);
			if( t.isAlive()) {
				// something the command started still holds its output open
				throw new CredentialException(ref, "the command's output didn't end");
			}
			if( p.exitValue() != 0 ) {
				reader.clear();
				throw new CredentialException(ref, "the command failed with exit status "+p.exitValue());
			}
			if( reader.failure != null ) {
				reader.clear();
				throw new CredentialException(ref, "can't read the command's output", reader.failure);
			}
			if( reader.tooLong ) {
				reader.clear();
				throw new CredentialException(ref, "the command printed more than "+MAX_OUTPUT+" bytes");
			}
			char[] ret = SecretFiles.chomp(SecretFiles.decode(reader.buf, reader.len));
			reader.clear();
			if( ret.length == 0 ) {
				throw new CredentialException(ref, "the command printed nothing");
			}
			return ret;
		} catch (InterruptedException e) {
			p.destroyForcibly();
			reader.clear();
			Thread.currentThread().interrupt();
			throw new CredentialException(ref, "interrupted");
		}
	}

	/** The locator split into a command and its arguments; quotes keep spaces in one. */
	static List<String> split(CredentialRef ref) throws CredentialException {
		List<String> ret = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean inToken = false;
		char quote = 0;
		String s = ref.locator();
		for(int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if( quote != 0 ) {
				if( c == quote ) {
					quote = 0;
				} else {
					cur.append(c);
				}
			} else if( c == '\'' || c == '"' ) {
				quote = c;
				inToken = true;
			} else if( Character.isWhitespace(c)) {
				if( inToken ) {
					ret.add(cur.toString());
					cur.setLength(0);
					inToken = false;
				}
			} else {
				cur.append(c);
				inToken = true;
			}
		}
		if( quote != 0 ) {
			throw new CredentialException(ref, "has an unclosed quote");
		}
		if( inToken ) {
			ret.add(cur.toString());
		}
		if( ret.isEmpty()) {
			throw new CredentialException(ref, "names no command");
		}
		return ret;
	}

	/** Reads the command's output into a buffer it can zero. */
	private static class Reader implements Runnable {
		final InputStream in;
		byte[] buf = new byte[256];
		int len;
		boolean tooLong;
		IOException failure;

		Reader(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			try {
				int n;
				while( (n = in.read(buf, len, buf.length-len)) >= 0 ) {
					len += n;
					if( len == buf.length ) {
						if( buf.length >= MAX_OUTPUT ) {
							tooLong = true;
							break;
						}
						byte[] bigger = Arrays.copyOf(buf, buf.length*2);
						Arrays.fill(buf, (byte) 0);
						buf = bigger;
					}
				}
			} catch (IOException e) {
				failure = e;
			} finally {
				try {
					in.close();
				} catch (IOException e) {
					// nothing more to read
				}
			}
		}

		void clear() {
			Arrays.fill(buf, (byte) 0);
			len = 0;
		}
	}
}
