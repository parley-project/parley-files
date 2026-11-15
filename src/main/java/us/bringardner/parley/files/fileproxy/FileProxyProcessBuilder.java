package us.bringardner.parley.files.fileproxy;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceProcessBuilder;

/**
 * Starts programs on this machine, through the JDK's {@link ProcessBuilder}: the environment
 * starts as a copy of this JVM's, and a null directory is this JVM's working directory.
 * @since 2.0.0
 */
final class FileProxyProcessBuilder extends FileSourceProcessBuilder {

	FileProxyProcessBuilder(FileProxyFactory factory, List<String> command) {
		super(factory, command, new HashMap<>(System.getenv()));
	}

	@Override
	protected Process launch(List<String> command, FileSource directory, Map<String, String> environment,
			boolean redirectErrorStream) throws IOException {
		ProcessBuilder b = new ProcessBuilder(command);
		if( directory != null ) {
			b.directory(((FileProxy) directory).getTarget());
		}
		Map<String, String> env = b.environment();
		env.clear();
		env.putAll(environment);
		b.redirectErrorStream(redirectErrorStream);
		return b.start();
	}
}
