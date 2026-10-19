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
 * The regions locked in this JVM, for every file system. It keeps the rule
 * {@link java.nio.channels.FileChannel#tryLock(long, long, boolean)} keeps: a JVM never holds two
 * overlapping regions of one path (in either mode), and asking for one throws
 * {@link OverlappingFileLockException}; regions that don't overlap are held side by side. On its
 * own it gives {@link LockScope#PROCESS} locks, which is what
 * {@link FileSource#tryLock(long, long, boolean)} does by default; a file system that can do better
 * passes a {@link LockBackend} that also asks everyone outside this JVM.
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
		public boolean acquire(long position, long size, boolean shared) {
			return true;
		}

		@Override
		public void release(long position, long size) {
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

	private final ConcurrentHashMap<String, Entry> held = new ConcurrentHashMap<>();

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
	 * Check a region as NIO does, and resolve a size of zero ("to the end of the file").
	 * @param position where it starts
	 * @param size its size
	 * @return the size to lock: {@code size}, or {@code Long.MAX_VALUE - position} for zero
	 * @throws IllegalArgumentException if position or size is negative, or they overflow
	 */
	public static long checkRange(long position, long size) {
		if( position < 0 || size < 0 || position + size < 0 ) {
			throw new IllegalArgumentException("Bad lock region: position " + position + ", size " + size);
		}
		return size == 0 ? Long.MAX_VALUE - position : size;
	}

	/**
	 * Whether two regions share a byte (positions and sizes as {@link #checkRange} returns them).
	 * @return true if they overlap
	 */
	public static boolean overlaps(long position1, long size1, long position2, long size2) {
		return position1 < end(position2, size2) && position2 < end(position1, size1);
	}

	private static long end(long position, long size) {
		return size > Long.MAX_VALUE - position ? Long.MAX_VALUE : position + size;
	}

	/**
	 * Take a lock on a region now, or return null if another process holds an overlapping one.
	 * @param source the path, kept in the lock for {@link FileSourceLock#source()}
	 * @param key what names the path for this file system (see {@link #keyOf(FileSource)})
	 * @param position where the region starts (checked by {@link #checkRange})
	 * @param size its size (as {@link #checkRange} returns it)
	 * @param shared true for a shared lock
	 * @param backend what else to ask; used for the path until this JVM holds no region of it (a
	 *        backend for a path already locked here is not used: the first one is)
	 * @return the lock, or null if someone outside this JVM holds an overlapping region
	 * @throws java.io.FileNotFoundException if the path doesn't exist (as a FileChannel can't be
	 *         opened on a missing file)
	 * @throws OverlappingFileLockException if this JVM already holds an overlapping region
	 * @throws IOException if the backend could not be asked
	 */
	public FileSourceLock tryAcquire(FileSource source, String key, long position, long size, boolean shared,
			LockBackend backend) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(backend, "backend");
		size = checkRange(position, size);
		requireExists(source);
		while (true) {
			Entry e = held.computeIfAbsent(key, k -> new Entry(k, backend));
			synchronized (e) {
				if( e.dead ) {
					continue; // emptied and removed between computeIfAbsent and here
				}
				for (Grant g : e.grants) {
					if( overlaps(g.position, g.size, position, size) ) {
						throw new OverlappingFileLockException();
					}
				}
				boolean got = false;
				try {
					got = e.backend.acquire(position, size, shared);
				} finally {
					if( !got ) {
						closeIfEmpty(e);
					}
				}
				if( !got ) {
					return null;
				}
				Grant g = new Grant(this, e, source, position, size, shared);
				e.grants.add(g);
				return g;
			}
		}
	}

	/** Called with e's monitor held. */
	private void closeIfEmpty(Entry e) {
		if( e.grants.isEmpty() ) {
			e.dead = true;
			held.remove(e.key, e);
			try {
				e.backend.close();
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Lock backend " + e.backend + " failed to close for " + e.key, ex);
			}
		}
	}

	/**
	 * What {@link FileSource#lock(long, long, boolean)} does by default:
	 * {@link FileSource#tryLock(long, long, boolean)} until it succeeds. Like
	 * {@link java.nio.channels.FileChannel#lock()} it waits for other processes as long as it takes,
	 * but not for this JVM: an overlapping region this JVM holds throws at once.
	 * @param source the path
	 * @param position where the region starts
	 * @param size its size (zero: to the end)
	 * @param shared true for a shared lock
	 * @return the lock
	 * @throws OverlappingFileLockException if this JVM already holds the path
	 * @throws FileLockInterruptionException if the thread is interrupted while waiting (its
	 *         interrupt status stays set)
	 * @throws IOException if the file system could not be asked
	 */
	public static FileSourceLock await(FileSource source, long position, long size, boolean shared) throws IOException {
		checkRange(position, size);
		long sleep = 1;
		while (true) {
			if( Thread.currentThread().isInterrupted() ) {
				throw new FileLockInterruptionException();
			}
			FileSourceLock l = source.tryLock(position, size, shared);
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

	/** @return the number of paths with a region locked in this JVM (for tests) */
	public int size() {
		return held.size();
	}

	void release(Grant g) {
		Entry e = g.entry;
		synchronized (e) {
			if( g.released ) {
				return;
			}
			g.released = true;
			e.grants.remove(g);
			try {
				e.backend.release(g.position, g.size);
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Lock backend " + e.backend + " failed to release " + e.key, ex);
			}
			// after the backend let go, so the next holder in this JVM finds it free outside too
			closeIfEmpty(e);
		}
	}

	/** One path with regions locked in this JVM. Guarded by its own monitor. */
	static final class Entry {
		final String key;
		final LockBackend backend;
		final java.util.List<Grant> grants = new java.util.ArrayList<>();
		boolean dead;

		Entry(String key, LockBackend backend) {
			this.key = key;
			this.backend = backend;
		}
	}

	/** A lock granted by this manager. */
	static final class Grant implements FileSourceLock {
		private final LockManager manager;
		final Entry entry;
		private final FileSource source;
		final long position;
		final long size;
		private final boolean shared;
		volatile boolean released;

		Grant(LockManager manager, Entry entry, FileSource source, long position, long size, boolean shared) {
			this.manager = manager;
			this.entry = entry;
			this.source = source;
			this.position = position;
			this.size = size;
			this.shared = shared;
		}

		@Override
		public FileSource source() {
			return source;
		}

		@Override
		public long position() {
			return position;
		}

		@Override
		public long size() {
			return size;
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
			return (shared ? "shared" : "exclusive") + " lock on " + entry.key + " [" + position + ", +" + size + ")"
					+ (released ? " (released)" : "");
		}
	}
}
