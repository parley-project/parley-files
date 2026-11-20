package us.bringardner.parley.files.test;

import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Memory file systems for a user the test chooses. The permission checks are the user's (the user running the test
 * decides them otherwise: the superuser, as when a test runs as root, is not held to the permission bits, D79).
 */
public final class MemoryUsers {

	private MemoryUsers() {
	}

	/** A user who is held to the permission bits, whoever runs the test. */
	public static MemoryFileSourceFactory nonRoot() {
		return as(new FileSourceUser(1000, "tester", 1000, "testers"));
	}

	/** The superuser, who may give files away and is not held to the permission bits. */
	public static MemoryFileSourceFactory root() {
		return as(new FileSourceUser(0, "root", 0, "wheel"));
	}

	public static MemoryFileSourceFactory as(final FileSourceUser user) {
		return new MemoryFileSourceFactory() {
			private static final long serialVersionUID = 1L;

			@Override
			public FileSourceUser whoAmI() {
				return user;
			}
		};
	}
}
