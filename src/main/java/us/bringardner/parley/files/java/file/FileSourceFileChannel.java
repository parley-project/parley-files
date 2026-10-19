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
 * <li>{@link #map} works for local files only (a mapped buffer can only come from the JDK's own
 * files); elsewhere it throws {@link UnsupportedOperationException}.</li>
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
	 * A real memory map, for a local file. Other file systems can't map: a mapped buffer can only
	 * come from the JDK's own files.
	 * @throws UnsupportedOperationException if the file isn't local
	 */
	@Override
	public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
		ensureOpen();
		if( local == null ) {
			throw new UnsupportedOperationException("Only a local file can be memory-mapped; "
					+ file.getAbsolutePath() + " is on " + file.getFileSourceFactory().getTypeId());
		}
		return local.map(mode, position, size);
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
		long sleep = 1;
		while (true) {
			if( !isOpen() ) {
				throw new AsynchronousCloseException();
			}
			if( Thread.currentThread().isInterrupted() ) {
				interrupted();
			}
			FileLock l = tryLock(position, size, shared);
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
		FileSourceLock l = file.tryLock(position, size, shared);
		if( l == null ) {
			return null;
		}
		Lock lock = new Lock(this, l.position(), l.size(), shared, l);
		locks.add(lock);
		if( !isOpen() ) {
			// closed meanwhile: don't leave a lock behind the channel
			lock.releaseQuietly();
			throw new AsynchronousCloseException();
		}
		return lock;
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
		List<Lock> held = new ArrayList<>(locks);
		locks.clear();
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
			((FileSourceFileChannel) acquiredBy()).locks.remove(this);
			lock.release();
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
