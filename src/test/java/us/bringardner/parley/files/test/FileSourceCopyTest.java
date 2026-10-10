package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Random;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCopy;
import us.bringardner.parley.files.StreamOptions;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

public class FileSourceCopyTest {

	/** a source that says what buffer it wants, and notes the options its streams were opened with */
	private static class Spy {
		final FileSource source;
		StreamOptions inputOptions;
		StreamOptions outputOptions;

		Spy(FileSource target, int wants) {
			source = (FileSource) Proxy.newProxyInstance(FileSource.class.getClassLoader(), new Class<?>[] { FileSource.class },
					(proxy, method, args) -> {
						if( method.getName().equals("getStreamDefaults") && args == null ) {
							return wants > 0 ? StreamOptions.buffer(wants) : StreamOptions.NONE;
						}
						if( method.getName().equals("getInputStream") && args != null && args.length == 1 ) {
							inputOptions = (StreamOptions) args[0];
						}
						if( method.getName().equals("getOutputStream") && args != null && args.length == 2 ) {
							outputOptions = (StreamOptions) args[1];
						}
						try {
							return method.invoke(target, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}
	}

	@Test
	void largerOfTheTwoWinsWithinLimits() throws Exception {
		MemoryFileSourceFactory f = new MemoryFileSourceFactory();
		FileSource a = f.createFileSource("/a");
		assertEquals(FileSourceCopy.DEFAULT_BLOCK, FileSourceCopy.blockSizeFor(a, a), "neither says");
		assertEquals(100_000, FileSourceCopy.blockSizeFor(new Spy(a, 100_000).source, new Spy(a, 20_000).source));
		assertEquals(FileSourceCopy.MIN_BLOCK, FileSourceCopy.blockSizeFor(new Spy(a, 100).source, a));
		assertEquals(FileSourceCopy.MAX_BLOCK, FileSourceCopy.blockSizeFor(new Spy(a, 64 * 1024 * 1024).source, a));
	}

	@Test
	void copiesBinaryContentAndOpensStreamsWithTheBlock() throws Exception {
		MemoryFileSourceFactory f = new MemoryFileSourceFactory();
		byte[] data = new byte[300_007];
		new Random(7).nextBytes(data);
		FileSource from = f.createFileSource("/from");
		try (OutputStream out = from.getOutputStream()) {
			out.write(data);
		}
		Spy src = new Spy(from, 50_000);
		Spy dst = new Spy(f.createFileSource("/to"), 0);

		assertEquals(data.length, FileSourceCopy.copy(src.source, dst.source));

		assertEquals(50_000, src.inputOptions.bufferSize());
		assertEquals(50_000, dst.outputOptions.bufferSize());
		try (InputStream in = f.createFileSource("/to").getInputStream()) {
			assertArrayEquals(data, in.readAllBytes());
		}
	}

	@Test
	void emptyFileCopies() throws Exception {
		MemoryFileSourceFactory f = new MemoryFileSourceFactory();
		FileSource from = f.createFileSource("/e");
		from.createNewFile();
		FileSource to = f.createFileSource("/e2");
		assertEquals(0, FileSourceCopy.copy(from, to));
		assertEquals(true, to.exists());
		assertEquals(0, to.length());
	}
}
