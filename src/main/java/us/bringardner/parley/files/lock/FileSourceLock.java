package us.bringardner.parley.files.lock;

import java.io.IOException;
import java.time.Instant;

import us.bringardner.parley.files.FileSource;

/**
 * A lease on one path, from {@link FileSource#tryLock} or {@link FileSource#lock}.
 * <p>
 * The lock belongs to this object, not to a thread: any thread may renew or close it. It is not
 * reentrant: asking again for a path you hold is refused like any other request, so code that
 * needs the lock further down is passed this object instead.
 * <p>
 * Use it with try-with-resources:
 * <pre>
 * try (FileSourceLock lock = dir.lock(LockMode.EXCLUSIVE, Duration.ofSeconds(30), Duration.ofSeconds(10))) {
 *     ... read, change ...
 *     lock.checkValid();   // still ours? then commit
 *     ... write ...
 * }
 * </pre>
 * Choose the lease well above the time the work takes (ten times is a good rule), or keep it
 * alive with a {@link LockRenewer}.
 */
public interface FileSourceLock extends AutoCloseable {

	/** @return the path this lock is on */
	FileSource getFileSource();

	/** @return EXCLUSIVE or SHARED */
	LockMode getMode();

	/**
	 * A fencing token: higher on every grant of this path within the lock's scope. A store that
	 * remembers the highest token it has seen can refuse writes from a holder whose lease ran out
	 * while it was paused.
	 * @return the token
	 */
	long getToken();

	/** @return when the lease runs out unless renewed (the real expiry, after any rounding) */
	Instant getExpiry();

	/** @return false once the lock was released or its lease ran out */
	boolean isValid();

	/**
	 * Extend the lease to now plus {@code lease}.
	 * @param lease the new lease, more than zero
	 * @return false if the lock was already lost; a lost lock is never revived
	 * @throws IOException if the file system could not be reached (the lock stays valid until its expiry)
	 */
	boolean renew(java.time.Duration lease) throws IOException;

	/**
	 * Call before committing a write.
	 * @throws LockLostException if the lock is no longer held
	 */
	void checkValid() throws LockLostException;

	/** Release the lock. Closing a released or lost lock does nothing. */
	@Override
	void close() throws IOException;
}
