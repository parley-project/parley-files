package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * A FileSource acts like a java.io.File: it can do more, never less, so every question a File
 * can answer gets the answer a File gives, in the same situation. This runs one list of
 * situations against a real java.io.File tree and against the same tree in a backend, and
 * they must answer alike.
 * <p>
 * A backend adds a subclass that says where its tree is:
 * <pre>
 * public class MyBackendFileLikeTest extends FileLikeBehaviorTests {
 *     protected FileSource sourceFor(String relative) { ... }
 * }
 * </pre>
 * Both trees start with the same files: {@code dir/} with {@code dir/inner/} in it, an empty
 * {@code plain.txt}, and a 5 byte {@code five.txt}. Every case runs on a tree of its own, so one
 * case's changes never show up in another.
 */
public abstract class FileLikeBehaviorTests {

	/**
	 * The backend's FileSource for a path under its (empty) test root, such as "dir/inner". The
	 * root is the same for every call of one test, and a different one for each test.
	 */
	protected abstract FileSource sourceFor(String relative) throws Exception;

	/**
	 * Whether canRead/canWrite/canExecute of paths that exist can be compared with a java.io.File's.
	 * A File answers for the user running the test. A remote backend answers for the user it logged
	 * in as, from the permission bits the server lists; if the test can't log in as the user who owns
	 * the files, those answers legitimately differ and a backend says so by returning false. (A path
	 * that doesn't exist is always compared: nothing can be done to it.)
	 */
	protected boolean permissionsOfExistingPathsAreComparable() {
		return true;
	}

	/**
	 * Whether the backend can create symbolic links ({@link FileSourceFactory#createSymbolicLink}).
	 * A backend whose protocol has no links says false, and the link tests are skipped for it.
	 */
	protected boolean supportsSymbolicLinks() {
		return true;
	}

	/** Whether the backend can create hard links ({@link FileSourceFactory#createLink}). */
	protected boolean supportsHardLinks() {
		return true;
	}

	/**
	 * Whether the backend has {@link FileSource#getRandomAccessStream(String)} and
	 * {@link FileSource#getSeekableInputStream()}; the FTP protocol can't write in the middle of a file.
	 */
	protected boolean supportsRandomAccess() {
		return true;
	}

	/**
	 * The smallest step of time the backend can store for a file, in milliseconds. A java.io.File's
	 * depends on the file system, a millisecond on this one. SFTP (version 3) and FTP store whole
	 * seconds, and say so with 1000: a time set is then stored truncated to a multiple of it.
	 */
	protected long modifiedTimeResolutionMillis() {
		return 1;
	}

	/** Called before each test, to give the backend an empty tree to work in. */
	protected abstract void newTree() throws Exception;

	private Path oracleRoot;

	@BeforeEach
	void buildBothTrees() throws Exception {
		resetTrees();
	}

	/** Gives both sides a fresh, identical tree (a test with several cases that change it uses this between them). */
	private void resetTrees() throws Exception {
		newTree();
		oracleRoot = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "filelike-oracle");
		File root = oracleRoot.toFile();
		new File(root, "dir").mkdir();
		new File(root, "dir/inner").mkdir();
		new File(root, "plain.txt").createNewFile();
		Files.write(new File(root, "five.txt").toPath(), new byte[5]);

