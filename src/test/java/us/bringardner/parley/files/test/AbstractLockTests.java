package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
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
import us.bringardner.parley.files.lock.LockScope;

/**
 * The lock contract, for every file system: the rules of NIO file locks, on FileSource paths. A file
 * system adds a small subclass (as it does for {@link FileLikeBehaviorTests}) and the suite runs every
 * case the file system's {@link FileSource#getLockScope()} promises, so a file system that claims
 * more than it delivers fails. Locks are taken on files the test creates, since only existing files
 * can be locked.
 * <ul>
 * <li>Every scope from {@code PROCESS} up: the in-JVM cases (one hold per JVM, release, paths).</li>
 * <li>{@code HOST} and up: the cross-process cases run a second JVM ({@link LockWorker}) on the same
 * file system. They need {@link #workerSpec(FileSource)} and {@link #fromWorkerSpec(String)}; a
 * subclass that doesn't provide them skips them.</li>
 * </ul>
 * The subclass needs a public no-argument constructor: the worker JVM creates one to call
 * {@link #fromWorkerSpec(String)}.
 */
public abstract class AbstractLockTests {

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
			l.release();
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

	/** An existing, empty file in {@link #dir}: only existing files can be locked. */
	protected FileSource file(String name) throws IOException {
		FileSource f = dir.getChild(name);
		if( !f.exists() ) {
			writeBytes(f, new byte[0]);
		}
		return f;
	}

