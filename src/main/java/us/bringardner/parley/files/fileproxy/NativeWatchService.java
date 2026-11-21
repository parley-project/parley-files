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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import us.bringardner.parley.core.NamedExecutors;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceWatchEvent.Kind;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.files.watch.PollingWatchService;

/**
 * The watch service of local folders: the operating system says when a folder changed (inotify on Linux,
 * ReadDirectoryChangesW on Windows, through java.nio.file), and the folder is then listed and compared at once, so a
 * change is told in milliseconds, not at the next poll, and the keys, their events and OVERFLOW behave as those of
 * {@link PollingWatchService}. A slow poll ({@value #SAFETY_SECONDS} seconds) stays as a net under it, for the changes
 * the system loses.
 *
 * <p>Where the JDK's own watch service only polls (macOS: every 10 seconds), nothing is gained, so
 * {@link FileProxyFactory#newWatchService()} gives the polling service there ({@link #isAvailable()}). The system
 * property {@value #PROPERTY_NATIVE} = false turns this service off.
 *
 * @since 2.0.0
 */
class NativeWatchService extends PollingWatchService {

	static final String PROPERTY_NATIVE = "parley.files.watch.native";
	static final long SAFETY_SECONDS = 30;

	private final WatchService system;
	private final Map<WatchKey, FileSource> folders = new ConcurrentHashMap<WatchKey, FileSource>();

	/**
	 * @return whether the JDK tells of changes by itself on this system (it doesn't where it polls), and the property
	 *         allows it
	 */
	static boolean isAvailable() {
		if( !Boolean.parseBoolean(System.getProperty(PROPERTY_NATIVE, "true")) ) {
			return false;
		}
		try (WatchService w = FileSystems.getDefault().newWatchService()) {
			return !w.getClass().getSimpleName().contains("Polling");
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	NativeWatchService() throws IOException {
		super(Duration.ofSeconds(SAFETY_SECONDS));
		system = FileSystems.getDefault().newWatchService();
		NamedExecutors.start("FileSourceNativeWatch", this::told);
	}

	/** the system's events: only that something happened to a folder matters, the listing tells what */
	private void told() {
		try {
			while( true ) {
				WatchKey k = system.take();
				FileSource folder = folders.get(k);
				k.pollEvents();
				if( !k.reset() ) {
					folders.remove(k);
				}
				if( folder != null ) {
					lookNow(folder);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.nio.file.ClosedWatchServiceException e) {
			// closed
		}
	}

	@Override
	public FileSourceWatchKey register(FileSource folder, Kind... kinds) throws IOException {
		if( !(folder instanceof FileProxy) ) {
			throw new IllegalArgumentException("Not a local folder: "+folder);
		}
		FileSourceWatchKey ret = super.register(folder, kinds);
		Path where = ((FileProxy) folder).target.toPath();
		try {
			WatchKey k = where.register(system, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
					StandardWatchEventKinds.OVERFLOW);
			folders.put(k, folder);
		} catch (IOException | RuntimeException e) {
			ret.cancel();
			throw new IOException("The system can't watch "+where+" ("+e+"); its limit of watches may be reached", e);
		}
		return ret;
	}

	@Override
	public void close() {
		super.close();
		try {
			system.close();
		} catch (IOException e) {
			// closing
		}
		folders.clear();
	}
}
