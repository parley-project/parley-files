/**
 * <PRE>
 *
 * Copyright Tony Bringardner 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 */
package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Extended attributes (D101): the user-defined attribute view of a FileSource on the memory provider and on local
 * files, through java.nio too, and the default (no view) for a provider that has nowhere to keep them.
 */
public class ExtendedAttributeTests {

	@TempDir
	Path tmp;

	private static byte[] b(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] get(UserDefinedFileAttributeView v, String name) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(v.size(name));
		assertEquals(buf.capacity(), v.read(name, buf));
		return buf.array();
	}

	private static void set(UserDefinedFileAttributeView v, String name, byte[] value) throws IOException {
		assertEquals(value.length, v.write(name, ByteBuffer.wrap(value)));
	}

	/** the same behaviour whoever keeps the attributes */
	private void exercise(FileSource f) throws IOException {
		UserDefinedFileAttributeView v = f.getUserDefinedFileAttributeView();
		assertNotNull(v);
		assertEquals("user", v.name());
		assertEquals(Collections.emptyList(), v.list());
		assertThrows(FileSystemException.class, () -> v.size("origin"));
		assertThrows(FileSystemException.class, () -> v.delete("origin"));

		set(v, "origin", b("https://example.com/a"));
		set(v, "tag", new byte[] { 0, 1, 2, (byte) 255 });
		set(v, "empty", new byte[0]);
		List<String> names = v.list();
		assertEquals(3, names.size());
		assertTrue(names.containsAll(List.of("origin", "tag", "empty")));
		assertArrayEquals(b("https://example.com/a"), get(v, "origin"));
		assertArrayEquals(new byte[] { 0, 1, 2, (byte) 255 }, get(v, "tag"));
		assertArrayEquals(new byte[0], get(v, "empty"));

		set(v, "origin", b("replaced"));
		assertArrayEquals(b("replaced"), get(v, "origin"));
		assertEquals(3, v.list().size());

		// a new view of the same file sees the same attributes
		assertEquals(3, f.getUserDefinedFileAttributeView().list().size());

		v.delete("tag");
		assertThrows(FileSystemException.class, () -> v.delete("tag"));
		assertEquals(2, v.list().size());

		// a buffer that is too small is refused, as the JDK does
		assertThrows(FileSystemException.class, () -> v.read("origin", ByteBuffer.allocate(2)));
	}

	@Test
	public void memoryProvider() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource f = mem.createFileSource("/x.txt");
			assertNull(f.getUserDefinedFileAttributeView(), "a file that isn't there keeps none");
			assertTrue(f.createNewFile());
			exercise(f);

			// a directory keeps them too
			FileSource d = mem.createFileSource("/dir");
			assertTrue(d.mkdirs());
			exercise(d);
		} finally {
			mem.disConnect();
		}
	}

	@Test
	public void memoryAttributesFollowARename() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource f = mem.createFileSource("/a.txt");
			assertTrue(f.createNewFile());
			set(f.getUserDefinedFileAttributeView(), "k", b("v"));
			FileSource g = mem.createFileSource("/b.txt");
			assertTrue(f.renameTo(g));
			assertArrayEquals(b("v"), get(g.getUserDefinedFileAttributeView(), "k"));
		} finally {
			mem.disConnect();
		}
	}

	@Test
	public void localFiles() throws Exception {
		Path file = Files.createFile(tmp.resolve("local.txt"));
		FileProxyFactory factory = new FileProxyFactory();
		FileSource f = factory.createFileSource(file.toString());
		// a file system with no user attributes (some temp folders, FAT) says so with null rather than failing
		Assumptions.assumeTrue(f.getUserDefinedFileAttributeView() != null, "this file system keeps no user attributes");
		exercise(f);
	}

	@Test
	public void throughJavaNio() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource f = mem.createFileSource("/n.txt");
			assertTrue(f.createNewFile());
			Path p = f.toPath();
			UserDefinedFileAttributeView v = Files.getFileAttributeView(p, UserDefinedFileAttributeView.class);
			assertNotNull(v);
			set(v, "k", b("v"));
			assertArrayEquals(b("v"), get(f.getUserDefinedFileAttributeView(), "k"));
			// and none for a file that isn't there, as the JDK answers
			assertNull(Files.getFileAttributeView(mem.createFileSource("/missing").toPath(), UserDefinedFileAttributeView.class));
		} finally {
			mem.disConnect();
		}
	}

	@Test
	public void noViewByDefault() throws Throwable {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource real = mem.createFileSource("/y.txt");
			assertTrue(real.createNewFile());
			// a provider that doesn't override it gets the interface default
			java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles
					.privateLookupIn(FileSource.class, java.lang.invoke.MethodHandles.lookup())
					.unreflectSpecial(FileSource.class.getMethod("getUserDefinedFileAttributeView"), FileSource.class)
					.bindTo(proxyOver(real));
			assertNull(mh.invoke());

			// DelegatingFileSource passes the view on
			FileSource wrapped = new DelegatingFileSource(real) {
				private static final long serialVersionUID = 1L;
			};
			exercise(wrapped);
		} finally {
			mem.disConnect();
		}
	}

	/** a FileSource whose every call goes to the real file, to run the interface's default code on */
	private static FileSource proxyOver(FileSource real) {
		java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
			try {
				return method.invoke(real, args);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause();
			}
		};
		return (FileSource) java.lang.reflect.Proxy.newProxyInstance(FileSource.class.getClassLoader(),
				new Class<?>[] { FileSource.class }, h);
	}
}
