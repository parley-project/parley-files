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

import us.bringardner.parley.files.FileSource;

/**
 * One file in a listing, with its details read once. A UI shows a lot of rows and repaints
 * them often; asking a remote file system for a file's size or date on every repaint is
 * slow, so a browser works from these snapshots instead. Refresh by listing again.
 */
public final class FileEntry {

	private final FileSource file;
	private final String name;
	private final boolean directory;
	private final boolean hidden;
	private final long length;
	private final long lastModified;
	private final IOException error;

	private FileEntry(FileSource file, String name, boolean directory, boolean hidden, long length,
			long lastModified, IOException error) {
		this.file = file;
		this.name = name;
		this.directory = directory;
		this.hidden = hidden;
		this.length = length;
		this.lastModified = lastModified;
		this.error = error;
	}

	/**
	 * Reads file's details now. If they can't be read the entry still exists, with what was
	 * read and the error ({@link #getError()}), so one unreadable file doesn't spoil a listing.
	 */
	public static FileEntry of(FileSource file) {
		String name = file.getName();
		boolean directory = false;
		boolean hidden = name.startsWith(".");
		long length = 0;
		long lastModified = 0;
		IOException error = null;
		try {
			directory = file.isDirectory();
			hidden = file.isHidden();
			if( !directory ) {
				length = file.length();
			}
			lastModified = file.lastModified();
		} catch (IOException e) {
			error = e;
		}
		return new FileEntry(file, name, directory, hidden, length, lastModified, error);
	}

	public FileSource getFile() {
		return file;
	}

	public String getName() {
		return name;
	}

	public boolean isDirectory() {
		return directory;
	}

	public boolean isHidden() {
		return hidden;
	}

	/** Bytes; 0 for a directory. */
	public long getLength() {
		return length;
	}

	/** Milliseconds since 1970, or 0 if unknown. */
	public long getLastModified() {
		return lastModified;
	}

	/** Why the details couldn't all be read, or null. */
	public IOException getError() {
		return error;
	}

	/** The extension without its dot ("txt"), or "" for none and for directories. */
	public String getExtension() {
		int idx = name.lastIndexOf('.');
		return directory || idx <= 0 ? "" : name.substring(idx+1);
	}

	/** The name to show: without its extension if showExtension is false (directories keep theirs). */
	public String getDisplayName(boolean showExtension) {
		if( showExtension || directory ) {
			return name;
		}
		int idx = name.lastIndexOf('.');
		return idx > 0 ? name.substring(0, idx) : name;
	}

	@Override
	public String toString() {
		return name+(directory ? "/" : "");
	}
}
