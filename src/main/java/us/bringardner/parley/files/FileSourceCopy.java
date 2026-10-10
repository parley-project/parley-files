package us.bringardner.parley.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import us.bringardner.parley.io.IoUtils;

/**
 * Copying the contents of one FileSource to another, in one place, so every caller moves data in
 * blocks that suit the two ends. Each file source knows what suits it (an SFTP connection its
 * chunk, a database its row size, an FTP connection its transfer buffer: see
 * {@link FileSource#getStreamDefaults()}), so a copy asks both and uses the larger, and opens its
 * streams with that size so their buffers match the loop.
 * <p>
 * This is for what a copy loop needs, not a replacement for one that has its own needs (progress,
 * pause, cancel): such a loop calls {@link #blockSizeFor(FileSource, FileSource)} and
 * {@link #optionsFor(int)} and keeps the rest.
 * <p>
 * Like java.nio.file.Files, a separate class: FileSource is meant to look like java.io.File.
 */
public final class FileSourceCopy {

	/** Not less than this, however small a source says it wants. */
	public static final int MIN_BLOCK = 8 * 1024;
	/** Not more than this: a bigger block makes progress and cancelling coarse, and holds memory. */
	public static final int MAX_BLOCK = 1024 * 1024;
	/** When neither end says. */
	public static final int DEFAULT_BLOCK = IoUtils.BUFFER_SIZE;

	private FileSourceCopy() {
	}

	/**
	 * @return the block to copy from one source to the other: the larger of what the two say they
	 * want, within {@link #MIN_BLOCK} and {@link #MAX_BLOCK}, or {@link #DEFAULT_BLOCK} if neither says
	 */
	public static int blockSizeFor(FileSource from, FileSource to) {
		int size = Math.max(wanted(from), wanted(to));
		if( size <= 0 ) {
			size = DEFAULT_BLOCK;
		}
		return Math.max(MIN_BLOCK, Math.min(MAX_BLOCK, size));
	}

	private static int wanted(FileSource f) {
		try {
			return f == null ? 0 : f.getStreamDefaults().bufferSize();
		} catch (RuntimeException e) {
			// a source that can't say (not connected, say) is the same as one that doesn't want
			return 0;
		}
	}

	/** @return the options to open both streams of a copy with */
	public static StreamOptions optionsFor(int block) {
		return StreamOptions.buffer(block);
	}

	/**
	 * Copies the contents of from to to, replacing what to had. Both streams are closed, also when
	 * the copy fails. A directory isn't copied: this is for files.
	 *
	 * @return the number of bytes copied
	 */
	public static long copy(FileSource from, FileSource to) throws IOException {
		int block = blockSizeFor(from, to);
		StreamOptions options = optionsFor(block);
		long total = 0;
		try (InputStream in = from.getInputStream(options); OutputStream out = to.getOutputStream(false, options)) {
			byte[] buffer = new byte[block];
			int n;
			while( (n = in.read(buffer)) >= 0 ) {
				if( n > 0 ) {
					out.write(buffer, 0, n);
					total += n;
				}
			}
		}
		return total;
	}
}
