package us.bringardner.parley.files.lock;

import java.io.IOException;

/**
 * What a file system adds to {@link LockManager} to make its locks reach further than one JVM. The
 * manager decides which holders in this JVM may share a path; the backend then asks everyone else
 * (other processes, other machines) and hands out fencing tokens.
 * <p>
 * The manager calls a backend with the path's monitor held, never twice at once for one path.
 * {@code acquire} is called for every grant in this JVM, including a second SHARED one while the
 * first is still held; {@code release} once for each successful {@code acquire}.
 */
public interface LockBackend {

	/**
	 * Take the lock outside this JVM, if needed.
	 * @param mode the mode asked for
	 * @return the fencing token, zero or more; or -1 if someone outside this JVM holds the path
	 * @throws IOException if the file system could not be asked (nothing is held)
	 */
	long acquire(LockMode mode) throws IOException;

	/**
	 * Give back one successful {@link #acquire(LockMode)}. Must not throw.
	 * @param mode the mode it was acquired with
	 */
	void release(LockMode mode);

	/** Locks only within this JVM: tokens from a counter, nothing to release. */
	static LockBackend inProcess() {
		return LockManager.IN_PROCESS;
	}
}