		sourceFor("dir").mkdir();
		sourceFor("dir/inner").mkdir();
		sourceFor("plain.txt").createNewFile();
		try (OutputStream out = sourceFor("five.txt").getOutputStream()) {
			out.write(new byte[5]);
		}
	}

	private File oracle(String relative) {
		return new File(oracleRoot.toFile(), relative);
	}

	/** What a question answers, in a form that can be compared between a File and a FileSource. */
	private static String answer(Object subject, String method) {
		try {
			Method m = subject.getClass().getMethod(method);
			m.setAccessible(true);
			Object v = m.invoke(subject);
			if( v == null ) {
				return "null";
			}
			if( v instanceof Object[] ) {
				return "array of " + ((Object[]) v).length;
			}
			return String.valueOf(v);
		} catch (InvocationTargetException e) {
			// IOException, FileNotFoundException, NoSuchFileException: all of them are what a
			// method declared "throws IOException" is allowed to throw, so they are the same answer
			Throwable cause = e.getCause();
			return "throws " + (cause instanceof java.io.IOException ? "IOException" : cause.getClass().getSimpleName());
		} catch (ReflectiveOperationException e) {
			return "no such method";
		}
	}

	/** Every difference a test finds, so one run shows all of them and not just the first. */
	private final List<String> differences = new ArrayList<>();

	private void same(String relative, String method) throws Exception {
		if( (method.equals("canRead") || method.equals("canWrite") || method.equals("canExecute"))
				&& oracle(relative).exists() && !permissionsOfExistingPathsAreComparable() ) {
			return;
		}
		String expected = answer(oracle(relative), method);
		String got = answer(sourceFor(relative), method);
		if( !expected.equals(got) ) {
			differences.add(relative + "." + method + "(): java.io.File " + expected + ", FileSource " + got);
		}
	}

	/**
	 * Differences a backend has not fixed yet, as the start of the text of each (such as
	 * "dir.setExecutable(false)"). They are printed as "KNOWN DIFFERENCE" and don't fail the test, so
	 * an unfixed one stays visible, and a new one still fails. Keep this empty if you can.
	 */
	protected java.util.Set<String> knownDifferences() {
		return java.util.Collections.emptySet();
	}

	@AfterEach
	void noDifferences() {
		if( !differences.isEmpty() ) {
			// a subclass may share one instance between its tests, so this test's list must not leak
			List<String> mine = new ArrayList<>(differences);
			differences.clear();
			List<String> failing = new ArrayList<>();
			for(String d : mine) {
				boolean known = false;
				for(String prefix : knownDifferences()) {
					if( d.startsWith(prefix) ) {
						known = true;
						System.out.println("KNOWN DIFFERENCE: " + d);
					}
				}
				if( !known ) {
					failing.add(d);
				}
			}
			if( !failing.isEmpty() ) {
				fail("A FileSource must answer as a java.io.File does; " + failing.size() + " difference(s):\n  "
						+ String.join("\n  ", failing));
			}
		}
	}

	// ------------------------------------------------------------ what a missing path says

	@Test
	void aMissingPath() throws Exception {
		for(String m : new String[] {"exists", "isFile", "isDirectory", "length", "lastModified", "canRead",
				"canWrite", "list", "listFiles", "isHidden", "delete"}) {
			same("missing.txt", m);
		}
	}

	// ------------------------------------------------------------ a file is not a directory

	@Test
	void aPlainFile() throws Exception {
		for(String m : new String[] {"exists", "isFile", "isDirectory", "length", "list", "listFiles",
				"mkdir", "mkdirs", "createNewFile", "canRead", "canWrite", "canExecute"}) {
			same("plain.txt", m);
		}
		same("five.txt", "length");
	}

	// ------------------------------------------------------------ a directory

	@Test
	void aDirectory() throws Exception {
		for(String m : new String[] {"exists", "isFile", "isDirectory", "list", "listFiles", "mkdir",
				"mkdirs", "canRead", "canWrite", "canExecute"}) {
			same("dir", m);
		}
		// not empty, so it can't be deleted
		same("dir", "delete");
	}

	// ------------------------------------------------------------ creating

	@Test
	void creatingWithAndWithoutTheParent() throws Exception {
		same("nodir/new.txt", "createNewFile");
		same("nodir/sub", "mkdir");
		same("nodir/sub", "mkdirs");
		same("nodir/sub", "exists");
		same("nodir/sub", "mkdirs");     // now it exists
		same("nodir/sub/deeper/still", "mkdirs");
		same("nodir/sub/deeper/still", "isDirectory");
	}

	@Test
	void aFileInThePathStopsMkdirs() throws Exception {
		same("plain.txt/under", "mkdir");
		same("plain.txt/under", "mkdirs");
		same("plain.txt/under", "exists");
		same("plain.txt/under", "createNewFile");
		same("plain.txt/under/deeper", "mkdirs");
	}

	@Test
	void creatingAFileThenRemovingIt() throws Exception {
		same("fresh.txt", "createNewFile");
		same("fresh.txt", "exists");
		same("fresh.txt", "createNewFile");   // again: already there
		same("fresh.txt", "delete");
		same("fresh.txt", "exists");
		same("fresh.txt", "delete");          // again: already gone
	}

	// ------------------------------------------------------------ names

	@Test
	void names() throws Exception {
		for(String r : new String[] {"plain.txt", "dir", "dir/inner"}) {
			assertEquals(oracle(r).getName(), sourceFor(r).getName(), "getName of " + r);
		}
	}

	// ------------------------------------------------------------ renaming

	/** What renaming did, as a string that can be compared: the answer and what is there afterwards. */
	private static String kind(File f) {
		return f.isFile() ? "file of " + f.length() : f.isDirectory() ? "directory" : "nothing";
	}

	private static String kind(FileSource f) throws IOException {
		return f.isFile() ? "file of " + f.length() : f.isDirectory() ? "directory" : "nothing";
	}

	private void renameCase(String from, String to) throws Exception {
		renameCase(from, to, false);
	}

	/**
	 * @param ontoExisting the destination is a file that exists. java.io.File.renameTo says this
	 *        "might not succeed if a file with the destination abstract pathname already exists",
	 *        so both outcomes are right, as long as it's one of them: the source replaced the
	 *        destination, or nothing changed.
	 */
	private void renameCase(String from, String to, boolean ontoExisting) throws Exception {
		resetTrees();
		String expected;
		File of = oracle(from), ot = oracle(to);
		String unchanged = "renamed false; source: " + kind(of) + "; target: " + kind(ot);
		try {
			boolean r = of.renameTo(ot);
			expected = "renamed " + r + "; source: " + kind(of) + "; target: " + kind(ot);
		} catch (Exception e) {
			expected = "throws " + (e instanceof IOException ? "IOException" : e.getClass().getSimpleName());
		}
		String got;
		FileSource sf = sourceFor(from), st = sourceFor(to);
		try {
			boolean r = sf.renameTo(st);
			got = "renamed " + r + "; source: " + kind(sourceFor(from)) + "; target: " + kind(sourceFor(to));
		} catch (Exception e) {
			got = "throws " + (e instanceof IOException ? "IOException" : e.getClass().getSimpleName());
		}
		boolean ok = expected.equals(got) || (ontoExisting && got.equals(unchanged));
		if( !ok ) {
			differences.add("renameTo " + from + " -> " + to + ": java.io.File [" + expected + "]"
					+ (ontoExisting ? " or [" + unchanged + "]" : "") + ", FileSource [" + got + "]");
		}
	}

	@Test
	void renamingAFileOrADirectory() throws Exception {
		renameCase("five.txt", "renamed.txt");     // a file to a new name
		renameCase("dir", "dir2");                 // a directory, with something in it
		renameCase("dir/inner", "inner2");         // up a level
		renameCase("five.txt", "dir/five.txt");    // into another directory
	}

	@Test
	void renamingWhatCantBeRenamed() throws Exception {
		renameCase("missing.txt", "other.txt");    // nothing there
		renameCase("plain.txt", "nodir/plain.txt");// into a directory that isn't there
		renameCase("plain.txt", "plain.txt");      // onto itself
	}

	@Test
	void renamingOntoSomethingThatExists() throws Exception {
		renameCase("plain.txt", "five.txt", true); // onto a file (either outcome is right)
		renameCase("five.txt", "dir");             // a file onto a directory
		renameCase("dir/inner", "plain.txt");      // a directory onto a file
	}

	// ------------------------------------------------------------ times

	private void timeCase(String relative, long when) throws Exception {
		resetTrees();
		File of = oracle(relative);
		String expected = "set " + of.setLastModified(when) + ", lastModified " + of.lastModified();
		FileSource sf = sourceFor(relative);
		String got;
		try {
			// FileSource.setLastModified is File's: a time in milliseconds
			got = "set " + sf.setLastModified(when) + ", lastModified " + sourceFor(relative).lastModified();
		} catch (Exception e) {
			got = "throws " + (e instanceof IOException ? "IOException" : e.getClass().getSimpleName());
		}
		if( !expected.equals(got) ) {
			differences.add(relative + ".setLastModified(" + when + "): java.io.File [" + expected + "], FileSource [" + got + "]");
		}
	}

	@Test
	void settingTheModifiedTime() throws Exception {
		long when = 1_600_000_000_000L;           // a whole second, so no file system rounds it
		timeCase("five.txt", when);
		timeCase("dir", when);
		timeCase("missing.txt", when);
	}

	// ------------------------------------------------------------ permissions

	private String permissionOutcome(boolean result, boolean canRead, boolean canWrite, boolean canExecute, boolean exists) {
		StringBuilder sb = new StringBuilder("set ").append(result);
		if( exists && permissionsOfExistingPathsAreComparable() ) {
			sb.append(", canRead ").append(canRead).append(", canWrite ").append(canWrite)
				.append(", canExecute ").append(canExecute);
		}
		return sb.toString();
	}

	private interface FilePermission { boolean apply(File f); }
	private interface SourcePermission { boolean apply(FileSource f) throws IOException; }

	private void permissionCase(String label, String relative, FilePermission onFile, SourcePermission onSource) throws Exception {
		resetTrees();
		File of = oracle(relative);
		boolean existed = of.exists();
		String expected = permissionOutcome(onFile.apply(of), of.canRead(), of.canWrite(), of.canExecute(), existed);
		String got;
		try {
			FileSource sf = sourceFor(relative);
			boolean r = onSource.apply(sf);
			FileSource after = sourceFor(relative);
			got = permissionOutcome(r, after.canRead(), after.canWrite(), after.canExecute(), existed);
		} catch (Exception e) {
			got = "throws " + (e instanceof IOException ? "IOException" : e.getClass().getSimpleName());
		}
		if( !expected.equals(got) ) {
			differences.add(relative + "." + label + ": java.io.File [" + expected + "], FileSource [" + got + "]");
		}
		restorePermissions(relative);
	}

	/**
	 * Puts back what a permission case took away, on both sides, so a backend whose tree outlives the
	 * test (a server's directory) isn't left with a directory nobody can write to or delete from.
	 */
	private void restorePermissions(String relative) {
		File of = oracle(relative);
		boolean directory = of.isDirectory();
		of.setReadable(true);
		of.setWritable(true);
		if( directory ) {
			// a directory without its execute bit can't be entered, so nothing in it can be reached
			of.setExecutable(true);
		}
		try {
			FileSource sf = sourceFor(relative);
			sf.setReadable(true);
			sf.setWritable(true);
			if( directory ) {
				sf.setExecutable(true);
			}
		} catch (Exception e) {
			// best effort: the case has already been judged
		}
	}

	@Test
	void permissionSetters() throws Exception {
		permissionCase("setWritable(false)", "five.txt", f -> f.setWritable(false), f -> f.setWritable(false));
		permissionCase("setWritable(false, true)", "five.txt", f -> f.setWritable(false, true), f -> f.setWritable(false, true));
		permissionCase("setReadOnly()", "five.txt", f -> f.setReadOnly(), f -> f.setReadOnly());
		permissionCase("setExecutable(true)", "five.txt", f -> f.setExecutable(true), f -> f.setExecutable(true));
		permissionCase("setExecutable(true, false)", "five.txt", f -> f.setExecutable(true, false), f -> f.setExecutable(true, false));
		permissionCase("setExecutable(false)", "dir", f -> f.setExecutable(false), f -> f.setExecutable(false));
		permissionCase("setExecutable(true)", "missing.txt", f -> f.setExecutable(true), f -> f.setExecutable(true));
		permissionCase("setReadable(false)", "five.txt", f -> f.setReadable(false), f -> f.setReadable(false));
		permissionCase("setWritable(false)", "dir", f -> f.setWritable(false), f -> f.setWritable(false));
		permissionCase("setWritable(false)", "missing.txt", f -> f.setWritable(false), f -> f.setWritable(false));
		permissionCase("setReadable(true)", "missing.txt", f -> f.setReadable(true), f -> f.setReadable(true));
	}

	// ------------------------------------------------------------ names

	@Test
	void anUnusualNameIsJustAName() throws Exception {
		for(String name : new String[] {"a b.txt", "\u00fcn\u00ef.txt", "UPPER.TXT", "dots.in.name", "-dash", "semi;colon"}) {
			resetTrees();
			File of = oracle(name);
			String expected = "created " + of.createNewFile() + ", exists " + of.exists() + ", name " + of.getName();
			FileSource sf = sourceFor(name);
			String got;
			try {
				got = "created " + sf.createNewFile() + ", exists " + sourceFor(name).exists() + ", name " + sf.getName();
			} catch (Exception e) {
				got = "throws " + (e instanceof IOException ? "IOException" : e.getClass().getSimpleName());
			}
			if( !expected.equals(got) ) {
				differences.add("name '" + name + "': java.io.File [" + expected + "], FileSource [" + got + "]");
			}
			// and it is in its parent's list
			java.util.Set<String> expectedNames = new java.util.TreeSet<>(java.util.Arrays.asList(oracle("").list()));
			String[] gotList = sourceFor("").list();
			java.util.Set<String> gotNames = gotList == null ? null : new java.util.TreeSet<>(java.util.Arrays.asList(gotList));
			if( !expectedNames.equals(gotNames) ) {
				differences.add("list() after creating '" + name + "': java.io.File " + expectedNames + ", FileSource " + gotNames);
			}
		}
	}

	@Test
	void waysOfWritingThePath() throws Exception {
		for(String m : new String[] {"exists", "isDirectory", "isFile"}) {
			same("dir/", m);                 // a trailing separator
			same("dir/./inner", m);          // a dot
			same("dir/../plain.txt", m);     // a dot-dot
			same("dir//inner", m);           // a doubled separator
		}
		for(String r : new String[] {"dir/inner", "dir/./inner", "dir/../dir/inner"}) {
			String expected = oracle(r).getName();
			String got = sourceFor(r).getName();
			if( !expected.equals(got) ) {
				differences.add("getName() of '" + r + "': java.io.File '" + expected + "', FileSource '" + got + "'");
			}
		}
	}

	// ------------------------------------------------------------ streams

	/** What running something yields, or the kind of exception it threw, comparable between a File and a FileSource. */
	private static String outcome(java.util.concurrent.Callable<String> what) {
		try {
			return what.call();
		} catch (java.io.IOException e) {
			return "throws IOException";
		} catch (Exception e) {
			return "throws " + e.getClass().getSimpleName();
		}
	}

	private void streamsAgree(String label, java.util.concurrent.Callable<String> onFile, java.util.concurrent.Callable<String> onSource) {
		String expected = outcome(onFile);
		String got = outcome(onSource);
		if( !expected.equals(got) ) {
			differences.add(label + ": java.io.File [" + expected + "], FileSource [" + got + "]");
		}
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for(byte x : b) {
			sb.append(String.format("%02x", x & 0xff));
		}
		return sb.toString();
	}

	@Test
	void openingForReadWhatCantBeRead() throws Exception {
		for(String r : new String[] {"nope.txt", "dir", "nodir/x.txt", "plain.txt/under"}) {
			streamsAgree("open '" + r + "' for reading", () -> {
				try (java.io.InputStream in = new java.io.FileInputStream(oracle(r))) {
					return "opened";
				}
			}, () -> {
				try (java.io.InputStream in = sourceFor(r).getInputStream()) {
					return "opened";
				}
			});
		}
	}

	@Test
	void openingForWriteWhatCantBeWritten() throws Exception {
		for(String r : new String[] {"dir", "nodir/x.txt", "plain.txt/under"}) {
			streamsAgree("open '" + r + "' for writing", () -> {
				try (OutputStream out = new java.io.FileOutputStream(oracle(r))) {
					return "opened";
				}
			}, () -> {
				try (OutputStream out = sourceFor(r).getOutputStream()) {
					return "opened";
				}
			});
			streamsAgree("open '" + r + "' for appending", () -> {
				try (OutputStream out = new java.io.FileOutputStream(oracle(r), true)) {
					return "opened";
				}
			}, () -> {
				try (OutputStream out = sourceFor(r).getOutputStream(true)) {
					return "opened";
				}
			});
		}
	}

	@Test
	void writingCreatesTruncatesAndAppends() throws Exception {
		byte[] all = new byte[256];
		for(int i = 0; i < 256; i++) {
			all[i] = (byte) i;
		}
		// a new file, and one open and closed with nothing written
		for(String r : new String[] {"made.bin", "empty.bin"}) {
			byte[] data = r.equals("empty.bin") ? new byte[0] : all;
			streamsAgree("write new '" + r + "'", () -> {
				try (OutputStream out = new java.io.FileOutputStream(oracle(r))) {
					out.write(data);
				}
				return "exists " + oracle(r).exists() + ", length " + oracle(r).length() + ", " + hex(Files.readAllBytes(oracle(r).toPath()));
			}, () -> {
				FileSource f = sourceFor(r);
				try (OutputStream out = f.getOutputStream()) {
					out.write(data);
				}
				FileSource again = sourceFor(r);
				try (java.io.InputStream in = again.getInputStream()) {
					return "exists " + again.exists() + ", length " + again.length() + ", " + hex(in.readAllBytes());
				}
			});
		}
		// five.txt exists: replacing it truncates, appending keeps what is there
		streamsAgree("truncate then append", () -> {
			try (OutputStream out = new java.io.FileOutputStream(oracle("five.txt"))) {
				out.write(new byte[] {1, 2});
			}
			try (OutputStream out = new java.io.FileOutputStream(oracle("five.txt"), true)) {
				out.write(new byte[] {3, 4});
			}
			return hex(Files.readAllBytes(oracle("five.txt").toPath())) + ", length " + oracle("five.txt").length();
		}, () -> {
			try (OutputStream out = sourceFor("five.txt").getOutputStream()) {
				out.write(new byte[] {1, 2});
			}
			try (OutputStream out = sourceFor("five.txt").getOutputStream(true)) {
				out.write(new byte[] {3, 4});
			}
			FileSource f = sourceFor("five.txt");
			try (java.io.InputStream in = f.getInputStream()) {
				return hex(in.readAllBytes()) + ", length " + f.length();
			}
		});
	}

	@Test
	void openingWithAppendFalseReplaces() throws Exception {
		streamsAgree("append=false onto five.txt", () -> {
			try (OutputStream out = new java.io.FileOutputStream(oracle("five.txt"), false)) {
				out.write(new byte[] {7});
			}
			return hex(Files.readAllBytes(oracle("five.txt").toPath()));
		}, () -> {
			try (OutputStream out = sourceFor("five.txt").getOutputStream(false)) {
				out.write(new byte[] {7});
			}
			try (java.io.InputStream in = sourceFor("five.txt").getInputStream()) {
				return hex(in.readAllBytes());
			}
		});
	}

	@Test
	void readingTheEdges() throws Exception {
		byte[] data = new byte[300];
		for(int i = 0; i < data.length; i++) {
			data[i] = (byte) (i * 7);
		}
		data[10] = (byte) 0xFF;
		data[11] = (byte) 0x80;
		Files.write(oracle("edges.bin").toPath(), data);
		try (OutputStream out = sourceFor("edges.bin").getOutputStream()) {
			out.write(data);
		}
		java.util.function.Function<java.util.concurrent.Callable<java.io.InputStream>, String> probe = open -> outcome(() -> {
			StringBuilder sb = new StringBuilder();
			try (java.io.InputStream in = open.call()) {
				sb.append("zero ").append(in.read(new byte[4], 0, 0)).append(";");
				sb.append("skip3 ").append(in.skip(3)).append(";");
				sb.append("next ").append(in.read()).append(";");
				byte[] block = new byte[16];
				int n = in.read(block, 2, 10);
				sb.append("block ").append(n).append(" ").append(hex(java.util.Arrays.copyOfRange(block, 2, 2 + n))).append(";");
				sb.append("rest ").append(in.readAllBytes().length).append(";");
				sb.append("eof ").append(in.read()).append(",").append(in.read(new byte[4])).append(";");
				sb.append("skipAtEof ").append(in.skip(5) <= 5).append(";");
				sb.append("stillEof ").append(in.read());
			}
			return sb.toString();
		});
		String expected = probe.apply(() -> new java.io.FileInputStream(oracle("edges.bin")));
		String got = probe.apply(() -> sourceFor("edges.bin").getInputStream());
		if( !expected.equals(got) ) {
			differences.add("reading edges.bin: java.io.File [" + expected + "], FileSource [" + got + "]");
		}
	}

	@Test
	void closingTwiceAndTwoReadersAtOnce() throws Exception {
		Files.write(oracle("two.bin").toPath(), new byte[] {9, 8, 7, 6});
		try (OutputStream out = sourceFor("two.bin").getOutputStream()) {
			out.write(new byte[] {9, 8, 7, 6});
		}
		streamsAgree("close twice", () -> {
			java.io.InputStream in = new java.io.FileInputStream(oracle("two.bin"));
			in.close();
			in.close();
			return "ok";
		}, () -> {
			java.io.InputStream in = sourceFor("two.bin").getInputStream();
			in.close();
			in.close();
			return "ok";
		});
		streamsAgree("two readers at once", () -> {
			try (java.io.InputStream a = new java.io.FileInputStream(oracle("two.bin")); java.io.InputStream b = new java.io.FileInputStream(oracle("two.bin"))) {
				return a.read() + "," + b.read() + "," + a.read() + "," + b.read();
			}
		}, () -> {
			try (java.io.InputStream a = sourceFor("two.bin").getInputStream(); java.io.InputStream b = sourceFor("two.bin").getInputStream()) {
				return a.read() + "," + b.read() + "," + a.read() + "," + b.read();
			}
		});
	}

	// ------------------------------------------------------------ links

	private FileSource symlink(String link, String target) throws Exception {
		FileSource t = sourceFor(target);
		return t.getFileSourceFactory().createSymbolicLink(sourceFor(link), t);
	}

	private FileSource hardlink(String link, String target) throws Exception {
		FileSource t = sourceFor(target);
		return t.getFileSourceFactory().createLink(sourceFor(link), t);
	}

	private void oracleSymlink(String link, String target) throws Exception {
		Files.createSymbolicLink(oracle(link).toPath(), oracle(target).toPath());
	}

	@Test
	void aSymbolicLinkToAFileActsLikeTheFile() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsSymbolicLinks());
		oracleSymlink("ln.txt", "five.txt");
		symlink("ln.txt", "five.txt");
		for(String m : new String[] {"exists", "isFile", "isDirectory", "length", "getName"}) {
			same("ln.txt", m);
		}
		// what is read through the link is what is in the target
		try (OutputStream out = sourceFor("five.txt").getOutputStream()) {
			out.write(new byte[] {4, 5, 6});
		}
		Files.write(oracle("five.txt").toPath(), new byte[] {4, 5, 6});
		streamsAgree("read through the link", () -> hex(Files.readAllBytes(oracle("ln.txt").toPath())), () -> {
			try (java.io.InputStream in = sourceFor("ln.txt").getInputStream()) {
				return hex(in.readAllBytes());
			}
		});
		same("ln.txt", "length");
		// writing through it changes the target
		streamsAgree("write through the link", () -> {
			try (OutputStream out = new java.io.FileOutputStream(oracle("ln.txt"))) {
				out.write(new byte[] {9});
			}
			return hex(Files.readAllBytes(oracle("five.txt").toPath()));
		}, () -> {
			try (OutputStream out = sourceFor("ln.txt").getOutputStream()) {
				out.write(new byte[] {9});
			}
			try (java.io.InputStream in = sourceFor("five.txt").getInputStream()) {
				return hex(in.readAllBytes());
			}
		});
	}

	@Test
	void aSymbolicLinkToADirectoryActsLikeTheDirectory() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsSymbolicLinks());
		oracleSymlink("lndir", "dir");
		symlink("lndir", "dir");
		for(String m : new String[] {"exists", "isFile", "isDirectory", "list"}) {
			same("lndir", m);
		}
		same("lndir/inner", "isDirectory");
		streamsAgree("list through the link", () -> new java.util.TreeSet<>(java.util.Arrays.asList(oracle("lndir").list())).toString(),
				() -> new java.util.TreeSet<>(java.util.Arrays.asList(sourceFor("lndir").list())).toString());
	}

	@Test
	void aSymbolicLinkIsItsOwnEntry() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsSymbolicLinks());
		oracleSymlink("ln.txt", "five.txt");
		symlink("ln.txt", "five.txt");
		// it is in its parent's list, and says where it points; the target says it points nowhere
		streamsAgree("link is listed", () -> new java.util.TreeSet<>(java.util.Arrays.asList(oracle("").list())).toString(),
				() -> new java.util.TreeSet<>(java.util.Arrays.asList(sourceFor("").list())).toString());
		FileSource linked = sourceFor("ln.txt").getLinkedTo();
		if( linked == null ) {
			differences.add("ln.txt.getLinkedTo(): a symbolic link to five.txt, FileSource null");
		} else if( !linked.getName().equals("five.txt") ) {
			differences.add("ln.txt.getLinkedTo(): java.io.File five.txt, FileSource " + linked.getName());
		}
		if( sourceFor("five.txt").getLinkedTo() != null ) {
			differences.add("five.txt.getLinkedTo(): not a link, FileSource not null");
		}
		// deleting the link leaves the target; deleting the target leaves a link that points at nothing
		same("ln.txt", "delete");
		same("ln.txt", "exists");
		same("five.txt", "exists");
		oracleSymlink("ln2.txt", "plain.txt");
		symlink("ln2.txt", "plain.txt");
		same("plain.txt", "delete");
		same("ln2.txt", "exists");
		same("ln2.txt", "isFile");
	}

	@Test
	void aSymbolicLinkMayPointAtNothing() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsSymbolicLinks());
		oracleSymlink("dangling", "nope.txt");
		symlink("dangling", "nope.txt");
		for(String m : new String[] {"exists", "isFile", "isDirectory", "length"}) {
			same("dangling", m);
		}
		same("dangling", "delete");
		same("dangling", "exists");
	}

	@Test
	void aHardLinkIsAnotherNameForTheSameFile() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsHardLinks());
		Files.createLink(oracle("hard.txt").toPath(), oracle("five.txt").toPath());
		hardlink("hard.txt", "five.txt");
		for(String m : new String[] {"exists", "isFile", "length"}) {
			same("hard.txt", m);
		}
		streamsAgree("write through a hard link", () -> {
			try (OutputStream out = new java.io.FileOutputStream(oracle("hard.txt"))) {
				out.write(new byte[] {1, 2, 3, 4, 5, 6, 7});
			}
			return oracle("five.txt").length() + " " + hex(Files.readAllBytes(oracle("five.txt").toPath()));
		}, () -> {
			try (OutputStream out = sourceFor("hard.txt").getOutputStream()) {
				out.write(new byte[] {1, 2, 3, 4, 5, 6, 7});
			}
			FileSource t = sourceFor("five.txt");
			try (java.io.InputStream in = t.getInputStream()) {
				return t.length() + " " + hex(in.readAllBytes());
			}
		});
		// deleting one name leaves the other
		same("five.txt", "delete");
		same("hard.txt", "exists");
		same("hard.txt", "length");
		if( sourceFor("hard.txt").getLinkedTo() != null ) {
			differences.add("hard.txt.getLinkedTo(): a hard link is not a symbolic link, FileSource not null");
		}
	}

	// ------------------------------------------------------------ a handle that was kept

	/**
	 * A java.io.File asks the file system every time, so a File made before a change sees it.
	 * These tests keep one handle, ask it something (so anything it remembers is remembered),
	 * change the file through a different object, and ask the first one again.
	 */
	private String describe(Object subject, String... methods) {
		StringBuilder sb = new StringBuilder();
		for(String m : methods) {
			if( sb.length() > 0 ) {
				sb.append(", ");
			}
			sb.append(m).append(' ').append(answer(subject, m));
		}
		return sb.toString();
	}

	private void heldAgree(String label, File heldFile, FileSource heldSource, String... methods) {
		String expected = describe(heldFile, methods);
		String got = describe(heldSource, methods);
		if( !expected.equals(got) ) {
			differences.add("held handle, " + label + ": java.io.File [" + expected + "], FileSource [" + got + "]");
		}
	}

	private static final String[] BASICS = {"exists", "isFile", "isDirectory", "length"};

	@Test
	void aHeldHandleSeesAFileBeingCreated() throws Exception {
		File heldFile = oracle("later.txt");
		FileSource held = sourceFor("later.txt");
		heldAgree("before", heldFile, held, BASICS);

		Files.write(oracle("later.txt").toPath(), new byte[] {1, 2, 3, 4});
		try (OutputStream out = sourceFor("later.txt").getOutputStream()) {
			out.write(new byte[] {1, 2, 3, 4});
		}
		heldAgree("after it was created", heldFile, held, BASICS);
	}

	@Test
	void aHeldHandleSeesAFileBeingDeleted() throws Exception {
		File heldFile = oracle("five.txt");
		FileSource held = sourceFor("five.txt");
		heldAgree("before", heldFile, held, BASICS);

		oracle("five.txt").delete();
		sourceFor("five.txt").delete();
		heldAgree("after it was deleted", heldFile, held, BASICS);
		heldAgree("after it was deleted (again)", heldFile, held, "exists", "isFile");
	}

	@Test
	void aHeldHandleSeesAFileBeingRewritten() throws Exception {
		File heldFile = oracle("five.txt");
		FileSource held = sourceFor("five.txt");
		heldAgree("before", heldFile, held, BASICS);

		byte[] longer = new byte[] {9, 8, 7, 6, 5, 4, 3, 2, 1};
		Files.write(oracle("five.txt").toPath(), longer);
		try (OutputStream out = sourceFor("five.txt").getOutputStream()) {
			out.write(longer);
		}
		heldAgree("after it was rewritten", heldFile, held, "length");
		streamsAgree("content through the held handle", () -> hex(Files.readAllBytes(heldFile.toPath())), () -> {
			try (java.io.InputStream in = held.getInputStream()) {
				return hex(in.readAllBytes());
			}
		});
	}

	@Test
	void aHeldDirectorySeesChildrenComingAndGoing() throws Exception {
		File heldFile = oracle("dir");
		FileSource held = sourceFor("dir");
		heldAgree("before", heldFile, held, "list");
		java.util.function.Function<String[], String> names = n -> n == null ? "null" : new java.util.TreeSet<>(java.util.Arrays.asList(n)).toString();
		streamsAgree("before", () -> names.apply(heldFile.list()), () -> names.apply(held.list()));

		oracle("dir/new.txt").createNewFile();
		sourceFor("dir/new.txt").createNewFile();
		streamsAgree("after a child was made", () -> names.apply(heldFile.list()), () -> names.apply(held.list()));
		streamsAgree("listFiles after a child was made", () -> "" + heldFile.listFiles().length, () -> "" + held.listFiles().length);

		oracle("dir/new.txt").delete();
		sourceFor("dir/new.txt").delete();
		streamsAgree("after the child was deleted", () -> names.apply(heldFile.list()), () -> names.apply(held.list()));
	}

	@Test
	void aHeldHandleSeesAFileBeingMovedAway() throws Exception {
		File heldFile = oracle("plain.txt");
		FileSource held = sourceFor("plain.txt");
		heldAgree("before", heldFile, held, BASICS);

		oracle("plain.txt").renameTo(oracle("moved.txt"));
		sourceFor("plain.txt").renameTo(sourceFor("moved.txt"));
		heldAgree("after it was moved", heldFile, held, BASICS);
		for(String m : BASICS) {
			same("moved.txt", m);
		}
	}

	@Test
	void aHeldHandleSeesAFileBecomeADirectory() throws Exception {
		File heldFile = oracle("plain.txt");
		FileSource held = sourceFor("plain.txt");
		heldAgree("before", heldFile, held, BASICS);

		oracle("plain.txt").delete();
		oracle("plain.txt").mkdir();
		sourceFor("plain.txt").delete();
		sourceFor("plain.txt").mkdir();
		// (not the length: a File gives a directory entry's size, which is whatever the platform says)
		heldAgree("after it became a directory", heldFile, held, "exists", "isFile", "isDirectory");
	}

	@Test
	void aHeldHandleSeesPermissionsChange() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(permissionsOfExistingPathsAreComparable());
		File heldFile = oracle("five.txt");
		FileSource held = sourceFor("five.txt");
		heldAgree("before", heldFile, held, "canRead", "canWrite");
		try {
			oracle("five.txt").setReadOnly();
			sourceFor("five.txt").setReadOnly();
			heldAgree("after setReadOnly", heldFile, held, "canRead", "canWrite");
		} finally {
			oracle("five.txt").setWritable(true);
			sourceFor("five.txt").setWritable(true);
		}
		heldAgree("after it was made writable again", heldFile, held, "canWrite");
	}

	// ------------------------------------------------------------ random access

	/** What a scenario can do to a RandomAccessFile or to the backend's stream, the same way. */
	private interface Ra {
		int read() throws java.io.IOException;
		int read(byte[] b, int off, int len) throws java.io.IOException;
		void write(int b) throws java.io.IOException;
		void write(byte[] b, int off, int len) throws java.io.IOException;
		void seek(long pos) throws java.io.IOException;
		long length() throws java.io.IOException;
		void setLength(long len) throws java.io.IOException;
		long getFilePointer() throws java.io.IOException;
		java.io.DataInput in();
		java.io.DataOutput out();
		void close() throws java.io.IOException;
	}

	private interface Scenario {
		String run(Ra ra) throws Exception;
	}

	private static Ra of(java.io.RandomAccessFile f) {
		return new Ra() {
			public int read() throws java.io.IOException { return f.read(); }
			public int read(byte[] b, int off, int len) throws java.io.IOException { return f.read(b, off, len); }
			public void write(int b) throws java.io.IOException { f.write(b); }
			public void write(byte[] b, int off, int len) throws java.io.IOException { f.write(b, off, len); }
			public void seek(long pos) throws java.io.IOException { f.seek(pos); }
			public long length() throws java.io.IOException { return f.length(); }
			public void setLength(long len) throws java.io.IOException { f.setLength(len); }
			public long getFilePointer() throws java.io.IOException { return f.getFilePointer(); }
			public java.io.DataInput in() { return f; }
			public java.io.DataOutput out() { return f; }
			public void close() throws java.io.IOException { f.close(); }
		};
	}

	private static Ra of(us.bringardner.parley.files.IRandomAccessStream f) {
		return new Ra() {
			public int read() throws java.io.IOException { return f.read(); }
			public int read(byte[] b, int off, int len) throws java.io.IOException { return f.read(b, off, len); }
			public void write(int b) throws java.io.IOException { f.write(b); }
			public void write(byte[] b, int off, int len) throws java.io.IOException { f.write(b, off, len); }
			public void seek(long pos) throws java.io.IOException { f.seek(pos); }
			public long length() throws java.io.IOException { return f.length(); }
			public void setLength(long len) throws java.io.IOException { f.setLength(len); }
			public long getFilePointer() throws java.io.IOException { return f.getFilePointer(); }
			public java.io.DataInput in() { return f; }
			public java.io.DataOutput out() { return f; }
			public void close() throws java.io.IOException { f.close(); }
		};
	}

	private int raCount;

	/** Ten bytes 0..9. */
	private static byte[] ten() {
		byte[] b = new byte[10];
		for(int i = 0; i < b.length; i++) {
			b[i] = (byte) i;
		}
		return b;
	}

	/**
	 * Runs a scenario on a RandomAccessFile and on the backend's stream, over the same starting
	 * file (none if initial is null), and compares what each did and the file each left.
	 */
	private void randomAccess(String label, byte[] initial, String mode, Scenario scenario) throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsRandomAccess());
		String name = "ra" + (++raCount) + ".bin";
		File of = oracle(name);
		FileSource sf = sourceFor(name);
		if( initial != null ) {
			Files.write(of.toPath(), initial);
			try (OutputStream out = sf.getOutputStream()) {
				out.write(initial);
			}
		}
		String expected = outcome(() -> {
			Ra ra;
			try {
				ra = of(new java.io.RandomAccessFile(of, mode));
			} catch (java.io.IOException e) {
				return "open throws IOException";
			}
			String r;
			try {
				r = scenario.run(ra);
			} catch (java.io.IOException e) {
				r = "throws IOException";
			}
			try {
				ra.close();
			} catch (java.io.IOException e) {
				// reported by the file below
			}
			return r + " | file " + (of.exists() ? of.length() + " " + hex(Files.readAllBytes(of.toPath())) : "missing");
		});
		String got = outcome(() -> {
			Ra ra;
			try {
				ra = of(sf.getRandomAccessStream(mode));
			} catch (java.io.IOException e) {
				return "open throws IOException";
			}
			String r;
			try {
				r = scenario.run(ra);
			} catch (java.io.IOException e) {
				r = "throws IOException";
			}
			try {
				ra.close();
			} catch (java.io.IOException e) {
				// reported by the file below
			}
			FileSource again = sourceFor(name);
			String content = "missing";
			if( again.exists() ) {
				try (java.io.InputStream in = again.getInputStream()) {
					content = again.length() + " " + hex(in.readAllBytes());
				}
			}
			return r + " | file " + content;
		});
		if( !expected.equals(got) ) {
			differences.add("random access, " + label + ": java.io.RandomAccessFile [" + expected + "], FileSource [" + got + "]");
		}
	}

	@Test
	void randomAccessOpening() throws Exception {
		randomAccess("read mode, missing file", null, "r", ra -> "opened");
		randomAccess("rw mode, missing file", null, "rw", ra -> "opened, length " + ra.length());
		randomAccess("rw mode, missing file, write", null, "rw", ra -> { ra.write(7); return "length " + ra.length(); });
		randomAccess("read mode, existing", ten(), "r", ra -> "length " + ra.length() + " at " + ra.getFilePointer());
		randomAccess("rw mode, existing keeps its content", ten(), "rw", ra -> "length " + ra.length());
		randomAccess("write in read mode", ten(), "r", ra -> { ra.write(1); return "wrote"; });
		randomAccess("setLength in read mode", ten(), "r", ra -> { ra.setLength(3); return "ok"; });
		randomAccess("a directory, read", null, "r", ra -> "opened");
		// a directory and a missing parent
		for(String r : new String[] {"dir", "nodir/x.bin", "plain.txt/under"}) {
			for(String mode : new String[] {"r", "rw"}) {
				streamsAgree("random access '" + mode + "' on '" + r + "'", () -> {
					try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(oracle(r), mode)) {
						return "opened";
					}
				}, () -> {
					org.junit.jupiter.api.Assumptions.assumeTrue(supportsRandomAccess());
					try (us.bringardner.parley.files.IRandomAccessStream f = sourceFor(r).getRandomAccessStream(mode)) {
						return "opened";
					}
				});
			}
		}
	}

	@Test
	void randomAccessReadingTheEnd() throws Exception {
		randomAccess("read at the end", ten(), "r", ra -> { ra.seek(10); return ra.read() + " " + ra.read(new byte[4], 0, 4) + " " + ra.getFilePointer(); });
		randomAccess("read past the end", ten(), "r", ra -> { ra.seek(25); return ra.read() + " at " + ra.getFilePointer() + " length " + ra.length(); });
		randomAccess("a short read at the end", ten(), "r", ra -> {
			byte[] b = new byte[10];
			ra.seek(7);
			int n = ra.read(b, 0, 10);
			return n + " " + hex(java.util.Arrays.copyOf(b, Math.max(n, 0))) + " at " + ra.getFilePointer();
		});
		randomAccess("zero length read", ten(), "r", ra -> "" + ra.read(new byte[4], 0, 0));
		randomAccess("readFully past the end", ten(), "r", ra -> { ra.seek(8); ra.in().readFully(new byte[5]); return "read"; });
		randomAccess("readInt with too few bytes", ten(), "r", ra -> { ra.seek(8); return "" + ra.in().readInt(); });
		randomAccess("unsigned bytes", new byte[] {(byte) 0xFF, (byte) 0x80, 0x7F, 0}, "r", ra -> ra.read() + " " + ra.read() + " " + ra.read() + " " + ra.read() + " " + ra.read());
		randomAccess("skipBytes", ten(), "r", ra -> {
			DataInputHelper h = new DataInputHelper(ra);
			return h.skip(3) + " " + ra.getFilePointer() + " " + h.skip(100) + " " + ra.getFilePointer() + " " + h.skip(5) + " " + h.skip(-2);
		});
	}

	/** skipBytes is on DataInput, so the scenarios reach it through here. */
	private static final class DataInputHelper {
		private final Ra ra;

		DataInputHelper(Ra ra) {
			this.ra = ra;
		}

		int skip(int n) throws java.io.IOException {
			return ra.in().skipBytes(n);
		}
	}

	@Test
	void randomAccessSeekingAndGrowing() throws Exception {
		randomAccess("seek negative", ten(), "rw", ra -> { ra.seek(-1); return "seeked"; });
		randomAccess("seek past the end does not grow", ten(), "rw", ra -> { ra.seek(15); return "at " + ra.getFilePointer() + " length " + ra.length(); });
		randomAccess("write after a seek past the end", ten(), "rw", ra -> { ra.seek(15); ra.write(7); return "at " + ra.getFilePointer() + " length " + ra.length(); });
		randomAccess("write a block after a seek past the end", ten(), "rw", ra -> {
			ra.seek(12);
			ra.write(new byte[] {1, 2, 3}, 0, 3);
			return "at " + ra.getFilePointer() + " length " + ra.length();
		});
		randomAccess("overwrite in the middle", ten(), "rw", ra -> { ra.seek(3); ra.write(new byte[] {(byte) 0xAA, (byte) 0xBB}, 0, 2); return "at " + ra.getFilePointer() + " length " + ra.length(); });
		randomAccess("setLength shorter", ten(), "rw", ra -> { ra.setLength(4); return "length " + ra.length() + " at " + ra.getFilePointer(); });
		randomAccess("setLength shorter than the pointer", ten(), "rw", ra -> { ra.seek(8); ra.setLength(4); return "length " + ra.length() + " at " + ra.getFilePointer(); });
		randomAccess("setLength longer is zeros", ten(), "rw", ra -> { ra.setLength(14); return "length " + ra.length() + " at " + ra.getFilePointer(); });
		randomAccess("shrink then grow", ten(), "rw", ra -> { ra.setLength(3); ra.setLength(8); ra.seek(0); return hexRead(ra, 8); });
		randomAccess("setLength zero", ten(), "rw", ra -> { ra.setLength(0); return "length " + ra.length() + " read " + ra.read(); });
		randomAccess("write extends and reads back", new byte[0], "rw", ra -> {
			ra.write(new byte[] {5, 6, 7}, 0, 3);
			ra.seek(0);
			return hexRead(ra, 3) + " length " + ra.length();
		});
	}

	private static String hexRead(Ra ra, int n) throws java.io.IOException {
		byte[] b = new byte[n];
		int got = 0;
		while( got < n ) {
			int r = ra.read(b, got, n - got);
			if( r < 0 ) {
				break;
			}
			got += r;
		}
		return got + ":" + hex(java.util.Arrays.copyOf(b, got));
	}

	@Test
	void randomAccessTypedData() throws Exception {
		randomAccess("typed values round trip and are big-endian", new byte[0], "rw", ra -> {
			java.io.DataOutput o = ra.out();
			o.writeInt(0x01020304);
			o.writeLong(0x1122334455667788L);
			o.writeShort(-2);
			o.writeBoolean(true);
			o.writeByte(200);
			o.writeChar('Z');
			o.writeFloat(1.5f);
			o.writeDouble(-2.25);
			o.writeUTF("h\u00e9llo");
			o.writeBytes("ab");
			o.writeChars("cd");
			ra.seek(0);
			java.io.DataInput i = ra.in();
			return i.readInt() + " " + i.readLong() + " " + i.readShort() + " " + i.readBoolean() + " " + i.readUnsignedByte()
					+ " " + i.readChar() + " " + i.readFloat() + " " + i.readDouble() + " " + i.readUTF()
					+ " " + (char) i.readByte() + (char) i.readByte() + i.readChar() + i.readChar() + " at " + ra.getFilePointer();
		});
		randomAccess("readLine", "ab\r\ncd\nef".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), "r", ra -> {
			java.io.DataInput i = ra.in();
			return "[" + i.readLine() + "][" + i.readLine() + "][" + i.readLine() + "][" + i.readLine() + "]";
		});
		randomAccess("readUnsignedShort and readUnsignedByte", new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD}, "r", ra ->
				ra.in().readUnsignedShort() + " " + ra.in().readUnsignedByte());
	}

	@Test
	void randomAccessAcrossManyBlocks() throws Exception {
		// larger than any one chunk or buffer a backend uses, written and read in odd sized pieces
		byte[] big = new byte[300_000];
		new java.util.Random(11).nextBytes(big);
		randomAccess("write odd sized pieces, read pieces back", new byte[0], "rw", ra -> {
			int at = 0;
			int[] sizes = {1, 7, 4093, 65_537, 99, 131_071};
			int i = 0;
			while( at < big.length ) {
				int n = Math.min(sizes[i++ % sizes.length], big.length - at);
				ra.write(big, at, n);
				at += n;
			}
			StringBuilder sb = new StringBuilder("length " + ra.length());
			for(long pos : new long[] {0, 99, 100, 4093, 65_535, 65_536, 299_999}) {
				ra.seek(pos);
				sb.append(' ').append(hexRead(ra, 5));
			}
			return sb.toString();
		});
		randomAccess("overwrite across a block edge", big, "rw", ra -> {
			ra.seek(65_534);
			ra.write(new byte[] {1, 2, 3, 4, 5, 6}, 0, 6);
			ra.seek(65_530);
			return hexRead(ra, 16) + " length " + ra.length();
		});
	}

	@Test
	void randomAccessUseAfterClose() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsRandomAccess());
		Files.write(oracle("closed.bin").toPath(), ten());
		try (OutputStream out = sourceFor("closed.bin").getOutputStream()) {
			out.write(ten());
		}
		streamsAgree("read after close", () -> {
			java.io.RandomAccessFile f = new java.io.RandomAccessFile(oracle("closed.bin"), "r");
			f.close();
			f.close();
			return "" + f.read();
		}, () -> {
			us.bringardner.parley.files.IRandomAccessStream f = sourceFor("closed.bin").getRandomAccessStream("r");
			f.close();
			f.close();
			return "" + f.read();
		});
	}

	@Test
	void aSeekableInputStreamActsLikeAReadOnlyRandomAccessFile() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(supportsRandomAccess());
		byte[] data = new byte[1000];
		new java.util.Random(5).nextBytes(data);
		data[0] = (byte) 0xFF;
		Files.write(oracle("seek.bin").toPath(), data);
		try (OutputStream out = sourceFor("seek.bin").getOutputStream()) {
			out.write(data);
		}
		java.util.function.Function<Object, String> probe = o -> outcome(() -> {
			StringBuilder sb = new StringBuilder();
			if( o instanceof java.io.RandomAccessFile ) {
				java.io.RandomAccessFile f = (java.io.RandomAccessFile) o;
				sb.append("length ").append(f.length());
				sb.append(" first ").append(f.read());
				f.seek(500);
				byte[] b = new byte[8];
				sb.append(" at500 ").append(f.read(b, 0, 8)).append(' ').append(hex(b)).append(" ptr ").append(f.getFilePointer());
				byte[] c = new byte[10];
				f.seek(100);
				sb.append(" offset ").append(f.read(c, 3, 5)).append(' ').append(hex(c)).append(" ptr ").append(f.getFilePointer());
				f.seek(995);
				sb.append(" tail ").append(f.read(new byte[20], 0, 20)).append(" ptr ").append(f.getFilePointer());
				f.seek(2000);
				sb.append(" past ").append(f.read()).append(" ptr ").append(f.getFilePointer());
			} else {
				us.bringardner.parley.files.ISeekableInputStream f = (us.bringardner.parley.files.ISeekableInputStream) o;
				sb.append("length ").append(f.length());
				sb.append(" first ").append(f.read());
				f.seek(500);
				byte[] b = new byte[8];
				sb.append(" at500 ").append(f.read(b, 0, 8)).append(' ').append(hex(b)).append(" ptr ").append(f.getFilePointer());
				byte[] c = new byte[10];
				f.seek(100);
				sb.append(" offset ").append(f.read(c, 3, 5)).append(' ').append(hex(c)).append(" ptr ").append(f.getFilePointer());
				f.seek(995);
				sb.append(" tail ").append(f.read(new byte[20], 0, 20)).append(" ptr ").append(f.getFilePointer());
				f.seek(2000);
				sb.append(" past ").append(f.read()).append(" ptr ").append(f.getFilePointer());
			}
			return sb.toString();
		});
		String expected;
		try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(oracle("seek.bin"), "r")) {
			expected = probe.apply(f);
		}
		String got;
		us.bringardner.parley.files.ISeekableInputStream s = sourceFor("seek.bin").getSeekableInputStream();
		try {
			got = probe.apply(s);
		} finally {
			s.close();
		}
		// seeking past the end of a read-only view must not change the file
		assertEquals(1000, sourceFor("seek.bin").length(), "the file was changed by a seek");
		if( !expected.equals(got) ) {
			differences.add("seekable stream: java.io.RandomAccessFile [" + expected + "], FileSource [" + got + "]");
		}
	}

	// ------------------------------------------------------------ filters

	private void both(String relative, String... names) throws Exception {
		for(String n : names) {
			String full = relative.isEmpty() ? n : relative + "/" + n;
			if( n.endsWith("/") ) {
				oracle(full).mkdir();
				sourceFor(full.substring(0, full.length()-1)).mkdir();
			} else {
				Files.write(oracle(full).toPath(), n.equals("big.dat") ? new byte[2000] : new byte[3]);
				try (OutputStream out = sourceFor(full).getOutputStream()) {
					out.write(n.equals("big.dat") ? new byte[2000] : new byte[3]);
				}
			}
		}
	}

	private static String sorted(java.util.Collection<String> names) {
		return names == null ? "null" : new java.util.TreeSet<>(names).toString();
	}

	@Test
	void filteringAListing() throws Exception {
		both("dir", "a.txt", "b.log", "c.txt", "sub/", ".hidden", "big.dat");
		java.util.List<String[]> cases = new java.util.ArrayList<>();
		// name, then a description used in the label
		cases.add(new String[] {".txt files", "txt"});
		cases.add(new String[] {"directories", "dir"});
		cases.add(new String[] {"nothing", "none"});
		cases.add(new String[] {"everything", "all"});
		cases.add(new String[] {"large files", "big"});
		for(String[] c : cases) {
			String kind = c[1];
			java.util.function.Predicate<File> onFile = f -> {
				switch(kind) {
				case "txt": return f.getName().endsWith(".txt");
				case "dir": return f.isDirectory();
				case "none": return false;
				case "big": return f.isFile() && f.length() > 1000;
				default: return true;
				}
			};
			java.util.function.Predicate<FileSource> onSource = f -> {
				try {
					switch(kind) {
					case "txt": return f.getName().endsWith(".txt");
					case "dir": return f.isDirectory();
					case "none": return false;
					case "big": return f.isFile() && f.length() > 1000;
					default: return true;
					}
				} catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			};
			streamsAgree("listFiles(filter) of " + c[0], () -> {
				File[] r = oracle("dir").listFiles(f -> onFile.test(f));
				return sorted(r == null ? null : java.util.Arrays.stream(r).map(File::getName).collect(java.util.stream.Collectors.toList()));
			}, () -> {
				FileSource[] r = sourceFor("dir").listFiles((us.bringardner.parley.files.FileSourceFilter) f -> onSource.test(f));
				return sorted(r == null ? null : java.util.Arrays.stream(r).map(FileSource::getName).collect(java.util.stream.Collectors.toList()));
			});
			streamsAgree("list(filter) of " + c[0], () -> {
				String[] r = oracle("dir").list((d, n) -> onFile.test(new File(d, n)));
				return sorted(r == null ? null : java.util.Arrays.asList(r));
			}, () -> {
				String[] r = sourceFor("dir").list((us.bringardner.parley.files.FileSourceFilter) f -> onSource.test(f));
				return sorted(r == null ? null : java.util.Arrays.asList(r));
			});
		}
		// no filter at all lists everything
		streamsAgree("listFiles(null)", () -> sorted(java.util.Arrays.stream(oracle("dir").listFiles((java.io.FileFilter) null)).map(File::getName).collect(java.util.stream.Collectors.toList())),
				() -> sorted(java.util.Arrays.stream(sourceFor("dir").listFiles((us.bringardner.parley.files.FileSourceFilter) null)).map(FileSource::getName).collect(java.util.stream.Collectors.toList())));
		streamsAgree("list(null)", () -> sorted(java.util.Arrays.asList(oracle("dir").list((java.io.FilenameFilter) null))),
				() -> sorted(java.util.Arrays.asList(sourceFor("dir").list((us.bringardner.parley.files.FileSourceFilter) null))));
		// a filter on what isn't a directory
		for(String r : new String[] {"plain.txt", "missing"}) {
			streamsAgree("listFiles(filter) of " + r, () -> {
				File[] x = oracle(r).listFiles(f -> true);
				return x == null ? "null" : "array of " + x.length;
			}, () -> {
				FileSource[] x = sourceFor(r).listFiles((us.bringardner.parley.files.FileSourceFilter) f -> true);
				return x == null ? "null" : "array of " + x.length;
			});
		}
	}

	// ------------------------------------------------------------ larger files, written and read as streams

	private static String digest(byte[] b, int from, int to) {
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			md.update(b, from, to - from);
			return hex(md.digest()).substring(0, 16);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void largerFilesWrittenAndReadAsStreams() throws Exception {
		// sizes around the buffers and rows backends use: 1 KB, 32 KB, 64 KB, 100 KB, 256 KB
		int[] sizes = {0, 1, 1023, 1024, 1025, 32767, 32768, 32769, 65535, 65536, 65537, 102399, 102400, 102401, 262145};
		for(int n : sizes) {
			byte[] data = new byte[n];
			new java.util.Random(n).nextBytes(data);
			String name = "large" + n + ".bin";
			// written in pieces that never line up with a block
			FileSource sf = sourceFor(name);
			try (OutputStream out = sf.getOutputStream()) {
				int at = 0;
				int[] pieces = {1, 4093, 999, 20011, 65537};
				int i = 0;
				while( at < n ) {
					int len = Math.min(pieces[i++ % pieces.length], n - at);
					out.write(data, at, len);
					at += len;
				}
			}
			StringBuilder got = new StringBuilder();
			FileSource again = sourceFor(name);
			got.append("length ").append(again.length());
			try (java.io.InputStream in = again.getInputStream()) {
				byte[] all = in.readAllBytes();
				got.append(" read ").append(all.length).append(' ').append(digest(all, 0, all.length));
			}
			StringBuilder expected = new StringBuilder("length " + n + " read " + n + " " + digest(data, 0, n));
			for(long pos : new long[] {0, 1, n / 2, Math.max(0, n - 1), n, n + 10L}) {
				int from = (int) Math.min(pos, n);
				expected.append(" from ").append(pos).append(' ').append(n - from).append(' ').append(digest(data, from, n));
				try (java.io.InputStream in = again.getInputStream(pos)) {
					byte[] tail = in.readAllBytes();
					got.append(" from ").append(pos).append(' ').append(tail.length).append(' ').append(digest(tail, 0, tail.length));
				}
			}
			// skip, then read: the stream's own skip across whatever block it uses
			if( n > 4 ) {
				try (java.io.InputStream in = again.getInputStream()) {
					long skipped = in.skip(n / 2);
					byte[] rest = in.readAllBytes();
					got.append(" skip ").append(skipped + rest.length);
					expected.append(" skip ").append(n);
				}
			}
			// appended to, then replaced by something shorter
			try (OutputStream out = sourceFor(name).getOutputStream(true)) {
				out.write(new byte[] {1, 2, 3, 4, 5});
			}
			got.append(" appended ").append(sourceFor(name).length());
			expected.append(" appended ").append(n + 5);
			try (OutputStream out = sourceFor(name).getOutputStream()) {
				out.write(data, 0, n / 2);
			}
			try (java.io.InputStream in = sourceFor(name).getInputStream()) {
				byte[] half = in.readAllBytes();
				got.append(" replaced ").append(half.length).append(' ').append(digest(half, 0, half.length));
			}
			expected.append(" replaced ").append(n / 2).append(' ').append(digest(data, 0, n / 2));
			if( !expected.toString().equals(got.toString()) ) {
				differences.add("a file of " + n + " bytes: expected [" + expected + "], FileSource [" + got + "]");
			}
		}
	}

	// ------------------------------------------------------------ modified times

	@Test
	void modifiedTimeKeepsWhatTheFileSystemCanStore() throws Exception {
		long resolution = modifiedTimeResolutionMillis();
		long when = 1_600_000_000_123L;       // has milliseconds
		resetTrees();
		File of = oracle("five.txt");
		assertTrue(of.setLastModified(when));
		long expectedFile = of.lastModified();      // what this file system keeps of it
		FileSource sf = sourceFor("five.txt");
		assertTrue(sf.setLastModified(when));
		long got = sourceFor("five.txt").lastModified();
		// What java.io.File keeps depends on the file system under it (whole seconds on some). A
		// backend may keep less (its own resolution), or exactly what was set, which is more; it
		// never gives some other time.
		long floor = expectedFile - (expectedFile % resolution);
		if( got != expectedFile && got != floor && got != when ) {
			differences.add("lastModified after setLastModified(" + when + "): java.io.File " + expectedFile
					+ ", FileSource " + got + " (resolution " + resolution + " ms)");
		}
	}

	@Test
	void modifiedTimeFollowsWrites() throws Exception {
		long resolution = Math.max(modifiedTimeResolutionMillis(), 1);
		long before = System.currentTimeMillis() - 2000;
		both("", "stamp.txt");
		long made = sourceFor("stamp.txt").lastModified();
		long after = System.currentTimeMillis() + 2000 + resolution;
		if( made < before || made > after ) {
			differences.add("lastModified of a file just made: " + made + " is not within 2 s of now (" + before + ".." + after + ")");
		}
		// set it to the past, write, and it is now
		assertTrue(sourceFor("stamp.txt").setLastModified(1_600_000_000_000L));
		assertEquals(1_600_000_000_000L, sourceFor("stamp.txt").lastModified());
		try (OutputStream out = sourceFor("stamp.txt").getOutputStream()) {
			out.write(new byte[] {1, 2, 3, 4});
		}
		long written = sourceFor("stamp.txt").lastModified();
		if( written < before ) {
			differences.add("lastModified after a write is " + written + ", which is not recent");
		}
	}

	@Test
	void aDirectoryIsModifiedWhenAChildIsAdded() throws Exception {
		// java.io.File does this on the file systems that matter; a backend with its own rows may not
		resetTrees();
		File od = oracle("dir");
		od.setLastModified(1_600_000_000_000L);
		sourceFor("dir").setLastModified(1_600_000_000_000L);
		new File(od, "child.txt").createNewFile();
		sourceFor("dir/child.txt").createNewFile();
		boolean expected = od.lastModified() > 1_600_000_000_000L;
		boolean got = sourceFor("dir").lastModified() > 1_600_000_000_000L;
		if( expected != got ) {
			differences.add("a directory's lastModified after a child was added: java.io.File changed " + expected + ", FileSource changed " + got);
		}
	}
}
