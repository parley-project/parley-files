package us.bringardner.parley.files.lock;

import java.io.IOException;

/** {@link us.bringardner.parley.files.FileSource#lock lock(...)} waited as long as it was allowed to. */
public class LockTimeoutException extends IOException {

	private static final long serialVersionUID = 1L;

	public LockTimeoutException(String message) {
		super(message);
	}
}
