/**
 * Leased, fenced locks on {@link us.bringardner.parley.files.FileSource} paths, so applications can
 * coordinate threads, processes and machines through their storage alone.
 * <p>
 * Every FileSource can lock: {@link us.bringardner.parley.files.FileSource#tryLock tryLock} and
 * {@link us.bringardner.parley.files.FileSource#lock lock} return a {@link us.bringardner.parley.files.lock.FileSourceLock}.
 * How far the locks reach depends on the file system, and
 * {@link us.bringardner.parley.files.FileSource#getLockScope() getLockScope()} says so honestly:
 * {@link us.bringardner.parley.files.lock.LockScope#PROCESS PROCESS} by default (this JVM),
 * {@link us.bringardner.parley.files.lock.LockScope#HOST HOST} for the local file system,
 * {@link us.bringardner.parley.files.lock.LockScope#CLUSTER CLUSTER} where the file system can
 * coordinate machines. An application that runs as several processes checks the scope at start-up.
 * <p>
 * The rules every file system keeps:
 * <ul>
 * <li>Per path: one EXCLUSIVE holder, or any number of SHARED holders and no EXCLUSIVE one. A lock
 * covers its path only, not what is in a directory. The path need not exist.</li>
 * <li>Every lock is a lease: it ends at its expiry unless renewed. A holder that dies doesn't block
 * others forever, and neither does a thread that forgets its lock. How a frozen (but living) process
 * is handled depends on the file system: for local files it keeps its locks until it runs again.
 * A lost lease is never revived.</li>
 * <li>Every grant carries a fencing token, higher each time the path is granted.</li>
 * <li>Locks are not reentrant, can't be upgraded from SHARED to EXCLUSIVE, belong to the lock object
 * (any thread may renew or close it), and promise no fairness to waiters.</li>
 * <li>Locks are advisory: they coordinate code that asks for them, not plain reads and writes.</li>
 * </ul>
 * File systems that can do better than {@code PROCESS} override {@code getLockScope()} and
 * {@code tryLock(...)}, usually by passing a {@link us.bringardner.parley.files.lock.LockBackend} to
 * {@link us.bringardner.parley.files.lock.LockManager}. The shared test suite's
 * {@code AbstractLockTests} checks every rule for the scope a file system claims.
 */
package us.bringardner.parley.files.lock;
