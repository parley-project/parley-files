package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The in-memory file system has the default locks: PROCESS scope. */
public class MemoryLockTests extends AbstractLockTests {

	private static final AtomicInteger COUNT = new AtomicInteger();

	@Override
	protected FileSource newLockDirectory() throws IOException {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource d = factory.createFileSource("/locks" + COUNT.incrementAndGet());
		d.mkdirs();
		return d;
	}

	/** Each memory factory is its own file system: the same path in two of them is two files. */
	@Test
	public void separateFileSystemsDontContend() throws IOException {
		MemoryFileSourceFactory a = new MemoryFileSourceFactory();
		MemoryFileSourceFactory b = new MemoryFileSourceFactory();
		a.connect();
		b.connect();
		FileSource fa = a.createFileSource("/same");
		FileSource fb = b.createFileSource("/same");
		writeBytes(fa, new byte[0]);
		writeBytes(fb, new byte[0]);
		try (FileSourceLock la = fa.tryLock();
				FileSourceLock lb = fb.tryLock()) {
			assertNotNull(la);
			assertNotNull(lb);
		}
	}
}
