package us.bringardner.parley.files.test;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.memory.MemoryFileSourceFactory;


public class MemoryNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setup() throws IOException {
		localTestFileDirPath = testFilesDir(MemoryNioProviderTests.class);
		remoteTestFileDirPath = "/NioProviderTests";
		localCacheDirPath = scratchDir("MemoryNioProviderTestsCache");
		factory = new MemoryFileSourceFactory();

		if(!factory.connect(new Properties())) {
			throw new IOException("Can't connect");
		}
	}
}
