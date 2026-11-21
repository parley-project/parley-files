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

import java.util.List;

/**
 * A folder registered with a {@link FileSourceWatchService}, as {@link java.nio.file.WatchKey} is for a
 * {@link java.nio.file.Path}. The service queues the key when the folder has events; read them with
 * {@link #pollEvents()}, then {@link #reset()} the key so it is queued again for the next change.
 * @since 2.0.0
 */
public interface FileSourceWatchKey {

	/** @return the folder this key watches */
	FileSource folder();

	/** @return false once the key is cancelled, the service is closed, or the folder is gone */
	boolean isValid();

	/**
	 * @return the events since the last call, oldest first, and forget them; empty if there are none
	 */
	List<FileSourceWatchEvent> pollEvents();

	/**
	 * Be ready for the next events: the key is queued again at once if some came since {@link #pollEvents()}.
	 * @return false if the key is no longer valid
	 */
	boolean reset();

	/** Stop watching. Events already queued can still be read. */
	void cancel();
}
