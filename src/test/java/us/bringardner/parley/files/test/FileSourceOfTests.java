package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** FileSource.of(...), toFile() and FileSourceFactory.resolve(String) (To-Do 18.1, 18.4, 18.9) */
public class FileSourceOfTests {

	@TempDir
	Path dir;

	@Test
	public void ofFileIsALocalFileWhateverTheDefault() throws IOException {
		File f = dir.resolve("a.txt").toFile();
		FileSource fs = FileSource.of(f);
		assertTrue(fs instanceof FileProxy);
		assertSame(FileSourceFactory.fileProxyFactory, fs.getFileSourceFactory());
		assertEquals(f.getAbsolutePath(), fs.getAbsolutePath());
		assertEquals(f, fs.toFile());
		assertThrows(NullPointerException.class, () -> FileSource.of((File) null));
	}

	@Test
	public void ofFileMakesARelativeFileAbsolute() throws IOException {
		FileSource fs = FileSource.of(new File("rel.txt"));
		assertEquals(new File("rel.txt").getAbsolutePath(), fs.getAbsolutePath());
		assertTrue(fs.toFile().isAbsolute());
	}

	@Test
	public void toFileOnlyForALocalFile() throws IOException {
		FileSourceFactory memory = new MemoryFileSourceFactory();
		FileSource m = memory.resolve("/x/y.txt");
		assertThrows(UnsupportedOperationException.class, m::toFile);
	}

	@Test
	public void ofStringIsAPathOnTheDefaultFactory() throws IOException {
		String p = dir.resolve("b.txt").toString();
		FileSource fs = FileSource.of(p);
		assertSame(FileSourceFactory.getDefaultFactory(), fs.getFileSourceFactory());
		assertEquals(p, fs.getAbsolutePath());
		assertThrows(NullPointerException.class, () -> FileSource.of((String) null));
	}

	@Test
	public void ofParentAndChild() throws IOException {
		String p = dir.toString();
		assertEquals(dir.resolve("c.txt").toString(), FileSource.of(p, "c.txt").getAbsolutePath());
		assertEquals(dir.resolve("c.txt").toString(), FileSource.of(FileSource.of(p), "c.txt").getAbsolutePath());
		// a null parent means the child alone, as java.io.File
		String alone = dir.resolve("d.txt").toString();
		assertEquals(alone, FileSource.of((String) null, alone).getAbsolutePath());
		assertEquals(alone, FileSource.of((FileSource) null, alone).getAbsolutePath());
		assertThrows(NullPointerException.class, () -> FileSource.of(p, null));
		assertThrows(NullPointerException.class, () -> FileSource.of(FileSource.of(p), null));
	}

	@Test
	public void ofUri() throws IOException {
		File f = dir.resolve("e.txt").toFile();
		FileSource local = FileSource.of(f.toURI());
		assertTrue(local instanceof FileProxy);
		assertEquals(f.getAbsolutePath(), local.getAbsolutePath());

		FileSource m = FileSource.of(URI.create("filesource:/u/v.txt?sourcetype=memory"));
		assertTrue(m.getFileSourceFactory() instanceof MemoryFileSourceFactory);
		assertEquals("/u/v.txt", m.getAbsolutePath());

		assertThrows(IllegalArgumentException.class, () -> FileSource.of(URI.create("http://example.com/x")));
		assertThrows(IllegalArgumentException.class, () -> FileSource.of(URI.create("x/y")));
		assertThrows(NullPointerException.class, () -> FileSource.of((URI) null));
	}

	@Test
	public void resolveAPathOnThisFactory() throws IOException {
		FileSourceFactory memory = new MemoryFileSourceFactory();
		FileSource f = memory.resolve("  /etc/parley/irc.properties ");
		assertSame(memory, f.getFileSourceFactory());
		assertEquals("/etc/parley/irc.properties", f.getAbsolutePath());
		assertTrue(memory.isConnected());
		assertTrue(f.getParentFile().mkdirs());
		try (OutputStream out = f.getOutputStream()) {
			out.write('x');
		}
		assertEquals(1, memory.resolve("/etc/parley/irc.properties").length());
		// C:\... is a path, not a URL
		assertFalse(FileSourceFactory.isUrl("C:\\parley"));
		assertThrows(NullPointerException.class, () -> memory.resolve(null));
	}

	@Test
	public void resolveAUrlInAnyCase() throws IOException {
		FileSourceFactory local = FileSourceFactory.fileProxyFactory;
		for (String scheme : new String[] { "filesource:", "FileSource:", "FILESOURCE:" }) {
			FileSource f = local.resolve(scheme + "/w/z.txt?sourcetype=memory");
			assertTrue(f.getFileSourceFactory() instanceof MemoryFileSourceFactory, scheme);
			assertEquals("/w/z.txt", f.getAbsolutePath());
		}
	}

	@Test
	public void aUrlsQueryIsNotInTheMessage() {
		String url = "filesource:/m?sourcetype=noSuchFileSystem&password=s3cret";
		IOException e = assertThrows(IOException.class, () -> FileSourceFactory.fileProxyFactory.resolve(url));
		String msg = String.valueOf(e.getMessage());
		assertFalse(msg.contains("s3cret"), msg);
		assertEquals("filesource:/m?...", FileSourceFactory.safe(url));
		assertEquals("/a?b", FileSourceFactory.safe("/a?b"));
		assertTrue(FileSourceFactory.isUrl(" filesource:/x"));
	}
}
