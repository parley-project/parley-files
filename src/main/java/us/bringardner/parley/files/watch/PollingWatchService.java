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

package us.bringardner.parley.files.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.NotDirectoryException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import us.bringardner.parley.core.NamedThreadFactory;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceWatchEvent;
import us.bringardner.parley.files.FileSourceWatchEvent.Kind;
import us.bringardner.parley.files.FileSourceWatchKey;
import us.bringardner.parley.files.FileSourceWatchService;

/**
 * A {@link FileSourceWatchService} for any provider: one thread (a {@link NamedThreadFactory} thread) lists every
 * watched folder on an interval and compares the names with the last listing, so a change made and undone between two
 * looks is not seen, and the time of the folder isn't used (a clock that ticks once a second or two can't make it miss
 * a change). A provider that can tell cheaply that a folder hasn't changed overrides {@link #changeStamp(FileSource)}
 * and the folder is listed only when the stamp moves.
 *
 * <p>A folder that is deleted, or isn't a folder any more, makes its key invalid; one that can't be listed
 * {@value #MAX_FAILURES} times in a row does too. A key that has more than {@value #MAX_QUEUED} events waiting has them
 * replaced by one {@link Kind#OVERFLOW}.
 *
 * <p>The interval of {@link #PollingWatchService()} is the system property {@value #PROPERTY_POLL_INTERVAL}, in
 * milliseconds, default {@value #DEFAULT_POLL_INTERVAL} (at least {@value #MIN_POLL_INTERVAL}).
 * @since 2.0.0
 */
public class PollingWatchService implements FileSourceWatchService {

	public static final String PROPERTY_POLL_INTERVAL = "parley.files.watch.pollInterval";
	public static final long DEFAULT_POLL_INTERVAL = 2000;
	public static final long MIN_POLL_INTERVAL = 50;
	public static final int MAX_FAILURES = 5;
	public static final int MAX_QUEUED = 512;

	private static final System.Logger LOG = System.getLogger(PollingWatchService.class.getName());
	private static final Kind[] BOTH = {Kind.ENTRY_CREATE, Kind.ENTRY_DELETE};

	private final Duration interval;
	private final ScheduledExecutorService scheduler;
	private final List<PollKey> keys = new CopyOnWriteArrayList<PollKey>();
	/** the keys with events, and at the end the closed marker */
	private final BlockingQueue<PollKey> ready = new LinkedBlockingQueue<PollKey>();
	private final PollKey closedMarker = new PollKey(null, EnumSet.noneOf(Kind.class));
	private volatile boolean closed;

	/** with the interval of the system property, or its default */
	public PollingWatchService() {
		this(Duration.ofMillis(Long.getLong(PROPERTY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL)));
	}

