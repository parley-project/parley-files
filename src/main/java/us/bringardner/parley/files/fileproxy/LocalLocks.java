package us.bringardner.parley.files.fileproxy;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.bringardner.parley.files.lock.LockBackend;

/**
 * Locks on the local file system that reach every process on the machine ({@code HOST} scope).
 * <p>
 * The operating system locks a real, open file, and the path being locked is often a directory or
 * doesn't exist yet. So each locked path gets a lock file in a lock directory, named by the
 * SHA-256 of the path's canonical form: {@code <hash>.lock} carries the OS lock. It is never
 * deleted: it is empty, and deleting a lock file while another process opens it would let two
 * processes lock two different files.
 * <p>
 * The lock directory is the first {@value FileProxyFactory#LOCK_DIRECTORY_NAME} directory found in
 * the locked path or above it. An application marks the root it serves by creating one there, and
 * every process serving that root then shares its locks. Without one, the
 * {@value FileProxyFactory#LOCK_DIRECTORY_PROPERTY} system property names the directory, or else
 * {@code parley-locks-<user>} in the system temp directory is used, private to the user: processes
 * run as different users don't share locks there, so a warning is logged the first time it is used
 * (create a {@value FileProxyFactory#LOCK_DIRECTORY_NAME} directory in the root you serve, or set
 * the property, to share locks between users).
 * <p>
 * Every process locking a path must find the same lock directory for it: create, move or delete a
 * lock directory only while nothing holds locks under it. Within one JVM, locks are keyed by the
 * locked file itself, so a changed lock directory can't let two holders in.
 * <p>
 * Java's file locks belong to the whole JVM, and so do these:
 * {@link us.bringardner.parley.files.lock.LockManager} sits in front and refuses a region that
 * overlaps one this JVM holds, so the OS is asked once per region, all on one channel. The OS frees a dead process's locks
 * at once; a process that is stopped or frozen keeps them until it runs again or dies. File locks
 * are unreliable on NFS and some other network file systems.
 * <p>
 * A lock is on the file, not its name, as an OS lock is: the lock file's name is a hash of the file's
 * identity, its file key (device and inode) where the system has one (Linux, macOS and other Unix
 * systems), so two hard links to a file, or {@code Foo} and {@code foo} on a case-insensitive volume,
 * are one file to the lock, and a renamed file keeps its locks. Where the system has no file key
 * (Windows), the canonical path stands in; Windows spells an existing file's canonical path in its
 * on-disk case, so only hard links are then different files to the lock. Two names for one file that
 * find different lock directories (hard links in different trees) are different files to the lock.
 * Lock files are opened without following symbolic links.
 */
final class LocalLocks {

	private static final Logger LOG = Logger.getLogger(LocalLocks.class.getName());

	private LocalLocks() {
	}

	/**
	 * The lock directory for a path.
	 * @param canonical the locked path, canonical
	 * @return the directory, canonical
	 * @throws IOException if the fallback can't be created or isn't private to this user
	 */
	static File lockDirectoryFor(File canonical) throws IOException {
		for (File d = canonical; d != null; d = d.getParentFile()) {
			File candidate = new File(d, FileProxyFactory.LOCK_DIRECTORY_NAME);
			if( candidate.isDirectory() ) {
				return candidate.getCanonicalFile();
			}
		}
		String configured = System.getProperty(FileProxyFactory.LOCK_DIRECTORY_PROPERTY);
		if( configured != null && !configured.isBlank() ) {
			File dir = new File(configured);
			if( !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
				throw new IOException("Can't create the lock directory " + dir);
			}
			return dir.getCanonicalFile();
		}
		File fallback = privateFallback().toFile();
		if( WARNED_FALLBACK.compareAndSet(false, true) ) {
			LOG.warning("No " + FileProxyFactory.LOCK_DIRECTORY_NAME + " directory in or above " + canonical + " and "
					+ FileProxyFactory.LOCK_DIRECTORY_PROPERTY + " isn't set, so local file locks use " + fallback
					+ ", which is private to the user " + System.getProperty("user.name")
					+ ": processes run as other users don't see these locks. To share them, create a "
					+ FileProxyFactory.LOCK_DIRECTORY_NAME + " directory in the root you serve, or set "
					+ FileProxyFactory.LOCK_DIRECTORY_PROPERTY + ".");
		}
		return fallback;
	}

	/** The fallback has been reported (once per JVM). */
	private static final java.util.concurrent.atomic.AtomicBoolean WARNED_FALLBACK = new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * What names a file to the locks: its file key (device and inode) where the system has one, so
	 * hard links and other spellings of one file are one file; its canonical path otherwise.
	 * @param canonical the file, canonical; it exists
	 * @return the identity
	 * @throws IOException if the file's attributes can't be read
	 */
	static String identityOf(File canonical) throws IOException {
		Object key = java.nio.file.Files.readAttributes(canonical.toPath(), java.nio.file.attribute.BasicFileAttributes.class)
				.fileKey();
		return key != null ? "key:" + key : "path:" + canonical.getPath();
	}

