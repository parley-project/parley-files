package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Regression tests for MemoryFileSource growth and delete() (review section 2):
 * every path that was merely looked up stayed in the tree forever, and
 * delete() never unlinked anything, always returned true and would delete a
 * non-empty directory.
 */
public class MemoryFileSourceGrowthTests {

	private MemoryFileSourceFactory factory;

	@BeforeEach
	public void setup() throws IOException {
		factory = new MemoryFileSourceFactory();
		factory.connect();
		assertTrue(factory.createFileSource("/dir").mkdirs());
	}

	private FileSource write(String path, String content) throws IOException {
		FileSource f = factory.createFileSource(path);
		try (OutputStream out = f.getOutputStream()) {
			out.write(content.getBytes("UTF-8"));
		}
		return f;
	}

	private static boolean collected(WeakReference<?> ref) {
		for (int i = 0; i < 20 && ref.get() != null; i++) {
			System.gc();
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ref.get() == null;
	}

	@Test
	public void lookupsOfMissingPathsAreNotRetained() throws IOException {
		WeakReference<FileSource> ref = new WeakReference<>(factory.createFileSource("/dir/missing/deeper"));
		for (int i = 0; i < 10_000; i++) {
			factory.createFileSource("/dir/probe-"+i).exists();
		}

		assertTrue(collected(ref), "looked-up node was released");
		assertArrayEquals(new String[0], factory.createFileSource("/dir").list());
	}

	@Test
	public void sameInstanceWhileHeld() throws IOException {
		FileSource a = factory.createFileSource("/dir/x/y");
		FileSource b = factory.createFileSource("/dir/x/y");
		assertSame(a, b);

		assertTrue(a.getParentFile().mkdirs());
		try (OutputStream out = a.getOutputStream()) {
			out.write(1);
		}
		assertTrue(b.isFile());
	}

	@Test
	public void existingFilesAreRetained() throws IOException {
		write("/dir/kept.txt", "kept");
		assertTrue(collected(new WeakReference<>(new Object())));   // force a GC or two
		FileSource f = factory.createFileSource("/dir/kept.txt");
		assertTrue(f.isFile());
		assertEquals(4, f.length());
	}

	/**
	 * Writing below a directory that was never created fails, as a FileOutputStream does, and leaves
	 * nothing behind; once the directories are made the file is kept.
	 */
	@Test
	public void fileUnderUncreatedDirectoryIsRefusedThenRetainedOnceCreated() throws IOException {
		FileSource f = factory.createFileSource("/orphan/sub/f.txt");
		assertThrows(java.io.FileNotFoundException.class, () -> f.getOutputStream().close());
		assertThrows(java.io.FileNotFoundException.class, () -> f.getOutputStream(true).close());
		assertFalse(f.exists());

		assertTrue(factory.createFileSource("/orphan/sub").mkdirs());
		write("/orphan/sub/f.txt", "data");
		assertTrue(collected(new WeakReference<>(new Object())));
		FileSource again = factory.createFileSource("/orphan/sub/f.txt");
		assertTrue(again.isFile());
		try (InputStream in = again.getInputStream()) {
			assertEquals("data", new String(in.readAllBytes(), "UTF-8"));
		}
	}

	@Test
	public void deleteUnlinksTheNode() throws IOException {
		FileSource f = write("/dir/gone.txt", "x");
		assertTrue(f.delete());
		assertFalse(f.exists());
		assertArrayEquals(new String[0], factory.createFileSource("/dir").list());

		WeakReference<FileSource> ref = new WeakReference<>(f);
		f = null;
		assertTrue(collected(ref), "deleted node was released");
		assertNull(ref.get());
	}

	@Test
	public void deleteMissingFileReturnsFalse() throws IOException {
		assertFalse(factory.createFileSource("/dir/never").delete());
	}

	@Test
	public void deleteNonEmptyDirectoryReturnsFalse() throws IOException {
		write("/dir/child.txt", "x");
		FileSource dir = factory.createFileSource("/dir");
		assertFalse(dir.delete(), "non-empty");
		assertTrue(dir.isDirectory());

		assertTrue(factory.createFileSource("/dir/child.txt").delete());
		assertTrue(dir.delete(), "now empty");
		assertFalse(dir.exists());
	}
}
