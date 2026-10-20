package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The in-memory file system acts like a java.io.File. */
public class MemoryFileLikeTest extends FileLikeBehaviorTests {

	private MemoryFileSourceFactory factory;

	@Override
	protected void newTree() {
		factory = new MemoryFileSourceFactory();
	}

	@Override
	protected FileSource sourceFor(String relative) throws Exception {
		return factory.createFileSource("/" + relative);
	}

	/** A replace into another memory file system fails, and the file there is kept. */
	@Test
	void aReplaceIntoAnotherMemoryTreeKeepsTheDestination() throws Exception {
		FileSource from = factory.createFileSource("/cross-from");
		MemoryFileSourceFactory other = new MemoryFileSourceFactory();
		FileSource to = other.createFileSource("/cross-to");
		try (OutputStream out = from.getOutputStream()) {
			out.write("new".getBytes());
		}
		try (OutputStream out = to.getOutputStream()) {
			out.write("old".getBytes());
		}
		assertFalse(from.renameTo(to, true), "another tree");
		try (InputStream in = other.createFileSource("/cross-to").getInputStream()) {
			assertArrayEquals("old".getBytes(), in.readAllBytes(), "the destination is kept");
		}
	}
}