	protected static void writeBytes(FileSource f, byte[] data) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
	}

	private FileSourceLock keep(FileSourceLock l) {
		if( l != null ) {
			held.add(l);
		}
		return l;
	}

	private FileSourceLock tryX(FileSource f) throws IOException {
		return keep(f.tryLock());
	}

	private FileSourceLock tryS(FileSource f) throws IOException {
		return keep(f.tryLock(true));
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

	/** One read-add-write of a counter under an exclusive lock on the counter itself. */
	protected static void increment(FileSource counter) throws IOException {
		try (FileSourceLock l = counter.lock()) {
			writeCount(counter, readCount(counter) + 1);
		}
	}

	// ------------------------------------------------------------------ every scope

	@Test
	public void scopeIsNotNone() {
		assertTrue(scopeAtLeast(LockScope.PROCESS), "a scope of NONE has nothing to test");
	}

	/** 1. The lock says what it is. */
	@Test
	public void lockDescribesItself() throws IOException {
		FileSource f = file("describe");
		FileSourceLock x = tryX(f);
		assertNotNull(x);
		assertFalse(x.isShared());
		assertTrue(x.isValid());
		assertSame(f, x.source());
		x.release();
		FileSourceLock s = tryS(f);
		assertNotNull(s);
		assertTrue(s.isShared());
		s.close();
		assertFalse(s.isValid(), "close() releases");
		try (FileSourceLock l = f.lock(true)) {
			assertTrue(l.isShared());
		}
		try (FileSourceLock l = f.lock()) {
			assertFalse(l.isShared(), "lock() is exclusive");
		}
	}

	// ------------------------------------------------------------------ through NIO

	/** A channel opened for writing (APPEND works on every file system, random access or not). */
	private static FileChannel writeChannel(FileSource f) throws IOException {
		return FileChannel.open(f.toPath(), StandardOpenOption.APPEND);
	}

	private static FileChannel readChannel(FileSource f) throws IOException {
		return FileChannel.open(f.toPath(), StandardOpenOption.READ);
	}

	/** 1n. FileChannel.lock() on a FileSource path is the FileSource's lock, as a FileLock. */
	@Test
	public void fileChannelLocksTheFile() throws IOException {
		FileSource f = file("channel");
		try (FileChannel ch = writeChannel(f); FileLock l = ch.lock()) {
			assertSame(ch, l.channel());
			assertFalse(l.isShared());
			assertTrue(l.isValid());
			assertEquals(0, l.position());
			assertEquals(Long.MAX_VALUE, l.size());
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "held by the JVM, as through FileSource");
			assertThrows(OverlappingFileLockException.class, () -> ch.tryLock(), "and through the channel");
		}
		assertNotNull(tryX(f), "released");
		held.remove(held.size() - 1).release();
		try (FileChannel ch = readChannel(f); FileLock l = ch.lock(0, Long.MAX_VALUE, true)) {
			assertTrue(l.isShared());
			assertSame(ch, l.channel());
		}
		try (FileChannel ch = writeChannel(f); FileLock l = ch.tryLock(10, 20, false)) {
			assertNotNull(l);
			assertEquals(10, l.position(), "the range asked for is reported");
			assertEquals(20, l.size());
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(true), "but the whole file is locked");
		}
	}

	/** 1o. NIO's rules for which channel may lock how, and closing the channel releases its locks. */
	@Test
	public void fileChannelRules() throws IOException {
		FileSource f = file("channelrules");
		try (FileChannel r = readChannel(f)) {
			assertThrows(NonWritableChannelException.class, () -> r.lock(), "exclusive needs a writable channel");
		}
		try (FileChannel w = writeChannel(f)) {
			assertThrows(NonReadableChannelException.class, () -> w.lock(0, Long.MAX_VALUE, true), "shared needs a readable channel");
			assertThrows(IllegalArgumentException.class, () -> w.tryLock(-1, 1, false));
		}
		FileChannel ch = writeChannel(f);
		FileLock l = ch.lock();
		ch.close();
		assertFalse(l.isValid(), "closing the channel releases its lock");
		assertThrows(ClosedChannelException.class, l::release);
		assertThrows(ClosedChannelException.class, () -> ch.tryLock());
		assertNotNull(tryX(f), "free again");
		held.remove(held.size() - 1).release();
		FileChannel ch2 = writeChannel(f);
		FileLock l2 = ch2.lock();
		l2.release();
		assertFalse(l2.isValid());
		assertTrue(ch2.isOpen(), "releasing a lock leaves the channel open");
		l2.release(); // twice is harmless
		ch2.close();
	}

	/** 2. A JVM holds a path once: asking again throws, in either mode, from tryLock and lock. */
	@Test
	public void secondRequestInThisJvmThrows() throws IOException {
		FileSource f = file("twice");
		assertNotNull(tryX(f));
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "exclusive again");
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(true), "shared while exclusive");
		assertThrows(OverlappingFileLockException.class, () -> f.lock(), "lock() doesn't wait for this JVM");
		held.remove(0).release();

		assertNotNull(tryS(f));
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(true), "shared twice in one JVM, as in NIO");
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "exclusive while shared");
	}

	/** 2b. Two FileSource objects for one path are one path to the lock. */
	@Test
	public void sameFileDifferentObjects() throws IOException {
		assertNotNull(tryX(file("same")));
		assertThrows(OverlappingFileLockException.class, () -> dir.getChild("same").tryLock());
	}

	/** 2c. The lock is the JVM's, not the thread's: another thread is refused the same way. */
	@Test
	public void otherThreadsAreRefusedNotQueued() throws Exception {
		FileSource f = file("threads");
		assertNotNull(tryX(f));
		AtomicReference<Throwable> seen = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				f.lock().release();
			} catch (Throwable e) {
				seen.set(e);
			}
		});
		t.start();
		t.join(10_000);
		assertFalse(t.isAlive(), "lock() didn't wait for this JVM's holder");
		assertTrue(seen.get() instanceof OverlappingFileLockException, "got " + seen.get());
	}

	/** 3. Release frees the path; releasing twice is harmless; any thread may release. */
	@Test
	public void releaseFromAnyThread() throws Exception {
		FileSource f = file("release");
		FileSourceLock x = f.tryLock();
		assertNotNull(x);
		Thread t = new Thread(() -> {
			try {
				x.release();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		t.start();
		t.join(10_000);
		assertFalse(x.isValid());
		x.release();
		x.close();
		assertNotNull(tryX(f), "released by the other thread");
		assertFalse(x.isValid(), "an old lock never comes back");
	}

	/** 4. Only what exists can be locked; locking a directory doesn't lock what's in it. */
	@Test
	public void onlyExistingFilesLock() throws IOException {
		FileSource missing = path("not-there");
		assertFalse(missing.exists());
		assertThrows(FileNotFoundException.class, () -> missing.tryLock(), "tryLock on a missing file");
		assertThrows(FileNotFoundException.class, () -> missing.tryLock(true));
		assertThrows(FileNotFoundException.class, () -> missing.lock(), "lock() doesn't wait for it to appear");
		assertFalse(missing.exists(), "nothing created");
		assertNotNull(tryX(file("not-there")), "lockable once it exists");

		FileSource sub = path("sub");
		assertTrue(sub.mkdirs());
		assertNotNull(tryX(sub), "a directory can be locked");
		FileSource inside = sub.getChild("inside");
		writeBytes(inside, new byte[0]);
		assertNotNull(tryX(inside), "a child is a different path");
		assertNotNull(tryX(dir), "the parent too");
	}

	/** 5. Many paths, many threads: each thread its own path, nothing refused, all released. */
	@Test
	public void separatePathsDontContend() throws Exception {
		int threads = 8;
		ExecutorService ex = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> all = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				FileSource f = file("own" + t);
				all.add(ex.submit(() -> {
					for (int i = 0; i < 100; i++) {
						try (FileSourceLock l = f.lock(i % 2 == 0)) {
							assertTrue(l.isValid());
						}
					}
					return null;
				}));
			}
			for (Future<?> fu : all) {
				fu.get(2, TimeUnit.MINUTES);
			}
		} finally {
			ex.shutdownNow();
		}
		assertNotNull(tryX(file("own0")), "all released");
	}

	// ------------------------------------------------------------------ HOST and up

	private Worker worker() throws Exception {
		assumeTrue(scopeAtLeast(LockScope.HOST), "locks reach only " + dir.getLockScope());
		assumeTrue(workerSpec(dir) != null, getClass().getSimpleName() + " doesn't run lock workers");
		Worker w = new Worker(getClass());
		workers.add(w);
		return w;
	}

	/** 10. Exclusive excludes everything, shared admits shared, across processes, both ways. */
	@Test
	public void crossProcessModes() throws Exception {
		Worker w = worker();
		FileSource f = file("xproc");
		String spec = workerSpec(f);

		assertEquals("GOT", w.ask("lock " + spec + " X"));
		assertNull(tryX(f), "held by the other process");
		assertNull(tryS(f), "shared while the other process holds exclusive");
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "released by the other process");
		assertEquals("BUSY", w.ask("lock " + spec + " X"), "held here");
		assertEquals("BUSY", w.ask("lock " + spec + " S"), "shared while held exclusive here");
		held.remove(held.size() - 1).release();

		assertEquals("GOT", w.ask("lock " + spec + " S"));
		FileSourceLock s = tryS(f);
		assertNotNull(s, "shared in both processes");
		s.release();
		held.remove(s);
		assertNull(tryX(f), "exclusive while the other process holds shared");
	}

	/** 10n. Through NIO too: a FileChannel's tryLock() is refused while another process holds the file. */
	@Test
	public void crossProcessThroughFileChannel() throws Exception {
		Worker w = worker();
		FileSource f = file("xchannel");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		try (FileChannel ch = writeChannel(f)) {
			assertNull(ch.tryLock(), "held by the other process");
			assertEquals("OK", w.ask("release"));
			try (FileLock l = ch.tryLock()) {
				assertNotNull(l, "free once released");
				assertEquals("BUSY", w.ask("lock " + workerSpec(f) + " S"), "and the other process is kept out");
			}
		}
	}

	/** 11. lock() waits for another process, and gets the path once it is released. */
	@Test
	public void lockWaitsForAnotherProcess() throws Exception {
		Worker w = worker();
		FileSource f = file("xwait");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<FileSourceLock> waiting = ex.submit(() -> f.lock());
			Thread.sleep(300);
			assertFalse(waiting.isDone(), "waits while the other process holds it");
			assertEquals("OK", w.ask("release"));
			assertNotNull(keep(waiting.get(10, TimeUnit.SECONDS)), "got it once released");
		} finally {
			ex.shutdownNow();
		}
	}

	/** 11b. Interrupting a waiting lock() throws FileLockInterruptionException and holds nothing. */
	@Test
	public void waitingLockCanBeInterrupted() throws Exception {
		Worker w = worker();
		FileSource f = file("xinterrupt");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> waiting = ex.submit(() -> {
				try {
					f.lock().release();
					return false;
				} catch (FileLockInterruptionException e) {
					return Thread.currentThread().isInterrupted();
				}
			});
			Thread.sleep(300);
			waiting.cancel(true);
			ex.shutdown();
			assertTrue(ex.awaitTermination(10, TimeUnit.SECONDS), "the waiter stopped");
		} finally {
			ex.shutdownNow();
		}
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "the interrupted request holds nothing");
	}

	/** 12. Killing the holder frees the path at once. */
	@Test
	public void deadHolderFreesPath() throws Exception {
		Worker w = worker();
		FileSource f = file("killed");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		assertNull(tryX(f));
		w.kill();
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			assertNotNull(keep(ex.submit(() -> f.lock()).get(10, TimeUnit.SECONDS)), "freed when the holder died");
		} catch (ExecutionException e) {
			throw new AssertionError(e.getCause());
		} finally {
			ex.shutdownNow();
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
