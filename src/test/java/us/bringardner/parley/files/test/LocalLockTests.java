package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
		try (FileSourceLock first = f.tryLock()) {
			assertNotNull(first);
			assertTrue(sub.getChild(FileProxyFactory.LOCK_DIRECTORY_NAME).mkdirs());
			assertThrows(OverlappingFileLockException.class, () -> f.tryLock(), "still held, though its lock file moved");
		}
	}

	/** Lock files are kept in the lock directory, never next to the locked path. */
	@Test
	public void lockFilesStayInTheLockDirectory() throws Exception {
		FileSource f = path("tidy");
		try (FileSourceLock l = f.tryLock()) {
			assertNotNull(l);
			String[] names = new File(dir.getCanonicalPath()).list();
			assertTrue(names != null && names.length == 1 && names[0].equals(FileProxyFactory.LOCK_DIRECTORY_NAME),
					"only the lock directory in " + java.util.Arrays.toString(names));
		}
	}
}
