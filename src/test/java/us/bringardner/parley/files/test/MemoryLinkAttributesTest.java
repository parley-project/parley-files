package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
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
	}

	@Test
	void symbolicLinkToNothingHasItsOwnTime() throws Exception {
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/dangling"), f.createFileSource("/d/nothing"));
		assertTrue(link.lastModified() > OLD);
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
}
