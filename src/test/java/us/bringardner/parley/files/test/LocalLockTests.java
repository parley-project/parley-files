package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.lock.FileSourceLock;

/**
 * The local file system (FileProxy): HOST scope, so the cross-process cases run against a second
 * JVM. Each test's directory has its own {@value FileProxyFactory#LOCK_DIRECTORY_NAME}, as an
 * application's root would.
 */
public class LocalLockTests extends AbstractLockTests {

	@Override
	protected FileSource newLockDirectory() throws Exception {
		Path parent = Files.createDirectories(Path.of(FileSourceTestSupport.scratchDir("locks")));
		Path d = Files.createTempDirectory(parent, "local");
		Files.createDirectories(d.resolve(FileProxyFactory.LOCK_DIRECTORY_NAME));
		return FileSourceFactory.getDefaultFactory().createFileSource(d.toString());
	}

	@Override
	protected String workerSpec(FileSource source) throws Exception {
		return new File(source.getCanonicalPath()).toURI().toString();
	}

	@Override
	protected FileSource fromWorkerSpec(String spec) throws Exception {
		return FileSourceFactory.getDefaultFactory().createFileSource(new File(java.net.URI.create(spec)).getPath());
	}

	/**
	 * A lock directory created while a path is locked must not let a second holder in: within the
	 * JVM, locks are keyed by the path, whichever lock directory each request finds.
	 */
	@Test
	public void newLockDirectoryDoesNotSplitTheLock() throws Exception {
		FileSource sub = path("sub");
		assertTrue(sub.mkdirs());
		FileSource f = sub.getChild("x");
		writeBytes(f, new byte[0]);
		try (FileSourceLock first = f.tryLock()) {
			assertNotNull(first);
			assertTrue(sub.getChild(FileProxyFactory.LOCK_DIRECTORY_NAME).mkdirs());
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "still held, though its lock file moved");
		}
	}

	/** Lock files are kept in the lock directory, never next to the locked path. */
	@Test
	public void lockFilesStayInTheLockDirectory() throws Exception {
		FileSource f = file("tidy");
		try (FileSourceLock l = f.tryLock()) {
			assertNotNull(l);
			String[] names = new File(dir.getCanonicalPath()).list();
			java.util.Arrays.sort(names);
			assertTrue(java.util.Arrays.equals(names, new String[] {FileProxyFactory.LOCK_DIRECTORY_NAME, "tidy"}),
					"only the file and the lock directory in " + java.util.Arrays.toString(names));
		}
	}

	/** Two hard links are one file to the lock, in this JVM and in another, as they are to an OS lock. */
	@Test
	public void hardLinksAreOneFile() throws Exception {
		FileSource a = file("original");
		Path link = Path.of(path("link").getCanonicalPath());
		try {
			Files.createLink(link, Path.of(a.getCanonicalPath()));
		} catch (UnsupportedOperationException | IOException e) {
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "no hard links here: " + e);
		}
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null,
				"no file keys here (Windows): hard links are different files to the lock");
		FileSource b = path("link");
		try (FileSourceLock l = a.tryLock()) {
			assertNotNull(l);
			assertThrows(OverlappingFileLockException.class, () -> b.tryLock(), "the link is the same file, held by this JVM");
			Worker w = worker();
			assertEquals("BUSY", w.ask("lock " + workerSpec(b) + " X"), "and another process finds it locked through the link");
		}
	}

	/** A lock is on the file, not its name: renamed, the file keeps it. */
	@Test
	public void aRenamedFileKeepsItsLock() throws Exception {
		FileSource f = file("before");
		try (FileSourceLock l = f.tryLock()) {
			assertNotNull(l);
			FileSource after = path("after");
			assertTrue(f.renameTo(after));
			org.junit.jupiter.api.Assumptions.assumeTrue(Files.readAttributes(Path.of(after.getCanonicalPath()),
					java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null, "no file keys here (Windows)");
			assertThrows(OverlappingFileLockException.class, () -> after.tryLock(), "held under its new name");
			Worker w = worker();
			assertEquals("BUSY", w.ask("lock " + workerSpec(after) + " X"), "in another process too");
		}
	}
}
