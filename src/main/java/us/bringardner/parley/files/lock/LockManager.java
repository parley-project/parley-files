package us.bringardner.parley.files.lock;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * The locks held in this JVM, for every file system: who holds which path, in which mode, until
 * when. On its own it gives {@link LockScope#PROCESS} locks, which is what
 * {@link FileSource#tryLock} does by default. A file system that can do better passes a
 * {@link LockBackend} that also asks everyone outside this JVM.
 * <p>
 * Leases are measured with {@link System#nanoTime()}. A background thread (a daemon, started on
 * first use) releases leases that ran out, so that a lock forgotten by a stuck thread doesn't keep
 * other processes waiting. That works while this process runs: a process that is stopped or frozen
 * keeps whatever its backend holds outside the JVM until it runs again (for local files the OS frees
 * a dead process's locks at once; a file system shared by machines expires them on its own clock).
 */
public final class LockManager {

	private static final Logger LOG = Logger.getLogger(LockManager.class.getName());
	private static final LockManager INSTANCE = new LockManager();

	/** How often expired leases are released. */
	static final long REAP_INTERVAL_MS = 100;

	private static final AtomicLong TOKENS = new AtomicLong();

	/** The backend for locks that stay in this JVM. */
	static final LockBackend IN_PROCESS = new LockBackend() {
		@Override
		public long acquire(LockMode mode) {
			return TOKENS.incrementAndGet();
		}

		@Override
		public void release(LockMode mode) {
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

	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
	private volatile ScheduledExecutorService reaper;

	private LockManager() {
	}

	/**
	 * A key that names a path on one file system: its type, its URL and the canonical path, so two
	 * FileSource objects for the same file get the same key.
	 * @param source the path
	 * @return the key
	 * @throws IOException if the canonical path can't be found
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
		return fileSystem + "|" + source.getCanonicalPath();
	}

	/**
	 * Take a lock now, or return null if it is held.
	 * @param source the path, kept in the lock for {@link FileSourceLock#getFileSource()}
	 * @param key what names the path for this file system (see {@link #keyOf(FileSource)})
	 * @param mode EXCLUSIVE or SHARED
	 * @param lease how long the lock lasts unless renewed; more than zero
	 * @param backend what else to ask; used for the path until no one in this JVM holds it
	 * @return the lock, or null if another holder has the path
	 * @throws IOException if the backend could not be asked
	 */
	public FileSourceLock tryAcquire(FileSource source, String key, LockMode mode, Duration lease, LockBackend backend)
			throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(backend, "backend");
		long leaseNanos = leaseNanos(lease);
		while (true) {
			Entry e = entries.computeIfAbsent(key, k -> new Entry(k, backend));
			synchronized (e) {
				if( e.dead ) {
					// emptied and removed by someone else between computeIfAbsent and here
					continue;
				}
				purgeExpired(e);
				boolean free = mode == LockMode.EXCLUSIVE
						? e.exclusive == null && e.shared.isEmpty()
						: e.exclusive == null;
				if( !free ) {
					return null;
				}
				long token;
				try {
					token = e.backend.acquire(mode);
				} catch (IOException | RuntimeException ex) {
					removeIfEmpty(e);
					throw ex;
				}
				if( token < 0 ) {
					removeIfEmpty(e);
					return null;
				}
				Grant g = new Grant(this, e, source, mode, token, System.nanoTime() + leaseNanos);
				if( mode == LockMode.EXCLUSIVE ) {
					e.exclusive = g;
				} else {
					e.shared.add(g);
				}
				startReaper();
				return g;
			}
		}
	}

	/** @return the number of paths locked in this JVM (for tests) */
	public int size() {
		return entries.size();
	}

	static long leaseNanos(Duration lease) {
		Objects.requireNonNull(lease, "lease");
		if( lease.isNegative() || lease.isZero() ) {
			throw new IllegalArgumentException("The lease must be more than zero: " + lease);
		}
		try {
			return lease.toNanos();
		} catch (ArithmeticException tooLong) {
			return Long.MAX_VALUE / 4;
		}
	}

	boolean renew(Grant g, Duration lease) {
		long nanos = leaseNanos(lease);
		Entry e = g.entry;
		synchronized (e) {
			if( g.released ) {
				return false;
			}
			if( g.expired(System.nanoTime()) ) {
				lose(e, g);
				removeIfEmpty(e);
				return false;
			}
			g.expiry = System.nanoTime() + nanos;
			return true;
		}
	}

	void release(Grant g) {
		Entry e = g.entry;
		synchronized (e) {
			if( g.released ) {
				return;
			}
			lose(e, g);
			removeIfEmpty(e);
		}
	}

	/** Take g out of e and give it back to the backend. Called with e's monitor held. */
	private void lose(Entry e, Grant g) {
		g.released = true;
		if( e.exclusive == g ) {
			e.exclusive = null;
		} else {
			e.shared.remove(g);
		}
		try {
			e.backend.release(g.mode);
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Lock backend " + e.backend + " failed to release " + e.key, ex);
		}
	}

	/** Called with e's monitor held. */
	private void purgeExpired(Entry e) {
		long now = System.nanoTime();
		if( e.exclusive != null && e.exclusive.expired(now) ) {
			lose(e, e.exclusive);
		}
		if( !e.shared.isEmpty() ) {
			List<Grant> gone = new ArrayList<>();
			for (Grant g : e.shared) {
				if( g.expired(now) ) {
					gone.add(g);
				}
			}
			for (Grant g : gone) {
				lose(e, g);
			}
		}
	}

	/** Called with e's monitor held. */
	private void removeIfEmpty(Entry e) {
		if( e.exclusive == null && e.shared.isEmpty() ) {
			e.dead = true;
			entries.remove(e.key, e);
		}
	}

	private void startReaper() {
		if( reaper == null ) {
			synchronized (this) {
				if( reaper == null ) {
					ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
						Thread t = new Thread(r, "parley-lock-reaper");
						t.setDaemon(true);
						return t;
					});
					s.scheduleWithFixedDelay(this::reap, REAP_INTERVAL_MS, REAP_INTERVAL_MS, TimeUnit.MILLISECONDS);
					reaper = s;
				}
			}
		}
	}

	private void reap() {
		try {
			for (Entry e : entries.values()) {
				synchronized (e) {
					if( !e.dead ) {
						purgeExpired(e);
						removeIfEmpty(e);
					}
				}
			}
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Releasing expired locks failed", ex);
		}
	}

	/** One locked path in this JVM. Guarded by its own monitor. */
	static final class Entry {
		final String key;
		final LockBackend backend;
		Grant exclusive;
		final Set<Grant> shared = new HashSet<>();
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
		final LockMode mode;
		private final long token;
		volatile long expiry;
		volatile boolean released;

		Grant(LockManager manager, Entry entry, FileSource source, LockMode mode, long token, long expiry) {
			this.manager = manager;
			this.entry = entry;
			this.source = source;
			this.mode = mode;
			this.token = token;
			this.expiry = expiry;
		}

		boolean expired(long now) {
			return now - expiry >= 0;
		}

		@Override
		public FileSource getFileSource() {
			return source;
		}

		@Override
		public LockMode getMode() {
			return mode;
		}

		@Override
		public long getToken() {
			return token;
		}

		@Override
		public Instant getExpiry() {
			long left = expiry - System.nanoTime();
			return Instant.now().plusNanos(Math.max(0, left));
		}

		@Override
		public boolean isValid() {
			return !released && !expired(System.nanoTime());
		}

		@Override
		public boolean renew(Duration lease) {
			return manager.renew(this, lease);
		}

		@Override
		public void checkValid() throws LockLostException {
			if( !isValid() ) {
				throw new LockLostException("The " + mode + " lock on " + entry.key + " (token " + token + ") is no longer held");
			}
		}

		@Override
		public void close() {
			manager.release(this);
		}

		@Override
		public String toString() {
			return mode + " lock on " + entry.key + " (token " + token + (isValid() ? ")" : ", lost)");
		}
	}
}
