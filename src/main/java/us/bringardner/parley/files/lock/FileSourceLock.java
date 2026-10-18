package us.bringardner.parley.files.lock;

import java.io.IOException;

import us.bringardner.parley.files.FileSource;

/**
 * A lock on one path, from {@link FileSource#lock()} or {@link FileSource#tryLock()}: the FileSource
 * counterpart of {@link java.nio.channels.FileLock}, and it behaves the same way.
 * <ul>
 * <li>It is held on behalf of the whole JVM, not a thread: asking again for a path this JVM holds
 * throws {@link java.nio.channels.OverlappingFileLockException}, whichever thread asks. So these
 * locks keep other processes out; threads in one JVM coordinate as they would with FileLock, with
 * Java locks of their own (parley-net's locks do that for servers).</li>
 * <li>It is valid until it is released, or the JVM ends. It doesn't expire.</li>
 * <li>Any thread may release it. Releasing twice is harmless.</li>
 * <li>It is advisory: it keeps out code that asks for the lock, not plain reads and writes. The
 * holder reads and writes the file as usual while it holds the lock.</li>
 * <li>Only an existing file or directory can be locked.</li>
 * </ul>
 * Use it with try-with-resources:
 * <pre>
 * try (FileSourceLock lock = dir.lock()) {
 *     ... read, change, write ...
 * }
 * </pre>
 * @since 1.1.0
 */
public interface FileSourceLock extends AutoCloseable {

	/**
	 * @return the path this lock is on (as {@link java.nio.channels.FileLock#channel()} names the channel)
	 */
	FileSource source();

	/** @return true for a shared lock, false for an exclusive one */
	boolean isShared();

	/** @return true until the lock is released */
	boolean isValid();

	/**
	 * Release the lock. Releasing a released lock does nothing.
	 * @throws IOException if the file system could not be told (the lock is released here anyway)
	 */
	void release() throws IOException;

	/** {@link #release()}, for try-with-resources. */
	@Override
	default void close() throws IOException {
		release();
	}
}
