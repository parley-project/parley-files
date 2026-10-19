package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockBackend;
import us.bringardner.parley.files.lock.LockManager;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** LockManager's handling of backends, apart from any one file system. */
public class LockManagerTests {

	/**
	 * A new lock on a path must not reach a new backend while the last holder's backend is still
	 * closing: closing a channel can release every lock the JVM holds on the file, the new one too.
	 */
	@Test
	public void aNewBackendWaitsForTheOldOneToClose() throws Exception {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource f = factory.createFileSource("/closing");
		try (OutputStream out = f.getOutputStream()) { out.write(1); }
		String key = "test|" + System.nanoTime();
		CountDownLatch closing = new CountDownLatch(1), finishClose = new CountDownLatch(1);
		AtomicBoolean closed = new AtomicBoolean(), acquiredWhileClosing = new AtomicBoolean();
		LockBackend old = new LockBackend() {
			public boolean acquire(long p, long s, boolean sh) { return true; }
			public void release(long p, long s) { }
			public void close() {
				closing.countDown();
				try { finishClose.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				closed.set(true);
			}
		};
		LockBackend next = new LockBackend() {
			public boolean acquire(long p, long s, boolean sh) { if( !closed.get() ) acquiredWhileClosing.set(true); return true; }
			public void release(long p, long s) { }
		};
		FileSourceLock first = LockManager.get().tryAcquire(f, key, 0, 0, false, old);
		assertNotNull(first);
		ExecutorService ex = Executors.newSingleThreadExecutor();
		java.util.concurrent.atomic.AtomicReference<Object> result = new java.util.concurrent.atomic.AtomicReference<>();
		Thread locker = new Thread(() -> {
			try {
				result.set(LockManager.get().tryAcquire(f, key, 0, 0, false, next));
			} catch (Throwable t) {
				result.set(t);
			}
		}, "second-locker");
		try {
			Future<?> releasing = ex.submit(() -> { first.release(); return null; });
			assertTrue(closing.await(10, TimeUnit.SECONDS), "the old backend is closing");
			locker.start();
			// No sleeping: wait until the second locker shows which way it went. Fixed, it is blocked
			// on the old entry's monitor; broken, it reaches the new backend while the old one closes.
			java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!acquiredWhileClosing.get() && !blockedOnAnEntry(mx, locker)) {
				assertTrue(System.nanoTime() < deadline, "the second locker neither waited nor got in: " + locker.getState());
				Thread.onSpinWait();
			}
			finishClose.countDown();
			releasing.get(10, TimeUnit.SECONDS);
			locker.join(10_000);
			assertTrue(result.get() instanceof FileSourceLock, "the second locker gets the lock once the close is done: " + result.get());
			((FileSourceLock) result.get()).release();
		} finally {
			finishClose.countDown();
			ex.shutdownNow();
		}
		assertFalse(acquiredWhileClosing.get(), "the new backend took the lock while the old one was closing");
	}

	/** Whether the thread is blocked on a LockManager entry's monitor. */
	private static boolean blockedOnAnEntry(java.lang.management.ThreadMXBean mx, Thread t) {
		if( t.getState() != Thread.State.BLOCKED ) {
			return false;
		}
		java.lang.management.ThreadInfo info = mx.getThreadInfo(t.getId());
		return info != null && info.getLockName() != null && info.getLockName().contains("LockManager$Entry");
	}
}
