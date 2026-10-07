package us.bringardner.parley.files.test;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.fileproxy.FileProxyRandomAccessIoController;


public class FileProxyRandomAccessIoBufferTests extends FileSourceRandomAccessIoBufferTests {

	@BeforeAll
	public static void setup() throws IOException {
		factory = new FileProxyFactory();
		remoteTestFileDirPath = "target/UnitTests";

		if(!factory.connect(new Properties())) {
			throw new IOException("Can't connect");
		}
	}

	@Override
	protected IRandomAccessIoController getRandomAccessFileStream(FileSource file) throws IOException {
		return new FileProxyRandomAccessIoController((FileProxy) file, "rw");
	}
}
