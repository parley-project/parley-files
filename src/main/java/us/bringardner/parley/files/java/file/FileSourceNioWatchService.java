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

package us.bringardner.parley.files.java.file;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import us.bringardner.parley.files.FileSourceWatchEvent;
import us.bringardner.parley.files.FileSourceWatchEvent.Kind;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.files.FileSourceWatchService;

/**
 * The java.nio.file {@link WatchService} of a FileSource file system: a view of a {@link FileSourceWatchService}
 * (the factory's own, so an NFS folder is watched by its change attribute), so that code written for
 * java.nio.file ({@code path.register(watcher, ENTRY_CREATE, ENTRY_DELETE)}) works on any FileSource.
 *
 * <p>What it reports is what FileSources can tell: entries added to and removed from a folder. ENTRY_MODIFY
 * is accepted in a list of kinds and never reported (a change to a file's content is not watched, D90), and a
 * registration for ENTRY_MODIFY alone is refused with an UnsupportedOperationException. Registering a folder again
 * with the same service gives another key, not the first one. The events' context is the name of the entry, as a
 * relative path.
 * @since 2.0.0
 */
final class FileSourceNioWatchService implements WatchService {

	private final FileSourceWatchService inner;
	private final Map<FileSourceWatchKey, NioKey> keys = new ConcurrentHashMap<FileSourceWatchKey, NioKey>();

	FileSourceNioWatchService(FileSourceWatchService inner) {
		this.inner = Objects.requireNonNull(inner, "inner");
	}

	/** @return the key of the folder, for the kinds java.nio.file names */
	WatchKey register(FileSourcePath folder, WatchEvent.Kind<?>[] kinds) throws IOException {
		Objects.requireNonNull(kinds, "events");
		if( kinds.length == 0 ) {
			throw new IllegalArgumentException("no event kinds");
		}
		EnumSet<Kind> wanted = EnumSet.noneOf(Kind.class);
		for (WatchEvent.Kind<?> k : kinds) {
			Objects.requireNonNull(k, "event kind");
			if( k == StandardWatchEventKinds.ENTRY_CREATE ) {
				wanted.add(Kind.ENTRY_CREATE);
			} else if( k == StandardWatchEventKinds.ENTRY_DELETE ) {
				wanted.add(Kind.ENTRY_DELETE);
			} else if( k != StandardWatchEventKinds.ENTRY_MODIFY && k != StandardWatchEventKinds.OVERFLOW ) {
				throw new UnsupportedOperationException("Event kind "+k.name()+" is not supported");
			}
		}
		if( wanted.isEmpty() ) {
			throw new UnsupportedOperationException("Only ENTRY_CREATE and ENTRY_DELETE can be watched");
		}
		FileSourceWatchKey k = inner.register(folder.getFileSource(), wanted.toArray(new Kind[0]));
		NioKey ret = new NioKey(k, folder);
		keys.put(k, ret);
		return ret;
	}

	/** @return the key for the one that is ready, null for one that was cancelled meanwhile */
	private WatchKey resolve(FileSourceWatchKey k) {
		return k == null ? null : keys.get(k);
	}

	@Override
	public WatchKey poll() {
		return resolve(inner.poll());
	}

	@Override
	public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
		return resolve(inner.poll(timeout, unit));
	}

	@Override
	public WatchKey take() throws InterruptedException {
		while( true ) {
			WatchKey ret = resolve(inner.take());
			if( ret != null ) {
				return ret;
			}
		}
	}

	@Override
	public void close() throws IOException {
		try {
			inner.close();
		} finally {
			keys.clear();
		}
	}

	private final class NioKey implements WatchKey {
		private final FileSourceWatchKey key;
		private final FileSourcePath folder;

		NioKey(FileSourceWatchKey key, FileSourcePath folder) {
			this.key = key;
			this.folder = folder;
		}

		@Override
		public boolean isValid() {
			return key.isValid();
		}

		@Override
		public List<WatchEvent<?>> pollEvents() {
			List<WatchEvent<?>> ret = new ArrayList<WatchEvent<?>>();
			for (FileSourceWatchEvent e : key.pollEvents()) {
				switch (e.kind()) {
				case ENTRY_CREATE:
					ret.add(new NioEvent<Path>(StandardWatchEventKinds.ENTRY_CREATE, folder.getFileSystem().getPath(e.name())));
					break;
				case ENTRY_DELETE:
					ret.add(new NioEvent<Path>(StandardWatchEventKinds.ENTRY_DELETE, folder.getFileSystem().getPath(e.name())));
					break;
				default:
					ret.add(new NioEvent<Object>(StandardWatchEventKinds.OVERFLOW, null));
					break;
				}
			}
			return ret;
		}

		@Override
		public boolean reset() {
			return key.reset();
		}

		@Override
		public void cancel() {
			key.cancel();
			keys.remove(key);
		}

		@Override
		public Path watchable() {
			return folder;
		}
	}

	private static final class NioEvent<T> implements WatchEvent<T> {
		private final WatchEvent.Kind<T> kind;
		private final T context;

		@SuppressWarnings("unchecked")
		NioEvent(WatchEvent.Kind<?> kind, T context) {
			this.kind = (WatchEvent.Kind<T>) kind;
			this.context = context;
		}

		@Override
		public WatchEvent.Kind<T> kind() {
			return kind;
		}

		@Override
		public int count() {
			return 1;
		}

		@Override
		public T context() {
			return context;
		}

		@Override
		public String toString() {
			return context == null ? kind.name() : kind.name()+" "+context;
		}
	}
}
