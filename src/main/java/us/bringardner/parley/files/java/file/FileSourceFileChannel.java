package us.bringardner.parley.files.java.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockManager;

/**
 * A {@link FileChannel} on a FileSource, from {@code FileChannel.open(path, ...)} on a FileSource path,
 * so NIO code runs unchanged on any FileSource. Reading, writing and seeking go to the channel
 * {@code newByteChannel} opens, or, for a local file, to a real JDK FileChannel on it (which also
 * gives {@link #map} and a real {@link #force}); locking always goes to the FileSource's own locks
 * ({@link FileSource#tryLock(long, long, boolean)}), region for region, wrapped as {@link FileLock}s,
 * with NIO's rules: a lock is held for the whole JVM
 * ({@link java.nio.channels.OverlappingFileLockException} for an overlapping one), a shared lock
 * needs a readable channel and an exclusive one a writable channel, a size of zero locks to the end
 * of the file, and closing the channel releases its locks.
 * <p>
 * Where it differs from a local FileChannel:
 * <ul>
 * <li>{@link #map} is a real memory map for a local file. Elsewhere (a mapped buffer can only come
 * from the JDK's own files) {@code READ_ONLY} and {@code PRIVATE} map a local copy of the region,
 * which is what those modes promise: a {@code READ_ONLY} map never writes, and a {@code PRIVATE}
 * one keeps its changes to itself. {@code READ_WRITE} throws {@link UnsupportedOperationException}:
 * a copy can't send its changes back as a real map does.</li>
 * <li>{@link #force} sends what is held and makes it as durable as the file system can (see
 * {@link us.bringardner.parley.files.Syncable}): on the disk for a local file, committed for a
 * database, synced by an SFTP server that can, only sent over FTP.</li>
 * <li>What can be done depends on the file system: without random access (FTP), a channel can only
 * read, or write from the start or the end.</li>
 * </ul>
 */
final class FileSourceFileChannel extends FileChannel {

	/** Waiting for a lock polls this often at most (ms), starting at 1 ms. */
	private static final long MAX_POLL_MS = 50;
	private static final int TRANSFER_BUFFER = 64 * 1024;

	private final FileSource file;
	private final SeekableByteChannel ch;
	/** For a local file: the JDK's own channel on it (also {@code ch}); null otherwise. */
	private final FileChannel local;
	private final boolean readable;
	private final boolean writable;
	private final Set<Lock> locks = ConcurrentHashMap.newKeySet();
	/**
	 * Taking a lock and closing the channel exclude each other: a lock is taken and added to
	 * {@link #locks} only while the channel is open, and close() empties {@link #locks} after, so it
	 * never returns with a lock it didn't see.
	 */
	private final Object lockGuard = new Object();

	/** A channel over what {@code newByteChannel} opened. */
	FileSourceFileChannel(FileSource file, SeekableByteChannel ch, boolean readable, boolean writable) {
		this(file, ch, null, readable, writable);
	}

	/** A channel on a local file: I/O on the JDK's channel, locks from the FileSource. */
	FileSourceFileChannel(FileSource file, FileChannel local, boolean readable, boolean writable) {
		this(file, local, local, readable, writable);
	}

	private FileSourceFileChannel(FileSource file, SeekableByteChannel ch, FileChannel local, boolean readable, boolean writable) {
		this.file = file;
		this.ch = ch;
		this.local = local;
		this.readable = readable;
		this.writable = writable;
	}

	private void ensureOpen() throws ClosedChannelException {
		if( !isOpen() ) {
			throw new ClosedChannelException();
		}
	}

