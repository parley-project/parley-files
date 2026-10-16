package us.bringardner.parley.files.lock;

import java.io.IOException;

/**
 * A lock is no longer held: it was released, or its lease ran out. Thrown by
 * {@link FileSourceLock#checkValid()} so that a holder doesn't commit work it no longer has the right to.
 */
public class LockLostException extends IOException {

	private static final long serialVersionUID = 1L;

	public LockLostException(String message) {
		super(message);
	}
}
