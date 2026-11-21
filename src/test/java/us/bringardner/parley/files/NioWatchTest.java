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

package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.java.file.FileSourcePath;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.files.watch.PollingWatchService;

/** Watching a FileSource folder through java.nio.file: Path.register and FileSystem.newWatchService (D94). */
public class NioWatchTest {

	private String savedInterval;
	private MemoryFileSourceFactory mem;
	private FileSource dir;
	private Path path;
	private WatchService service;

	@BeforeEach
	public void setUp() throws Exception {
		savedInterval = System.getProperty(PollingWatchService.PROPERTY_POLL_INTERVAL);
		System.setProperty(PollingWatchService.PROPERTY_POLL_INTERVAL, "50");
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		dir = mem.createFileSource("/nio-watched");
		assertTrue(dir.mkdirs());
		path = new FileSourcePath(dir);
		service = path.getFileSystem().newWatchService();
	}

	@AfterEach
	public void tearDown() throws Exception {
		service.close();
		mem.disConnect();
		if( savedInterval == null ) {
			System.clearProperty(PollingWatchService.PROPERTY_POLL_INTERVAL);
		} else {
			System.setProperty(PollingWatchService.PROPERTY_POLL_INTERVAL, savedInterval);
		}
	}

	/** what the next key says, as "KIND name": it is reset for the next */
	private List<String> next(WatchKey expected) throws Exception {
		WatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertNotNull(k, "no events came");
		assertEquals(expected, k);
		List<String> ret = new ArrayList<String>();
		for (WatchEvent<?> e : k.pollEvents()) {
			assertEquals(1, e.count());
			ret.add(e.kind().name()+(e.context() == null ? "" : " "+e.context()));
		}
		assertTrue(k.reset());
		return ret;
	}

	@Test
	public void createAndDeleteAreToldWithTheNameAsAPath() throws Exception {
		WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
		assertEquals(path, key.watchable());
		assertTrue(key.isValid());

		assertTrue(mem.createFileSource("/nio-watched/a").createNewFile());
		assertEquals("[ENTRY_CREATE a]", next(key).toString());

		assertTrue(mem.createFileSource("/nio-watched/a").delete());
		assertEquals("[ENTRY_DELETE a]", next(key).toString());

		WatchEvent<?> created;
		assertTrue(mem.createFileSource("/nio-watched/b").createNewFile());
		WatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertNotNull(k);
		created = k.pollEvents().get(0);
		assertTrue(created.context() instanceof Path);
		assertEquals("b", created.context().toString());
	}

	@Test
	public void aKindThatWasNotAskedForIsNotToldAndModifyIsAcceptedButNeverToldOf() throws Exception {
		WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
		FileSource a = mem.createFileSource("/nio-watched/a");
		assertTrue(a.createNewFile());
		assertNull(service.poll(300, TimeUnit.MILLISECONDS), "a create was not asked for");
		assertTrue(a.delete());
		assertEquals("[ENTRY_DELETE a]", next(key).toString());
	}

	@Test
	public void whatCantBeRegisteredIsRefused() throws Exception {
		assertThrows(UnsupportedOperationException.class, () -> path.register(service, StandardWatchEventKinds.ENTRY_MODIFY));
		assertThrows(IllegalArgumentException.class, () -> path.register(service));
		FileSource file = mem.createFileSource("/nio-watched/f");
		assertTrue(file.createNewFile());
		assertThrows(NotDirectoryException.class, () -> new FileSourcePath(file).register(service, StandardWatchEventKinds.ENTRY_CREATE));
		try (WatchService other = FileSystems.getDefault().newWatchService()) {
			assertThrows(ProviderMismatchException.class, () -> path.register(other, StandardWatchEventKinds.ENTRY_CREATE));
		}
	}

	@Test
	public void aCancelledKeyIsNotValid() throws Exception {
		WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE);
		key.cancel();
		assertFalse(key.isValid());
		assertTrue(mem.createFileSource("/nio-watched/a").createNewFile());
		assertNull(service.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void aClosedServiceThrows() throws Exception {
		WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE);
		service.close();
		assertFalse(key.isValid());
		assertThrows(ClosedWatchServiceException.class, () -> service.poll());
		assertThrows(ClosedWatchServiceException.class, () -> service.take());
		assertThrows(ClosedWatchServiceException.class, () -> path.register(service, StandardWatchEventKinds.ENTRY_CREATE));
	}

	@Test
	public void theFactorysOwnServiceIsUsed() throws Exception {
		IOException[] seen = new IOException[1];
		FileSourceFactory f = new MemoryFileSourceFactory() {
			@Override
			public FileSourceWatchService newWatchService() throws IOException {
				seen[0] = new IOException("asked");
				return super.newWatchService();
			}
		};
		f.connect(new Properties());
		try (WatchService w = new FileSourcePath("/", f).getFileSystem().newWatchService()) {
			assertNotNull(seen[0]);
		} finally {
			f.disConnect();
		}
	}
}
