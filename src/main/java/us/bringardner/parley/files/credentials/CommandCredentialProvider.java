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
 * {@link #setEnabled(boolean)} or start the JVM with {@code -Dparley.credentials.cmd.enabled=true} (one
 * caller only: {@code -Dparley.credentials.sftp.cmd.enabled=true}, see {@link CredentialSettings}).
 * <p>
 * The command runs directly, not through a shell: arguments are split on spaces, and single
 * or double quotes keep spaces in one. Its standard error is discarded (it might echo what
 * it was given), standard input is empty, and it is stopped after
 * {@code parley.credentials.cmd.timeout}, a duration ({@code 10s} by default; {@code parley.credentials.ID.cmd.timeout}
 * for one caller). A failing
 * exit status or empty output is an error.
 */
public class CommandCredentialProvider implements CredentialProvider {

	/** System property that enables the provider when it's "true" (see {@link CredentialSettings} for one caller's). */
	public static final String PROP_ENABLED = CredentialSettings.PREFIX + "cmd.enabled";
	/** System property: how long a command may run, a duration (see {@link CredentialSettings} for one caller's). */
	public static final String PROP_TIMEOUT = CredentialSettings.PREFIX + "cmd.timeout";
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
		return isEnabled(null);
	}

	/** Whether the provider is on for this request's caller ({@code parley.credentials.ID.cmd.enabled}, else the JVM's). */
	public static boolean isEnabled(CredentialRequest request) {
		Boolean e = enabled;
		return e != null ? e : Boolean.parseBoolean(CredentialSettings.get("cmd.enabled", request));
	}

	/** How long a command may run for this request's caller, in ms */
	static long timeout(CredentialRequest request) {
		String v = CredentialSettings.get("cmd.timeout", request);
		if( v == null ) {
			return DEFAULT_TIMEOUT;
		}
		try {
			return us.bringardner.parley.core.util.Durations.parse(v);
		} catch (IllegalArgumentException e) {
			System.err.println("Invalid "+PROP_TIMEOUT+": "+e.getMessage()+". Using "+us.bringardner.parley.core.util.Durations.format(DEFAULT_TIMEOUT));
			return DEFAULT_TIMEOUT;
		}
	}

	@Override
	public String scheme() {
		return "cmd";
	}

	@Override
	public String description() {
		return "the output of a command, such as a password manager's CLI (read only)";
	}

	/** On for the JVM, or for at least one caller (parley.credentials.ID.cmd.enabled=true): resolve checks the caller */
	@Override
	public boolean isAvailable() {
		if( isEnabled() ) {
			return true;
		}
		if( enabled != null ) {
			return false;
		}
		for(String name : System.getProperties().stringPropertyNames()) {
			if( name.startsWith(CredentialSettings.PREFIX) && name.endsWith(".cmd.enabled")
					&& Boolean.parseBoolean(System.getProperty(name, "").trim()) ) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String unavailableReason() {
		return isAvailable() ? "" : "it is off; enable it with -D"+PROP_ENABLED+"=true";
	}

	@Override
	public char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		if( !isEnabled(request) ) {
			throw new CredentialException(ref, "the cmd provider is off for "+(request == null || request.factoryId().isEmpty() ? "this program" : request.factoryId())
					+"; enable it with -D"+PROP_ENABLED+"=true");
		}
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
			long timeout = timeout(request);
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
			IOException failure = reader.failure();
			if( failure != null ) {
				reader.clear();
				throw new CredentialException(ref, "can't read the command's output", failure);
			}
			if( reader.tooLong()) {
				reader.clear();
				throw new CredentialException(ref, "the command printed more than "+MAX_OUTPUT+" bytes");
			}
			char[] ret = reader.takeOutput();
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

	/**
	 * Reads the command's output into a buffer it can zero. The reading thread and the caller share
	 * the buffer, so both go through this object's lock: a clear() after a timeout, while the
	 * command may still be printing, zeroes everything read so far and stops any more being kept.
	 */
	private static class Reader implements Runnable {
		final InputStream in;
		// guarded by this
		private byte[] buf = new byte[256];
		private int len;
		private boolean tooLong;
		private IOException failure;
		private boolean cleared;

		Reader(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			byte[] chunk = new byte[256];
			try {
				int n;
				while( (n = in.read(chunk)) >= 0 ) {
					boolean more = append(chunk, n);
					Arrays.fill(chunk, 0, n, (byte) 0);
					if( !more ) {
						break;
					}
				}
			} catch (IOException e) {
				synchronized (this) {
					failure = e;
				}
			} finally {
				Arrays.fill(chunk, (byte) 0);
				try {
					in.close();
				} catch (IOException e) {
					// nothing more to read
				}
			}
		}

		/** Keeps n bytes of chunk; false once nothing more should be read (cleared, or too long). */
		private synchronized boolean append(byte[] chunk, int n) {
			if( cleared ) {
				return false;
			}
			if( len+n >= MAX_OUTPUT ) {
				tooLong = true;
				return false;
			}
			if( len+n > buf.length ) {
				byte[] bigger = Arrays.copyOf(buf, Math.max(buf.length*2, len+n));
				Arrays.fill(buf, (byte) 0);
				buf = bigger;
			}
			System.arraycopy(chunk, 0, buf, len, n);
			len += n;
			return true;
		}

		synchronized IOException failure() {
			return failure;
		}

		synchronized boolean tooLong() {
			return tooLong;
		}

		/** The output as characters, without its last line break; the bytes are zeroed. */
		synchronized char[] takeOutput() {
			char[] ret = SecretFiles.chomp(SecretFiles.decode(buf, len));
			clear();
			return ret;
		}

		synchronized void clear() {
			Arrays.fill(buf, (byte) 0);
			len = 0;
			cleared = true;
		}
	}
}
