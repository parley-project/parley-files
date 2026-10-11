package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
