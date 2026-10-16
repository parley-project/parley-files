package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockLostException;
import us.bringardner.parley.files.lock.LockMode;
import us.bringardner.parley.files.lock.LockRenewer;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.lock.LockTimeoutException;

/**
 * The lock contract, for every file system. A file system adds a small subclass (as it does for
 * {@link FileLikeBehaviorTests}) and the suite runs every case the file system's
 * {@link FileSource#getLockScope()} promises, so a file system that claims more than it delivers
 * fails.
 * <ul>
 * <li>Every scope from {@code PROCESS} up: cases 1 to 9 and the renewer, with threads in this JVM.</li>
 * <li>{@code HOST} and {@code CLUSTER}: cases 10 to 13 run a second JVM ({@link LockWorker}) on the
 * same file system. They need {@link #workerSpec(FileSource)} and {@link #fromWorkerSpec(String)};
 * a subclass that doesn't provide them skips them.</li>
 * <li>{@link FileSource#supportsFencedWrites()} true: case 14 is left to the file system's own tests,
 * because how a write is tied to a lock is particular to each one.</li>
 * </ul>
 * The subclass needs a public no-argument constructor: the worker JVM creates one to call
 * {@link #fromWorkerSpec(String)}.
 */
public abstract class AbstractLockTests {

	/** Short enough to keep the suite quick, long enough for slow machines. */
	protected static final Duration LONG = Duration.ofSeconds(30);

	/** The directory this test works in, from {@link #newLockDirectory()}. */
	protected FileSource dir;

	private final List<FileSourceLock> held = new ArrayList<>();
	private final List<Worker> workers = new ArrayList<>();

	/**
	 * @return an empty, existing directory on the file system under test, new for each test
	 * @throws Exception if it can't be made
	 */
	protected abstract FileSource newLockDirectory() throws Exception;

	/**
	 * For the cross-process cases: a string that {@link #fromWorkerSpec(String)} turns back into the
	 * same path in another JVM. Null (the default) skips them.
	 * @param source a path under {@link #dir}
	 * @return the spec, or null
	 * @throws Exception if it can't be made
	 */
	protected String workerSpec(FileSource source) throws Exception {
		return null;
	}

	/**
	 * In the worker JVM, on a new instance of this class: the FileSource for a spec from
	 * {@link #workerSpec(FileSource)}.
	 * @param spec the spec
	 * @return the path
	 * @throws Exception if it can't be made
	 */
	protected FileSource fromWorkerSpec(String spec) throws Exception {
		throw new UnsupportedOperationException(getClass().getName() + " doesn't run lock workers");
	}

	@BeforeEach
	public void makeLockDirectory() throws Exception {
		dir = newLockDirectory();
	}

	@AfterEach
	public void releaseEverything() throws IOException {
		for (FileSourceLock l : held) {
			l.close();
		}
		held.clear();
		for (Worker w : workers) {
			w.stop();
		}
		workers.clear();
	}

	// ------------------------------------------------------------------ helpers

	protected FileSource path(String name) throws IOException {
		return dir.getChild(name);
	}

	private FileSourceLock keep(FileSourceLock l) {
		if( l != null ) {
			held.add(l);
		}
		return l;
	}

	private FileSourceLock tryX(FileSource f) throws IOException {
		return keep(f.tryLock(LockMode.EXCLUSIVE, LONG));
	}

	private FileSourceLock tryS(FileSource f) throws IOException {
		return keep(f.tryLock(LockMode.SHARED, LONG));
	}

	private boolean scopeAtLeast(LockScope scope) {
		return dir.getLockScope().compareTo(scope) >= 0;
	}

	/** Read an integer from a file; a missing or empty file is 0. */
	protected static int readCount(FileSource f) throws IOException {
		if( !f.exists() ) {
			return 0;
		}
		try (InputStream in = f.getInputStream()) {
			String s = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			return s.isEmpty() ? 0 : Integer.parseInt(s);
		}
	}

