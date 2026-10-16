package us.bringardner.parley.files.fileproxy;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import us.bringardner.parley.files.lock.LockMode;

/**
 * Locks on the local file system that reach every process on the machine ({@code HOST} scope).
 * <p>
 * The operating system locks a real, open file, and the path being locked is often a directory or
 * doesn't exist yet. So each locked path gets a lock file in a lock directory, named by the
 * SHA-256 of the path's canonical form: {@code <hash>.lock} carries the OS lock, and
 * {@code <hash>.seq} holds the last fencing token handed out. Neither is ever deleted: they are
 * tiny, and deleting a lock file while another process opens it would let two processes lock two
 * different files.
 * <p>
 * The lock directory is the first {@value FileProxyFactory#LOCK_DIRECTORY_NAME} directory found in
 * the locked path or above it. An application marks the root it serves by creating one there, and
 * every process serving that root then shares its locks. Without one, the
 * {@value FileProxyFactory#LOCK_DIRECTORY_PROPERTY} system property names the directory, or else
 * {@code parley-locks-<user>} in the system temp directory is used, private to the user (so
 * processes run as different users don't share locks there).
 * <p>
 * Every process locking a path must find the same lock directory for it: create, move or delete a
 * lock directory only while nothing holds locks under it. Within one JVM, locks are keyed by the
 * locked path itself, so a changed lock directory can't let two holders in.
 * <p>
 * Java's file locks belong to the whole JVM, and asking twice for one file from the same JVM
 * throws {@code OverlappingFileLockException};
 * {@link us.bringardner.parley.files.lock.LockManager} sits in front, so only the first holder in
 * this JVM goes to the OS, and later SHARED holders share its OS lock. The OS frees a dead
 * process's locks at once. A lease is enforced by the holding process itself (it releases the OS
 * lock when the lease runs out), so a process that is stopped or frozen keeps its locks until it
 * runs again or dies. File locks are unreliable on NFS and some other network file systems.
 * <p>
 * Lock files are opened without following symbolic links. Paths are hashed as Java's canonical
 * form spells them, so on a case-insensitive volume, {@code Foo} and {@code foo} that don't exist
 * yet are different paths to the lock.
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
		return privateFallback().toFile();
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
		UserPrincipal me = dir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
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

	/** Open a lock or token file for reading and writing, creating it, never through a link. */
	private static FileChannel open(File f) throws IOException {
		return FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ,
				StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * The next token for a path: read, add one, write, all under an OS lock on the token file, so
	 * tokens increase across processes and restarts. Never less than the clock in microseconds, so
	 * even a lost or damaged token file can't make tokens go back (unless the clock does too).
	 * The caller holds the path's LockManager entry, so no other thread of this JVM is here for the
	 * same file.
	 */
	static long nextToken(File seqFile) throws IOException {
		try (FileChannel ch = open(seqFile); FileLock ignored = ch.lock()) {
			long last = 0;
			if( ch.size() == Long.BYTES ) {
				ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
				while (buf.hasRemaining() && ch.read(buf, buf.position()) > 0) {
					// read all 8 bytes
				}
				if( !buf.hasRemaining() ) {
					buf.flip();
					last = buf.getLong();
				}
			} else if( ch.size() != 0 ) {
				LOG.warning("Damaged lock token file " + seqFile + " (" + ch.size() + " bytes); starting from the clock");
			}
			long next = Math.max(last + 1, System.currentTimeMillis() * 1000);
			ByteBuffer out = ByteBuffer.allocate(Long.BYTES);
			out.putLong(next).flip();
			ch.truncate(0);
			while (out.hasRemaining()) {
				ch.write(out, out.position());
			}
			ch.force(false);
			return next;
		}
	}

	/**
	 * The OS side of one locked path. LockManager calls it with the path's monitor held: once per
	 * grant in this JVM. The first grant takes the OS lock; later SHARED grants share it; the last
	 * release gives it back.
	 */
	static final class Backend implements LockBackend {
		private final File lockFile;
		private final File seqFile;
		private FileChannel channel;
		private FileLock osLock;
		private int holders;

		Backend(File lockFile, File seqFile) {
			this.lockFile = lockFile;
			this.seqFile = seqFile;
		}

		@Override
		public long acquire(LockMode mode) throws IOException {
			if( holders == 0 ) {
				FileChannel ch = open(lockFile);
				FileLock l;
				try {
					l = ch.tryLock(0, Long.MAX_VALUE, mode == LockMode.SHARED);
				} catch (OverlappingFileLockException sameFileElsewhere) {
					// only if two locked paths share a lock file (a link into the lock directory):
					// another holder in this JVM has it, so it is busy
					ch.close();
					return -1;
				} catch (IOException | RuntimeException e) {
					ch.close();
					throw e;
				}
				if( l == null ) {
					ch.close();
					return -1;
				}
				channel = ch;
				osLock = l;
			}
			long token;
			try {
				token = nextToken(seqFile);
			} catch (IOException | RuntimeException e) {
				if( holders == 0 ) {
					closeOsLock();
				}
				throw e;
			}
			holders++;
			return token;
		}

		@Override
		public void release(LockMode mode) {
			if( holders > 0 && --holders == 0 ) {
				closeOsLock();
			}
		}

		private void closeOsLock() {
			try {
				if( osLock != null ) {
					osLock.release();
				}
			} catch (IOException e) {
				LOG.log(Level.FINE, "Releasing the OS lock on " + lockFile, e);
			}
			try {
				if( channel != null ) {
					channel.close();
				}
			} catch (IOException e) {
				LOG.log(Level.FINE, "Closing " + lockFile, e);
			}
			osLock = null;
			channel = null;
		}

		@Override
		public String toString() {
			return "local lock file " + lockFile;
		}
	}
}
