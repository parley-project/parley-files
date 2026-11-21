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

import java.util.Objects;

/**
 * A change in a watched folder, as {@link java.nio.file.WatchEvent} is for a {@link java.nio.file.Path}: what happened
 * and to which entry. A rename is an {@link Kind#ENTRY_DELETE} of the old name and an {@link Kind#ENTRY_CREATE} of the
 * new one.
 * @see FileSourceWatchService
 * @since 2.0.0
 */
public final class FileSourceWatchEvent {

	/** What happened. */
	public enum Kind {
		/** an entry was added to the folder (created, or moved or renamed into it) */
		ENTRY_CREATE,
		/** an entry was taken out of the folder (deleted, or moved or renamed out of it) */
		ENTRY_DELETE,
		/**
		 * Events were lost or too many came: look at the folder again. Always delivered, never asked for.
		 */
		OVERFLOW
	}

	private final Kind kind;
	private final String name;

	/**
	 * @param kind what happened
	 * @param name the entry's name in the folder, null for {@link Kind#OVERFLOW}
	 */
	public FileSourceWatchEvent(Kind kind, String name) {
		this.kind = Objects.requireNonNull(kind, "kind");
		this.name = kind == Kind.OVERFLOW ? null : Objects.requireNonNull(name, "name");
	}

	public Kind kind() {
		return kind;
	}

	/** @return the entry's name, as {@link FileSource#getName()} says it; null for {@link Kind#OVERFLOW} */
	public String name() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FileSourceWatchEvent && ((FileSourceWatchEvent) o).kind == kind
				&& Objects.equals(((FileSourceWatchEvent) o).name, name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(kind, name);
	}

	@Override
	public String toString() {
		return name == null ? kind.toString() : kind+" "+name;
	}
}
