package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceGroup;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.java.file.FileSourcePath;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Regression tests for equals / hashCode / compareTo (review section 3,
 * "Identity and collections").
 */
public class IdentityTests {

	private static final FileSourceFactory LOCAL = FileSourceFactory.getDefaultFactory();

	@Test
	public void fileProxyWorksInHashSets() {
		FileSource a = new FileProxy(new File("some/rel/path.txt"), LOCAL);
		FileSource b = new FileProxy(new File("some/rel/path.txt").getAbsoluteFile(), LOCAL);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		Set<FileSource> set = new HashSet<>();
		set.add(a);
		assertTrue(set.contains(b));
	}

	/** compareTo used o.toString(), which for a FileProxy built from a relative File is relative. */
	@Test
	public void fileProxyCompareToIsConsistentWithEquals() {
		FileSource a = new FileProxy(new File("some/rel/path.txt"), LOCAL);
		FileSource b = new FileProxy(new File("some/rel/path.txt").getAbsoluteFile(), LOCAL);
		assertEquals(0, a.compareTo(b));
		assertEquals(0, b.compareTo(a));
		FileSource c = new FileProxy(new File("some/rel/zzz.txt"), LOCAL);
		assertTrue(a.compareTo(c) < 0);
		assertTrue(c.compareTo(a) > 0);
	}

	@Test
	public void memoryFileSourceWorksInHashSets() throws IOException {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource a = factory.createFileSource("/x/y");
		FileSource b = factory.createFileSource("/x/./y");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		Set<FileSource> set = new HashSet<>();
		set.add(a);
		assertTrue(set.contains(b));
		assertEquals(0, a.compareTo(b));
	}

	@Test
	public void fileSourceGroupHashMatchesEquals() {
		FileSourceGroup a = new FileSourceGroup(1, "staff");
		FileSourceGroup b = new FileSourceGroup(2, "staff");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		Set<FileSourceGroup> set = new HashSet<>();
		set.add(a);
		assertTrue(set.contains(b));
	}

	/** FileSourcePath had no equals/hashCode at all. */
	@Test
	public void fileSourcePathEqualsAndHashCode() {
		Path a = new FileSourcePath("/a/b/c", LOCAL);
		Path b = new FileSourcePath("/a/b/c", LOCAL);
		Path c = new FileSourcePath("/a/b/d", LOCAL);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
		Set<Path> set = new HashSet<>();
		set.add(a);
		assertTrue(set.contains(b));

		Path mem = new FileSourcePath("/a/b/c", new MemoryFileSourceFactory());
		assertNotEquals(a, mem, "different file systems");
	}

	/** compareTo called normalize(), which changed the path it was called on. */
	@Test
	public void fileSourcePathCompareToDoesNotModifyThePath() {
		Path a = new FileSourcePath("/a/../b", LOCAL);
		Path b = new FileSourcePath("/b", LOCAL);
		assertNotEquals(0, a.compareTo(b));
		assertEquals("/a/../b", a.toString());
		assertEquals(0, a.compareTo(new FileSourcePath("/a/../b", LOCAL)));
	}
}
