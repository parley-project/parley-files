package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * A symbolic link answers lastModified, lastAccessTime and getOwner with its own
 * values, not its target's (D78).
 */
public class FileProxyLinkAttributesTest {

	private static final long TARGET_TIME = 1_500_000_000_000L;
	private static final long LINK_TIME = 1_600_000_000_000L;

	private Path link(Path tmp) throws Exception {
		Path target = tmp.resolve("target.txt");
		Files.write(target, new byte[] {1});
		Files.setLastModifiedTime(target, FileTime.fromMillis(TARGET_TIME));
		Path link = tmp.resolve("link.txt");
		try {
			Files.createSymbolicLink(link, target);
		} catch (UnsupportedOperationException | java.io.IOException e) {
			assumeTrue(false, "no symbolic links here: " + e);
		}
		BasicFileAttributeView view = Files.getFileAttributeView(link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		try {
			view.setTimes(FileTime.fromMillis(LINK_TIME), FileTime.fromMillis(LINK_TIME), null);
		} catch (UnsupportedOperationException | java.io.IOException e) {
			assumeTrue(false, "can't set a link's own times here: " + e);
		}
		assumeTrue(Files.getLastModifiedTime(link, LinkOption.NOFOLLOW_LINKS).toMillis() == LINK_TIME);
		return link;
	}

	@Test
	public void linkHasItsOwnTimes(@TempDir Path tmp) throws Exception {
		Path link = link(tmp);
		FileSource f = new FileProxyFactory().createFileSource(link.toString());
		assertEquals(LINK_TIME, f.lastModified(), "lastModified");
		assertEquals(LINK_TIME, f.lastAccessTime(), "lastAccessTime");
		assertNotEquals(TARGET_TIME, f.lastModified());
		assertEquals(TARGET_TIME, f.contentLastModified(), "what the link leads to was last changed then");
	}

	@Test
	public void linkHasItsOwnOwner(@TempDir Path tmp) throws Exception {
		Path link = link(tmp);
		FileSource f = new FileProxyFactory().createFileSource(link.toString());
		assertEquals(Files.getOwner(link, LinkOption.NOFOLLOW_LINKS).getName(), f.getOwner().getName());
	}

	@Test
	public void brokenLinkAnswersItsOwnTime(@TempDir Path tmp) throws Exception {
		Path link = link(tmp);
		Files.delete(tmp.resolve("target.txt"));
		FileSource f = new FileProxyFactory().createFileSource(link.toString());
		assertEquals(LINK_TIME, f.lastModified());
	}

	@Test
	public void regularFileIsUnchanged(@TempDir Path tmp) throws Exception {
		Path p = tmp.resolve("plain.txt");
		Files.write(p, new byte[] {1});
		Files.setLastModifiedTime(p, FileTime.fromMillis(TARGET_TIME));
		FileSource f = new FileProxyFactory().createFileSource(p.toString());
		assertEquals(TARGET_TIME, f.lastModified());
	}

	@Test
	void settersOnALinkChangeTheLinkNotTheTarget(@TempDir Path tmp) throws Exception {
		Path link = link(tmp);
		Path target = tmp.resolve("target.txt");
		FileSource f = new FileProxyFactory().createFileSource(link.toString());
		long other = 1_700_000_000_000L;
		assertTrue(f.setLastModifiedTime(other));
		assertTrue(f.setLastAccessTime(other));
		assertEquals(other, Files.getLastModifiedTime(link, LinkOption.NOFOLLOW_LINKS).toMillis());
		assertEquals(other, f.lastModified());
		assertEquals(TARGET_TIME, Files.getLastModifiedTime(target).toMillis());
		assertEquals(TARGET_TIME, new FileProxyFactory().createFileSource(target.toString()).lastModified());
	}

	@Test
	void linkHasItsOwnModeBitsAndLengthButReadingFollowsIt(@TempDir Path tmp) throws Exception {
		assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
		Path link = link(tmp);
		Path target = tmp.resolve("target.txt");
		Files.write(target, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
		Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
		FileSource f = new FileProxyFactory().createFileSource(link.toString());

		// the link's own mode, as ls -l shows it
		assertEquals(Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ), f.canGroupRead());
		assertEquals(Files.getPosixFilePermissions(link, LinkOption.NOFOLLOW_LINKS).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE), f.canOtherWrite());
		// the length of the link text, as lstat has it
		assertEquals(Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size(), f.length());
		assertNotEquals(10, f.length());
		// reading follows the link
		assertEquals(10, f.contentLength());
		assertArrayEquals(new byte[] {9, 10}, f.tail(2));

		// setting a bit through the link changes the file's bit, not the file's other bits to the link's
		assertTrue(f.setOwnerWritable(false));
		assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
	}
}
