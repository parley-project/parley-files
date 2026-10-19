package us.bringardner.parley.files;

import java.io.IOException;

/**
 * Something written to a file (an output stream, a random access stream) that can make what it has
 * written durable: {@link #sync()} first sends whatever it still holds (as {@code flush()} does),
 * then asks the file system to put it on its storage, as {@code fsync} does for a local file, so it
 * survives a crash of the machine that keeps the file.
 * <p>
 * How far that reaches depends on the file system. Local files are synced to the disk; a database
 * commits each write; an SFTP server is asked to sync the file where it can be
 * ({@code fsync@openssh.com}); FTP can only send the data. A stream from a FileSource that isn't
 * Syncable can be flushed, and nothing more.
 * <p>
 * {@link java.nio.channels.FileChannel#force(boolean)} on a FileSource path calls it.
 * @since 1.1.0
 */
public interface Syncable {

	/**
	 * Send what is held, then make it durable as far as the file system can.
	 * @throws IOException if it can't be written or synced
	 */
	void sync() throws IOException;
}
