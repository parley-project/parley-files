package us.bringardner.parley.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Replacing the contents of a file so a failure never leaves it half written, in one place, so
 * every program that saves a file does it the same way. Like {@link FileSourceCopy}, a separate
 * class: FileSource is meant to look like java.io.File.
 * <p>
 * Where the file already exists and its file system replaces a file in one step
 * ({@link FileSourceCapabilities#atomicReplace()}), the new contents are written to a hidden file
 * in the same folder, that file gets the old one's permissions, and it is renamed over the old one
 * ({@link FileSource#renameTo(FileSource, boolean)}). If anything fails the hidden file is
 * deleted and the old file is as it was. Otherwise (a new file, a file system that can't replace
 * in one step, a file with no parent) the contents are written to the file itself.
 * <p>
 * What this does not do is decide whether the file may be replaced: locking it, and checking that
 * nobody changed it since it was read, are the caller's, which knows what it read.
 */
public class FileSourceReplace {

	/** Writes the new contents. It is given the stream to write to and does not close it. */
	@FunctionalInterface
	public interface Writer {
		void write(OutputStream out) throws IOException;
	}

	protected FileSourceReplace() {
	}

	/**
	 * @return true if replacing target would be done through a hidden file and a rename: it exists,
	 * has a parent, and its file system replaces in one step
	 */
	public static boolean isOneStep(FileSource target) throws IOException {
		return target.exists() && target.getParentFile() != null && target.getCapabilities().atomicReplace();
	}

	/**
	 * Replace the contents of target with what writer writes.
	 * @throws IOException if it could not be written or renamed; target is then as it was if
	 * {@link #isOneStep(FileSource)} was true, otherwise it may be part written
	 */
	public static void replace(FileSource target, Writer writer) throws IOException {
		if( !isOneStep(target) ) {
			try (OutputStream out = target.getOutputStream()) {
				writer.write(out);
			}
			return;
		}
		FileSource temp = target.getParentFile().getChild("."+target.getName()+".new-"+Long.toHexString(System.nanoTime()));
		boolean done = false;
		try {
			try (OutputStream out = temp.getOutputStream()) {
				writer.write(out);
			}
			copyAccess(target, temp);
			done = temp.renameTo(target, true);
			if( !done ) {
				throw new IOException("could not replace "+target.getName());
			}
		} finally {
			if( !done ) {
				temp.delete();
			}
		}
	}

	/** Replace the contents of target with the bytes of data. */
	public static void replace(FileSource target, byte[] data) throws IOException {
		replace(target, out->out.write(data));
	}

	/**
	 * Replace the contents of target with those of source, copied in blocks that suit the two
	 * ends (see {@link FileSourceCopy}).
	 * @return the number of bytes copied
	 */
	public static long replace(FileSource target, FileSource source) throws IOException {
		int block = FileSourceCopy.blockSizeFor(source, target);
		long[] total = new long[1];
		try (InputStream in = source.getInputStream(FileSourceCopy.optionsFor(block))) {
			replace(target, out->{
				byte[] buffer = new byte[block];
				int n;
				while( (n = in.read(buffer)) >= 0 ) {
					out.write(buffer, 0, n);
					total[0] += n;
				}
			});
		}
		return total[0];
	}

	/**
	 * Give {@code to} the permissions of {@code from}, as far as the file system can say and set them. A file
	 * system with none to copy is not an error.
	 */
	public static void copyAccess(FileSource from, FileSource to) {
		try {
			to.setOwnerReadable(from.canOwnerRead());
			to.setOwnerWritable(from.canOwnerWrite());
			to.setOwnerExecutable(from.canOwnerExecute());
			to.setGroupReadable(from.canGroupRead());
			to.setGroupWritable(from.canGroupWrite());
			to.setGroupExecutable(from.canGroupExecute());
			to.setOtherReadable(from.canOtherRead());
			to.setOtherWritable(from.canOtherWrite());
			to.setOtherExecutable(from.canOtherExecute());
		} catch( IOException | RuntimeException e ) {
			// a file system with no permissions to copy
		}
	}
}
