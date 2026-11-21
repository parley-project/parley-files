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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Extended attributes (D101): the FileSource methods on the memory provider, on local files, and the default
 * for a provider that has nowhere to keep them.
 */
public class ExtendedAttributeTests {

	@TempDir
	Path tmp;

	private static byte[] b(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/** the same behaviour whoever keeps the attributes */
	private void exercise(FileSource f) throws IOException {
		assertTrue(f.isExtendedAttributesSupported());
		assertEquals(Collections.emptyList(), f.getExtendedAttributeNames());
		assertNull(f.getExtendedAttribute("origin"));
		assertFalse(f.removeExtendedAttribute("origin"));

		f.setExtendedAttribute("origin", b("https://example.com/a"));
		f.setExtendedAttribute("tag", new byte[] { 0, 1, 2, (byte) 255 });
		f.setExtendedAttribute("empty", new byte[0]);
		List<String> names = f.getExtendedAttributeNames();
		assertEquals(3, names.size());
		assertTrue(names.containsAll(List.of("origin", "tag", "empty")));
		assertArrayEquals(b("https://example.com/a"), f.getExtendedAttribute("origin"));
		assertArrayEquals(new byte[] { 0, 1, 2, (byte) 255 }, f.getExtendedAttribute("tag"));
		assertArrayEquals(new byte[0], f.getExtendedAttribute("empty"));

		f.setExtendedAttribute("origin", b("replaced"));
		assertArrayEquals(b("replaced"), f.getExtendedAttribute("origin"));
		assertEquals(3, f.getExtendedAttributeNames().size());

		assertTrue(f.removeExtendedAttribute("tag"));
		assertFalse(f.removeExtendedAttribute("tag"));
		assertNull(f.getExtendedAttribute("tag"));
		assertEquals(2, f.getExtendedAttributeNames().size());

		// what comes back is a copy, not the stored bytes
		byte[] v = f.getExtendedAttribute("origin");
		v[0] = 'X';
		assertArrayEquals(b("replaced"), f.getExtendedAttribute("origin"));
	}

	@Test
	public void memoryProvider() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource f = mem.createFileSource("/x.txt");
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
			f.setExtendedAttribute("k", b("v"));
			FileSource g = mem.createFileSource("/b.txt");
			assertTrue(f.renameTo(g));
			assertArrayEquals(b("v"), g.getExtendedAttribute("k"));
		} finally {
			mem.disConnect();
		}
	}

	@Test
	public void localFiles() throws Exception {
		Path file = Files.createFile(tmp.resolve("local.txt"));
		FileProxyFactory factory = new FileProxyFactory();
		FileSource f = factory.createFileSource(file.toString());
		// a file system with no user attributes (some temp folders, FAT) says so rather than failing
		Assumptions.assumeTrue(f.isExtendedAttributesSupported(), "this file system keeps no user attributes");
		exercise(f);
	}

	@Test
	public void unsupportedByDefault() throws Exception {
		MemoryFileSourceFactory mem = new MemoryFileSourceFactory();
		try {
			mem.connect(new Properties());
			FileSource real = mem.createFileSource("/y.txt");
			assertTrue(real.createNewFile());
			// a provider that doesn't override anything gets the interface defaults
			FileSource bare = withDefaults(real);
			assertFalse(bare.isExtendedAttributesSupported());
			assertThrows(UnsupportedOperationException.class, () -> bare.getExtendedAttributeNames());
			assertThrows(UnsupportedOperationException.class, () -> bare.getExtendedAttribute("a"));
			assertThrows(UnsupportedOperationException.class, () -> bare.setExtendedAttribute("a", new byte[0]));
			assertThrows(UnsupportedOperationException.class, () -> bare.removeExtendedAttribute("a"));

			// DelegatingFileSource passes all five to the file it wraps
			FileSource wrapped = new DelegatingFileSource(real) {
				private static final long serialVersionUID = 1L;
			};
			exercise(wrapped);
		} finally {
			mem.disConnect();
		}
	}

	/**
	 * A FileSource that has never heard of extended attributes: every call goes to the real file except the five,
	 * which run the interface's own default code (what a provider that doesn't override them gets).
	 */
	private static FileSource withDefaults(FileSource real) {
		java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
			if( method.getName().endsWith("ExtendedAttribute") || method.getName().endsWith("ExtendedAttributes")
					|| method.getName().endsWith("ExtendedAttributeNames") || method.getName().equals("isExtendedAttributesSupported") ) {
				java.lang.invoke.MethodHandle mh = java.lang.invoke.MethodHandles
						.privateLookupIn(FileSource.class, java.lang.invoke.MethodHandles.lookup())
						.unreflectSpecial(method, FileSource.class).bindTo(proxy);
				try {
					return mh.invokeWithArguments(args == null ? new Object[0] : args);
				} catch (Throwable t) {
					throw t;
				}
			}
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
