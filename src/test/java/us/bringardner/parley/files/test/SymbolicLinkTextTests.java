package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.files.tree.VirtualFileSourceFactory;

/** A symbolic link keeps the exact text it was made with: createSymbolicLink(link, text) and getLinkText(). */
public class SymbolicLinkTextTests {

	private void roundTrip(FileSourceFactory f, FileSource dir) throws IOException {
		FileSource target = dir.getChild("real.txt");
		try (java.io.OutputStream out = target.getOutputStream()) {
			out.write("data".getBytes());
		}

		// relative text stays relative, and the link follows it
		FileSource rel = f.createSymbolicLink(dir.getChild("rel"), "real.txt");
		assertEquals("real.txt", rel.getLinkText());
		assertEquals(4, dir.getChild("rel").contentLength());
		assertEquals("real.txt".length(), dir.getChild("rel").length());

		// text that goes up and comes back down
		assertTrue(dir.getChild("sub").mkdirs());
		FileSource up = f.createSymbolicLink(dir.getChild("sub").getChild("up"), "../real.txt");
		assertEquals("../real.txt", up.getLinkText());
		assertEquals(4, dir.getChild("sub").getChild("up").contentLength());
		assertEquals("../real.txt".length(), dir.getChild("sub").getChild("up").length());

		// text that names nothing
		FileSource dangling = f.createSymbolicLink(dir.getChild("dangling"), "no/such/place");
		assertEquals("no/such/place", dangling.getLinkText());
		assertFalse(dir.getChild("dangling").getChild("x").exists());

		// absolute text
		FileSource abs = f.createSymbolicLink(dir.getChild("abs"), target.getAbsolutePath());
		assertEquals(target.getAbsolutePath(), abs.getLinkText());

		// not a link, or a link already there
		assertNull(target.getLinkText());
		assertNull(dir.getChild("sub").getLinkText());
		assertThrows(IOException.class, () -> f.createSymbolicLink(dir.getChild("rel"), "other"));
	}

	@Test
	public void localFiles(@TempDir Path tmp) throws Exception {
		Path probe = tmp.resolve("probe");
		try {
			Files.createSymbolicLink(probe, tmp);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "no symbolic links here: " + e);
		}
		Files.delete(probe);
		FileProxyFactory f = new FileProxyFactory();
		roundTrip(f, f.createFileSource(tmp.toString()));
		// the operating system holds the same text
		assertEquals("../real.txt", Files.readSymbolicLink(tmp.resolve("sub").resolve("up")).toString());
	}

	@Test
	public void memoryFiles() throws Exception {
		MemoryFileSourceFactory f = new MemoryFileSourceFactory();
		f.connect(new Properties());
		try {
			FileSource dir = f.createFileSource("/dir");
			assertTrue(dir.mkdirs());
			roundTrip(f, dir);
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aFactoryThatCantKeepTheTextRefuses() throws Exception {
		MemoryFileSourceFactory m = new MemoryFileSourceFactory();
		m.connect(new Properties());
		try {
			VirtualFileSourceFactory v = new VirtualFileSourceFactory();
			assertThrows(IOException.class, () -> v.createSymbolicLink(m.createFileSource("/x"), "y"));
		} finally {
			m.disConnect();
		}
	}
}