	// ------------------------------------------------------------------ reading and writing

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		ensureOpen();
		return ch.read(dst);
	}

	@Override
	public synchronized long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
		ensureOpen();
		java.util.Objects.checkFromIndexSize(offset, length, dsts.length);
		long total = 0;
		for (int i = offset; i < offset + length; i++) {
			if( !dsts[i].hasRemaining() ) {
				continue;
			}
			int n = ch.read(dsts[i]);
			if( n < 0 ) {
				return total == 0 ? -1 : total;
			}
			total += n;
			if( dsts[i].hasRemaining() ) {
				break; // a short read: stop here, as a file channel does
			}
		}
		return total;
	}

	@Override
	public synchronized int write(ByteBuffer src) throws IOException {
		ensureOpen();
		return ch.write(src);
	}

	@Override
	public synchronized long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
		ensureOpen();
		java.util.Objects.checkFromIndexSize(offset, length, srcs.length);
		long total = 0;
		for (int i = offset; i < offset + length; i++) {
			while (srcs[i].hasRemaining()) {
				total += ch.write(srcs[i]);
			}
		}
		return total;
	}

	@Override
	public synchronized long position() throws IOException {
		ensureOpen();
		return ch.position();
	}

	@Override
	public synchronized FileChannel position(long newPosition) throws IOException {
		ensureOpen();
		if( newPosition < 0 ) {
			throw new IllegalArgumentException("negative position " + newPosition);
		}
		ch.position(newPosition);
		return this;
	}

	@Override
	public synchronized long size() throws IOException {
		ensureOpen();
		return ch.size();
	}

	@Override
	public synchronized FileChannel truncate(long size) throws IOException {
		ensureOpen();
		if( size < 0 ) {
			throw new IllegalArgumentException("negative size " + size);
		}
		if( !writable ) {
			throw new NonWritableChannelException();
		}
		long position = ch.position();
		ch.truncate(size);
		if( position > size ) {
			ch.position(size);
		}
		return this;
	}

	/**
	 * Send what is held and make it as durable as the file system can: the JDK's force for a local
	 * file (to the disk), otherwise {@link us.bringardner.parley.files.Syncable#sync()} on the stream
	 * being written.
	 */
	@Override
	public void force(boolean metaData) throws IOException {
		ensureOpen();
		if( local != null ) {
			local.force(metaData);
		} else if( ch instanceof FileSourceChannels.Base ) {
			((FileSourceChannels.Base) ch).force();
		}
	}

	@Override
	public int read(ByteBuffer dst, long position) throws IOException {
		if( local != null ) {
			ensureOpen();
			return local.read(dst, position);
		}
		return readAt(dst, position);
	}

	private synchronized int readAt(ByteBuffer dst, long position) throws IOException {
		ensureOpen();
		if( position < 0 ) {
			throw new IllegalArgumentException("negative position " + position);
		}
		if( !readable ) {
			throw new NonReadableChannelException();
		}
		long was = ch.position();
		try {
			ch.position(position);
			return ch.read(dst);
		} finally {
			ch.position(was);
		}
	}

	@Override
	public int write(ByteBuffer src, long position) throws IOException {
		if( local != null ) {
			ensureOpen();
			return local.write(src, position);
		}
		return writeAt(src, position);
	}

	private synchronized int writeAt(ByteBuffer src, long position) throws IOException {
		ensureOpen();
		if( position < 0 ) {
			throw new IllegalArgumentException("negative position " + position);
		}
		if( !writable ) {
			throw new NonWritableChannelException();
		}
		long was = ch.position();
		try {
			ch.position(position);
			int n = 0;
			while (src.hasRemaining()) {
				n += ch.write(src);
			}
			return n;
		} finally {
			ch.position(was);
		}
	}

	@Override
	public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
		ensureOpen();
		if( local != null ) {
			return local.transferTo(position, count, target);
		}
		if( position < 0 || count < 0 ) {
			throw new IllegalArgumentException("negative position or count");
		}
		ByteBuffer buf = ByteBuffer.allocate((int) Math.min(TRANSFER_BUFFER, Math.max(1, count)));
		long done = 0;
		while (done < count) {
			buf.clear();
			buf.limit((int) Math.min(buf.capacity(), count - done));
			int n = read(buf, position + done);
			if( n <= 0 ) {
				break;
			}
			buf.flip();
			while (buf.hasRemaining()) {
				target.write(buf);
			}
			done += n;
		}
		return done;
	}

	@Override
	public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
		ensureOpen();
		if( local != null ) {
			return local.transferFrom(src, position, count);
		}
		if( position < 0 || count < 0 ) {
			throw new IllegalArgumentException("negative position or count");
		}
		ByteBuffer buf = ByteBuffer.allocate((int) Math.min(TRANSFER_BUFFER, Math.max(1, count)));
		long done = 0;
		while (done < count) {
			buf.clear();
			buf.limit((int) Math.min(buf.capacity(), count - done));
			int n = src.read(buf);
			if( n <= 0 ) {
				break;
			}
			buf.flip();
			write(buf, position + done);
			done += n;
		}
		return done;
	}

	/**
	 * Map a region of the file into memory. For a local file it is a real map, in every mode. On
	 * other file systems a mapped buffer can't come from the file itself (only the JDK makes them,
	 * from its own files), so {@code READ_ONLY} and {@code PRIVATE} map a copy of the region, read
	 * into a temporary local file now: exactly what those modes promise, at the cost of reading the
	 * region. Later changes to the file aren't seen by the map (a real map may see them).
	 * @throws UnsupportedOperationException for {@code READ_WRITE} on a file that isn't local: a
	 *         copy can't send its changes back as a real map does
	 */
	@Override
	public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
		ensureOpen();
		if( local != null ) {
			return local.map(mode, position, size);
		}
		java.util.Objects.requireNonNull(mode, "mode");
		if( position < 0 ) {
			throw new IllegalArgumentException("negative position " + position);
		}
		if( size < 0 || size > Integer.MAX_VALUE || position + size < 0 ) {
			throw new IllegalArgumentException("Bad map size " + size + " at position " + position);
		}
		if( mode == MapMode.READ_WRITE ) {
			throw new UnsupportedOperationException("READ_WRITE needs a local file: a map of " + file.getAbsolutePath()
					+ " (on " + file.getFileSourceFactory().getTypeId() + ") would be a copy, which can't send its changes back."
					+ " READ_ONLY and PRIVATE work");
		}
		if( mode != MapMode.READ_ONLY && mode != MapMode.PRIVATE ) {
			throw new UnsupportedOperationException("Map mode " + mode);
		}
		if( !readable ) {
			throw new NonReadableChannelException();
		}
		if( mode == MapMode.PRIVATE && !writable ) {
			throw new NonWritableChannelException();
		}
		return mapCopy(mode, position, (int) size);
	}

	/** Read the region into a temporary local file and map that; the file goes as soon as it is mapped. */
	private MappedByteBuffer mapCopy(MapMode mode, long position, int size) throws IOException {
		java.nio.file.Path tmp = java.nio.file.Files.createTempFile("parley-map-", ".tmp");
		try (FileChannel copy = FileChannel.open(tmp, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
			ByteBuffer buf = ByteBuffer.allocate(Math.max(1, Math.min(TRANSFER_BUFFER, size)));
			long done = 0;
			while (done < size) {
				buf.clear();
				buf.limit((int) Math.min(buf.capacity(), size - done));
				int n = read(buf, position + done);
				if( n <= 0 ) {
					break; // past the end of the file: the rest of the map is zeros
				}
				buf.flip();
				while (buf.hasRemaining()) {
					done += copy.write(buf, done);
				}
			}
			if( size > 0 && copy.size() < size ) {
				copy.write(ByteBuffer.wrap(new byte[1]), size - 1L); // zeros to the end
			}
			return copy.map(mode, 0, size);
		} finally {
			try {
				// the map stays valid without the file (where the system can't delete a mapped
				// file, it goes when the JVM ends)
				java.nio.file.Files.deleteIfExists(tmp);
			} catch (IOException e) {
				tmp.toFile().deleteOnExit();
			}
		}
	}

	// ------------------------------------------------------------------ locking

	/**
	 * Lock a region of the file, waiting as long as another process holds an overlapping one.
	 * @throws FileLockInterruptionException if the thread is interrupted while waiting (the channel
	 *         is then closed, as an interruptible channel is)
	 * @throws AsynchronousCloseException if another thread closes the channel while this one waits
	 */
	@Override
	public FileLock lock(long position, long size, boolean shared) throws IOException {
		check(position, size, shared);
		// while it waits, the region is claimed in this JVM: another request for it throws, as NIO's does
		try (LockManager.Wait w = LockManager.beginWait(position, size)) {
			long sleep = 1;
			while (true) {
				if( !isOpen() ) {
					throw new AsynchronousCloseException();
				}
				if( Thread.currentThread().isInterrupted() ) {
					interrupted();
				}
				// not tryLock: its open check would report a close meanwhile as ClosedChannelException
				FileLock l = w.attempt(() -> attempt(position, size, shared));
				if( l != null ) {
					return l;
				}
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					interrupted();
				}
				sleep = Math.min(sleep * 2, MAX_POLL_MS);
			}
		}
	}

	private void interrupted() throws IOException {
		try {
			close();
		} catch (IOException e) {
			// closing is the point; the interrupt is what we report
		}
		throw new FileLockInterruptionException();
	}

	/** Lock a region of the file now, or return null if another process holds an overlapping one. */
	@Override
	public FileLock tryLock(long position, long size, boolean shared) throws IOException {
		check(position, size, shared);
		return attempt(position, size, shared);
	}

	/**
	 * One try, the arguments checked: the lock, or null if another process holds an overlapping
	 * region; AsynchronousCloseException if the channel is closed meanwhile.
	 */
	private FileLock attempt(long position, long size, boolean shared) throws IOException {
		synchronized (lockGuard) {
			// isOpen() turns false before implCloseChannel runs, so a close that has begun is seen here
			if( !isOpen() ) {
				throw new AsynchronousCloseException();
			}
			FileSourceLock l = file.tryLock(position, size, shared);
			if( l == null ) {
				return null;
			}
			Lock lock = new Lock(this, l.position(), l.size(), shared, l);
			locks.add(lock);
			return lock;
		}
	}

	private void check(long position, long size, boolean shared) throws IOException {
		if( position < 0 || size < 0 || position + size < 0 ) {
			throw new IllegalArgumentException("Bad lock range: position " + position + ", size " + size);
		}
		ensureOpen();
		if( shared && !readable ) {
			throw new NonReadableChannelException();
		}
		if( !shared && !writable ) {
			throw new NonWritableChannelException();
		}
	}

	@Override
	protected void implCloseChannel() throws IOException {
		List<Lock> held;
		synchronized (lockGuard) {
			held = new ArrayList<>(locks);
			locks.clear();
		}
		for (Lock l : held) {
			l.releaseQuietly();
		}
		ch.close();
	}

	/** A FileSource lock, as a FileLock of this channel. */
	private static final class Lock extends FileLock {
		private final FileSourceLock lock;

		Lock(FileSourceFileChannel channel, long position, long size, boolean shared, FileSourceLock lock) {
			super(channel, position, size, shared);
			this.lock = lock;
		}

		@Override
		public boolean isValid() {
			return lock.isValid() && acquiredBy().isOpen();
		}

		@Override
		public void release() throws IOException {
			if( !acquiredBy().isOpen() ) {
				throw new ClosedChannelException();
			}
			// released before it leaves the channel's set: a close meanwhile still finds it, and its
			// release waits for this one (FileSourceLock.release is serialized and idempotent), so
			// close() never returns with the region held
			lock.release();
			((FileSourceFileChannel) acquiredBy()).locks.remove(this);
		}

		void releaseQuietly() {
			try {
				lock.release();
			} catch (IOException e) {
				// released here anyway
			}
		}
	}
}
