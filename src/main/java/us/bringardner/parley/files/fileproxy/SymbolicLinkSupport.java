package us.bringardner.parley.files.fileproxy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Whether this process may create symbolic links in the operating system's file system.
 * <p>
 * On Unix it can, unless the file system has no links. On Windows, creating a symbolic link needs
 * the <em>SeCreateSymbolicLinkPrivilege</em>: it is held by administrators (in an elevated process)
 * and by every user when <strong>Developer Mode</strong> is on (Settings &gt; System &gt; For
 * developers), or when a policy grants it. Otherwise the JDK reports "A required privilege is not
 * held by the client". Hard links and everything else are not affected.
 * <p>
 * The answer comes from trying: a link is made and removed in a temporary directory. That is the
 * only check that covers Developer Mode, elevation and policy together, and it does not depend on the
 * language of the system's error messages.
 */
public final class SymbolicLinkSupport {

	private static volatile Boolean available;

	private SymbolicLinkSupport() {
	}

	/** True when the operating system is Windows. */
	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	/**
	 * Whether this process can create symbolic links in the temporary directory. The result is
	 * kept, because it can't change while the process runs.
	 */
	public static boolean isAvailable() {
		Boolean ret = available;
		if( ret == null ) {
			ret = isAvailableIn(null);
			available = ret;
		}
		return ret;
	}

	/**
	 * Whether this process can create symbolic links in the given directory (the temporary
	 * directory when it is null). Not kept: the file system may be another one (a FAT volume has no links).
	 */
	public static boolean isAvailableIn(Path directory) {
		Path dir = null;
		try {
			dir = directory == null
					? Files.createTempDirectory("parley-symlink-probe")
					: Files.createTempDirectory(directory, "parley-symlink-probe");
			Path target = Files.createFile(dir.resolve("target"));
			Files.createSymbolicLink(dir.resolve("link"), target);
			return true;
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			return false;
		} finally {
			if( dir != null ) {
				try (Stream<Path> walk = Files.walk(dir)) {
					walk.sorted(Comparator.reverseOrder()).forEach(p -> {
						try {
							Files.deleteIfExists(p);
						} catch (IOException e) {
							// a probe's leftovers are in the temp directory; nothing depends on them
						}
					});
				} catch (IOException e) {
					// as above
				}
			}
		}
	}

	/** What to do about it, for a message. */
	public static String advice() {
		return "Creating a symbolic link on Windows needs the \"Create symbolic links\" privilege. "
				+ "Turn on Developer Mode (Settings > System > For developers), then sign out and in again, "
				+ "or run the program elevated (as Administrator). Hard links are not affected.";
	}

	/**
	 * The exception to throw for a link that couldn't be made. On Windows without the privilege it
	 * says what is wrong and what to do; otherwise the original is returned as it is.
	 */
	static IOException explain(IOException failure, Path link, Path target) {
		if( failure instanceof FileAlreadyExistsException || !(failure instanceof FileSystemException)
				|| !isWindows() || isAvailable() ) {
			return failure;
		}
		return new SymbolicLinkPrivilegeException(link.toString(), target == null ? null : target.toString(), failure);
	}

	/**
	 * A symbolic link couldn't be created because this process does not have the Windows privilege
	 * for it. It is a {@link FileSystemException}, so callers that catch {@link IOException} still do;
	 * the cause is the operating system's own error.
	 */
	public static class SymbolicLinkPrivilegeException extends FileSystemException {

		private static final long serialVersionUID = 1L;

		public SymbolicLinkPrivilegeException(String link, String target, Throwable cause) {
			super(link, target, "cannot create a symbolic link: this process does not have the symbolic link "
					+ "privilege. " + advice());
			initCause(cause);
		}
	}
}
