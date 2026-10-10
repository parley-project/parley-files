package us.bringardner.parley.files.test;

import java.nio.file.Files;
import java.nio.file.Path;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/** The local file system through FileProxy acts like a java.io.File (it is one underneath). */
public class LocalFileLikeTest extends FileLikeBehaviorTests {

	private Path root;

	@Override
	protected void newTree() throws Exception {
		root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "filelike-local");
	}

	@Override
	protected FileSource sourceFor(String relative) throws Exception {
		return FileSourceFactory.getDefaultFactory().createFileSource(root.resolve(relative).toString());
	}
}