	public PollingWatchService(Duration interval) {
		long ms = Math.max(MIN_POLL_INTERVAL, Objects.requireNonNull(interval, "interval").toMillis());
		this.interval = Duration.ofMillis(ms);
		scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("FileSourceWatch"));
		scheduler.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
	}

	public Duration getPollInterval() {
		return interval;
	}

	/**
	 * Something that changes every time the entries of the folder change, and is cheaper to get than a listing (a
	 * change counter of the file system). The folder is listed only when the stamp isn't the one of the last look; a
	 * stamp that doesn't change for every change (a time with a coarse clock) must not be used. The default is none, so
	 * the folder is listed every time.
	 * @return the stamp, compared with {@link Object#equals}; null for none
	 * @throws IOException if the folder can't be asked; it counts as a failure to list
	 */
	protected Object changeStamp(FileSource folder) throws IOException {
		return null;
	}

	@Override
	public FileSourceWatchKey register(FileSource folder, Kind... kinds) throws IOException {
		Objects.requireNonNull(folder, "folder");
		if( closed ) {
			throw new ClosedWatchServiceException();
		}
		EnumSet<Kind> wanted = EnumSet.noneOf(Kind.class);
		for (Kind k : kinds == null || kinds.length == 0 ? BOTH : kinds) {
			if( k != Kind.OVERFLOW ) {
				wanted.add(Objects.requireNonNull(k, "kind"));
			}
		}
		if( wanted.isEmpty() ) {
			wanted.addAll(Arrays.asList(BOTH));
		}
		synchronized (keys) {
			for (PollKey k : keys) {
				if( k.valid && k.folder.equals(folder) ) {
					k.setKinds(wanted);
					return k;
				}
			}
			if( !folder.isDirectory() ) {
				throw new NotDirectoryException(folder.getAbsolutePath());
			}
			PollKey key = new PollKey(folder, wanted);
			key.stamp = changeStamp(folder);
			key.known = names(folder);
			keys.add(key);
			return key;
		}
	}

	private static Set<String> names(FileSource folder) throws IOException {
		String[] list = folder.list();
		if( list == null ) {
			throw new IOException("can't list "+folder.getAbsolutePath());
		}
		return new HashSet<String>(Arrays.asList(list));
	}

	private void tick() {
		for (PollKey key : keys) {
			if( closed ) {
				return;
			}
			if( !key.valid ) {
				keys.remove(key);
				continue;
			}
			try {
				look(key);
			} catch (Throwable t) {
				LOG.log(System.Logger.Level.WARNING, "Watching "+key.folder+" failed: "+t);
			}
		}
	}

	private void look(PollKey key) {
		FileSource folder = key.folder;
		Set<String> now;
		try {
			if( !folder.isDirectory() ) {
				LOG.log(System.Logger.Level.DEBUG, "The watched folder "+folder+" is gone or isn't a folder now");
				key.cancel();
				return;
			}
			Object stamp = changeStamp(folder);
			if( stamp != null && stamp.equals(key.stamp) ) {
				key.failures = 0;
				return;
			}
			now = names(folder);
			key.stamp = stamp;
		} catch (IOException | RuntimeException e) {
			if( ++key.failures >= MAX_FAILURES ) {
				LOG.log(System.Logger.Level.WARNING, "Watching "+folder+" ended: it could not be listed "+MAX_FAILURES
						+" times in a row ("+e+")");
				key.cancel();
			} else {
				LOG.log(System.Logger.Level.DEBUG, "Listing "+folder+" for the watch failed: "+e);
			}
			return;
		}
		key.failures = 0;
		List<FileSourceWatchEvent> events = new ArrayList<FileSourceWatchEvent>();
		for (String n : new TreeSet<String>(key.known)) {
			if( !now.contains(n) ) {
				events.add(new FileSourceWatchEvent(Kind.ENTRY_DELETE, n));
			}
		}
		for (String n : new TreeSet<String>(now)) {
			if( !key.known.contains(n) ) {
				events.add(new FileSourceWatchEvent(Kind.ENTRY_CREATE, n));
			}
		}
		key.known = now;
		if( !events.isEmpty() ) {
			key.add(events);
		}
	}

	@Override
	public FileSourceWatchKey poll() {
		return unmarked(ready.poll());
	}

	@Override
	public FileSourceWatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
		return unmarked(ready.poll(timeout, unit));
	}

	@Override
	public FileSourceWatchKey take() throws InterruptedException {
		return unmarked(ready.take());
	}

	/** the closed marker wakes every taker, so it goes back for the next */
	private PollKey unmarked(PollKey k) {
		if( k == closedMarker ) {
			ready.offer(closedMarker);
			throw new ClosedWatchServiceException();
		}
		return k;
	}

	@Override
	public void close() {
		if( closed ) {
			return;
		}
		closed = true;
		scheduler.shutdownNow();
		for (PollKey k : keys) {
			k.valid = false;
		}
		keys.clear();
		ready.offer(closedMarker);
	}

	private final class PollKey implements FileSourceWatchKey {
		final FileSource folder;
		private volatile EnumSet<Kind> kinds;
		volatile boolean valid = true;
		/** used by the polling thread only */
		Set<String> known = Collections.emptySet();
		Object stamp;
		int failures;
		private final List<FileSourceWatchEvent> events = new ArrayList<FileSourceWatchEvent>();
		private boolean overflowed;
		/** true from the time the key is queued until reset() */
		private boolean signalled;

		PollKey(FileSource folder, EnumSet<Kind> kinds) {
			this.folder = folder;
			this.kinds = kinds;
		}

		void setKinds(EnumSet<Kind> kinds) {
			this.kinds = kinds;
		}

		void add(List<FileSourceWatchEvent> found) {
			synchronized (this) {
				if( !valid && !signalled ) {
					return;
				}
				for (FileSourceWatchEvent e : found) {
					if( kinds.contains(e.kind()) && !overflowed ) {
						events.add(e);
						if( events.size() > MAX_QUEUED ) {
							events.clear();
							events.add(new FileSourceWatchEvent(Kind.OVERFLOW, null));
							overflowed = true;
						}
					}
				}
				if( !events.isEmpty() && !signalled ) {
					signalled = true;
					ready.offer(this);
				}
			}
		}

		@Override
		public FileSource folder() {
			return folder;
		}

		@Override
		public boolean isValid() {
			return valid;
		}

		@Override
		public List<FileSourceWatchEvent> pollEvents() {
			synchronized (this) {
				List<FileSourceWatchEvent> ret = new ArrayList<FileSourceWatchEvent>(events);
				events.clear();
				overflowed = false;
				return ret;
			}
		}

		@Override
		public boolean reset() {
			synchronized (this) {
				if( !valid ) {
					return false;
				}
				if( signalled ) {
					if( events.isEmpty() ) {
						signalled = false;
					} else {
						ready.offer(this);
					}
				}
				return true;
			}
		}

		@Override
		public void cancel() {
			valid = false;
		}

		@Override
		public String toString() {
			return "watch of "+folder+(valid ? "" : " (cancelled)");
		}
	}
}
