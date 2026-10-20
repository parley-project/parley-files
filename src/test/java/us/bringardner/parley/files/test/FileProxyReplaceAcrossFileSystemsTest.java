package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * A replace from another file system (here the temp directory and the build directory, where
 * they are on different ones, as on a Mac with the project on another volume): the target is
 * replaced through a copy next to it, never left missing or half written.
 */
public class FileProxyReplaceAcrossFileSystemsTest {

	@Test
	void aReplaceFromAnotherFileSystem() throws Exception {
		Path here = Files.createDirectories(new File("target/replace-across").getAbsoluteFile().toPath());
		Path there = Files.createTempDirectory("replace-across");
		Assumptions.assumeFalse(Files.getFileStore(here).equals(Files.getFileStore(there)),
				"the temp and build directories are on one file system here");
		Path target = here.resolve("target.txt");
		Path source = there.resolve("source.txt");
		Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
		Files.write(source, "new".getBytes(StandardCharsets.UTF_8));
		FileSourceFactory f = FileSourceFactory.getDefaultFactory();
		FileSource from = f.createFileSource(source.toString());
		FileSource to = f.createFileSource(target.toString());
		assertTrue(from.renameTo(to, true));
		assertEquals("new", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
		assertFalse(Files.exists(source), "the original is gone");
		try (java.util.stream.Stream<Path> s = Files.list(here)) {
			assertEquals(1, s.count(), "no copy left next to it");
		}
	}
}
