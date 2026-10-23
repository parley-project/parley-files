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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * A tree on the local disk with a memory file system connected in it: what fsh's shell and the
 * console browser both see.
 */
public class TreeTest {

	@TempDir
	Path tmp;

	private static FileSource memoryTop() throws IOException {
		MemoryFileSourceFactory m = new MemoryFileSourceFactory();
		m.connect(new Properties());
		FileSource top = m.listRoots()[0];
		try (OutputStream out = top.getChild("inmem.txt").getOutputStream()) {
			out.write("in memory\n".getBytes());
		}
		return top;
	}

	private static List<String> names(FileSource dir) throws IOException {
		List<String> ret = new ArrayList<>();
		for(FileSource f : dir.listFiles()) {
			ret.add(f.getName());
		}
		return ret;
	}

	@Test
	public void aConnectionIsListedWhereItIsAndItsFilesAreTheTrees() throws IOException {
		String base = tmp.toFile().getCanonicalPath().replace('\\', '/');
		assertTrue(new File(tmp.toFile(), "local").mkdir());
		VirtualFileSourceFactory tree = new VirtualFileSourceFactory();
		assertTrue(tree.mount(base+"/mem", memoryTop()));
		// it can't be connected twice, or at /
		assertFalse(tree.mount(base+"/mem", memoryTop()));
		assertFalse(tree.mount("/", memoryTop()));

		FileSource dir = tree.createFileSource(base);
		assertTrue(dir instanceof TreeFile, dir.getClass().getName());
		List<String> names = names(dir);
		assertTrue(names.contains("mem") && names.contains("local"), names.toString());

		FileSource mem = tree.createFileSource(base+"/mem");
		assertTrue(mem.isDirectory());
		assertEquals(base+"/mem", mem.getAbsolutePath());
		FileSource f = tree.createFileSource(base+"/mem/inmem.txt");
		assertTrue(f.exists());
		assertEquals(base+"/mem/inmem.txt", f.getAbsolutePath());
		// up out of the connection, back into the local folder it's in
		assertEquals(base, mem.getParentFile().getAbsolutePath());
		assertTrue(names(f.getParentFile().getParentFile()).contains("mem"));

		// a local folder with no connection under it is the local disk's own, and LocalFiles says so
		FileSource local = tree.createFileSource(base+"/local");
		assertFalse(local instanceof TreeFile);
		assertTrue(LocalFiles.isLocal(local));
		assertTrue(LocalFiles.isLocal(dir));
		assertFalse(LocalFiles.isLocal(mem));

		assertEquals(1, tree.connections().size());
		assertNotNull(tree.connectionAt(base+"/mem"));
		assertNotNull(tree.unmount(base+"/mem"));
		assertNull(tree.connectionAt(base+"/mem"));
		assertFalse(names(tree.createFileSource(base)).contains("mem"));
	}

	/** a tree with another file system as / (as tests and a tree on a remote system have) */
	@Test
	public void aTreeOnAnotherFileSystem() throws IOException {
		FileSource root = memoryTop();
		VirtualFileSourceFactory tree = new VirtualFileSourceFactory(root);
		assertTrue(tree.mount("/sub", memoryTop()));
		assertTrue(names(tree.createFileSource("/")).contains("sub"));
		assertTrue(tree.createFileSource("/sub/inmem.txt").exists());
		assertTrue(tree.uses(tree.connectionAt("/sub").getFileSourceFactory()));
	}

	@Test
	public void pathsAreLogical() {
		assertEquals("/a/c", VirtualFileSourceFactory.logicalPath("/a/b/../c/."));
		assertEquals("/", VirtualFileSourceFactory.logicalPath("/.."));
		assertEquals("/a", VirtualFileSourceFactory.parentPath("/a/b"));
		assertEquals("/", VirtualFileSourceFactory.parentPath("/a"));
		assertNull(VirtualFileSourceFactory.parentPath("/"));
		assertEquals(FileSourceFactory.class, VirtualFileSourceFactory.class.getSuperclass());
	}
}
