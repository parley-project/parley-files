package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

	@AfterEach
	void noDifferences() {
		if( !differences.isEmpty() ) {
			fail("A FileSource must answer as a java.io.File does; " + differences.size() + " difference(s):\n  "
					+ String.join("\n  ", differences));
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
				"mkdir", "mkdirs", "createNewFile", "canRead", "canWrite"}) {
			same("plain.txt", m);
		}
		same("five.txt", "length");
	}

	// ------------------------------------------------------------ a directory

	@Test
	void aDirectory() throws Exception {
		for(String m : new String[] {"exists", "isFile", "isDirectory", "list", "listFiles", "mkdir",
				"mkdirs", "canRead", "canWrite"}) {
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

}
