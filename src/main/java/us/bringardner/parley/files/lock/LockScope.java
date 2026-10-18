package us.bringardner.parley.files.lock;

/**
 * How far a file system's locks reach (see {@link us.bringardner.parley.files.FileSource#getLockScope()}).
 * Each scope includes the ones before it, so {@code scope.compareTo(HOST) >= 0} asks "at least HOST".
 */
public enum LockScope {

	/**
	 * No locking. A file system that reports NONE must also override {@code tryLock(boolean)} to
	 * throw {@link UnsupportedOperationException}; the defaults give PROCESS locks.
	 */
	NONE,

	/** All threads in this JVM. Other processes don't see the locks. */
	PROCESS,

	/** All processes on this machine that use the same lock directory. */
	HOST,

	/** All processes on all machines that share the file system (no file system in parley-files offers this). */
	CLUSTER
}
