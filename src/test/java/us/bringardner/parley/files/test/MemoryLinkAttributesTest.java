package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * A symbolic link answers lastModified, lastAccessTime and creationTime with its own
 * values; a hard link still shares its file's (D78).
 */
public class MemoryLinkAttributesTest {

	private static final long OLD = 1_500_000_000_000L;

	private MemoryFileSourceFactory f;

	@BeforeEach
	void setUp() throws Exception {
		f = new MemoryFileSourceFactory();
		f.connect();
		assertTrue(f.createFileSource("/d").mkdirs());
	}

	private FileSource oldFile(String path) throws Exception {
		FileSource s = f.createFileSource(path);
		try (OutputStream out = s.getOutputStream()) {
			out.write(1);
		}
		assertTrue(s.setLastModifiedTime(OLD));
		assertTrue(s.setLastAccessTime(OLD));
		assertTrue(s.setCreateTime(OLD));
		return s;
	}

	@Test
	void symbolicLinkHasItsOwnTimes() throws Exception {
		FileSource target = oldFile("/d/target");
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), target);
		assertNotEquals(OLD, link.lastModified());
		assertNotEquals(OLD, link.lastAccessTime());
		assertNotEquals(OLD, link.creationTime());
		assertEquals(OLD, target.lastModified());
		assertEquals(OLD, target.lastAccessTime());
		assertEquals(OLD, target.creationTime());
		assertEquals(OLD, link.contentLastModified(), "the content is the file's: when it changed is the file's time");
		assertEquals(OLD, target.contentLastModified());
	}

	@Test
	void symbolicLinkToNothingHasItsOwnTime() throws Exception {
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/dangling"), f.createFileSource("/d/nothing"));
		assertTrue(link.lastModified() > OLD);
		assertEquals(0, link.contentLastModified(), "it leads nowhere");
	}

	@Test
	void ownerAndGroupAreTheLinksOwn() throws Exception {
		FileSource target = oldFile("/d/target");
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), target);
		assertEquals(target.getOwner().getName(), link.getOwner().getName());
		assertEquals(target.getGroup().getName(), link.getGroup().getName());
	}

	@Test
	void hardLinkStillSharesItsFilesTimes() throws Exception {
		FileSource target = oldFile("/d/target");
		FileSource hard = f.createLink(f.createFileSource("/d/hard"), target);
		assertEquals(OLD, hard.lastModified());
		assertEquals(OLD, hard.lastAccessTime());
		assertEquals(OLD, hard.creationTime());
	}

	@Test
	void settersOnASymbolicLinkChangeTheLinkNotTheTarget() throws Exception {
		FileSource target = oldFile("/d/target");
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), target);
		long other = 1_700_000_000_000L;
		assertTrue(link.setLastModifiedTime(other));
		assertTrue(link.setLastAccessTime(other));
		assertTrue(link.setCreateTime(other));
		assertEquals(other, link.lastModified());
		assertEquals(other, link.lastAccessTime());
		assertEquals(other, link.creationTime());
		assertEquals(OLD, target.lastModified());
		assertEquals(OLD, target.lastAccessTime());
		assertEquals(OLD, target.creationTime());

		FileSourceUser who = new FileSourceUser(1234, "someoneElse", 4321, "theirGroup");
		assertTrue(link.setOwner(who));
		assertTrue(link.setGroup(who.getGroup()));
		assertEquals("someoneElse", link.getOwner().getName());
		assertEquals("theirGroup", link.getGroup().getName());
		assertNotEquals("someoneElse", target.getOwner().getName());
		assertNotEquals("theirGroup", target.getGroup().getName());
	}

	@Test
	void settingATimeOnALinkToNothingWorks() throws Exception {
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/dangling"), f.createFileSource("/d/nothing"));
		assertTrue(link.setLastModifiedTime(OLD));
		assertEquals(OLD, link.lastModified());
	}

	@Test
	void settersOnAHardLinkStillChangeTheFile() throws Exception {
		FileSource target = oldFile("/d/target");
		FileSource hard = f.createLink(f.createFileSource("/d/hard"), target);
		assertTrue(hard.setLastModifiedTime(OLD + 1000));
		assertEquals(OLD + 1000, target.lastModified());
	}

	@Test
	void linkHasItsOwnModeBitsButAccessFollowsTheFile() throws Exception {
		FileSource target = oldFile("/d/target");
		assertTrue(target.setOwnerReadable(false));
		assertTrue(target.setGroupReadable(false));
		assertTrue(target.setOtherReadable(false));
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), target);
		// rwxrwxrwx whatever the file is, as lstat shows a link
		assertTrue(link.canOwnerRead() && link.canOwnerWrite() && link.canOwnerExecute());
		assertTrue(link.canGroupRead() && link.canGroupWrite() && link.canGroupExecute());
		assertTrue(link.canOtherRead() && link.canOtherWrite() && link.canOtherExecute());
		assertFalse(target.canOwnerRead());
		// but opening follows the link, so can this user read it: no, as the file says
		assertFalse(link.canRead());
		assertTrue(target.setOwnerReadable(true));
		assertTrue(link.canRead());
		// chmod through a link changes the file, not the link
		assertTrue(link.setOwnerReadable(false));
		assertFalse(target.canOwnerRead());
		assertTrue(link.canOwnerRead());
	}

	@Test
	void linkHasItsOwnLengthButReadingFollowsIt() throws Exception {
		FileSource target = f.createFileSource("/d/target");
		try (OutputStream out = target.getOutputStream()) {
			out.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30});
		}
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), "target");
		assertEquals("target".length(), link.length(), "the length of the link text");
		assertEquals(30, target.length());
		assertEquals(30, link.contentLength(), "what reading gives");
		assertEquals(30, target.contentLength());
		byte[] first = link.head(1000);
		assertEquals(30, first.length, "head reads through the link");
		assertArrayEquals(new byte[] {29, 30}, link.tail(2));
		try (java.io.InputStream in = link.getInputStream()) {
			assertEquals(30, in.readAllBytes().length);
		}
	}

	@Test
	void linkToNothingHasALengthButNoContent() throws Exception {
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/dangling"), "nothing");
		assertEquals("nothing".length(), link.length());
		assertEquals(0, link.contentLength());
		assertFalse(link.canRead());
	}
}
