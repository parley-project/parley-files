package us.bringardner.parley.files.lock;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;

/** Helpers for file systems that implement locking. */
public final class Locks {

	/** The longest pause between attempts while waiting for a lock. */
	static final long MAX_BACKOFF_MS = 100;

	private Locks() {
	}

	/**
	 * Call {@code source.tryLock(mode, lease)} until it succeeds or {@code wait} runs out, backing off
	 * from 2 ms to {@value #MAX_BACKOFF_MS} ms between attempts. This is what
	 * {@link FileSource#lock(LockMode, Duration, Duration)} does by default.
	 * @param source the path
	 * @param mode EXCLUSIVE or SHARED
	 * @param lease the lease each attempt asks for
	 * @param wait how long to keep trying; zero means one attempt
	 * @return the lock
	 * @throws LockTimeoutException if the lock didn't come in time
	 * @throws IOException if an attempt failed
	 * @throws InterruptedException if the thread was interrupted while waiting
	 */
	public static FileSourceLock await(FileSource source, LockMode mode, Duration lease, Duration wait)
			throws IOException, InterruptedException {
		Objects.requireNonNull(wait, "wait");
		if( wait.isNegative() ) {
			throw new IllegalArgumentException("wait must not be negative: " + wait);
		}
		long deadline = System.nanoTime() + LockManager.leaseNanos(wait.isZero() ? Duration.ofNanos(1) : wait);
		long pause = 2;
		while (true) {
			FileSourceLock lock = source.tryLock(mode, lease);
			if( lock != null ) {
				return lock;
			}
			long left = deadline - System.nanoTime();
			if( left <= 0 ) {
				throw new LockTimeoutException("Waited " + wait + " for a " + mode + " lock on " + source);
			}
			Thread.sleep(Math.max(1, Math.min(pause, left / 1_000_000 + 1)));
			pause = Math.min(pause * 2, MAX_BACKOFF_MS);
		}
	}

	/**
	 * A stream that checks the lock when it is closed: the data is flushed and the stream closed
	 * either way, then {@link LockLostException} is thrown if the lock was lost, so the caller knows
	 * not to commit what it wrote. This is the default fencing of
	 * {@link FileSource#getOutputStream(FileSourceLock)}; a file system that can make the check and
	 * the write one atomic step does better.
	 * @param out the stream to write
	 * @param lock the lock the write is made under
	 * @return the checking stream
	 */
	public static OutputStream checkOnClose(OutputStream out, FileSourceLock lock) {
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(lock, "lock");
		return new FilterOutputStream(out) {
			private boolean closed;

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
			}

			@Override
			public void close() throws IOException {
				if( closed ) {
					return;
				}
				closed = true;
				super.close();
				lock.checkValid();
			}
		};
	}
}
