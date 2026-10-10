package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** Moving a link, and moving the name that holds a file other names share. */
public class MemoryLinkRenameTest {

	private MemoryFileSourceFactory f;

	@BeforeEach
	void setUp() throws Exception {
		f = new MemoryFileSourceFactory();
		f.connect();
		assertTrue(f.createFileSource("/d").mkdirs());
	}

	private FileSource file(String path, byte... content) throws Exception {
		FileSource s = f.createFileSource(path);
		try (OutputStream out = s.getOutputStream()) {
			out.write(content);
		}
		return s;
	}

	private byte[] read(String path) throws Exception {
		try (InputStream in = f.createFileSource(path).getInputStream()) {
			return in.readAllBytes();
		}
	}

	@Test
	void renamingASymbolicLinkMovesTheLinkNotTheTarget() throws Exception {
		FileSource target = file("/d/target", (byte) 1, (byte) 2);
		FileSource link = f.createSymbolicLink(f.createFileSource("/d/ln"), target);
		assertTrue(link.renameTo(f.createFileSource("/d/ln2")));

		assertFalse(f.createFileSource("/d/ln").exists());
		FileSource moved = f.createFileSource("/d/ln2");
		assertEquals("target", moved.getLinkedTo().getName());
		assertArrayEquals(new byte[] {1, 2}, read("/d/ln2"));
		assertTrue(target.exists());
		assertNull(target.getLinkedTo());
		java.util.Set<String> names = new java.util.TreeSet<>(java.util.Arrays.asList(f.createFileSource("/d").list()));
		assertEquals(java.util.Set.of("target", "ln2"), names);
	}

	@Test
	void renamingAHardLinkLeavesTheFileAndKeepsSharing() throws Exception {
		FileSource owner = file("/d/owner", (byte) 7);
		FileSource hard = f.createLink(f.createFileSource("/d/hard"), owner);
		assertTrue(hard.renameTo(f.createFileSource("/d/hard2")));

		assertFalse(f.createFileSource("/d/hard").exists());
		assertArrayEquals(new byte[] {7}, read("/d/hard2"));
		try (OutputStream out = f.createFileSource("/d/hard2").getOutputStream()) {
			out.write(new byte[] {8, 9});
		}
		assertArrayEquals(new byte[] {8, 9}, read("/d/owner"));
		// and the file outlives its first name
		assertTrue(f.createFileSource("/d/owner").delete());
		assertArrayEquals(new byte[] {8, 9}, read("/d/hard2"));
	}

	@Test
	void renamingTheNameThatHoldsTheFileKeepsOtherNamesWorking() throws Exception {
		FileSource owner = file("/d/owner", (byte) 4, (byte) 5, (byte) 6);
		f.createLink(f.createFileSource("/d/h1"), owner);
		f.createLink(f.createFileSource("/d/h2"), owner);

		assertTrue(f.createFileSource("/d/owner").renameTo(f.createFileSource("/d/renamed")));

		assertFalse(f.createFileSource("/d/owner").exists());
		for(String n : new String[] {"/d/renamed", "/d/h1", "/d/h2"}) {
			assertArrayEquals(new byte[] {4, 5, 6}, read(n), n);
		}
		// a write through any name is seen by all
		try (OutputStream out = f.createFileSource("/d/h1").getOutputStream()) {
			out.write(new byte[] {1});
		}
		for(String n : new String[] {"/d/renamed", "/d/h1", "/d/h2"}) {
			assertArrayEquals(new byte[] {1}, read(n), n);
		}
		// removing names one by one leaves the last
		assertTrue(f.createFileSource("/d/renamed").delete());
		assertTrue(f.createFileSource("/d/h1").delete());
		assertArrayEquals(new byte[] {1}, read("/d/h2"));
		assertEquals(1, f.createFileSource("/d/h2").length());
	}

	@Test
	void aDirectoryWithALinkInItIsNotEmpty() throws Exception {
		assertTrue(f.createFileSource("/d/sub").mkdir());
		f.createSymbolicLink(f.createFileSource("/d/sub/dangling"), f.createFileSource("/d/nothing"));
		assertFalse(f.createFileSource("/d/sub").delete());
		assertTrue(f.createFileSource("/d/sub/dangling").delete());
		assertTrue(f.createFileSource("/d/sub").delete());
	}
}
