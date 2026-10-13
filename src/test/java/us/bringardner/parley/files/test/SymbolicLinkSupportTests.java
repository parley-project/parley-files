package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.fileproxy.SymbolicLinkSupport;
import us.bringardner.parley.files.fileproxy.SymbolicLinkSupport.SymbolicLinkPrivilegeException;

/**
 * On Windows a symbolic link needs Developer Mode or an elevated process. What the library says
 * when it can't make one is checked here, whichever way this machine is set up.
 */
public class SymbolicLinkSupportTests {

	@TempDir
	Path dir;

	private FileProxy file(String name) {
		return new FileProxy(new File(dir.toFile(), name), new FileProxyFactory());
	}

	/**
	 * Off by default. A CI job that is meant to have the privilege (a Windows runner is an
	 * administrator) runs with -Dparley.requireSymlinks=true, so a runner that lost it fails the
	 * build instead of skipping the link tests.
	 */
	@Test
	public void symbolicLinksAreRequiredWhenAskedFor() {
		Assumptions.assumeTrue(Boolean.getBoolean("parley.requireSymlinks"), "parley.requireSymlinks is not set");
		assertTrue(SymbolicLinkSupport.isAvailable(), "parley.requireSymlinks is set but this process cannot create "
				+ "symbolic links. " + SymbolicLinkSupport.advice());
	}

	@Test
	public void probeAgreesWithMakingALink() throws IOException {
		Path target = Files.createFile(dir.resolve("t"));
		boolean made;
		try {
			Files.createSymbolicLink(dir.resolve("l"), target);
			made = true;
		} catch (IOException | UnsupportedOperationException e) {
			made = false;
		}
		assertEquals(made, SymbolicLinkSupport.isAvailableIn(dir));
	}

	@Test
	public void theProbeLeavesNothingBehind() throws IOException {
		SymbolicLinkSupport.isAvailableIn(dir);
		try (var s = Files.list(dir)) {
			assertEquals(0, s.count());
		}
	}

	@Test
	public void aLinkIsMadeOrTheErrorSaysWhatToDo() throws Exception {
		FileSource target = file("five.txt");
		target.createNewFile();
		FileSource link = file("ln.txt");

		if( SymbolicLinkSupport.isAvailable() ) {
			FileSource made = target.getFileSourceFactory().createSymbolicLink(link, target);
			assertNotNull(made.getLinkedTo());
			return;
		}

		//  the explanation is for Windows; elsewhere the operating system's error is passed on as it is
		Assumptions.assumeTrue(SymbolicLinkSupport.isWindows(), "the privilege message is Windows only");
		SymbolicLinkPrivilegeException e = assertThrows(SymbolicLinkPrivilegeException.class,
				() -> target.getFileSourceFactory().createSymbolicLink(link, target));
		assertTrue(e.getMessage().contains("Developer Mode"), e.getMessage());
		assertTrue(e.getMessage().contains("ln.txt"), "names the link: " + e.getMessage());
		assertNotNull(e.getCause(), "keeps the operating system's own error");
		assertFalse(link.exists());
	}

	@Test
	public void aLinkThatIsAlreadyThereIsNotBlamedOnPrivilege() throws Exception {
		Assumptions.assumeTrue(SymbolicLinkSupport.isAvailable(), "needs symbolic links");
		FileSource target = file("five.txt");
		target.createNewFile();
		FileSource link = file("ln.txt");
		link.createNewFile();
		assertThrows(java.nio.file.FileAlreadyExistsException.class,
				() -> target.getFileSourceFactory().createSymbolicLink(link, target));
	}
}
