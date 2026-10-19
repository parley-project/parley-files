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
			assertEquals(10, l.position());
			assertEquals(20, l.size());
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(true), "the whole file overlaps it");
			try (FileLock other = ch.tryLock(0, 10, false)) {
				assertNotNull(other, "the region before it is free");
			}
		}
		try (FileChannel ch = writeChannel(f); FileLock l = ch.lock(5, 0, false)) {
			assertEquals(Long.MAX_VALUE - 5, l.size(), "size zero: to the end, as NIO reports it");
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

	/** 2r. Regions: ones that don't overlap are held side by side in one JVM; overlapping ones throw. */
	@Test
	public void regionsInThisJvm() throws IOException {
		FileSource f = file("regions");
		FileSourceLock a = keep(f.tryLock(0, 10, false));
		FileSourceLock b = keep(f.tryLock(10, 10, true));
		assertNotNull(a);
		assertNotNull(b, "a region next to a held one");
		assertEquals(0, a.position());
		assertEquals(10, a.size());
		assertTrue(a.overlaps(5, 1));
		assertFalse(a.overlaps(10, 5));
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(5, 10, false), "overlaps both");
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(15, 1, true), "shared on shared, still one JVM");
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "the whole file overlaps them");
		FileSourceLock end = keep(f.tryLock(100, 0, false));
		assertNotNull(end, "size zero: from 100 to the end");
		assertEquals(Long.MAX_VALUE - 100, end.size());
		assertThrows(OverlappingFileLockException.class, () -> f.tryLock(1L << 40, 1, false), "to the end means far past it too");
		assertNotNull(keep(f.tryLock(20, 80, false)), "the gap between them");
		a.release();
		assertNotNull(keep(f.tryLock(2, 3, false)), "free once released");
	}

	/** 2s. Regions are checked as NIO checks them. */
	@Test
	public void badRegionsAreRefused() throws IOException {
		FileSource f = file("badregions");
		assertThrows(IllegalArgumentException.class, () -> f.tryLock(-1, 10, false));
		assertThrows(IllegalArgumentException.class, () -> f.tryLock(0, -1, false));
		assertThrows(IllegalArgumentException.class, () -> f.tryLock(Long.MAX_VALUE, 2, false), "overflows");
		assertThrows(IllegalArgumentException.class, () -> f.lock(-1, 1, true));
		assertNotNull(tryX(f), "refused requests hold nothing");
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

	/**
	 * A second JVM on this file system, for a subclass's own cross-process cases; the test is skipped
	 * where the scope or the subclass doesn't allow one.
	 */
	protected Worker worker() throws Exception {
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

	/** 10r. Regions across processes: overlapping ones exclude each other unless both are shared. */
	@Test
	public void crossProcessRegions() throws Exception {
		Worker w = worker();
		FileSource f = file("xregions");
		String spec = workerSpec(f);
		assertEquals("GOT", w.ask("lock " + spec + " X 0 10"));
		assertNull(f.tryLock(), "the whole file overlaps the other process's region");
		FileSourceLock beside = keep(f.tryLock(10, 10, false));
		assertNotNull(beside, "a region beside it");
		assertNull(f.tryLock(2, 5, false), "overlapping the other's, exclusive");
		assertNull(f.tryLock(2, 5, true), "overlapping the other's, shared against exclusive");
		beside.release();
		assertEquals("OK", w.ask("release"));

		assertEquals("GOT", w.ask("lock " + spec + " S 0 10"));
		FileSourceLock s = keep(f.tryLock(5, 10, true));
		assertNotNull(s, "shared regions may overlap across processes");
		s.release();
		assertNull(f.tryLock(5, 10, false), "exclusive against the other's shared region");
		assertNotNull(keep(f.tryLock(10, 5, false)), "beside it, exclusive");
		assertEquals("BUSY", w.ask("lock " + spec + " X 12 1"), "the other process is kept out of ours");
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
		assertNull(f.tryLock(), "busy elsewhere, and the interrupted waiter left no claim in this JVM");
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "the interrupted request holds nothing");
	}

	/** 11c. Closing the channel from another thread stops a waiting lock(): AsynchronousCloseException. */
	@Test
	public void closingTheChannelStopsAWaitingLock() throws Exception {
		Worker w = worker();
		FileSource f = file("xclose");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		FileChannel ch = writeChannel(f);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<FileLock> waiting = ex.submit(() -> ch.lock());
			Thread.sleep(300);
			ch.close();
			java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
					() -> waiting.get(10, TimeUnit.SECONDS));
			assertTrue(e.getCause() instanceof java.nio.channels.AsynchronousCloseException, "closed while waiting: " + e.getCause());
		} finally {
			ex.shutdownNow();
		}
		assertNull(f.tryLock(), "busy elsewhere, and the stopped waiter left no claim in this JVM");
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "the stopped request holds nothing");
	}

	/** 11d. Interrupting a waiting FileChannel.lock(): FileLockInterruptionException, channel closed, nothing held. */
	@Test
	public void waitingChannelLockCanBeInterrupted() throws Exception {
		Worker w = worker();
		FileSource f = file("xchinterrupt");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		FileChannel ch = writeChannel(f);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			Future<Boolean> waiting = ex.submit(() -> {
				try {
					ch.lock().release();
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
		assertFalse(ch.isOpen(), "an interrupted lock() closes the channel, as NIO's does");
		assertNull(f.tryLock(), "busy elsewhere, and the interrupted waiter left no claim in this JVM");
		assertEquals("OK", w.ask("release"));
		assertNotNull(tryX(f), "the interrupted request holds nothing");
	}

	/**
	 * 11e. close() racing tryLock() and release() on the channel: once close() returns, the channel
	 * holds nothing, whatever the other thread was doing.
	 */
	@Test
	public void closeRacingLockAndReleaseLeavesNothingHeld() throws Exception {
		FileSource f = file("closerace");
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try {
			for (int i = 0; i < 300; i++) {
				FileChannel ch = writeChannel(f);
				boolean releaseToo = i % 2 == 0;
				java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(2);
				Future<?> other = ex.submit(() -> {
					go.await();
					try {
						FileLock l = ch.tryLock();
						if( l != null && releaseToo ) {
							l.release();
						}
					} catch (ClosedChannelException closed) {
						// closed first (AsynchronousCloseException is one too)
					}
					return null;
				});
				go.await();
				ch.close();
				FileSourceLock after = f.tryLock();
				assertNotNull(after, "round " + i + ": free once close() returned");
				after.release();
				other.get(10, TimeUnit.SECONDS);
			}
		} finally {
			ex.shutdownNow();
		}
	}

	/**
	 * 11f. As with FileChannel.lock: while one thread waits for a region, another thread of the JVM
	 * asking for an overlapping region gets OverlappingFileLockException, by tryLock or lock, through
	 * FileSource and through a channel; a region that doesn't overlap is still free to take, and the
	 * waiter gets its region when the other process lets go.
	 */
	@Test
	public void aSecondThreadCantWaitForTheSameRegion() throws Exception {
		Worker w = worker();
		FileSource f = file("xwaiters");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X 0 10"));
		ExecutorService ex = Executors.newFixedThreadPool(2);
		try {
			Future<FileSourceLock> waiting = ex.submit(() -> f.lock(0, 10, false));
			awaitClaim(f, 5, 1);
			assertThrows(OverlappingFileLockException.class, () -> f.lock(5, 10, false), "lock: another thread waits for it");
			Future<FileSourceLock> second = ex.submit(() -> f.lock(0, 1, true));
			java.util.concurrent.ExecutionException e = assertThrows(java.util.concurrent.ExecutionException.class,
					() -> second.get(10, TimeUnit.SECONDS));
			assertTrue(e.getCause() instanceof OverlappingFileLockException, "lock from another thread: " + e.getCause());
			try (FileChannel ch = writeChannel(f)) {
				assertThrows(OverlappingFileLockException.class, () -> ch.tryLock(2, 1, false), "through a channel too");
			}
			FileSourceLock beside = f.tryLock(20, 5, false);
			assertNotNull(beside, "a region that doesn't overlap is free");
			beside.release();
			assertEquals("OK", w.ask("release"));
			FileSourceLock got = waiting.get(10, TimeUnit.SECONDS);
			assertNotNull(got, "the waiter gets its region");
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(0, 1, false), "now held");
			got.release();
		} finally {
			ex.shutdownNow();
		}
		assertNotNull(tryX(f), "nothing left claimed");
	}

	/** 11g. The same for a thread waiting in FileChannel.lock(). */
	@Test
	public void aSecondThreadCantWaitForTheSameRegionOfAChannel() throws Exception {
		Worker w = worker();
		FileSource f = file("xchwaiters");
		assertEquals("GOT", w.ask("lock " + workerSpec(f) + " X"));
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try (FileChannel ch = writeChannel(f)) {
			Future<FileLock> waiting = ex.submit(() -> ch.lock());
			awaitClaim(f, 0, 1);
			assertThrows(OverlappingFileLockException.class, () -> ch.tryLock(), "the same channel");
			try (FileChannel other = writeChannel(f)) {
				assertThrows(OverlappingFileLockException.class, () -> other.lock(), "another channel, lock");
			}
			assertEquals("OK", w.ask("release"));
			FileLock got = waiting.get(10, TimeUnit.SECONDS);
			assertTrue(got.isValid());
			got.release();
		} finally {
			ex.shutdownNow();
		}
		assertNotNull(tryX(f), "nothing left claimed");
	}

	/** Wait until a thread of this JVM has claimed the region: tryLock throws instead of finding it busy. */
	private static void awaitClaim(FileSource f, long position, long size) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (true) {
			try {
				assertNull(f.tryLock(position, size, false), "held by the other process");
			} catch (OverlappingFileLockException claimed) {
				return;
			}
			assertTrue(System.nanoTime() < deadline, "the waiting thread never claimed the region");
			Thread.sleep(5);
		}
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
	protected static final class Worker {
		private final Process process;
		private final PrintWriter in;
		private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

		Worker(Class<?> testClass) throws IOException {
			String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
			List<String> cmd = new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path")));
			// the parley settings this JVM runs with (a lock directory, say), so the worker locks alike
			for (String name : System.getProperties().stringPropertyNames()) {
				if( name.startsWith("parley.") ) {
					cmd.add("-D" + name + "=" + System.getProperty(name));
				}
			}
			cmd.add(LockWorker.class.getName());
			cmd.add(testClass.getName());
			ProcessBuilder pb = new ProcessBuilder(cmd);
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

		public String ask(String command) throws InterruptedException, IOException {
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