	/** {@code parley-locks-<user>} in the temp directory, created for this user only and checked. */
	private static Path privateFallback() throws IOException {
		String user = System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_");
		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "parley-locks-" + user);
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if( !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) ) {
			try {
				if( posix ) {
					Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
				} else {
					Files.createDirectory(dir);
				}
			} catch (java.nio.file.FileAlreadyExistsException raced) {
				// made by another process of ours: checked below
			}
		}
		if( !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ) {
			throw new IOException("The lock directory " + dir + " is not a directory (or is a link)");
		}
		UserPrincipal owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
		UserPrincipal me = currentUser(dir);
		if( !owner.equals(me) ) {
			throw new IOException("The lock directory " + dir + " belongs to " + owner.getName()
					+ ", not to this user: set " + FileProxyFactory.LOCK_DIRECTORY_PROPERTY + " or create a "
					+ FileProxyFactory.LOCK_DIRECTORY_NAME + " directory in the root you serve");
		}
		if( posix && !Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------")) ) {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
		}
		return dir.toRealPath();
	}

	/**
	 * This process's user as the file system knows it. By name if the name can be looked up; when
	 * it can't (a container whose user isn't in the passwd file, say), as the owner of a file this
	 * process creates in {@code dir}, which fails if this user can't write there.
	 */
	private static UserPrincipal currentUser(Path dir) throws IOException {
		try {
			return dir.getFileSystem().getUserPrincipalLookupService()
					.lookupPrincipalByName(System.getProperty("user.name", ""));
		} catch (java.nio.file.attribute.UserPrincipalNotFoundException | UnsupportedOperationException e) {
			Path probe = Files.createTempFile(dir, ".owner-", ".tmp");
			try {
				return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
			} finally {
				Files.deleteIfExists(probe);
			}
		}
	}

	static String hashOf(String canonicalPath) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(canonicalPath.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(h.length * 2);
			for (byte b : h) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every JVM", e);
		}
	}

	/** Open a lock file for reading and writing, creating it, never through a link. */
	private static FileChannel open(File f) throws IOException {
		return FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ,
				StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * The OS side of one locked path: OS locks on the same regions of its lock file. One channel holds
	 * them all, open while this JVM holds any region: Java's file locks belong to the JVM, and on some
	 * systems closing any channel on a file releases every lock the JVM holds on it, so a channel per
	 * region would lose the others' locks. LockManager calls it at most once at a time.
	 */
	static final class Backend implements LockBackend {
		private final File lockFile;
		private FileChannel channel;
		private final java.util.Map<Long, java.util.Map<Long, FileLock>> regions = new java.util.HashMap<>();

		Backend(File lockFile) {
			this.lockFile = lockFile;
		}

		@Override
		public synchronized boolean acquire(long position, long size, boolean shared) throws IOException {
			boolean opened = false;
			if( channel == null ) {
				channel = open(lockFile);
				opened = true;
			}
			FileLock l;
			try {
				l = channel.tryLock(position, size, shared);
			} catch (OverlappingFileLockException sameFileElsewhere) {
				// only if two locked paths share a lock file (a link into the lock directory):
				// this JVM holds the region under the other path, so it is busy
				l = null;
			} catch (IOException | RuntimeException e) {
				if( opened ) {
					closeChannel();
				}
				throw e;
			}
			if( l == null ) {
				if( opened ) {
					closeChannel();
				}
				return false;
			}
			regions.computeIfAbsent(position, k -> new java.util.HashMap<>()).put(size, l);
			return true;
		}

		@Override
		public synchronized void release(long position, long size) {
			java.util.Map<Long, FileLock> bySize = regions.get(position);
			FileLock l = bySize == null ? null : bySize.remove(size);
			if( bySize != null && bySize.isEmpty() ) {
				regions.remove(position);
			}
			if( l != null ) {
				try {
					l.release();
				} catch (IOException e) {
					LOG.log(Level.FINE, "Releasing an OS lock on " + lockFile, e);
				}
			}
		}

		@Override
		public synchronized void close() {
			for (java.util.Map<Long, FileLock> bySize : regions.values()) {
				for (FileLock l : bySize.values()) {
					try {
						l.release();
					} catch (IOException e) {
						LOG.log(Level.FINE, "Releasing an OS lock on " + lockFile, e);
					}
				}
			}
			regions.clear();
			closeChannel();
		}

		private void closeChannel() {
			try {
				if( channel != null ) {
					channel.close();
				}
			} catch (IOException e) {
				LOG.log(Level.FINE, "Closing " + lockFile, e);
			}
			channel = null;
		}

		@Override
		public String toString() {
			return "local lock file " + lockFile;
		}
	}
}
