package us.bringardner.parley.files.credentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * {@code @file:PATH}: the contents of a file (one trailing line end dropped). The file must be
 * readable by its owner only, as ssh requires of a private key. For servers and for the
 * secret files Docker and Kubernetes mount. {@code ~} at the start is the home directory.
 */
public class FileCredentialProvider implements CredentialProvider {

	@Override
	public String scheme() {
		return "file";
	}

	@Override
	public String description() {
		return "files readable by their owner only";
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public char[] resolve(CredentialRef ref, CredentialRequest request) throws CredentialException {
		Path p = path(ref);
		SecretFiles.checkOwnerOnly(ref, p);
		byte[] bytes = null;
		try {
			bytes = Files.readAllBytes(p);
			return SecretFiles.chomp(SecretFiles.decode(bytes, bytes.length));
		} catch (NoSuchFileException e) {
			throw new CredentialException(ref, p+" doesn't exist");
		} catch (IOException e) {
			throw new CredentialException(ref, "can't read "+p, e);
		} finally {
			if( bytes != null ) {
				Arrays.fill(bytes, (byte) 0);
			}
		}
	}

	@Override
	public boolean canStore() {
		return true;
	}

	@Override
	public void store(CredentialRef ref, char[] secret) throws CredentialException {
		Path p = path(ref);
		byte[] bytes = SecretFiles.encode(secret);
		try {
			SecretFiles.writeOwnerOnly(p, bytes);
		} catch (IOException e) {
			throw new CredentialException(ref, "can't write "+p, e);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	@Override
	public void remove(CredentialRef ref) throws CredentialException {
		Path p = path(ref);
		try {
			if( !Files.deleteIfExists(p)) {
				throw new CredentialException(ref, p+" doesn't exist");
			}
		} catch (CredentialException e) {
			throw e;
		} catch (IOException e) {
			throw new CredentialException(ref, "can't delete "+p, e);
		}
	}

	private static Path path(CredentialRef ref) throws CredentialException {
		if( ref.locator().isEmpty()) {
			throw new CredentialException(ref, "names no file");
		}
		return SecretFiles.expand(ref.locator());
	}
}
