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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import us.bringardner.parley.files.watch.PollingWatchService;

/**
 * Tells of the entries added to and removed from folders, as {@link java.nio.file.WatchService} does for a
 * {@link java.nio.file.Path}: register a folder with {@link FileSource#register(FileSourceWatchService, FileSourceWatchEvent.Kind...)}
 * and take the keys that come back with events.
 *
 * <pre>
 * try (FileSourceWatchService service = folder.getFileSourceFactory().newWatchService()) {
 *     FileSourceWatchKey key = folder.register(service, Kind.ENTRY_CREATE, Kind.ENTRY_DELETE);
 *     while( true ) {
 *         FileSourceWatchKey ready = service.take();
 *         for (FileSourceWatchEvent e : ready.pollEvents()) { ... }
 *         if( !ready.reset() ) { break; }
 *     }
 * }
 * </pre>
 *
 * A service that comes from {@link FileSourceFactory#newWatchService()} works for the folders of its factory: it
 * does what the provider can do best (push where the file system has it). {@link #polling(Duration)} works for the
 * folders of any provider: it lists each watched folder on an interval and compares.
 * Only changes to the folder's entries are told, not to what is in a file or in a folder under it.
 * @since 2.0.0
 */
public interface FileSourceWatchService extends Closeable {

	/**
	 * Watch a folder. A folder that is watched already by this service gets its kinds changed and the same key back.
	 * @param folder the folder
	 * @param kinds {@link FileSourceWatchEvent.Kind#ENTRY_CREATE}, {@link FileSourceWatchEvent.Kind#ENTRY_DELETE} or both
	 * ({@link FileSourceWatchEvent.Kind#OVERFLOW} needn't be asked for); none means both
	 * @return the key
	 * @throws java.nio.file.NotDirectoryException if the folder isn't one
	 * @throws java.nio.file.ProviderMismatchException if this service can't watch folders of the folder's provider
	 * @throws ClosedWatchServiceException if this service is closed
	 * @throws IOException if the folder can't be read
	 */
	FileSourceWatchKey register(FileSource folder, FileSourceWatchEvent.Kind... kinds) throws IOException;

	/** @return a key that has events, or null if there is none now; queued keys are given once until {@link FileSourceWatchKey#reset()} */
	FileSourceWatchKey poll();

	/**
	 * @return a key that has events, or null if none came within the time
	 * @throws InterruptedException if the wait was interrupted
	 * @throws ClosedWatchServiceException if this service is (or becomes) closed
	 */
	FileSourceWatchKey poll(long timeout, TimeUnit unit) throws InterruptedException;

	/**
	 * Wait for a key that has events.
	 * @throws InterruptedException if the wait was interrupted
	 * @throws ClosedWatchServiceException if this service is (or becomes) closed
	 */
	FileSourceWatchKey take() throws InterruptedException;

	/** Stop watching everything, and wake those waiting in {@link #take()}. */
	@Override
	void close() throws IOException;

	/**
	 * A service that works for the folders of any provider by listing them on an interval.
	 * @param interval the time between two looks at the folders
	 */
	static FileSourceWatchService polling(Duration interval) {
		return new PollingWatchService(interval);
	}
}
