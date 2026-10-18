package us.bringardner.parley.files.test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;

/**
 * The second JVM of {@link AbstractLockTests}' cross-process cases. It creates the test class
 * named on its command line, then reads commands from standard input and answers each with one
 * line starting with {@value #PREFIX} (anything else on standard output is ignored):
 * <ul>
 * <li>{@code lock <spec> X|S}: try to lock, exclusive or shared; {@code GOT} or {@code BUSY}</li>
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
					if( last != null ) {
						last.release();
						last = null;
					}
					FileSourceLock l = f.tryLock("S".equals(w[2]));
					if( l == null ) {
						reply("BUSY");
					} else {
						last = l;
						reply("GOT");
					}
					break;
				}
				case "release":
					if( last != null ) {
						last.release();
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
