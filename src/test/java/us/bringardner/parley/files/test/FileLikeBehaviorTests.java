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
}
