/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * A new memory file system starts at its root, a directory it has, and doesn't call itself
 * "Local" (it used to start at the JVM's working directory, a local path it didn't have).
 */
public class MemoryCurrentDirectoryTest {

	@Test
	public void startsAtItsRoot() throws Exception {
		MemoryFileSourceFactory factory = new MemoryFileSourceFactory();
		assertTrue(factory.connect());
		FileSource cwd = factory.getCurrentDirectory();
		assertEquals("/", cwd.getAbsolutePath());
		assertTrue(cwd.exists());
		assertTrue(cwd.isDirectory());
		// a relative path is under the root
		assertEquals("/a/b", factory.createFileSource("a/b").getAbsolutePath());
		factory.disConnect();
		assertEquals("/", factory.getCurrentDirectory().getAbsolutePath(), "and again after a disconnect");
	}

	@Test
	public void itsTitleIsntLocal() {
		assertEquals("Memory", new MemoryFileSourceFactory().getTitle());
	}
}
