package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceProcessBuilder;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** FileSourceProcessBuilder: the local file system runs programs; one that can't returns null. */
public class FileSourceProcessBuilderTest {

	@TempDir
	Path tmp;

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	/** what p wrote to its output, after it ended; every stream of p is closed */
	private static String run(Process p, String input) throws Exception {
		try {
			try (OutputStream in = p.getOutputStream()) {
				if( input != null ) {
					in.write(input.getBytes(StandardCharsets.UTF_8));
				}
			}
			String out;
			try (InputStream o = p.getInputStream()) {
				out = new String(o.readAllBytes(), StandardCharsets.UTF_8);
			}
			assertTrue(p.waitFor(60, TimeUnit.SECONDS));
			return out.replace("\r\n", "\n");
		} finally {
			p.getErrorStream().close();
			p.destroyForcibly();
		}
	}

	@Test
	public void localRunsInTheDirectoryWithTheEnvironment() throws Exception {
		assumeFalse(windows());
		FileSourceFactory f = new FileProxyFactory();
		FileSource dir = f.createFileSource(tmp.toRealPath().toString());
		FileSourceProcessBuilder b = f.processBuilder("sh", "-c", "pwd; echo \"$PARLEY_TEST\"; cat; echo oops >&2; exit 3");
		assertNotNull(b);
		b.directory(dir).redirectErrorStream(true).environment().put("PARLEY_TEST", "it's set");
		Process p = b.start();
		assertEquals(tmp.toRealPath()+"\nit's set\nfrom stdin\noops\n", run(p, "from stdin\n"));
		assertEquals(3, p.exitValue());
	}

	@Test
	public void environmentStartsAsThisJvms() {
		FileSourceProcessBuilder b = new FileProxyFactory().processBuilder("x");
		assertEquals(System.getenv(), b.environment());
	}

	@Test
	public void checksWhatItIsGiven() throws IOException {
		FileSourceFactory f = new FileProxyFactory();
		assertThrows(IndexOutOfBoundsException.class, () -> f.processBuilder().start());
		FileSourceProcessBuilder b = f.processBuilder("x");
		b.environment().put("A=B", "1");
		assertThrows(IllegalArgumentException.class, b::start);
		// a directory of another file system
		MemoryFileSourceFactory memory = new MemoryFileSourceFactory();
		FileSource elsewhere = memory.createFileSource("/");
		IOException e = assertThrows(IOException.class, () -> f.processBuilder("x").directory(elsewhere).start());
		assertTrue(e.getMessage().contains("not on the file system the program runs on"), e.getMessage());
	}

	@Test
	public void onlyFileSystemsThatRunProgramsHaveOne() throws IOException {
		FileSourceFactory local = new FileProxyFactory();
		assertTrue(local.createFileSource(tmp.toString()).getCapabilities().runsCommands());
		MemoryFileSourceFactory memory = new MemoryFileSourceFactory();
		assertNull(memory.processBuilder("ls"));
		assertFalse(memory.createFileSource("/").getCapabilities().runsCommands());
	}
}
