/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.browse;

import java.util.ArrayDeque;
import java.util.Deque;

import us.bringardner.parley.files.FileSource;

/** Back and forward through the directories visited, like a browser's buttons. */
public final class NavigationHistory {

	private final Deque<FileSource> back = new ArrayDeque<>();
	private final Deque<FileSource> forward = new ArrayDeque<>();
	private final int max;
	private FileSource current;

	public NavigationHistory() {
		this(100);
	}

	/** @param max the most directories kept each way */
	public NavigationHistory(int max) {
		this.max = Math.max(1, max);
	}

	/** The directory now shown, or null before the first {@link #go}. */
	public FileSource current() {
		return current;
	}

	/** Goes to dir; forward history is dropped. Going to the current directory changes nothing. */
	public void go(FileSource dir) {
		if( DirectoryListing.samePath(dir, current)) {
			return;
		}
		if( current != null ) {
			back.push(current);
			while( back.size() > max ) {
				back.removeLast();
			}
		}
		forward.clear();
		current = dir;
	}

	public boolean canGoBack() {
		return !back.isEmpty();
	}

	public boolean canGoForward() {
		return !forward.isEmpty();
	}

	/** Goes back; returns the directory now current (unchanged if there's nowhere to go). */
	public FileSource back() {
		if( !back.isEmpty()) {
			forward.push(current);
			current = back.pop();
		}
		return current;
	}

	/** Goes forward; returns the directory now current (unchanged if there's nowhere to go). */
	public FileSource forward() {
		if( !forward.isEmpty()) {
			back.push(current);
			current = forward.pop();
		}
		return current;
	}
}
