package us.bringardner.parley.files.lock;

import java.io.IOException;

/**
 * What a file system adds to {@link LockManager} to make its locks reach other processes. The
 * manager makes sure this JVM holds a path at most once; the backend then asks everyone outside
 * this JVM, as an OS file lock does.
 * <p>
 * The manager calls {@code acquire} at most once at a time per path, and {@code release} once for
 * each successful {@code acquire}.
 * @since 1.1.0
 */
public interface LockBackend {

	/**
	 * Take the lock outside this JVM, if needed. Must not wait.
	 * @param shared true for a shared lock
	 * @return false if someone outside this JVM holds the path
	 * @throws IOException if the file system could not be asked (nothing is held)
	 */
	boolean acquire(boolean shared) throws IOException;

	/** Give back a successful {@link #acquire(boolean)}. Must not throw. */
	void release();

	/** @return locks only within this JVM: nothing to ask, nothing to give back */
	static LockBackend inProcess() {
		return LockManager.IN_PROCESS;
	}
}
