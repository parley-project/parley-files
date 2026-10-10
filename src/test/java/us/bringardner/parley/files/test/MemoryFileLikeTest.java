package us.bringardner.parley.files.test;

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
}
