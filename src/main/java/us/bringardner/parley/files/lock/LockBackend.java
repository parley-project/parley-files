package us.bringardner.parley.files.lock;

import java.io.IOException;

/**
 * What a file system adds to {@link LockManager} to make its locks reach other processes. The
 * manager makes sure this JVM never holds two overlapping regions of one path; the backend then asks
 * everyone outside this JVM, as an OS file lock does.
 * <p>
 * One backend serves one path for as long as this JVM holds any region of it: the manager keeps the
 * backend it was given with the first lock and calls it, at most once at a time, for every later
 * region, until the last is released; then it calls {@link #close()}. {@code release} is called
 * once for each successful {@code acquire}, with the same region.
 * @since 1.1.0
 */
public interface LockBackend {

	/**
	 * Take a region outside this JVM, if needed. Must not wait.
	 * @param position where the region starts
	 * @param size its size ({@code Long.MAX_VALUE - position} for "to the end")
	 * @param shared true for a shared lock
	 * @return false if someone outside this JVM holds an overlapping region (not shared like this)
	 * @throws IOException if the file system could not be asked (nothing is held)
	 */
	boolean acquire(long position, long size, boolean shared) throws IOException;

	/**
	 * Give back a successful {@link #acquire}. Must not throw.
	 * @param position the region's position, as acquired
	 * @param size its size, as acquired
	 */
	void release(long position, long size);

	/** No region of the path is held in this JVM any more. Must not throw. */
	default void close() {
	}

	/** @return locks only within this JVM: nothing to ask, nothing to give back */
	static LockBackend inProcess() {
		return LockManager.IN_PROCESS;
	}
}
