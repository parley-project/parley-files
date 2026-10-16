package us.bringardner.parley.files.test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockMode;

/**
 * The second JVM of {@link AbstractLockTests}' cross-process cases. It creates the test class
 * named on its command line, then reads commands from standard input and answers each with one
 * line starting with {@value #PREFIX} (anything else on standard output is ignored):
 * <ul>
 * <li>{@code lock <spec> X|S <leaseMs>}: try to lock; {@code GOT <token>} or {@code BUSY}</li>
 * <li>{@code release}: release the last lock taken; {@code OK}</li>
 * <li>{@code count <spec> <n>}: add 1 to the counter file n times, each under its lock; {@code DONE}</li>
 * <li>{@code exit}</li>
 * </ul>
 * Errors answer {@code ERROR <message>}.
 */
public final class LockWorker {

	static final String PREFIX = "@@ ";

	private LockWorker() {
	}

	public static void main(String[] args) throws Exception {
		AbstractLockTests test = (AbstractLockTests) Class.forName(args[0]).getDeclaredConstructor().newInstance();
		FileSourceLock last = null;
		reply("READY");
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			String[] w = line.trim().split(" ");
			try {
				switch (w[0]) {
				case "lock": {
					FileSource f = test.fromWorkerSpec(w[1]);
					LockMode mode = "S".equals(w[2]) ? LockMode.SHARED : LockMode.EXCLUSIVE;
					FileSourceLock l = f.tryLock(mode, Duration.ofMillis(Long.parseLong(w[3])));
					if( l == null ) {
						reply("BUSY");
					} else {
						if( last != null ) {
							last.close();
						}
						last = l;
						reply("GOT " + l.getToken());
					}
					break;
				}
				case "release":
					if( last != null ) {
						last.close();
						last = null;
					}
					reply("OK");
					break;
				case "count": {
					FileSource counter = test.fromWorkerSpec(w[1]);
					int n = Integer.parseInt(w[2]);
					for (int i = 0; i < n; i++) {
						AbstractLockTests.increment(counter);
					}
					reply("DONE");
					break;
				}
				case "exit":
					System.exit(0);
					break;
				default:
					reply("ERROR unknown command " + w[0]);
				}
			} catch (Exception e) {
				reply("ERROR " + e);
			}
		}
		System.exit(0);
	}

	private static void reply(String s) {
		System.out.println(PREFIX + s);
		System.out.flush();
	}
}
