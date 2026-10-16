package us.bringardner.parley.files.lock;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps a lock alive for as long as its holder needs it, by renewing it at half its lease. For
 * holders whose work has no fixed length: a POP3 session, a delivery attempt, a server's lease on
 * its root.
 * <pre>
 * try (FileSourceLock lock = root.lock(LockMode.EXCLUSIVE, lease, wait);
 *      LockRenewer renewer = LockRenewer.start(lock, lease)) {
 *     ...
 * }
 * </pre>
 * The renewer stops by itself when the lock is lost (it can't be revived). A failed renewal is
 * retried at the next interval; the lock stays valid until its expiry in the meantime.
 */
public final class LockRenewer implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(LockRenewer.class.getName());
	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "parley-lock-renewer");
		t.setDaemon(true);
		return t;
	});

	/** The shortest lease a renewer keeps alive: it renews at half the lease, at least every 10 ms. */
	public static final Duration MIN_LEASE = Duration.ofMillis(50);

	private final FileSourceLock lock;
	private final Duration lease;
	private volatile ScheduledFuture<?> task;
	private volatile boolean lost;
	private volatile boolean closed;

	private LockRenewer(FileSourceLock lock, Duration lease) {
		this.lock = lock;
		this.lease = lease;
	}

	/**
	 * Start renewing.
	 * @param lock the lock to keep
	 * @param lease the lease to renew it with each time, at least {@link #MIN_LEASE}
	 * @return the renewer; close it to stop (closing doesn't release the lock)
	 */
	public static LockRenewer start(FileSourceLock lock, Duration lease) {
		Objects.requireNonNull(lock, "lock");
		LockManager.leaseNanos(lease);
		if( lease.compareTo(MIN_LEASE) < 0 ) {
			throw new IllegalArgumentException("A renewed lease must be at least " + MIN_LEASE + ": " + lease);
		}
		LockRenewer r = new LockRenewer(lock, lease);
		long period = Math.max(10, lease.toMillis() / 2);
		r.task = TIMER.scheduleWithFixedDelay(r::renew, period, period, TimeUnit.MILLISECONDS);
		if( r.closed ) {
			r.task.cancel(false);
		}
		return r;
	}

	private void stop() {
		ScheduledFuture<?> t = task;
		if( t != null ) {
			t.cancel(false);
		}
	}

	private void renew() {
		try {
			if( !lock.renew(lease) ) {
				lost = true;
				stop();
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(e instanceof IOException ? Level.FINE : Level.WARNING, "Renewing " + lock + " failed; trying again", e);
			if( !lock.isValid() ) {
				lost = true;
				stop();
			}
		}
	}

	/** @return true if a renewal found the lock already lost */
	public boolean isLost() {
		return lost;
	}

	/** Stop renewing. The lock itself stays held until its holder closes it or it expires. */
	@Override
	public void close() {
		closed = true;
		stop();
	}
}
