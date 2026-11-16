package us.bringardner.parley.files;

import java.util.Objects;

import us.bringardner.parley.files.lock.LockScope;

/**
 * What a file system can guarantee, so code that needs a guarantee (a lock framework, a safe
 * replace) can ask instead of knowing every file system by name. Each file system declares its
 * own ({@link FileSource#getCapabilities()}) and documents them; one that declares nothing gets
 * {@link #defaults(LockScope)}: what the FileSource contract requires of every file system, and
 * nothing more.
 * <p>
 * Immutable: each {@code with} method returns a changed copy.
 * @since 1.1.0
 */
public final class FileSourceCapabilities {

	private final LockScope lockScope;
	private final boolean atomicCreateNewFile;
	private final boolean atomicMkdir;
	private final boolean atomicReplace;
	private final boolean durableSync;
	private final boolean orderedOperations;

	private FileSourceCapabilities(LockScope lockScope, boolean atomicCreateNewFile, boolean atomicMkdir,
			boolean atomicReplace, boolean durableSync, boolean orderedOperations) {
		this.lockScope = Objects.requireNonNull(lockScope, "lockScope");
		this.atomicCreateNewFile = atomicCreateNewFile;
		this.atomicMkdir = atomicMkdir;
		this.atomicReplace = atomicReplace;
		this.durableSync = durableSync;
		this.orderedOperations = orderedOperations;
	}

	/**
	 * What a file system that declares nothing is taken to have: the lock scope it reports, and
	 * the atomic {@link FileSource#createNewFile()} and {@link FileSource#mkdir()} the FileSource
	 * contract requires; no atomic replace, no durable sync, no promise of ordered operations.
	 * @param lockScope how far its locks reach
	 * @return the capabilities
	 */
	public static FileSourceCapabilities defaults(LockScope lockScope) {
		return new FileSourceCapabilities(lockScope, true, true, false, false, false);
	}

	/**
	 * @return how far {@link FileSource#tryLock(long, long, boolean)} locks reach (what
	 *         {@link FileSource#getLockScope()} says)
	 */
	public LockScope lockScope() {
		return lockScope;
	}

	/**
	 * @return whether {@link FileSource#createNewFile()} is test-and-create: of several callers
	 *         racing for one path, exactly one gets true
	 */
	public boolean atomicCreateNewFile() {
		return atomicCreateNewFile;
	}

	/** @return whether {@link FileSource#mkdir()} is test-and-create, as createNewFile is */
	public boolean atomicMkdir() {
		return atomicMkdir;
	}

	/**
	 * @return whether {@link FileSource#renameTo(FileSource, boolean) renameTo(dest, true)} replaces
	 *         an existing file in one step, so nobody ever finds it missing or half there
	 */
	public boolean atomicReplace() {
		return atomicReplace;
	}

	/**
	 * @return whether {@link Syncable#sync()} on its output streams makes what was written durable
	 *         (it survives a crash of the machine that stores it)
	 */
	public boolean durableSync() {
		return durableSync;
	}

	/**
	 * @return whether every operation on it, from every client, is applied in one order: what one
	 *         caller has done is seen by any caller that asks after it (one disk, one database, one
	 *         FTP or SFTP server; not a store that replicates without coordination)
	 */
	public boolean orderedOperations() {
		return orderedOperations;
	}

	/** @param scope how far its locks reach */
	public FileSourceCapabilities withLockScope(LockScope scope) {
		return new FileSourceCapabilities(scope, atomicCreateNewFile, atomicMkdir, atomicReplace, durableSync, orderedOperations);
	}

	/** @param b whether createNewFile is atomic */
	public FileSourceCapabilities withAtomicCreateNewFile(boolean b) {
		return new FileSourceCapabilities(lockScope, b, atomicMkdir, atomicReplace, durableSync, orderedOperations);
	}

	/** @param b whether mkdir is atomic */
	public FileSourceCapabilities withAtomicMkdir(boolean b) {
		return new FileSourceCapabilities(lockScope, atomicCreateNewFile, b, atomicReplace, durableSync, orderedOperations);
	}

	/** @param b whether renameTo(dest, true) replaces atomically */
	public FileSourceCapabilities withAtomicReplace(boolean b) {
		return new FileSourceCapabilities(lockScope, atomicCreateNewFile, atomicMkdir, b, durableSync, orderedOperations);
	}

	/** @param b whether sync makes writes durable */
	public FileSourceCapabilities withDurableSync(boolean b) {
		return new FileSourceCapabilities(lockScope, atomicCreateNewFile, atomicMkdir, atomicReplace, b, orderedOperations);
	}

	/** @param b whether every operation is applied in one order */
	public FileSourceCapabilities withOrderedOperations(boolean b) {
		return new FileSourceCapabilities(lockScope, atomicCreateNewFile, atomicMkdir, atomicReplace, durableSync, b);
	}

	@Override
	public boolean equals(Object o) {
		if( !(o instanceof FileSourceCapabilities) ) {
			return false;
		}
		FileSourceCapabilities c = (FileSourceCapabilities) o;
		return lockScope == c.lockScope && atomicCreateNewFile == c.atomicCreateNewFile && atomicMkdir == c.atomicMkdir
				&& atomicReplace == c.atomicReplace && durableSync == c.durableSync && orderedOperations == c.orderedOperations;
	}

	@Override
	public int hashCode() {
		return Objects.hash(lockScope, atomicCreateNewFile, atomicMkdir, atomicReplace, durableSync, orderedOperations);
	}

	@Override
	public String toString() {
		return "FileSourceCapabilities[lockScope=" + lockScope + ", atomicCreateNewFile=" + atomicCreateNewFile + ", atomicMkdir="
				+ atomicMkdir + ", atomicReplace=" + atomicReplace + ", durableSync=" + durableSync + ", orderedOperations="
				+ orderedOperations + "]";
	}
}
