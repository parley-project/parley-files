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
/**
 * Support for user interfaces that browse files: the part of a file chooser, file browser or
 * "recent files" menu that doesn't depend on any UI toolkit. <b>Nothing else in parley-files uses
 * this package</b>, and programs that only read and write files can ignore it.
 *
 * <h2>Why it is here</h2>
 * The Swing UI (parley-files-swing), the JavaFX UI (parley-files-fx) and text UIs such as
 * parley-files-console all need the same logic, and each is built on a different toolkit. Putting
 * this code in any one of them would make the others depend on that toolkit (Swing apps pulling
 * in JavaFX, say), and a module of its own wasn't worth its upkeep. So it lives below all of them,
 * in parley-files, with no UI dependencies: no Swing, AWT or JavaFX.
 *
 * <h2>What's in it</h2>
 * <ul>
 * <li>{@link us.bringardner.parley.files.browse.FileEntry}: a file's details, read once, so a view
 *     doesn't ask a remote file system (FTP, SFTP) again on every repaint.</li>
 * <li>{@link us.bringardner.parley.files.browse.DirectoryListing}: lists a directory with progress
 *     and cancel, sort orders and hidden files; says which entries can be picked and gives a
 *     directory's ancestors (for a path bar).</li>
 * <li>{@link us.bringardner.parley.files.browse.NavigationHistory}: back and forward.</li>
 * <li>{@link us.bringardner.parley.files.browse.SelectionMode}: files, directories or both.</li>
 * <li>{@link us.bringardner.parley.files.browse.RecentFile}: an entry in a recent files list, saved
 *     without its secrets and reopened later.</li>
 * <li>{@link us.bringardner.parley.files.browse.FileCopy}: copying a dropped or pasted file into a
 *     folder, the same way in every UI.</li>
 * </ul>
 *
 * Keep it that way: no UI toolkit imports here, and nothing outside this package should need it.
 */
package us.bringardner.parley.files.browse;
