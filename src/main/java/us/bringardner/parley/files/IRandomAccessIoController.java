package us.bringardner.parley.files;

import java.io.IOException;

/**
 * Controls the IO for a random access stream
 */
public interface IRandomAccessIoController extends AutoCloseable {
	int read(long position) throws IOException;
	void write(long position, byte value) throws IOException;

	/**
	 * Read up to len bytes starting at position into b[off..].
	 * Implementations should override this: the default calls read(long)
	 * once per byte.
	 * @return the number of bytes read, or -1 if position is at or past EOF
	 */
	default int read(long position, byte[] b, int off, int len) throws IOException {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		if( len == 0 ) {
			return 0;
		}
		int count = 0;
		while( count < len ) {
			int v = read(position + count);
			if( v < 0 ) {
				break;
			}
			b[off + count++] = (byte) v;
		}
		return count == 0 ? -1 : count;
	}

	/**
	 * Write len bytes from b[off..] starting at position.
	 * Implementations should override this: the default calls
	 * write(long, byte) once per byte.
	 */
	default void write(long position, byte[] b, int off, int len) throws IOException {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		for(int i = 0; i < len; i++) {
			write(position + i, b[off + i]);
		}
	}
	long length() throws IOException;
	void setLength(long newLength) throws IOException;
	void save() throws IOException;

	/**
	 * After {@link #save()}: ask the file system to make what was written durable (see
	 * {@link Syncable}). The default does nothing more than save already did.
	 * @throws IOException if it can't be synced
	 * @since 1.1.0
	 */
	default void sync() throws IOException {
	}
	FileSource getFile();

	/**
	 * Whether this controller checked the file when it was opened, against the file system itself, as
	 * RandomAccessFile does: it throws FileNotFoundException for a directory, for a file missing in "r"
	 * mode, and for one it can't create in a write mode. Where the file system can say at open time
	 * that this user may not read (or write) it, it throws then too; otherwise a read or write the file
	 * system refuses fails when it is done (FTP can't tell before it tries).
	 * <p>
	 * true: {@link FileSourceRandomAccessStream} doesn't check the file again from what the FileSource
	 * says (canRead(), canWrite()). Over a remote protocol those are a guess made from the listing's
	 * owner and permission bits (FTP: the logged-in user is often not the owner the listing shows, and the
	 * server's own access rules don't show there), so they can refuse a file the server lets this user
	 * write. The default is false: the stream checks.
	 * @since 1.1.0
	 */
	default boolean checksAccessOnOpen() {
		return false;
	}
}
