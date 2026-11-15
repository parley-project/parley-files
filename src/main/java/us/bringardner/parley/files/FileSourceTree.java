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
package us.bringardner.parley.files;

import java.io.IOException;
import java.io.Serializable;
import java.util.Set;

/**
 * The tree a {@link DelegatingFileSource} is named by: what it asks when its path is the tree's
 * (/mem/notes) rather than its own file's (/notes). A DelegatingFileSource with no tree passes its
 * path through like everything else.
 *
 * {@link us.bringardner.parley.files.tree.VirtualFileSourceFactory} is the tree fsh and the
 * viewers use.
 */
public interface FileSourceTree extends Serializable {

	/** the file at path, a path of this tree (absolute, or relative to its current folder) */
	FileSource createFileSource(String path) throws IOException;

	/**
	 * The file at path, which a folder of this tree listed as file: the tree's if path is a
	 * connection, in one, or has one under it; else file itself.
	 */
	FileSource inTree(String path, FileSource file) throws IOException;

	/** the names of the connections directly in folder (a path of this tree) */
	Set<String> connectionsIn(String folder);

	/** the parent of path, or null at the top */
	String parentPath(String path);

	/** name in folder */
	String join(String folder, String name);
}
