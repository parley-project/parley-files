/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */

package us.bringardner.parley.files.fileproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceWatchEvent;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.files.FileSourceWatchService;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Local folders are watched by what the operating system says (D99): a change is told long before the slow poll that
 * stays as a net. Where the JDK only polls (macOS) there is no such service and these tests are skipped.
 */
public class NativeWatchTest {

	private String saved;
	private Path dir;
	private FileProxyFactory factory;
	private FileSource folder;
	private FileSourceWatchService service;

	@BeforeEach
	public void setUp() throws Exception {
		saved = System.getProperty(NativeWatchService.PROPERTY_NATIVE);
		System.clearProperty(NativeWatchService.PROPERTY_NATIVE);
		Assumptions.assumeTrue(NativeWatchService.isAvailable(), "the JDK's watch service only polls here");
		dir = Files.createTempDirectory("native-watch");
		factory = new FileProxyFactory();
		factory.connect(new Properties());
		folder = factory.createFileSource(dir.toString());
		service = factory.newWatchService();
	}

	@AfterEach
	public void tearDown() throws Exception {
		if( service != null ) {
			service.close();
		}
		if( dir != null ) {
			File[] kids = dir.toFile().listFiles();
			if( kids != null ) {
				for (File k : kids) {
					k.delete();
				}
			}
			Files.deleteIfExists(dir);
		}
		if( saved == null ) {
			System.clearProperty(NativeWatchService.PROPERTY_NATIVE);
		} else {
			System.setProperty(NativeWatchService.PROPERTY_NATIVE, saved);
		}
	}

	/** the events of the next key, which must come in far less than the 30 seconds of the net */
	private List<String> next(FileSourceWatchKey key) throws Exception {
		FileSourceWatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertNotNull(k, "the system's change was not told in 5 seconds");
		assertEquals(key, k);
		List<String> ret = new ArrayList<String>();
		for (FileSourceWatchEvent e : k.pollEvents()) {
			ret.add(e.toString());
		}
		assertTrue(k.reset());
		return ret;
	}

	@Test
	public void theFactoryMakesTheNativeService() throws Exception {
		assertInstanceOf(NativeWatchService.class, service);
	}

	@Test
	public void createRenameAndDeleteAreToldAtOnce() throws Exception {
		FileSourceWatchKey key = service.register(folder);

		assertTrue(new File(dir.toFile(), "a").createNewFile());
		assertEquals("[ENTRY_CREATE a]", next(key).toString());

		assertTrue(new File(dir.toFile(), "a").renameTo(new File(dir.toFile(), "b")));
		List<String> got = next(key);
		while( got.size() < 2 ) {
			got.addAll(next(key));
		}
		assertTrue(got.contains("ENTRY_DELETE a") && got.contains("ENTRY_CREATE b"), got.toString());

		assertTrue(new File(dir.toFile(), "b").delete());
		assertEquals("[ENTRY_DELETE b]", next(key).toString());
	}

	@Test
	public void whatIsNotALocalFolderOrNotAFolderIsRefused() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		try {
			FileSource m = mem.createFileSource("/x");
			assertTrue(m.mkdirs());
			assertThrows(IllegalArgumentException.class, () -> service.register(m));
		} finally {
			mem.disConnect();
		}
		File f = new File(dir.toFile(), "file");
		assertTrue(f.createNewFile());
		assertThrows(java.nio.file.NotDirectoryException.class, () -> service.register(factory.createFileSource(f.getPath())));
	}

	@Test
	public void theSettingTurnsItOff() throws Exception {
		System.setProperty(NativeWatchService.PROPERTY_NATIVE, "false");
		try (FileSourceWatchService polling = factory.newWatchService()) {
			assertFalse(polling instanceof NativeWatchService);
		}
	}

	@Test
	public void aCancelledKeyIsNotTold() throws Exception {
		FileSourceWatchKey key = service.register(folder);
		key.cancel();
		assertFalse(key.isValid());
		assertTrue(new File(dir.toFile(), "late").createNewFile());
		assertNull(service.poll(500, TimeUnit.MILLISECONDS));
	}
}
