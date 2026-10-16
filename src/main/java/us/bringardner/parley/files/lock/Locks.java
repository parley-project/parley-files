package us.bringardner.parley.files.lock;

import java.io.IOException;
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
}
