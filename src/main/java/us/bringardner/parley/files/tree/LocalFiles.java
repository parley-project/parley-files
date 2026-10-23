/**
 * <PRE>
 *
 * Copyright Tony Bringardner 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 *	@author Tony Bringardner
 */
package us.bringardner.parley.files.tree;

import java.io.File;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.fileproxy.FileProxy;

/**
 * The local file a file of a tree (VirtualFileSourceFactory) is, for what only a local file has:
 * a program's working directory, links, permission bits, a named pipe. A tree's files are the
 * local file system's (FileProxy), or TreeFiles (a local folder with a connection under it, or a file of a
 * connected file system).
 */
public final class LocalFiles {

	private LocalFiles() {
	}

	/** the local file f is, or null (f is a connected file system's) */
	public static File of(FileSource f) {
		FileSource real = TreeFile.unwrap(f);
		return real instanceof FileProxy ? ((FileProxy) real).getTarget() : null;
	}

	/** f is a local file */
	public static boolean isLocal(FileSource f) {
		return of(f) != null;
	}
}
