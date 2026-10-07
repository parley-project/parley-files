package us.bringardner.parley.files.test;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.memory.MemoryFileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.files.memory.MemoryRandomAccessIoController;


public class MemoryRandomAccessIoBufferTests extends FileSourceRandomAccessIoBufferTests {

	@BeforeAll
	public static void setup() throws IOException {
		factory = new MemoryFileSourceFactory();
		remoteTestFileDirPath = "target/UnitTests";

		if(!factory.connect(new Properties())) {
			throw new IOException("Can't connect");
		}
	}

	@Override
	protected IRandomAccessIoController getRandomAccessFileStream(FileSource file) throws IOException {
		return new MemoryRandomAccessIoController((MemoryFileSource) file);
	}
}
