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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;

/**
 * Listing directories for a chooser or browser, the same way in every UI. Listing can be slow
 * (remote file systems): call {@link #list} off the UI thread.
 */
public final class DirectoryListing {

	private DirectoryListing() {
	}

	/** By name, ignoring case (then exactly, so the order is always the same). */
	public static final Comparator<FileEntry> BY_NAME = Comparator
			.comparing((FileEntry e)->e.getName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(FileEntry::getName);

	/** Oldest first, then by name. */
	public static final Comparator<FileEntry> BY_MODIFIED = Comparator
			.comparingLong(FileEntry::getLastModified).thenComparing(BY_NAME);

	/** Smallest first, then by name. */
	public static final Comparator<FileEntry> BY_SIZE = Comparator
			.comparingLong(FileEntry::getLength).thenComparing(BY_NAME);

	/** order, with directories before files. */
	public static Comparator<FileEntry> directoriesFirst(Comparator<FileEntry> order) {
		return Comparator.comparing((FileEntry e)->!e.isDirectory()).thenComparing(order);
	}

	/**
	 * The entries in dir, by name, with their details read once.
	 *
	 * @param progress told how far listing has got, and asked whether to stop; may be null
	 * @return the entries; empty if dir has none or isn't a directory
	 * @throws CancellationException if progress says to stop
	 */
	public static List<FileEntry> list(FileSource dir, FileSourceProgress progress) throws IOException {
		FileSource[] kids = dir.listFiles(progress);
		if( kids == null || kids.length == 0 ) {
			return new ArrayList<>();
		}
		List<FileEntry> ret = new ArrayList<>(kids.length);
		if( progress != null ) {
			progress.setMaximum(kids.length);
		}
		for(int idx=0; idx < kids.length; idx++) {
			if( progress != null ) {
				if( progress.isCanceled()) {
					throw new CancellationException("Listing "+dir.getAbsolutePath()+" was canceled");
				}
				progress.setProgress(idx);
			}
			ret.add(FileEntry.of(kids[idx]));
		}
		if( progress != null ) {
			progress.setProgress(kids.length);
		}
		ret.sort(BY_NAME);
		return ret;
	}

	/** The directories in dir (for a folder tree), by name, hidden ones only if showHidden. */
	public static List<FileEntry> directories(FileSource dir, boolean showHidden, FileSourceProgress progress) throws IOException {
		List<FileEntry> ret = new ArrayList<>();
		for(FileEntry e : list(dir, progress)) {
			if( e.isDirectory() && (showHidden || !e.isHidden())) {
				ret.add(e);
			}
		}
		return ret;
	}

	/** entries without the hidden ones, unless showHidden. */
	public static List<FileEntry> shown(List<FileEntry> entries, boolean showHidden) {
		List<FileEntry> ret = new ArrayList<>(entries.size());
		for(FileEntry e : entries) {
			if( showHidden || !e.isHidden()) {
				ret.add(e);
			}
		}
		return ret;
	}

	/**
	 * True if the user may pick entry: it's of a kind mode allows, and a file passes filter
	 * (null for any). Directories that can't be picked can still be opened.
	 */
	public static boolean isSelectable(FileEntry entry, SelectionMode mode, FileSourceFilter filter) {
		if( entry.isDirectory()) {
			return mode != SelectionMode.FILES;
		}
		if( mode == SelectionMode.DIRECTORIES ) {
			return false;
		}
		return filter == null || filter.accept(entry.getFile());
	}

	/** dir and the directories above it, the top one first (for a path bar). */
	public static List<FileSource> ancestors(FileSource dir) throws IOException {
		List<FileSource> ret = new ArrayList<>();
		for(FileSource d = dir; d != null && ret.size() < 1000; d = d.getParentFile()) {
			ret.add(d);
		}
		Collections.reverse(ret);
		return ret;
	}

	/** True if a and b are the same path on the same file system (asks neither file system anything). */
	public static boolean samePath(FileSource a, FileSource b) {
		if( a == b ) {
			return true;
		}
		if( a == null || b == null ) {
			return false;
		}
		return a.getAbsolutePath().equals(b.getAbsolutePath())
				&& a.getFileSourceFactory().isSameFileSystem(b.getFileSourceFactory());
	}
}
