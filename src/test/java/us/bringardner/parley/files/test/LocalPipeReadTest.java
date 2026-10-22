package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * Reading a named pipe through the local file system (FileProxy), as fsh's process substitution
 * does ({@code wc -l < <(echo one)}). A stream from {@code Files.newInputStream} fails on a pipe
 * ("Illegal seek" from {@code available()}), so FileProxy reads anything that isn't a regular
 * file with a FileInputStream.
 */
public class LocalPipeReadTest {

	@TempDir
	Path dir;

	private Path fifo() throws Exception {
		Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().startsWith("windows"), "named pipes");
		Path p = dir.resolve("pipe");
		Process mk;
		try {
			mk = new ProcessBuilder("mkfifo", p.toString()).inheritIO().start();
		} catch (java.io.IOException e) {
			Assumptions.abort("no mkfifo here");
			return null;
		}
		Assumptions.assumeTrue(mk.waitFor(10, TimeUnit.SECONDS) && mk.exitValue() == 0, "mkfifo failed");
		return p;
	}

	private static CompletableFuture<Void> write(Path p, String text) {
		return CompletableFuture.runAsync(() -> {
			try (FileOutputStream out = new FileOutputStream(p.toFile())) {
				out.write(text.getBytes(StandardCharsets.UTF_8));
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static FileSource source(Path p) throws Exception {
		return FileSourceFactory.getDefaultFactory().createFileSource(p.toString());
	}

	@Test
	public void aPipeCanBeRead() throws Exception {
		Path p = fifo();
		CompletableFuture<Void> writer = write(p, "one\ntwo\n");
		try (InputStream in = source(p).getInputStream()) {
			in.available(); // what threw "Illegal seek"
			assertEquals("one\ntwo\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		writer.get(10, TimeUnit.SECONDS);
	}

	@Test
	public void aPipeCanBeReadFromAPosition() throws Exception {
		Path p = fifo();
		CompletableFuture<Void> writer = write(p, "one\ntwo\n");
		try (InputStream in = source(p).getInputStream(4)) {
			assertEquals("two\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		writer.get(10, TimeUnit.SECONDS);
	}

	@Test
	public void aRegularFileStillReadsAsBefore() throws Exception {
		Path f = Files.writeString(dir.resolve("f.txt"), "one\ntwo\n");
		try (InputStream in = source(f).getInputStream()) {
			assertEquals(8, in.available());
			assertEquals("one\ntwo\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		try (InputStream in = source(f).getInputStream(4)) {
			assertEquals("two\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void aMissingFileIsStillFileNotFound() throws Exception {
		FileSource missing = source(dir.resolve("missing"));
		assertThrows(FileNotFoundException.class, () -> missing.getInputStream());
	}
}
