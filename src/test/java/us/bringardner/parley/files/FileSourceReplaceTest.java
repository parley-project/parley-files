package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

public class FileSourceReplaceTest {

	private static FileSource folder() throws IOException {
		MemoryFileSourceFactory memory = new MemoryFileSourceFactory();
		memory.connect();
		FileSource dir = memory.createFileSource("/d");
		assertTrue(dir.mkdirs());
		return dir;
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void replacesTheContentsAndLeavesNothingBeside() throws IOException {
		FileSource dir = folder();
		FileSource f = dir.getChild("a.txt");
		write(f, "old");
		assertTrue(FileSourceReplace.isOneStep(f) == f.getCapabilities().atomicReplace());
		FileSourceReplace.replace(f, "new".getBytes(StandardCharsets.UTF_8));
		assertEquals("new", read(f));
		assertEquals(1, dir.listFiles().length);
	}

	@Test
	public void aFailedWriteLeavesTheOldFileAndNothingBeside() throws IOException {
		FileSource dir = folder();
		FileSource f = dir.getChild("b.txt");
		write(f, "old");
		boolean oneStep = FileSourceReplace.isOneStep(f);
		assertThrows(IOException.class, ()->FileSourceReplace.replace(f, out->{
			out.write('x');
			throw new IOException("disk full");
		}));
		if( oneStep ) {
			assertEquals("old", read(f));
		}
		assertEquals(1, dir.listFiles().length);
	}

	@Test
	public void aNewFileIsWrittenWhereItIs() throws IOException {
		FileSource dir = folder();
		FileSource f = dir.getChild("c.txt");
		assertFalse(f.exists());
		assertFalse(FileSourceReplace.isOneStep(f));
		FileSourceReplace.replace(f, "made".getBytes(StandardCharsets.UTF_8));
		assertEquals("made", read(f));
		assertEquals(1, dir.listFiles().length);
	}

	@Test
	public void copiesFromAnotherFileSource() throws IOException {
		FileSource dir = folder();
		FileSource from = dir.getChild("from.txt");
		FileSource to = dir.getChild("to.txt");
		write(from, "0123456789");
		write(to, "x");
		assertEquals(10, FileSourceReplace.replace(to, from));
		assertEquals("0123456789", read(to));
		assertEquals(2, dir.listFiles().length);
	}
}
