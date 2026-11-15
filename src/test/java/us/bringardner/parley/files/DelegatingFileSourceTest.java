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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** A DelegatingFileSource with no tree passes everything through to its file. */
public class DelegatingFileSourceTest {

	private MemoryFileSourceFactory mem;
	private FileSource dir;

	@BeforeEach
	public void setUp() throws IOException {
		mem = new MemoryFileSourceFactory();
		mem.connect(new Properties());
		dir = mem.listRoots()[0].getChild("dir");
		assertTrue(dir.mkdirs());
		write(dir.getChild("a.txt"), "aaa");
		write(dir.getChild("b.txt"), "bb");
	}

	@AfterEach
	public void tearDown() throws IOException {
		mem.disConnect();
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes());
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes());
		}
	}

	private static String[] sortedNames(FileSource[] files) {
		String[] ret = new String[files.length];
		for(int idx = 0; idx < files.length; idx++) {
			ret[idx] = files[idx].getName();
		}
		Arrays.sort(ret);
		return ret;
	}

	@Test
	public void itsPathNameAndParentAreItsFiles() throws IOException {
		FileSource d = new DelegatingFileSource(dir);
		assertEquals(dir.getAbsolutePath(), d.getAbsolutePath());
		assertEquals(dir.getPath(), d.getPath());
		assertEquals(dir.getName(), d.getName());
		assertEquals(dir.getParent(), d.getParent());
		assertEquals(dir.getParentFile().getAbsolutePath(), d.getParentFile().getAbsolutePath());
		assertEquals(dir.toString(), d.toString());
		assertEquals(dir.isAbsolute(), d.isAbsolute());
		assertTrue(d.isDirectory());
		assertFalse(d.isFile());
	}

	@Test
	public void itsChildrenAreItsFiles() throws IOException {
		FileSource d = new DelegatingFileSource(dir);
		assertArrayEquals(sortedNames(dir.listFiles()), sortedNames(d.listFiles()));
		String[] names = d.list();
		Arrays.sort(names);
		assertArrayEquals(new String[] {"a.txt", "b.txt"}, names);
		FileSource a = d.getChild("a.txt");
		assertEquals(dir.getChild("a.txt").getAbsolutePath(), a.getAbsolutePath());
		assertEquals("aaa", read(a));
		assertEquals(1, d.listFiles(f -> f.getName().startsWith("b")).length);
	}

	@Test
	public void itsBytesAndChangesAreItsFiles() throws IOException {
		FileSource real = dir.getChild("a.txt");
		FileSource d = new DelegatingFileSource(real);
		assertEquals(3, d.length());
		assertEquals("aaa", read(d));
		write(d, "changed");
		assertEquals("changed", read(real));
		assertTrue(d.delete());
		assertFalse(real.exists());
	}

	@Test
	public void itIsEqualToAnotherWrappingTheSameFile() throws IOException {
		FileSource a = new DelegatingFileSource(dir.getChild("a.txt"));
		FileSource a2 = new DelegatingFileSource(dir.getChild("a.txt"));
		FileSource b = new DelegatingFileSource(dir.getChild("b.txt"));
		assertEquals(a, a2);
		assertEquals(a.hashCode(), a2.hashCode());
		assertFalse(a.equals(b));
		assertTrue(a.compareTo(b) < 0);
	}

	@Test
	public void unwrapGivesTheFileBack() {
		FileSource d = new DelegatingFileSource(dir);
		assertSame(dir, DelegatingFileSource.unwrap(d));
		assertSame(dir, ((DelegatingFileSource) d).real());
		assertFalse(((DelegatingFileSource) d).isConnectionPoint());
	}

	@Test
	public void aSubclassChangesOnlyWhatItOverrides() throws IOException {
		FileSource d = new DelegatingFileSource(dir.getChild("a.txt")) {
			private static final long serialVersionUID = 1L;

			@Override
			public String getName() {
				return "renamed.txt";
			}
		};
		assertEquals("renamed.txt", d.getName());
		assertEquals("aaa", read(d));
		assertEquals(dir.getChild("a.txt").getAbsolutePath(), d.getAbsolutePath());
	}

	@Test
	public void itNeedsAFile() {
		assertThrows(NullPointerException.class, () -> new DelegatingFileSource(null));
	}
}
