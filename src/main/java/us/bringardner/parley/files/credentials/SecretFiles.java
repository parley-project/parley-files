package us.bringardner.parley.files.credentials;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Helpers the file-based providers share: owner-only files and UTF-8 without Strings. */
final class SecretFiles {

	private static final Set<PosixFilePermission> NOT_OWNER = EnumSet.of(
			PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
			PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

	private SecretFiles() {
	}

	/** path with a leading ~ replaced by the user's home directory. */
	static Path expand(String path) {
		if( path.equals("~") ) {
			return Paths.get(System.getProperty("user.home"));
		}
		if( path.startsWith("~/") || path.startsWith("~\\")) {
			return Paths.get(System.getProperty("user.home"), path.substring(2));
		}
		return Paths.get(path);
	}

	/** True where the file system has POSIX permissions (not on Windows). */
	static boolean hasPosix(Path p) {
		Path dir = p.toAbsolutePath().getParent();
		return Files.getFileAttributeView(dir == null ? p : dir, PosixFileAttributeView.class) != null;
	}

	/**
	 * Refuses a file that its group or others can use, the way ssh refuses such a private key.
	 * (On a file system without POSIX permissions the check is skipped.)
	 */
	static void checkOwnerOnly(CredentialRef ref, Path p) throws CredentialException {
		try {
			if( Files.getFileAttributeView(p, PosixFileAttributeView.class) == null ) {
				return;
			}
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
			for(PosixFilePermission perm : perms) {
				if( NOT_OWNER.contains(perm)) {
					throw new CredentialException(ref, p+" can be read by others; run: chmod 600 "+p);
				}
			}
		} catch (CredentialException e) {
			throw e;
		} catch (IOException e) {
			throw new CredentialException(ref, "can't check the permissions of "+p, e);
		}
	}

	/**
	 * Writes data to p, readable by its owner only, replacing it in one step (a temporary
	 * file in the same directory, then a move). Creates the directory owner-only if missing.
	 */
	static void writeOwnerOnly(Path p, byte[] data) throws IOException {
		Path dir = p.toAbsolutePath().getParent();
		boolean posix = hasPosix(p);
		if( dir != null && !Files.isDirectory(dir)) {
			if( posix ) {
				Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			} else {
				Files.createDirectories(dir);
			}
		}
		Path tmp = posix
				? Files.createTempFile(dir, ".parley", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
				: Files.createTempFile(dir, ".parley", ".tmp");
		try {
			Files.write(tmp, data, StandardOpenOption.TRUNCATE_EXISTING);
			try {
				Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** UTF-8 bytes to chars, zeroing nothing (the caller owns bytes). */
	static char[] decode(byte[] bytes, int len) {
		CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes, 0, len));
		char[] ret = Arrays.copyOfRange(cb.array(), cb.position(), cb.limit());
		Arrays.fill(cb.array(), '\0');
		return ret;
	}

	/** chars to UTF-8 bytes; the caller zeroes the result. */
	static byte[] encode(char[] chars) {
		ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] ret = Arrays.copyOfRange(bb.array(), bb.position(), bb.limit());
		Arrays.fill(bb.array(), (byte) 0);
		return ret;
	}

	/** chars without one trailing line end (\n or \r\n), as a file or a command's output has. */
	static char[] chomp(char[] chars) {
		int n = chars.length;
		if( n > 0 && chars[n-1] == '\n' ) {
			n--;
			if( n > 0 && chars[n-1] == '\r' ) {
				n--;
			}
		}
		if( n == chars.length ) {
			return chars;
		}
		char[] ret = Arrays.copyOf(chars, n);
		Arrays.fill(chars, '\0');
		return ret;
	}
}
