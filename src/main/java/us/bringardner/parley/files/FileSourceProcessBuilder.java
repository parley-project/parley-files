package us.bringardner.parley.files;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a program on the system a {@link FileSourceFactory}'s files are on, the way
 * {@link java.lang.ProcessBuilder} starts one on this machine: this is to ProcessBuilder what
 * {@link FileSource} is to {@link java.io.File}. Get one from
 * {@link FileSourceFactory#processBuilder(List)}; a factory that can't run programs returns null
 * there, and {@link FileSourceCapabilities#runsCommands()} is false for its files.
 * <p>
 * As with ProcessBuilder, a builder is not thread-safe, and it can start several processes: each
 * {@link #start()} uses the command, directory and environment it has at the time. The
 * {@link Process} it returns is an ordinary java.lang.Process: write to its
 * {@link Process#getOutputStream()}, read its {@link Process#getInputStream()} and
 * {@link Process#getErrorStream()}, and {@link Process#waitFor()} its exit status.
 * <p>
 * What depends on the file system:
 * <ol>
 * <li>{@link #environment()}: on the local file system it starts as a copy of this JVM's
 * environment, as ProcessBuilder's does. On a remote one it starts empty and holds the variables
 * to set on top of the environment the server gives the program (which can't be cleared).</li>
 * <li>{@link #directory(FileSource)} null: the local file system's default is this JVM's working
 * directory; a remote one's is the user's home directory on the server.</li>
 * <li>How the program is found and run: locally, as ProcessBuilder does; remotely, the
 * file system's own documentation says (SFTP: by the user's shell on the server).</li>
 * </ol>
 * @since 2.0.0
 */
public abstract class FileSourceProcessBuilder {

	private final FileSourceFactory factory;
	private List<String> command;
	private FileSource directory;
	private final Map<String, String> environment;
	private boolean redirectErrorStream;

	/**
	 * @param factory the file system the programs run on
	 * @param command the program and its arguments (copied)
	 * @param environment the environment {@link #environment()} starts with (kept, not copied)
	 */
	protected FileSourceProcessBuilder(FileSourceFactory factory, List<String> command, Map<String, String> environment) {
		if( factory == null ) {
			throw new NullPointerException("factory");
		}
		this.factory = factory;
		this.command = new ArrayList<>(command);
		this.environment = environment == null ? new HashMap<>() : environment;
	}

	/** @return the file system the programs run on */
	public FileSourceFactory factory() {
		return factory;
	}

	/** @return the program and its arguments; changes to the list change the builder, as ProcessBuilder's */
	public List<String> command() {
		return command;
	}

	/** @param command the program and its arguments (the list is kept, as ProcessBuilder keeps it) @return this */
	public FileSourceProcessBuilder command(List<String> command) {
		if( command == null ) {
			throw new NullPointerException("command");
		}
		this.command = command;
		return this;
	}

	/** @param command the program and its arguments @return this */
	public FileSourceProcessBuilder command(String... command) {
		this.command = new ArrayList<>(Arrays.asList(command));
		return this;
	}

	/** @return the working directory, null for the file system's default */
	public FileSource directory() {
		return directory;
	}

	/**
	 * @param directory the working directory, a directory of this builder's file system (null:
	 *        the file system's default, see the class description)
	 * @return this
	 */
	public FileSourceProcessBuilder directory(FileSource directory) {
		this.directory = directory;
		return this;
	}

	/** @return the environment the program starts with (see the class description); changes change the builder */
	public Map<String, String> environment() {
		return environment;
	}

	/** @return whether the error output goes to the same stream as the output */
	public boolean redirectErrorStream() {
		return redirectErrorStream;
	}

	/**
	 * @param redirectErrorStream true: the program's error output is merged into its output
	 *        ({@link Process#getInputStream()}), and {@link Process#getErrorStream()} is empty
	 * @return this
	 */
	public FileSourceProcessBuilder redirectErrorStream(boolean redirectErrorStream) {
		this.redirectErrorStream = redirectErrorStream;
		return this;
	}

	/**
	 * Starts the program.
	 * @return the running program
	 * @throws IndexOutOfBoundsException if the command is empty, as ProcessBuilder does
	 * @throws NullPointerException if an element of the command, or a name or value of the
	 *         environment, is null
	 * @throws IllegalArgumentException if an environment name is empty or contains '=' or a NUL
	 * @throws IOException if the directory is not on this builder's file system, or the program
	 *         can't be started
	 */
	public final Process start() throws IOException {
		List<String> cmd = new ArrayList<>(command);
		if( cmd.isEmpty()) {
			throw new IndexOutOfBoundsException("The command is empty");
		}
		for (String s : cmd) {
			if( s == null ) {
				throw new NullPointerException("An element of the command is null");
			}
		}
		Map<String, String> env = new HashMap<>();
		for (Map.Entry<String, String> e : environment.entrySet()) {
			String name = e.getKey();
			if( name == null || e.getValue() == null ) {
				throw new NullPointerException("A name or value of the environment is null");
			}
			if( name.isEmpty() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0 ) {
				throw new IllegalArgumentException("Not an environment variable name: "+name);
			}
			env.put(name, e.getValue());
		}
		FileSource dir = null;
		if( directory != null ) {
			dir = DelegatingFileSource.unwrap(directory);
			if( !factory.isSameFileSystem(dir.getFileSourceFactory())) {
				throw new IOException(directory.getAbsolutePath()+" is not on the file system the program runs on ("
						+factory.getTypeId()+")");
			}
		}
		return launch(cmd, dir, env, redirectErrorStream);
	}

	/**
	 * Starts the program, after {@link #start()} has checked what it was given.
	 * @param command the program and its arguments, not empty, no nulls
	 * @param directory the working directory, a file of this file system (unwrapped from any
	 *        DelegatingFileSource), or null for the default
	 * @param environment the environment, checked as start() says
	 * @param redirectErrorStream whether the error output is merged into the output
	 * @return the running program
	 * @throws IOException if it can't be started
	 */
	protected abstract Process launch(List<String> command, FileSource directory, Map<String, String> environment,
			boolean redirectErrorStream) throws IOException;
}
