package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/** getParent() returns the parent's path, as java.io.File does, for every implementation. */
public class GetParentTests {

	@ParameterizedTest
	@ValueSource(strings = { "fileproxy", "memory" })
	public void parentIsAPath(String type) throws IOException {
		FileSourceFactory factory = FileSourceFactory.getFileSourceFactory(type);
		factory.connect();
		//  A virtual file system has paths of its own: a host path (C:\Users\x) isn't one on Windows
		boolean memory = type.equals("memory");
		File tmp = memory ? null : Files.createTempDirectory("parent").toFile();
		String base = memory ? "/parent" + System.nanoTime() : tmp.getAbsolutePath();
		try {
			FileSource dir = factory.createFileSource(base);
			dir.mkdirs();
			FileSource child = dir.getChild("sub").getChild("x.txt");
			String expected = memory ? base + "/sub" : new File(new File(tmp, "sub"), "x.txt").getParent();
			assertEquals(expected, child.getParent());
			assertEquals(child.getParentFile().getAbsolutePath(), child.getParent());
			assertEquals(memory ? "/" : tmp.getParent(), dir.getParent());
			FileSource root = factory.listRoots()[0];
			assertNull(root.getParent());
		} finally {
			if( tmp != null ) {
				tmp.delete();
			}
		}
	}
}
