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
 */

package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.NotDirectoryException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSourceWatchEvent.Kind;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;
import us.bringardner.parley.files.watch.PollingWatchService;

/** Watching a folder for entries added and removed (D94). The memory provider stands in for any. */
public class FileSourceWatchTest {

	private static final Duration FAST = Duration.ofMillis(50);

	private MemoryFileSourceFactory mem;
	private FileSource dir;
	private FileSourceWatchService service;

	@BeforeEach
	public void setUp() throws Exception {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		dir = mem.createFileSource("/watched");
		assertTrue(dir.mkdirs());
		service = FileSourceWatchService.polling(FAST);
	}

	@AfterEach
	public void tearDown() throws Exception {
		service.close();
		mem.disConnect();
	}

	private FileSource child(String name) throws IOException {
		return mem.createFileSource("/watched/"+name);
	}

	private static String show(List<FileSourceWatchEvent> events) {
		return events.toString();
	}

	/** the events of the next key, waiting up to 5 seconds */
	private List<FileSourceWatchEvent> next() throws Exception {
		FileSourceWatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertNotNull(k, "no events came");
		List<FileSourceWatchEvent> ret = k.pollEvents();
		assertTrue(k.reset());
		return ret;
	}

	@Test
	public void createDeleteAndRenameAreTold() throws Exception {
		FileSourceWatchKey key = dir.register(service);
		assertSame(dir, key.folder());
		assertTrue(child("a").createNewFile());
		assertEquals("[ENTRY_CREATE a]", show(next()));
		assertTrue(child("a").renameTo(child("b")));
		assertEquals("[ENTRY_DELETE a, ENTRY_CREATE b]", show(next()));
		assertTrue(child("b").delete());
		assertEquals("[ENTRY_DELETE b]", show(next()));
		assertTrue(child("sub").mkdir());
		assertEquals("[ENTRY_CREATE sub]", show(next()));
	}

