package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * The owner's read and write answers are the owner's bits of the mode, as the group's and other's are, whoever runs the
 * process (a root process can write a file whose mode has no write bit).
 */
public class FileProxyOwnerBitsTest {

	@Test
	public void ownerBitsAreTheModeBits(@TempDir Path tmp) throws Exception {
		assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
		for (String mode : new String[] {"r--r--r--", "rw-r--r--", "-w-------", "---rwxrwx", "rwx------"}) {
			Path p = tmp.resolve("f" + mode);
			Files.write(p, new byte[] {1});
			Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
			FileSource f = new FileProxyFactory().createFileSource(p.toString());
			assertEquals(mode.charAt(0) == 'r', f.canOwnerRead(), "owner read of " + mode);
			assertEquals(mode.charAt(1) == 'w', f.canOwnerWrite(), "owner write of " + mode);
			assertEquals(mode.charAt(2) == 'x', f.canOwnerExecute(), "owner execute of " + mode);
			assertEquals(mode.charAt(3) == 'r', f.canGroupRead(), "group read of " + mode);
			assertEquals(mode.charAt(7) == 'w', f.canOtherWrite(), "other write of " + mode);
		}
	}
}
