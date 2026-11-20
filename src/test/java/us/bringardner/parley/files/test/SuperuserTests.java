package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceGroup;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * The superuser (the user with id 0) is not held to the permission bits and may give files away, as on Unix (D79); anyone
 * else is held to them, and gives a file only to a group they are in and only when they own it.
 */
public class SuperuserTests {

	private MemoryFileSourceFactory factory;

	@AfterEach
	void close() throws IOException {
		if( factory != null ) {
			factory.disConnect();
		}
	}

	private FileSource file(MemoryFileSourceFactory f, String path) throws IOException {
		factory = f;
		factory.connect();
		FileSource s = factory.createFileSource(path);
		try (OutputStream out = s.getOutputStream()) {
			out.write(1);
		}
		return s;
	}

	private static void mode(FileSource f, int m) throws IOException {
		assertTrue(f.setOwnerReadable((m & 0400) != 0) & f.setOwnerWritable((m & 0200) != 0) & f.setOwnerExecutable((m & 0100) != 0)
				& f.setGroupReadable((m & 040) != 0) & f.setGroupWritable((m & 020) != 0) & f.setGroupExecutable((m & 010) != 0)
				& f.setOtherReadable((m & 04) != 0) & f.setOtherWritable((m & 02) != 0) & f.setOtherExecutable((m & 01) != 0));
	}

	@Test
	void theSuperuserIsNotHeldToTheBits() throws IOException {
		Assumptions.assumeFalse(FileSourceFactory.isWindows());
		FileSource f = file(MemoryUsers.root(), "/a.txt");
		mode(f, 0);
		assertTrue(f.canRead());
		assertTrue(f.canWrite());
		assertFalse(f.canExecute(), "a file nobody can run is not run by the superuser either");
		mode(f, 0100);
		assertTrue(f.canExecute());
		mode(f, 0);
		FileSource d = factory.createFileSource("/d");
		assertTrue(d.mkdirs());
		mode(d, 0);
		assertTrue(d.canExecute(), "the superuser may search any folder");
		assertTrue(d.canRead());
		assertTrue(d.canWrite());
	}

	@Test
	void anotherUserIsHeldToThem() throws IOException {
		FileSource f = file(MemoryUsers.nonRoot(), "/a.txt");
		mode(f, 0);
		assertFalse(f.canRead());
		assertFalse(f.canWrite());
		assertFalse(f.canExecute());
		mode(f, 0600);
		assertTrue(f.canRead());
		assertTrue(f.canWrite());
		assertFalse(f.canExecute());
	}

	@Test
	void onlyTheSuperuserGivesAFileAway() throws IOException {
		Assumptions.assumeFalse(FileSourceFactory.isWindows());
		FileSourceUser pat = new FileSourceUser(1001, "pat", 1001, "pats");
		FileSource mine = file(MemoryUsers.nonRoot(), "/mine.txt");
		assertTrue(mine.setOwner(mine.getOwner()), "the same owner is no change");
		assertFalse(mine.setOwner(pat), "an ordinary user may not give a file away");
		assertTrue(mine.getOwner().getName().equals("tester"));
		factory.disConnect();

		FileSource theirs = file(MemoryUsers.root(), "/theirs.txt");
		assertTrue(theirs.setOwner(pat));
		assertTrue(theirs.getOwner().getName().equals("pat"));
		assertTrue(theirs.setGroup(new FileSourceGroup(4242, "anything")));
	}

	@Test
	void anOwnerGivesAFileToAGroupTheyAreIn() throws IOException {
		Assumptions.assumeFalse(FileSourceFactory.isWindows());
		FileSource mine = file(MemoryUsers.nonRoot(), "/mine.txt");
		assertTrue(mine.setGroup(new FileSourceGroup(1000, "testers")), "the group it has");
		assertFalse(mine.setGroup(new FileSourceGroup(4242, "strangers")), "a group the owner is not in");
		assertTrue(mine.getGroup().getName().equals("testers"));
	}
}
