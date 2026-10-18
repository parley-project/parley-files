/**
 * Locks on {@link us.bringardner.parley.files.FileSource} paths, shaped like NIO's
 * {@link java.nio.channels.FileChannel#lock()} and {@link java.nio.channels.FileLock}, so processes
 * sharing a store can keep out of each other's way.
 * <p>
 * Every FileSource can lock: {@link us.bringardner.parley.files.FileSource#tryLock() tryLock()} and
 * {@link us.bringardner.parley.files.FileSource#lock() lock()}, each also with a {@code shared}
 * argument, return a {@link us.bringardner.parley.files.lock.FileSourceLock}. How far the locks reach
 * depends on the file system, and
 * {@link us.bringardner.parley.files.FileSource#getLockScope() getLockScope()} says so:
 * {@link us.bringardner.parley.files.lock.LockScope#PROCESS PROCESS} by default (this JVM),
 * {@link us.bringardner.parley.files.lock.LockScope#HOST HOST} for the local file system. An
 * application that runs as several processes checks the scope at start-up.
 * <p>
 * The rules, as for NIO file locks:
 * <ul>
 * <li>Per path: one exclusive holder, or any number of shared holders and no exclusive one, across
 * processes. Only an existing file or directory can be locked
 * ({@link java.io.FileNotFoundException} otherwise), as a FileChannel needs an existing file. A lock
 * on a directory covers the directory only, not what is in it.</li>
 * <li>A lock is held on behalf of the whole JVM. A JVM holds a path at most once: asking again, from
 * any thread, throws {@link java.nio.channels.OverlappingFileLockException}. Threads in one JVM
 * coordinate with Java locks of their own, as they would with FileLock; parley-net's locks do this
 * for servers.</li>
 * <li>{@code tryLock} returns null if another process holds the path; {@code lock} waits as long as
 * it takes, and throws {@link java.nio.channels.FileLockInterruptionException} if the thread is
 * interrupted.</li>
 * <li>A lock lasts until it is released or the JVM ends; the OS frees a dead process's local locks
 * at once. Locks are advisory, can't be upgraded from shared to exclusive, and belong to the lock
 * object (any thread may release it).</li>
 * </ul>
 * File systems that can do better than {@code PROCESS} override {@code getLockScope()} and
 * {@code tryLock(boolean)}, usually by passing a {@link us.bringardner.parley.files.lock.LockBackend}
 * to {@link us.bringardner.parley.files.lock.LockManager}. The shared test suite's
 * {@code AbstractLockTests} checks every rule for the scope a file system claims.
 */
package us.bringardner.parley.files.lock;
