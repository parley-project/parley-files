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
		ExecutorService ex = Executors.newFixedThreadPool(2);
		try {
			Future<?> releasing = ex.submit(() -> { first.release(); return null; });
			assertTrue(closing.await(10, TimeUnit.SECONDS));
			Future<FileSourceLock> second = ex.submit(() -> LockManager.get().tryAcquire(f, key, 0, 0, false, next));
			Thread.sleep(200); // give the second locker time to get in, if it can
			finishClose.countDown();
			releasing.get(10, TimeUnit.SECONDS);
			FileSourceLock l = second.get(10, TimeUnit.SECONDS);
			assertNotNull(l);
			l.release();
		} finally { finishClose.countDown(); ex.shutdownNow(); }
		assertFalse(acquiredWhileClosing.get(), "the new backend took the lock while the old one was closing");
	}
}
