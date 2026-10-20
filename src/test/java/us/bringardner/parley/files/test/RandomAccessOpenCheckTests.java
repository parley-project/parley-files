package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * FileSourceRandomAccessStream checks the file from canRead()/canWrite() unless the controller says it
 * checked the file itself when it opened it (checksAccessOnOpen()); the mode is checked either way.
 */
public class RandomAccessOpenCheckTests {

	/** Holds nothing; it only says whether it checked the file. */
	private static final class Controller implements IRandomAccessIoController {
		private final FileSource file;
		private final boolean checked;

		Controller(FileSource file, boolean checked) {
			this.file = file;
			this.checked = checked;
		}

		@Override
		public boolean checksAccessOnOpen() {
			return checked;
		}

		@Override
		public int read(long position) {
			return -1;
		}

		@Override
		public void write(long position, byte value) {
		}

		@Override
		public long length() {
			return 0;
		}

		@Override
		public void setLength(long newLength) {
		}

		@Override
		public void save() {
		}

		@Override
		public FileSource getFile() {
			return file;
		}

		@Override
		public void close() {
		}
	}

	private static FileSource readOnlyFile() throws IOException {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		factory.connect();
		FileSource file = factory.createFileSource("/readOnly.bin");
		try (OutputStream out = file.getOutputStream()) {
			out.write(1);
		}
		assertTrue(file.setReadOnly());
		assertFalse(file.canWrite());
		return file;
	}

	@Test
	void theStreamChecksByDefault() throws Exception {
		FileSource file = readOnlyFile();
		assertFalse(new Controller(file, false).checksAccessOnOpen());
		assertThrows(FileNotFoundException.class, () -> new FileSourceRandomAccessStream(new Controller(file, false), "rw"));
		new FileSourceRandomAccessStream(new Controller(file, false), "r").close();
	}

	@Test
	void notAgainWhenTheControllerChecked() throws Exception {
		FileSource file = readOnlyFile();
		try (FileSourceRandomAccessStream s = new FileSourceRandomAccessStream(new Controller(file, true), "rw")) {
			assertEquals(0, s.length());
		}
		// a missing file isn't made by the stream either: that was the controller's to do
		FileSource missing = file.getFileSourceFactory().createFileSource("/missing.bin");
		new FileSourceRandomAccessStream(new Controller(missing, true), "rw").close();
		assertFalse(missing.exists());
	}

	@Test
	void theModeIsCheckedEitherWay() throws Exception {
		FileSource file = readOnlyFile();
		for(boolean checked : new boolean[] {false, true}) {
			assertThrows(IllegalArgumentException.class, () -> new FileSourceRandomAccessStream(new Controller(file, checked), "x"));
		}
		try (FileSourceRandomAccessStream s = new FileSourceRandomAccessStream(new Controller(file, true), "r")) {
			assertThrows(IOException.class, () -> s.write(1), "read only");
		}
	}
}