	@Test
	public void nothingChangedNothingIsTold() throws Exception {
		dir.register(service);
		assertNull(service.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void entriesThereAtRegistrationAreNotEvents() throws Exception {
		assertTrue(child("old").createNewFile());
		dir.register(service);
		assertNull(service.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void aKeyIsGivenOnceUntilItIsReset() throws Exception {
		FileSourceWatchKey key = dir.register(service);
		assertTrue(child("a").createNewFile());
		FileSourceWatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertSame(key, k);
		// not reset: the next change is kept, but the key isn't queued again
		assertTrue(child("b").createNewFile());
		assertNull(service.poll(400, TimeUnit.MILLISECONDS));
		assertEquals("[ENTRY_CREATE a, ENTRY_CREATE b]", show(k.pollEvents()));
		assertTrue(child("c").createNewFile());
		Thread.sleep(300);
		assertTrue(k.reset());
		assertSame(key, service.poll(5, TimeUnit.SECONDS));
		assertEquals("[ENTRY_CREATE c]", show(k.pollEvents()));
	}

	@Test
	public void onlyTheKindsAskedForAreTold() throws Exception {
		assertTrue(child("x").createNewFile());
		dir.register(service, Kind.ENTRY_DELETE);
		assertTrue(child("y").createNewFile());
		assertTrue(child("x").delete());
		assertEquals("[ENTRY_DELETE x]", show(next()));
	}

	@Test
	public void registeringAgainKeepsTheKeyAndChangesTheKinds() throws Exception {
		FileSourceWatchKey a = dir.register(service, Kind.ENTRY_DELETE);
		FileSourceWatchKey b = dir.register(service, Kind.ENTRY_CREATE);
		assertSame(a, b);
		assertTrue(child("a").createNewFile());
		assertEquals("[ENTRY_CREATE a]", show(next()));
	}

	@Test
	public void tooManyEventsBecomeAnOverflow() throws Exception {
		dir.register(service);
		int n = PollingWatchService.MAX_QUEUED+50;
		for (int i = 0; i < n; i++) {
			assertTrue(child("f"+i).createNewFile());
		}
		FileSourceWatchKey k = service.poll(5, TimeUnit.SECONDS);
		assertNotNull(k);
		assertEquals("[OVERFLOW]", show(k.pollEvents()));
	}

	@Test
	public void aFolderThatIsGoneEndsTheKey() throws Exception {
		FileSourceWatchKey key = dir.register(service);
		assertTrue(dir.delete());
		long end = System.currentTimeMillis()+5000;
		while( key.isValid() && System.currentTimeMillis() < end ) {
			Thread.sleep(50);
		}
		assertFalse(key.isValid());
		assertFalse(key.reset());
	}

	@Test
	public void cancellingStopsTheEvents() throws Exception {
		FileSourceWatchKey key = dir.register(service);
		key.cancel();
		assertFalse(key.isValid());
		assertTrue(child("a").createNewFile());
		assertNull(service.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	public void aFileIsNotAFolder() throws Exception {
		FileSource f = child("file");
		assertTrue(f.createNewFile());
		assertThrows(NotDirectoryException.class, () -> f.register(service));
	}

	@Test
	public void closingWakesWhoWaitsAndRefusesMore() throws Exception {
		Thread closer = new Thread(() -> {
			try {
				Thread.sleep(200);
				service.close();
			} catch (Exception e) {
				// the test fails on take
			}
		});
		closer.start();
		assertThrows(ClosedWatchServiceException.class, () -> service.take());
		assertThrows(ClosedWatchServiceException.class, () -> service.poll());
		assertThrows(ClosedWatchServiceException.class, () -> dir.register(service));
		closer.join(5000);
	}

	@Test
	public void theThreadEndsWithTheService() throws Exception {
		dir.register(service);
		assertTrue(watchThreads() > 0);
		service.close();
		long end = System.currentTimeMillis()+5000;
		while( watchThreads() > 0 && System.currentTimeMillis() < end ) {
			Thread.sleep(20);
		}
		assertEquals(0, watchThreads());
	}

	private static long watchThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("FileSourceWatch") && t.isAlive()).count();
	}

	@Test
	public void aStampThatHasNotMovedSavesTheListing() throws Exception {
		AtomicInteger stamp = new AtomicInteger();
		AtomicInteger asked = new AtomicInteger();
		try (FileSourceWatchService s = new PollingWatchService(FAST) {
			@Override
			protected Object changeStamp(FileSource folder) {
				asked.incrementAndGet();
				return stamp.get();
			}
		}) {
			dir.register(s);
			assertTrue(child("a").createNewFile());
			// the file is there but the stamp says nothing changed: no listing, no event
			assertNull(s.poll(400, TimeUnit.MILLISECONDS));
			assertTrue(asked.get() > 1);
			stamp.incrementAndGet();
			FileSourceWatchKey k = s.poll(5, TimeUnit.SECONDS);
			assertNotNull(k);
			assertEquals("[ENTRY_CREATE a]", show(k.pollEvents()));
		}
	}

	@Test
	public void aFactoryGivesAPollingServiceWithTheConfiguredInterval() throws Exception {
		String saved = System.getProperty(PollingWatchService.PROPERTY_POLL_INTERVAL);
		System.setProperty(PollingWatchService.PROPERTY_POLL_INTERVAL, "300");
		try (FileSourceWatchService s = mem.newWatchService()) {
			assertEquals(Duration.ofMillis(300), ((PollingWatchService) s).getPollInterval());
		} finally {
			if( saved == null ) {
				System.clearProperty(PollingWatchService.PROPERTY_POLL_INTERVAL);
			} else {
				System.setProperty(PollingWatchService.PROPERTY_POLL_INTERVAL, saved);
			}
		}
		try (FileSourceWatchService s = FileSourceWatchService.polling(Duration.ofMillis(1))) {
			assertEquals(Duration.ofMillis(PollingWatchService.MIN_POLL_INTERVAL), ((PollingWatchService) s).getPollInterval());
		}
	}

	@Test
	public void aWrappedFolderCanBeWatched() throws Exception {
		FileSource wrapped = new DelegatingFileSource(dir);
		wrapped.register(service);
		assertTrue(child("w").createNewFile());
		assertEquals("[ENTRY_CREATE w]", show(next()));
		assertEquals(Arrays.asList("w"), Arrays.asList(wrapped.list()));
	}
}