	protected static void writeCount(FileSource f, int value) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(Integer.toString(value).getBytes(StandardCharsets.UTF_8));
		}
	}

	/** One read-add-write of a counter under an EXCLUSIVE lock on the counter itself. */
	protected static void increment(FileSource counter) throws IOException, InterruptedException {
		try (FileSourceLock l = counter.lock(LockMode.EXCLUSIVE, LONG, LONG)) {
			int v = readCount(counter);
			l.checkValid();
			writeCount(counter, v + 1);
		}
	}

	// ------------------------------------------------------------------ every scope

	@Test
	public void scopeIsNotNone() {
		assertTrue(scopeAtLeast(LockScope.PROCESS), "a scope of NONE has nothing to test");
	}

	/** 1. Exclusive excludes exclusive; shared admits shared; each excludes the other. */
	@Test
	public void modesExcludeEachOther() throws IOException {
		FileSource f = path("modes");
		FileSourceLock x = tryX(f);
		assertNotNull(x);
		assertEquals(LockMode.EXCLUSIVE, x.getMode());
		assertNull(tryX(f), "two EXCLUSIVE holders");
		assertNull(tryS(f), "SHARED while EXCLUSIVE is held");
		x.close();

		FileSourceLock s1 = tryS(f);
		FileSourceLock s2 = tryS(f);
		assertNotNull(s1);
		assertNotNull(s2, "SHARED holders share");
		assertNull(tryX(f), "EXCLUSIVE while SHARED is held");
		s1.close();
		assertNull(tryX(f), "EXCLUSIVE while one SHARED is still held");
		s2.close();
		assertNotNull(tryX(f), "free again");
	}

	/** 1b. Two FileSource objects for one path see the same lock. */
	@Test
	public void sameFileDifferentObjects() throws IOException {
		assertNotNull(tryX(path("same")));
		assertNull(tryX(dir.getChild("same")), "a second object for the same path");
	}

	/** 2. Not reentrant: asking again for a held path is refused. */
	@Test
	public void notReentrant() throws IOException {
		FileSource f = path("reentrant");
		assertNotNull(tryX(f));
		assertNull(tryX(f), "the same thread asking again");
	}

	/** 3. Close releases; closing twice is harmless; another thread may close. */
	@Test
	public void closeReleasesFromAnyThread() throws Exception {
		FileSource f = path("close");
		FileSourceLock x = f.tryLock(LockMode.EXCLUSIVE, LONG);
		assertNotNull(x);
		Thread t = new Thread(() -> {
			try {
				x.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		t.start();
		t.join(10_000);
		assertFalse(x.isValid());
		x.close();
		assertThrows(LockLostException.class, x::checkValid);
		assertNotNull(tryX(f), "released by the other thread");
	}

	/** 4. A lease runs out: the path can be taken, and the old lock knows it lost it. */
	@Test
	public void leaseExpires() throws Exception {
		FileSource f = path("expires");
		FileSourceLock x = f.tryLock(LockMode.EXCLUSIVE, Duration.ofMillis(300));
		assertNotNull(x);
		assertTrue(x.isValid());
		Thread.sleep(700);
		assertFalse(x.isValid(), "past its lease");
		assertThrows(LockLostException.class, x::checkValid);
		assertNotNull(tryX(f), "another caller gets the path");
		assertFalse(x.renew(LONG), "a lost lease is never revived");
		x.close();
		assertNull(tryX(f), "closing the lost lock doesn't release the new holder's");
	}

	/** 5. renew() before expiry keeps the path past the original expiry. */
	@Test
	public void renewExtends() throws Exception {
		FileSource f = path("renew");
		FileSourceLock x = keep(f.tryLock(LockMode.EXCLUSIVE, Duration.ofMillis(1500)));
		assertNotNull(x);
		Thread.sleep(300);
		assertTrue(x.renew(Duration.ofSeconds(10)));
		Thread.sleep(1700);
		assertTrue(x.isValid(), "renewed past the first expiry");
		assertNull(tryX(f));
		assertTrue(x.getExpiry().isAfter(java.time.Instant.now()));
	}

	/** 5b. The renewer keeps a short lease alive, and stops when closed. */
	@Test
	public void renewerKeepsLockAlive() throws Exception {
		FileSource f = path("renewer");
		Duration lease = Duration.ofMillis(600);
		FileSourceLock x = keep(f.tryLock(LockMode.EXCLUSIVE, lease));
		assertNotNull(x);
		try (LockRenewer r = LockRenewer.start(x, lease)) {
			Thread.sleep(2000);
			assertTrue(x.isValid(), "kept alive for several leases");
			assertFalse(r.isLost());
		}
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (x.isValid() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		assertFalse(x.isValid(), "expires once the renewer stops");
	}

	/** 5c. A renewer refuses a lease too short to renew in time. */
	@Test
	public void renewerRefusesTinyLease() throws IOException {
		FileSourceLock x = keep(path("tiny").tryLock(LockMode.EXCLUSIVE, LONG));
		assertThrows(IllegalArgumentException.class, () -> LockRenewer.start(x, Duration.ofMillis(10)));
	}

	/** 6. Tokens for one path strictly increase. */
	@Test
	public void tokensIncrease() throws IOException {
		FileSource f = path("tokens");
		long last = Long.MIN_VALUE;
		for (int i = 0; i < 20; i++) {
			LockMode mode = i % 3 == 0 ? LockMode.SHARED : LockMode.EXCLUSIVE;
			try (FileSourceLock l = f.tryLock(mode, LONG)) {
				assertNotNull(l);
				assertTrue(l.getToken() > last, "token " + l.getToken() + " after " + last);
				last = l.getToken();
			}
		}
	}

	/** 7. A missing path can be locked; locking a directory doesn't lock what's in it. */
	@Test
	public void noHierarchy() throws IOException {
		FileSource missing = path("not-there-yet");
		assertFalse(missing.exists());
		assertNotNull(tryX(missing));
		assertFalse(missing.exists(), "locking doesn't create the path");

		FileSource sub = path("sub");
		assertTrue(sub.mkdirs());
		assertNotNull(tryX(sub));
		assertNotNull(tryX(sub.getChild("inside")), "a child is a different path");
		assertNotNull(tryX(dir), "the parent too");
	}

	/** 8. lock(..., wait) times out while held, and succeeds when the holder releases in time. */
	@Test
	public void lockWaits() throws Exception {
		FileSource f = path("wait");
		FileSourceLock x = f.tryLock(LockMode.EXCLUSIVE, LONG);
		assertNotNull(x);
		long start = System.nanoTime();
		assertThrows(LockTimeoutException.class, () -> f.lock(LockMode.EXCLUSIVE, LONG, Duration.ofMillis(300)));
		assertTrue(System.nanoTime() - start >= 250_000_000L, "waited about as long as asked");

		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<FileSourceLock> waiting = ex.submit(() -> f.lock(LockMode.EXCLUSIVE, LONG, Duration.ofSeconds(10)));
			Thread.sleep(300);
			assertFalse(waiting.isDone());
			x.close();
			assertNotNull(keep(waiting.get(10, TimeUnit.SECONDS)), "got it once released");
		} finally {
			ex.shutdownNow();
		}
	}

	/** 8b. Bad arguments are refused. */
	@Test
	public void badArguments() throws IOException {
		FileSource f = path("args");
		assertThrows(IllegalArgumentException.class, () -> f.tryLock(LockMode.EXCLUSIVE, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> f.tryLock(LockMode.EXCLUSIVE, Duration.ofSeconds(-1)));
		assertThrows(NullPointerException.class, () -> f.tryLock(null, LONG));
		assertNotNull(tryX(f), "refused requests hold nothing");
	}

	/** 9. No lost updates: 8 threads each add 1 to a counter 200 times under the lock. */
	@Test
	public void noLostUpdatesAcrossThreads() throws Exception {
		FileSource counter = path("counter");
		writeCount(counter, 0);
		int threads = 8;
		int each = 200;
		ExecutorService ex = Executors.newFixedThreadPool(threads);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			List<Future<?>> all = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				all.add(ex.submit(() -> {
					try {
						for (int i = 0; i < each; i++) {
							increment(counter);
						}
					} catch (Throwable e) {
						failure.compareAndSet(null, e);
					}
				}));
			}
			for (Future<?> fu : all) {
				fu.get(5, TimeUnit.MINUTES);
			}
		} finally {
			ex.shutdownNow();
		}
		if( failure.get() != null ) {
			throw new AssertionError("an increment failed", failure.get());
		}
		assertEquals(threads * each, readCount(counter));
	}

	// ------------------------------------------------------------------ HOST and CLUSTER

	private Worker worker() throws Exception {
		assumeTrue(scopeAtLeast(LockScope.HOST), "locks reach only " + dir.getLockScope());
		assumeTrue(workerSpec(dir) != null, getClass().getSimpleName() + " doesn't run lock workers");
		Worker w = new Worker(getClass());
		workers.add(w);
		return w;
	}

	/** 10. A lock held in one process excludes the other, both ways. */
	@Test
	public void crossProcessExclusion() throws Exception {
		Worker w = worker();
		FileSource f = path("xproc");
		String spec = workerSpec(f);

		assertTrue(w.ask("lock " + spec + " X 30000").startsWith("GOT"));
		assertNull(tryX(f), "held by the other process");
		assertNull(tryS(f), "SHARED while the other process holds EXCLUSIVE");
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "released by the other process");
		assertEquals("BUSY", w.ask("lock " + spec + " X 30000"), "held here");
		held.get(held.size() - 1).close();

		assertTrue(w.ask("lock " + spec + " S 30000").startsWith("GOT"));
		assertNotNull(tryS(f), "SHARED in both processes");
		assertNull(tryX(f), "EXCLUSIVE while the other process holds SHARED");
	}

	/** 11. Killing the holder frees the path: at once for HOST, when the lease ends for CLUSTER. */
	@Test
	public void deadHolderFreesPath() throws Exception {
		Worker w = worker();
		FileSource f = path("killed");
		long leaseMs = 2000;
		assertTrue(w.ask("lock " + workerSpec(f) + " X " + leaseMs).startsWith("GOT"));
		assertNull(tryX(f));
		w.kill();
		long wait = dir.getLockScope() == LockScope.HOST ? 2000 : leaseMs + 5000;
		assertNotNull(keep(f.lock(LockMode.EXCLUSIVE, LONG, Duration.ofMillis(wait))), "freed after the holder died");
	}

	/** 12. Tokens increase across processes. */
	@Test
	public void tokensIncreaseAcrossProcesses() throws Exception {
		Worker w = worker();
		FileSource f = path("xtokens");
		String spec = workerSpec(f);
		long last = Long.MIN_VALUE;
		for (int i = 0; i < 6; i++) {
			long token;
			if( i % 2 == 0 ) {
				String r = w.ask("lock " + spec + " X 30000");
				assertTrue(r.startsWith("GOT"), r);
				token = Long.parseLong(r.substring(4).trim());
				assertEquals("OK", w.ask("release"));
			} else {
				try (FileSourceLock l = f.tryLock(LockMode.EXCLUSIVE, LONG)) {
					assertNotNull(l);
					token = l.getToken();
				}
			}
			assertTrue(token > last, "token " + token + " after " + last);
			last = token;
		}
	}

	/** 13. No lost updates across two processes counting at once. */
	@Test
	public void noLostUpdatesAcrossProcesses() throws Exception {
		Worker w = worker();
		FileSource counter = path("xcounter");
		writeCount(counter, 0);
		int each = 150;
		w.send("count " + workerSpec(counter) + " " + each);
		for (int i = 0; i < each; i++) {
			increment(counter);
		}
		assertEquals("DONE", w.reply(Duration.ofMinutes(5)));
		assertEquals(2 * each, readCount(counter));
	}

	/** 10b. Releasing one of two SHARED holders here still keeps the other process out. */
	@Test
	public void partialSharedReleaseAcrossProcesses() throws Exception {
		Worker w = worker();
		FileSource f = path("xshared");
		FileSourceLock s1 = tryS(f);
		FileSourceLock s2 = tryS(f);
		assertNotNull(s1);
		assertNotNull(s2);
		assertEquals("BUSY", w.ask("lock " + workerSpec(f) + " X 30000"));
		s1.close();
		assertEquals("BUSY", w.ask("lock " + workerSpec(f) + " X 30000"), "one SHARED holder is left");
		s2.close();
		assertTrue(w.ask("lock " + workerSpec(f) + " X 30000").startsWith("GOT"), "both released");
	}

	/** 11b. A lease that runs out here, never closed, frees the path for the other process. */
	@Test
	public void expiryHereFreesPathThere() throws Exception {
		Worker w = worker();
		FileSource f = path("xexpire");
		FileSourceLock x = f.tryLock(LockMode.EXCLUSIVE, Duration.ofMillis(400));
		assertNotNull(x);
		assertEquals("BUSY", w.ask("lock " + workerSpec(f) + " X 30000"));
		Thread.sleep(1200);
		assertTrue(w.ask("lock " + workerSpec(f) + " X 30000").startsWith("GOT"), "freed when the lease ran out");
		assertFalse(x.isValid());
	}

	// ------------------------------------------------------------------ the worker JVM

	/** A second JVM running {@link LockWorker}, driven line by line. */
	static final class Worker {
		private final Process process;
		private final PrintWriter in;
		private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

		Worker(Class<?> testClass) throws IOException {
			String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
			ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
					LockWorker.class.getName(), testClass.getName());
			pb.redirectError(ProcessBuilder.Redirect.INHERIT);
			process = pb.start();
			in = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
			Thread reader = new Thread(() -> {
				try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						if( line.startsWith(LockWorker.PREFIX) ) {
							replies.add(line.substring(LockWorker.PREFIX.length()));
						}
					}
				} catch (IOException e) {
					// the worker went away
				}
			}, "lock-worker-reader");
			reader.setDaemon(true);
			reader.start();
			try {
				String hello = reply(Duration.ofSeconds(60));
				if( !"READY".equals(hello) ) {
					throw new IOException("The lock worker didn't start: " + hello);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		void send(String command) {
			in.println(command);
		}

		String reply(Duration timeout) throws InterruptedException, IOException {
			String r = replies.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if( r == null ) {
				throw new IOException("No reply from the lock worker in " + timeout);
			}
			return r;
		}

		String ask(String command) throws InterruptedException, IOException {
			send(command);
			return reply(Duration.ofSeconds(60));
		}

		void kill() throws InterruptedException {
			process.destroyForcibly();
			process.waitFor(30, TimeUnit.SECONDS);
		}

		void stop() {
			if( process.isAlive() ) {
				in.println("exit");
				try {
					if( !process.waitFor(10, TimeUnit.SECONDS) ) {
						process.destroyForcibly();
					}
				} catch (InterruptedException e) {
					process.destroyForcibly();
					Thread.currentThread().interrupt();
				}
			}
		}
	}
}
