package us.bringardner.parley.files.lock;

import java.io.IOException;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.OverlappingFileLockException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * The paths locked in this JVM, for every file system. It keeps the rule
 * {@link java.nio.channels.FileChannel#tryLock()} keeps: a JVM holds a path at most once, and asking
 * again throws {@link OverlappingFileLockException}. On its own it gives {@link LockScope#PROCESS}
 * locks, which is what {@link FileSource#tryLock(boolean)} does by default; a file system that can
 * do better passes a {@link LockBackend} that also asks everyone outside this JVM.
 * @since 1.1.0
 */
public final class LockManager {

	private static final Logger LOG = Logger.getLogger(LockManager.class.getName());
	private static final LockManager INSTANCE = new LockManager();

	/** {@link #await} polls this often at most (ms), starting at 1 ms. */
	static final long MAX_POLL_MS = 50;

	/** The backend for locks that stay in this JVM. */
	static final LockBackend IN_PROCESS = new LockBackend() {
		@Override
		public boolean acquire(boolean shared) {
			return true;
		}

		@Override
		public void release() {
			// nothing outside the JVM to give back
		}

		@Override
		public String toString() {
			return "in-process";
		}
	};

	/** @return the JVM's lock manager */
	public static LockManager get() {
		return INSTANCE;
	}

	private final ConcurrentHashMap<String, Grant> held = new ConcurrentHashMap<>();

	private LockManager() {
	}

	/**
	 * A key that names a path on one file system: its type, its URL and the canonical path, so two
	 * FileSource objects for the same file get the same key. Where the canonical path can't be found
	 * (a server that won't show the directories above the user's, say), the absolute path is used:
	 * two names for one file (through a link) are then two paths to this JVM's lock table.
	 * @param source the path
	 * @return the key
	 * @throws IOException if neither path can be found
	 */
	public static String keyOf(FileSource source) throws IOException {
		FileSourceFactory factory = source.getFileSourceFactory();
		String fileSystem = "?";
		if( factory != null ) {
			String url;
			try {
				url = factory.getURL();
			} catch (RuntimeException e) {
				url = null;
			}
			fileSystem = factory.getTypeId() + "|" + url;
		}
		String path;
		try {
			path = source.getCanonicalPath();
		} catch (IOException e) {
			path = source.getAbsolutePath();
		}
		return fileSystem + "|" + path;
	}

	/**
	 * Take a lock now, or return null if another process holds it.
	 * @param source the path, kept in the lock for {@link FileSourceLock#source()}
	 * @param key what names the path for this file system (see {@link #keyOf(FileSource)})
	 * @param shared true for a shared lock
	 * @param backend what else to ask
	 * @return the lock, or null if someone outside this JVM holds the path
	 * @throws java.io.FileNotFoundException if the path doesn't exist (as a FileChannel can't be
	 *         opened on a missing file)
	 * @throws OverlappingFileLockException if this JVM already holds the path (or is taking it)
	 * @throws IOException if the backend could not be asked
	 */
	public FileSourceLock tryAcquire(FileSource source, String key, boolean shared, LockBackend backend) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(backend, "backend");
		requireExists(source);
		Grant g = new Grant(this, key, source, shared, backend);
		if( held.putIfAbsent(key, g) != null ) {
			throw new OverlappingFileLockException();
		}
		boolean got = false;
		try {
			got = backend.acquire(shared);
		} finally {
			if( !got ) {
				held.remove(key, g);
			}
		}
		return got ? g : null;
	}

	/**
	 * What {@link FileSource#lock(boolean)} does by default: {@link FileSource#tryLock(boolean)} until
	 * it succeeds. Like {@link java.nio.channels.FileChannel#lock()} it waits for other processes as
	 * long as it takes, but not for this JVM: a path this JVM holds throws at once.
	 * @param source the path
	 * @param shared true for a shared lock
	 * @return the lock
	 * @throws OverlappingFileLockException if this JVM already holds the path
	 * @throws FileLockInterruptionException if the thread is interrupted while waiting (its
	 *         interrupt status stays set)
	 * @throws IOException if the file system could not be asked
	 */
	public static FileSourceLock await(FileSource source, boolean shared) throws IOException {
		long sleep = 1;
		while (true) {
			if( Thread.currentThread().isInterrupted() ) {
				throw new FileLockInterruptionException();
			}
			FileSourceLock l = source.tryLock(shared);
			if( l != null ) {
				return l;
			}
			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FileLockInterruptionException();
			}
			sleep = Math.min(sleep * 2, MAX_POLL_MS);
		}
	}

	/**
	 * The rule every implementation keeps: only an existing file or directory can be locked.
	 * @param source the path
	 * @throws java.io.FileNotFoundException if it doesn't exist
	 * @throws IOException if the file system could not be asked
	 */
	public static void requireExists(FileSource source) throws IOException {
		if( !source.exists() ) {
			throw new java.io.FileNotFoundException("Can't lock " + source.getAbsolutePath() + ": it doesn't exist");
		}
	}

	/** @return the number of paths locked in this JVM (for tests) */
	public int size() {
		return held.size();
	}

	void release(Grant g) {
		synchronized (g) {
			if( g.released ) {
				return;
			}
			g.released = true;
		}
		try {
			g.backend.release();
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Lock backend " + g.backend + " failed to release " + g.key, ex);
		} finally {
			// after the backend let go, so the next holder in this JVM finds it free outside too
			held.remove(g.key, g);
		}
	}

	/** A lock granted by this manager. */
	static final class Grant implements FileSourceLock {
		private final LockManager manager;
		final String key;
		private final FileSource source;
		private final boolean shared;
		final LockBackend backend;
		volatile boolean released;

		Grant(LockManager manager, String key, FileSource source, boolean shared, LockBackend backend) {
			this.manager = manager;
			this.key = key;
			this.source = source;
			this.shared = shared;
			this.backend = backend;
		}

		@Override
		public FileSource source() {
			return source;
		}

		@Override
		public boolean isShared() {
			return shared;
		}

		@Override
		public boolean isValid() {
			return !released;
		}

		@Override
		public void release() {
			manager.release(this);
		}

		@Override
		public String toString() {
			return (shared ? "shared" : "exclusive") + " lock on " + key + (released ? " (released)" : "");
		}
	}
}
