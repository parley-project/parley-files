package us.bringardner.parley.files.credentials;

import java.io.IOException;

/**
 * A reference couldn't be resolved, stored or removed. The message names the reference and
 * says why; it never holds a secret, so it is safe to show and log.
 */
public class CredentialException extends IOException {

	private static final long serialVersionUID = 1L;

	private final CredentialRef ref;

	public CredentialException(CredentialRef ref, String reason) {
		super((ref == null ? "" : ref+": ")+reason);
		this.ref = ref;
	}

	public CredentialException(CredentialRef ref, String reason, Throwable cause) {
		super((ref == null ? "" : ref+": ")+reason, cause);
		this.ref = ref;
	}

	/** The reference that failed, or null. */
	public CredentialRef getRef() {
		return ref;
	}
}
