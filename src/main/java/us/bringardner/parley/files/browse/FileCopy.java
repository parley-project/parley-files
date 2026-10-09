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
import java.io.InputStream;
import java.io.OutputStream;

import us.bringardner.parley.files.FileSource;

/**
 * Copying a dropped or pasted file into a folder, the same way in every UI. It was a static
 * method of the Swing FileSourceChooserDialog until that moved to parley-files-swing.
 */
public final class FileCopy {

	private FileCopy() {
	}

	/**
	 * Copies file (a directory with everything in it) into dir under its own name.
	 * Nothing happens if the file doesn't exist or is already directly in dir.
	 * <p>
	 * Before BJL-24 the check was !dir.isChildOfMine(file), which skipped anything
	 * anywhere below dir, and a directory could be copied into itself, which never ends.
	 *
	 * @return the copy, or null if nothing was copied
	 * @throws IOException if a directory would be copied into itself or one of its
	 *   own subdirectories, or the copy fails
	 */
	public static FileSource copyInto(FileSource dir, FileSource file) throws IOException {
		if( !file.exists()) {
			return null;
		}
		FileSource parent = file.getParentFile();
		if( parent != null && sameFile(parent, dir)) {
			// already there
			return null;
		}
		if( file.isDirectory() && file.isChildOfMine(dir)) {
			throw new IOException("Can't copy "+file.getAbsolutePath()+" into itself ("+dir.getAbsolutePath()+")");
		}

		FileSource newFile = dir.getChild(file.getName());
		if( file.isDirectory()) {
			if( !newFile.mkdirs() && !newFile.isDirectory()) {
				throw new IOException("Can't create directory "+newFile.getAbsolutePath());
			}
			FileSource[] kids = file.listFiles();
			if( kids != null ) {
				for(FileSource kid : kids) {
					copyInto(newFile, kid);
				}
			}
		} else {
			try(InputStream in = file.getInputStream(); OutputStream out = newFile.getOutputStream()) {
				in.transferTo(out);
			}
		}
		return newFile;
	}

	private static boolean sameFile(FileSource a, FileSource b) throws IOException {
		return a.getFileSourceFactory().isSameFileSystem(b.getFileSourceFactory())
				&& a.getCanonicalPath().equals(b.getCanonicalPath());
	}
}
