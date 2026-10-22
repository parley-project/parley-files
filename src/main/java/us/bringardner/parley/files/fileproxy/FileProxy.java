/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.01.05-V000.01.04-V000.01.00-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.files.fileproxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;


import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourcePrincipal;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockManager;
import us.bringardner.parley.files.lock.LockScope;


/**
 * @author Tony Bringardner
 * Acts as a proxy to a File Object to expose the FileSource interface
 */
public class FileProxy implements FileSource {

	public interface PermissionManager {
		boolean canRead() throws IOException;
		boolean canWrite() throws IOException;
		boolean canExecute() throws IOException;
		boolean canOwnerExecute() throws IOException;
		boolean canOwnerRead() throws IOException;
		boolean canOwnerWrite() throws IOException;
		boolean canGroupRead() throws IOException;
		boolean canGroupWrite() throws IOException;
		boolean canGroupExecute() throws IOException;
		boolean canOtherRead() throws IOException;
		boolean canOtherWrite() throws IOException;
		boolean canOtherExecute() throws IOException;

		boolean setExecutable(boolean b, boolean ownerOnly) throws IOException;
		boolean setReadable(boolean b, boolean  ownerOnly) throws IOException;
		boolean setWritable(boolean b, boolean  ownerOnly) throws IOException;
		boolean setExecutable(boolean b) throws IOException;
		boolean setReadable(boolean b) throws IOException;
		boolean setWritable(boolean b) throws IOException;
		boolean setGroupExecutable(boolean b) throws IOException;
		boolean setGroupReadable(boolean b) throws IOException;
		boolean setGroupWritable(boolean b) throws IOException;
		boolean setOwnerReadable(boolean b) throws IOException;
		boolean setOwnerWritable(boolean b) throws IOException;
		boolean setOwnerExecutable(boolean b) throws IOException;
		boolean setOtherReadable(boolean b) throws IOException;
		boolean setOtherWritable(boolean b) throws IOException;
		boolean setOtherExecutable(boolean b) throws IOException;

		public boolean setLastAccessTime(long time) throws IOException ;

		public boolean setCreateTime(long time) throws IOException;
		public boolean setGroup(GroupPrincipal group) throws IOException; 
	}

	private class PosixPermissionManager implements PermissionManager {
		/* (non-Javadoc)
		 * @see us.bringardner.parley.files.FileSource#canRead()
		 */
		public boolean canRead() throws IOException {
			return target.canRead();
		}

		/* (non-Javadoc)
		 * @see us.bringardner.parley.files.FileSource#canWrite()
		 */
		public boolean canWrite() {
			return target.canWrite();
		}
		
		@Override
		public boolean canExecute() throws IOException {
			return target.canExecute();
		}

		@Override
		public boolean canOwnerExecute() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.OWNER_EXECUTE);
			}
			return ret;
		}

		@Override
		public boolean canOwnerRead() throws IOException {		 
			return canRead();
		}
		@Override
		public boolean canOwnerWrite() throws IOException {		
			return canWrite();
		}
		@Override
		public boolean canGroupRead() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.GROUP_READ);
			}
			return ret;
		}
		@Override
		public boolean canGroupWrite() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.GROUP_WRITE);
			}
			return ret;
		}

		@Override
		public boolean canGroupExecute() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.GROUP_EXECUTE);
			}
			return ret;
		}

		@Override
		public boolean canOtherRead() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.OTHERS_READ);
			}
			return ret;
		}

		@Override
		public boolean canOtherWrite() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.OTHERS_WRITE);
			}
			return ret;
		}
		@Override
		public boolean canOtherExecute() throws IOException {
			boolean ret = false;
			Set<PosixFilePermission> p = getPosixPermissions();
			if( p != null ) {
				ret =  p.contains(PosixFilePermission.OTHERS_EXECUTE);
			}
			return ret;
		}

		@Override
		public boolean setExecutable(boolean executable, boolean ownerOnly) {
			return target.setExecutable(executable, ownerOnly);		
		}



		@Override
		public boolean setReadable(boolean readable, boolean ownerOnly) {
			return target.setReadable(readable, ownerOnly);

		}



		@Override
		public boolean setWritable(boolean writetable, boolean ownerOnly) {
			return target.setWritable(writetable, ownerOnly);

		}



		@Override
		public boolean setExecutable(boolean executable)  throws IOException {
			return target.setExecutable(executable);

		}



		@Override
		public boolean setReadable(boolean readable) throws IOException {
			boolean ret = target.setReadable(readable);
			return ret;

		}



		@Override
		public boolean setWritable(boolean writetable)  throws IOException {
			return target.setWritable(writetable);		
		}

		@Override
		public boolean setGroupExecutable(boolean executable) throws IOException {
			return setPosixPermision(executable, PosixFilePermission.GROUP_EXECUTE);		
		}

		@Override
		public boolean setGroupReadable(boolean readable) throws IOException {
			return setPosixPermision(readable, PosixFilePermission.GROUP_READ);
		}

		@Override
		public boolean setGroupWritable(boolean writeable) throws IOException {
			return setPosixPermision(writeable, PosixFilePermission.GROUP_WRITE);
		}

		@Override
		public boolean setOwnerReadable(boolean readable) throws IOException {
			return setPosixPermision(readable, PosixFilePermission.OWNER_READ);
		}

		@Override
		public boolean setOwnerWritable(boolean writeable) throws IOException {
			return setPosixPermision(writeable, PosixFilePermission.OWNER_WRITE);
		}

		@Override
		public boolean setOwnerExecutable(boolean executable) throws IOException {		
			return setPosixPermision(executable, PosixFilePermission.OWNER_EXECUTE);
		}

		@Override
		public boolean setOtherReadable(boolean readable) throws IOException {
			return setPosixPermision(readable,PosixFilePermission.OTHERS_READ);
		}

		@Override
		public boolean setOtherWritable(boolean writeable) throws IOException {
			return setPosixPermision(writeable,PosixFilePermission.OTHERS_WRITE);
		}

		@Override
		public boolean setOtherExecutable(boolean executable) throws IOException {
			return setPosixPermision(executable,PosixFilePermission.OTHERS_EXECUTE);
		}
		@Override
		public boolean setLastAccessTime(long time) throws IOException {

			try {
				PosixFileAttributeView v = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class);
				v.setTimes(null,FileTime.from(Instant.ofEpochMilli(time)), null);

			} catch(Throwable e) {
				return false;
			}

			return true;
		}

		@Override
		public boolean setCreateTime(long time) throws IOException {
			try {
				PosixFileAttributeView v = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class);
				v.setTimes(null,null,FileTime.from(Instant.ofEpochMilli(time)));			
			} catch(Throwable e) {
				return false;
			}

			return true;
		}

		@Override
		public boolean setGroup(GroupPrincipal group) throws IOException {
			try {
				PosixFileAttributeView v = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class);
				v.setGroup(group);
			} catch (Exception e) {
				return false;
			}				
			return true;
		}

		

	}

	private static final long serialVersionUID = 1L;
	File target; 
	private volatile String name;
	private FileSourceFactory theCreator ;
	// Not serializable (JDK principals, OS-specific helper): rebuilt on demand
	// after deserialization. They used to be plain fields, so serializing a
	// FileProxy threw NotSerializableException.
	private transient volatile GroupPrincipal group;
	private transient volatile UserPrincipal owner;
	private transient volatile PermissionManager permissions;

	public FileProxy(File target,FileSourceFactory creator) {
		this.target = target;
		this.theCreator = creator;
	}

	private PermissionManager permissions() {
		PermissionManager ret = permissions;
		if( ret == null ) {
			ret = FileSourceFactory.isWindows()?new WindowsPermissionManager(target): new PosixPermissionManager();
			permissions = ret;
		}
		return ret;
	}

	private synchronized	Set<PosixFilePermission> getPosixPermissions() throws IOException {
		Set<PosixFilePermission> ret = new HashSet<PosixFilePermission>() ;
		// As java.io.File, stat and chmod do: a symbolic link has the permissions of what it
		// leads to. (They were the link's own, which is rwxr-xr-x or rwxrwxrwx whatever the file
		// is, and setting one bit wrote the link's bits onto the file.) Only a link that
		// points at nothing has nothing to follow, and has its own.
		PosixFileAttributeView view2 = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class);
		if (view2 != null) {
			PosixFileAttributes at;
			try {
				at = view2.readAttributes();
			} catch (java.nio.file.NoSuchFileException e) {
				PosixFileAttributeView own = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
				at = own == null ? null : own.readAttributes();
			}
			if( at != null ) {
				ret = new HashSet<PosixFilePermission>(at.permissions());
			}
		}

		return ret;
	}


	/**
	 * Read-modify-write of the permission set, so synchronized: two threads
	 * setting different bits on this object could otherwise lose one update.
	 * (Separate FileProxy objects for the same file, or other processes, can
	 * still race; the OS offers no atomic single-bit update.)
	 */
	private synchronized boolean setPosixPermision(boolean b, PosixFilePermission p) throws IOException {
		Set<PosixFilePermission> perms = getPosixPermissions();
		
		if(b) {
			if( !perms.contains(p)) {
				perms.add(p);
				Files.setPosixFilePermissions(target.toPath(), perms);
			}
		} else {
			if( perms.contains(p)) {
				perms.remove(p);
				Files.setPosixFilePermissions(target.toPath(), perms);
			}
		}	
		// no errors so I assume it worked
		return true;
	}
	
	

	/* (non-Javadoc)
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	public int compareTo(Object o) {
		if (o instanceof FileSource) {
			int ret = getAbsolutePath().compareTo(((FileSource) o).getAbsolutePath());
			return ret != 0 ? ret : getClass().getName().compareTo(o.getClass().getName());
		}
		// (used to compare against o.toString(), which for a FileProxy can be a relative path)
		return getAbsolutePath().compareTo(String.valueOf(o));
	}


	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#canRead()
	 */
	public boolean canRead() throws IOException {
		return permissions().canRead();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#canWrite()
	 */
	public boolean canWrite() throws IOException {
		return permissions().canWrite();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#canWrite()
	 */
	public boolean canExecute() throws IOException {
		return permissions().canExecute();
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return permissions().canOwnerExecute();
	}

	@Override
	public boolean canOwnerRead() throws IOException {		 
		return permissions().canOwnerRead();
	}
	@Override
	public boolean canOwnerWrite() throws IOException {		
		return permissions().canOwnerWrite();
	}
	@Override
	public boolean canGroupRead() throws IOException {
		return permissions().canGroupRead();
	}
	@Override
	public boolean canGroupWrite() throws IOException {
		return permissions().canGroupWrite();
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return permissions().canGroupExecute();
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return permissions().canOtherRead();
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return permissions().canOtherWrite();
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return permissions().canOtherExecute();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#createNewFile()
	 */
	public boolean createNewFile() throws IOException {
		return target.createNewFile();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#delete()
	 */
	public boolean delete() {

		return target.delete();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#exists()
	 */
	public boolean exists() {
		return target.exists();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getCanonicalPath()
	 */
	public String getCanonicalPath() throws IOException {

		return canonicalPath(target).replace('\\','/');
	}

	/**
	 * java.io.File.getCanonicalPath(), plus symbolic links resolved on Windows, where File leaves
	 * them alone (so a link inside a directory that leads outside it looked like it was inside:
	 * isChildOfMine() relies on the canonical path being the real one).
	 */
	private static String canonicalPath(File f) throws IOException {
		String c = f.getCanonicalPath();
		if( File.separatorChar != '\\' ) {
			return c;
		}
		try {
			//  Real path of the nearest part that exists, then the rest of the name as it is.
			java.nio.file.Path existing = java.nio.file.Paths.get(c);
			java.util.ArrayDeque<String> rest = new java.util.ArrayDeque<>();
			while( existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS) ) {
				java.nio.file.Path n = existing.getFileName();
				if( n == null ) {
					return c;
				}
				rest.addFirst(n.toString());
				existing = existing.getParent();
			}
			if( existing == null ) {
				return c;
			}
			java.nio.file.Path real = existing.toRealPath();
			for(String s : rest) {
				real = real.resolve(s);
			}
			return real.toString();
		} catch (IOException | RuntimeException e) {
			//  A link that leads nowhere, or a name Windows can't read: what File says
			return c;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getName()
	 */
	public String getName() {
		if(name == null ) {
			synchronized(this) {
				if(name == null ) {
					name = target.getName();
					if( name.isEmpty()) {
						name = target.getPath();
					}
				}
			}
		}
		return name;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getParent()
	 */
	public String getParent() {

		return target.getParent();
	}

	/**
	 * True if child is this file or below it. (A plain startsWith used to
	 * treat /x/ab as a child of /x/a.)
	 */

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getParentFile()
	 */
	public FileSource getParentFile() {
		FileSource ret = null;
		File f = target.getParentFile();
		if( f != null ) {
			ret = new FileProxy(f,theCreator);
		}

		return ret;
	}



	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isDirectory()
	 */
	public boolean isDirectory() {

		return target.isDirectory();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isFile()
	 */
	public boolean isFile() {

		return target.isFile();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#length()
	 */
	public long length() {

		return target.length();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#lastModified()
	 */
	public long lastModified() {
		long ret = target.lastModified();
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#listFiles()
	 */
	public FileSource[] listFiles() {
		return listFiles((FileSourceFilter)null);
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#listFiles(us.bringardner.parley.files.FileSourceFilter)
	 */
	public FileSource[] listFiles(FileSourceFilter filter) {
		File [] ret1 = target.listFiles();
		ArrayList<FileProxy> list = new ArrayList<FileProxy>();
		FileSource [] ret = null;

		if( ret1 != null ) {
			int sz = 0;
			for(int idx=0; idx < ret1.length; idx++ ) {
				FileProxy tmp  = new FileProxy(ret1[idx],theCreator);
				if(filter==null || filter.accept(tmp)){
					list.add(tmp);
					sz ++;
				}
			}

			ret = new FileSource[sz];

			for(int idx=0; idx < ret.length; idx++ ) {
				ret[idx] = (FileProxy)list.get(idx);
			}
		}

		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#mkdir()
	 */
	public boolean mkdir() {

		return target.mkdir();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#mkdirs()
	 */
	public boolean mkdirs() {

		return target.mkdirs();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#renameTo(us.bringardner.parley.files.FileSource)
	 */
	/**
	 * Rename/move this file to dest, which must also be a local (FileProxy)
	 * file.
	 * 
	 * This used to rename to new File(dest.getCanonicalPath()): that followed
	 * symbolic links in the destination, turned a dest from another factory
	 * (FTP, memory ...) into a same-named local path, and on an error printed
	 * the stack trace and returned false.
	 * 
	 * @return true if it was renamed. False, as java.io.File.renameTo does, when it wasn't:
	 *         dest isn't a local file, it already exists (renaming never replaces a file; use
	 *         Files.move with REPLACE_EXISTING for that), the source is missing, or dest's
	 *         directory is.
	 */
	public boolean renameTo(FileSource dest) throws IOException {
		if( !(dest instanceof FileProxy) ) {
			return false;
		}
		try {
			Files.move(target.toPath(), ((FileProxy) dest).target.toPath());
		} catch (IOException e) {
			// as java.io.File.renameTo: any failure (it is already there, the source isn't, the
			// directory isn't, no permission) is "false", not an exception. This used to throw
			// for all but "already there".
			return false;
		}
		name = null;
		return true; 
	}


	/**
	 * With {@code replaceExisting}, one atomic move over the file already there (the operating
	 * system's rename): nobody sees it missing. From another file system (where no rename is
	 * possible), the file is copied next to {@code dest} first and that copy moved over it in one
	 * step, so {@code dest} is still never missing or half written; the original goes afterwards.
	 * @since 1.1.0
	 */
	@Override
	public boolean renameTo(FileSource dest, boolean replaceExisting) throws IOException {
		if( !replaceExisting ) {
			return renameTo(dest);
		}
		if( !(dest instanceof FileProxy) || !exists() || isDirectory() || ((FileProxy) dest).target.isDirectory() ) {
			return false;
		}
		java.nio.file.Path from = target.toPath();
		java.nio.file.Path to = ((FileProxy) dest).target.toPath();
		try {
			try {
				atomicMove(from, to);
			} catch (java.nio.file.AtomicMoveNotSupportedException across) {
				// another file system: a copy on dest's own file system, then one step over dest
				java.nio.file.Path tmp = to.resolveSibling("." + to.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
				Files.copy(from, tmp, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
				try {
					atomicMove(tmp, to);
				} catch (IOException e) {
					Files.deleteIfExists(tmp);
					throw e;
				}
				Files.delete(from);
			}
		} catch (IOException e) {
			return false;
		}
		name = null;
		return true;
	}

	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

	/**
	 * One atomic move over whatever is at {@code to}. On Windows a file another program has open for
	 * a moment (a virus scanner or the search indexer looking at a file just written, say) can't
	 * be replaced, and the move is refused as access denied or "in use": it is tried again for up to
	 * about two seconds before giving up. Elsewhere, and for any other failure, the first answer
	 * stands.
	 */
	private static void atomicMove(java.nio.file.Path from, java.nio.file.Path to) throws IOException {
		for (int attempt = 0; ; attempt++) {
			try {
				Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				throw e;
			} catch (java.nio.file.FileSystemException e) {
				if( !WINDOWS || attempt >= 40 || e instanceof java.nio.file.NoSuchFileException
						|| e instanceof java.nio.file.FileAlreadyExistsException || e instanceof java.nio.file.DirectoryNotEmptyException ) {
					throw e;
				}
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new java.io.InterruptedIOException("Interrupted while replacing " + to);
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setLastModified(long)
	 */
	@Override
	public boolean setLastModifiedTime(long time) {
		return target.setLastModified(time);		
	}


	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setReadOnly()
	 */
	public boolean setReadOnly() {
		return target.setReadOnly();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getInputStream()
	 */
	public InputStream getInputStream() throws IOException {
		// Through NIO rather than FileInputStream: on Windows a FileInputStream keeps others from
		// renaming or deleting the file while it is open, so a reader could make a writer's
		// commit (rename over it) fail. NIO opens it with FILE_SHARE_DELETE; elsewhere nothing changes.
		if( target.isDirectory() ) {
			throw new FileNotFoundException(target + " (Is a directory)");
		}
		if( !isRegular() ) {
			// A pipe, device or anything else that isn't a regular file (fsh's process substitution
			// reads a named pipe): NIO's stream asks the channel for its size and position, which a
			// pipe hasn't got ("Illegal seek" from available()), and so do FileInputStream's
			// readAllBytes() and skip(). The rename problem above is only about regular files, so
			// these get a FileInputStream that is only ever read in order. A missing file lands
			// here too and gets FileInputStream's FileNotFoundException.
			return new SequentialInputStream(new FileInputStream(target));
		}
		try {
			return Files.newInputStream(target.toPath());
		} catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException e) {
			// as FileInputStream reports it, for callers that catch FileNotFoundException
			FileNotFoundException fnf = new FileNotFoundException(target + (e instanceof java.nio.file.AccessDeniedException
					? " (Permission denied)" : " (No such file or directory)"));
			fnf.initCause(e);
			throw fnf;
		}
	}

	/**
	 * A stream that has no position (a pipe or device): reads in order only, so readAllBytes(),
	 * readNBytes(), skip() and transferTo() never ask the stream underneath for a position.
	 */
	private static final class SequentialInputStream extends java.io.FilterInputStream {
		SequentialInputStream(InputStream in) {
			super(in);
		}

		@Override
		public long skip(long n) throws IOException {
			byte[] buf = new byte[(int) Math.min(8192, Math.max(n, 1))];
			long left = n;
			while( left > 0 ) {
				int got = read(buf, 0, (int) Math.min(buf.length, left));
				if( got < 0 ) {
					break;
				}
				left -= got;
			}
			return n - Math.max(left, 0);
		}
	}

	/** a regular file (not a pipe, device or socket), following links */
	private boolean isRegular() {
		return Files.isRegularFile(target.toPath());
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getOutputStream()
	 */
	public OutputStream getOutputStream() throws FileNotFoundException {
		return new SyncableFileOutputStream(target, false);
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getOutputStream(boolean)
	 */
	public OutputStream getOutputStream(boolean append) throws FileNotFoundException {

		return new SyncableFileOutputStream(target, append);
	}



	public String toString() {
		return target.toString();
	}

	/** Return a Factory that can be used to create a factory of this type.
	 * @see us.bringardner.parley.files.FileSource#getFileSourceFactory()
	 */
	public FileSourceFactory getFileSourceFactory() {
		if( theCreator == null ){
			theCreator = FileSourceFactory.fileProxyFactory;
		}
		return theCreator;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#toURL()
	 */
	
	public URL toURL() throws MalformedURLException {
		URL ret = null;

		String path = null;

		try {
			path = getCanonicalPath();
		} catch (IOException e) {
			throw new MalformedURLException("Can't get path");
		}
		//  '/' in the URL and quoted characters, as toURI() does: a raw Windows path (C:\dir\file, or
		//  one with a space) is not a valid URI, so getFileSource(URL) could not read this URL back.
		String query = FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+FileProxyFactory.FACTORY_ID;
		try {
			ret = new URL(new java.net.URI(FileSourceFactory.FILE_SOURCE_PROTOCOL,
					path.replace(File.separatorChar, '/')+"?"+query, null).toString());
		} catch (java.net.URISyntaxException e) {
			throw new MalformedURLException("Can't make a URL for "+path+": "+e.getMessage());
		}

		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getChild(java.lang.String)
	 */
	public FileSource getChild(String path) throws IOException {
		return new FileProxy(new File(target,path),theCreator);
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getCreateDate()
	 */
	public long getCreateDate() {
		return target.lastModified();
	}

	public static String getContentType(String name) {
		String ret = null;
		if( name !=null ) {
			int idx=name.lastIndexOf('.');
			if( idx > 0 ){
				String ext = name.substring(idx+1);
				ret = FileProxyFactory.getMimeType(ext);
			}
		}

		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getContentType()
	 */
	public String getContentType() {
		String ret = getContentType(getName());		 
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#list()
	 */
	public String[] list() {

		return target.list();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#list(us.bringardner.parley.files.FileSourceFilter)
	 */
	public String[] list(FileSourceFilter filter) {
		String [] ret = null;
		FileSource [] list = listFiles(filter);
		if( list != null ) {
			ret = new String[list.length];
			for(int idx=0; idx<ret.length; idx++ ) {
				ret[idx] = list[idx].getName();
			}
		}
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getAbsolutePath()
	 */
	public String getAbsolutePath() {

		return target.getAbsolutePath();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getPath()
	 */
	public String getPath() {

		return target.getPath();
	}

	/** java.io.File's own deleteOnExit: deleted when the VM exits. */
	@Override
	public void deleteOnExit() {
		target.deleteOnExit();
	}

	/** As java.io.File: false if this FileProxy was made from a relative File. */
	@Override
	public boolean isAbsolute() {
		return target.isAbsolute();
	}

	@Override
	public long getTotalSpace() {
		return target.getTotalSpace();
	}

	@Override
	public long getFreeSpace() {
		return target.getFreeSpace();
	}

	@Override
	public long getUsableSpace() {
		return target.getUsableSpace();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isVersionSupported()
	 */
	public boolean isVersionSupported() {
		// No version support
		return false;
	}

	@Override
	public UserPrincipal getOwner() throws IOException {
		if(owner == null ) {
			synchronized (this) {
				if( owner == null ) {
					// names are cached by uid across files (BJL-31)
					owner = PrincipalCache.owner(target.toPath());
				}
			}
		}

		return owner;
	}

	@Override
	public GroupPrincipal getGroup() throws IOException {
		if( group == null ) {
			synchronized (this) {
				if (permissions() instanceof WindowsPermissionManager) {
					WindowsPermissionManager wpm = (WindowsPermissionManager) permissions();
					group = (GroupPrincipal) wpm.getGroupPrincipal();
				} else {
					// names are cached by gid across files (BJL-31)
					if (Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS) != null) {
						group = PrincipalCache.group(target.toPath());
						if( group == null ) {
							group = new GroupPrincipal() {

								@Override
								public String getName() {
									return "Unknown";
								}
							};
						}
					}
				}
			}
		}
		return group;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getVersion()
	 */
	public long getVersion() {
		return 0;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getVersionDate()
	 */
	public long getVersionDate() {
		//  Only one version so just use the lastModified date 
		return lastModified();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setVersionDate()
	 */
	public boolean setVersionDate(long time) {
		// Just update the modification date
		return setLastModifiedTime(time);

	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setVersion(long, boolean)
	 */
	public boolean setVersion(long version, boolean saveChange) {
		return false; 
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getMaxVersion()
	 */
	public long getMaxVersion() {
		return 0;
	}

	public InputStream getInputStream(long startingPos) throws IOException {
		if( startingPos < 0 ) {
			throw new IOException("Can't skipp to "+startingPos);
		}
		InputStream ret = getInputStream();
		if( startingPos > 0 && !isRegular() ) {
			// no position on a pipe or device: read up to it, as 1.0.0 did
			long skipped = ret.skip(startingPos);
			if( skipped != startingPos ) {
				ret.close();
				throw new IOException("Can't skipp to "+startingPos+" skipped="+skipped);
			}
		} else if( startingPos > 0 ) {
			// NIO's stream won't skip past the end, as FileInputStream does; a position can
			java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(target.toPath());
			ret.close();
			try {
				ch.position(startingPos);
			} catch (IOException | RuntimeException e) {
				ch.close();
				throw e;
			}
			ret = java.nio.channels.Channels.newInputStream(ch);
		}
		return ret;
	}

	@Override
	public boolean equals(Object obj) {
		boolean ret = false;
		if (obj instanceof FileProxy) {			
			ret = getAbsolutePath().equals(((FileProxy) obj).getAbsolutePath());			
		}
		return ret;
	}

	@Override
	public int hashCode() {
		// equals() compares absolute paths, so hash the same thing
		return getAbsolutePath().hashCode();
	}

	@Override
	public void dereferenceChilderen() {
		// Nothing to do.

	}

	@Override
	public void refresh() {
		// The owner and group are cached; forget them so they're re-read
		owner = null;
		group = null;

	}

	public File getTarget() {
		return target;
	}


	@Override
	public String getTitle() {
		return "Local";
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) {
		return listFiles();
	}

	@Override
	public FileSource getLinkedTo() {
		FileSource ret = null;
		try {

			Path path = target.toPath();
			BasicFileAttributeView view = Files.getFileAttributeView(path, BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
			if (view != null) {
				BasicFileAttributes at = view.readAttributes();
				if( at.isSymbolicLink()) {
					Path newPath = Files.readSymbolicLink(path);
					if( newPath != null) {
						// A relative target is relative to the link's directory,
						// not the process's current directory as it used to be.
						ret = new FileProxy(path.resolveSibling(newPath).toFile(), theCreator);
					}

				}
			}

		} catch (IOException e) {
			//ignore here
		}

		return ret;
	}

	@Override
	public boolean isHidden() {
		return target.isHidden();
	}



	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {

		return new FileProxySeekableInputStream(this);
	}



	@Override
	public boolean setExecutable(boolean executable, boolean ownerOnly) throws IOException {
		return permissions().setExecutable(executable, ownerOnly);		
	}



	@Override
	public boolean setReadable(boolean readable, boolean ownerOnly) throws IOException {
		return permissions().setReadable(readable, ownerOnly);

	}



	@Override
	public boolean setWritable(boolean writetable, boolean ownerOnly) throws IOException {
		return permissions().setWritable(writetable, ownerOnly);

	}



	@Override
	public boolean setExecutable(boolean executable)  throws IOException {
		return permissions().setExecutable(executable);

	}



	@Override
	public boolean setReadable(boolean readable) throws IOException {
		boolean ret = permissions().setReadable(readable);
		return ret;

	}



	@Override
	public boolean setWritable(boolean writetable)  throws IOException {
		return permissions().setWritable(writetable);		
	}

	@Override
	public boolean setGroupExecutable(boolean executable) throws IOException {
		return permissions().setGroupExecutable(executable);		
	}

	@Override
	public boolean setGroupReadable(boolean readable) throws IOException {
		return permissions().setGroupReadable(readable);
	}

	@Override
	public boolean setGroupWritable(boolean writeable) throws IOException {
		return permissions().setGroupWritable(writeable);
	}

	@Override
	public boolean setOwnerReadable(boolean readable) throws IOException {
		return permissions().setOwnerReadable(readable);
	}

	@Override
	public boolean setOwnerWritable(boolean writeable) throws IOException {
		return permissions().setOwnerWritable(writeable);
	}

	@Override
	public boolean setOwnerExecutable(boolean executable) throws IOException {		
		return permissions().setOwnerExecutable(executable);
	}

	@Override
	public boolean setOtherReadable(boolean readable) throws IOException {
		return permissions().setOtherReadable(readable);
	}

	@Override
	public boolean setOtherWritable(boolean writeable) throws IOException {
		return permissions().setOtherWritable(writeable);
	}

	@Override
	public boolean setOtherExecutable(boolean executable) throws IOException {
		return permissions().setOtherExecutable(executable);
	}

	@Override
	public long lastAccessTime() throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(target.toPath(), BasicFileAttributes.class);
		FileTime time = attrs.lastAccessTime();
		return time.toMillis();
	}

	@Override
	public long creationTime() throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(target.toPath(), BasicFileAttributes.class);
		FileTime time = attrs.creationTime();
		return time.toMillis();		
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {		
		return permissions().setLastAccessTime(time);
	}

	@Override
	public boolean setCreateTime(long time) throws IOException {
		return permissions().setCreateTime(time);
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		try {
			if( group instanceof FileSourcePrincipal ) {
				// e.g. the group getGroup() returned: the OS only accepts its own principals
				try {
					group = target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(group.getName());
				} catch (IOException | UnsupportedOperationException e) {
					return false;
				}
			}
			return permissions().setGroup(group);
		} finally {
			this.group = null;   // cached value is stale now (it was never cleared)
		}
	}

	@Override
	public boolean setOwner(UserPrincipal owner) throws IOException {
		try {
			if( owner instanceof FileSourcePrincipal ) {
				// e.g. the owner getOwner() returned: the OS only accepts its own principals
				owner = target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(owner.getName());
			}
			FileOwnerAttributeView v = Files.getFileAttributeView(target.toPath(), FileOwnerAttributeView.class);
			v.setOwner(owner);
		} catch (Exception e) {
			return false;
		} finally {
			this.owner = null;   // cached value is stale now (it was never cleared)
		}
		return true;
	}

	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {

		return new FileSourceRandomAccessStream(new FileProxyRandomAccessIoController(this, mode), mode);
	}

	/**
	 * {@link LockScope#HOST}: every process on this machine that uses the same lock directory (see
	 * {@link FileProxyFactory#LOCK_DIRECTORY_NAME}).
	 * @since 1.1.0
	 */
	@Override
	public LockScope getLockScope() {
		return LockScope.HOST;
	}

	/**
	 * Local files: locks reach the machine (HOST), create, mkdir and replace are atomic (the
	 * operating system's own; a replace from another file system goes through a copy next to the
	 * target, see {@link #renameTo(FileSource, boolean)}), sync is fsync, and operations are applied
	 * in one order. On a network file system (NFS, SMB) these are only as good as that file system
	 * makes them; one that can't rename in one step at all makes the replace fail rather than leave
	 * a gap.
	 * @since 1.1.0
	 */
	@Override
	public us.bringardner.parley.files.FileSourceCapabilities getCapabilities() {
		return us.bringardner.parley.files.FileSourceCapabilities.defaults(LockScope.HOST).withAtomicReplace(true)
				.withDurableSync(true).withOrderedOperations(true);
	}

	/**
	 * A lock that every process on this machine sees, through an OS lock on the same region of a lock
	 * file (see {@link FileProxyFactory#LOCK_DIRECTORY_NAME}).
	 * @since 1.1.0
	 */
	@Override
	public FileSourceLock tryLock(long position, long size, boolean shared) throws IOException {
		size = LockManager.checkRange(position, size);
		LockManager.requireExists(this);
		File canonical = new File(getCanonicalPath());
		File dir = LocalLocks.lockDirectoryFor(canonical);
		// the file's identity (its file key, where the system has one), so hard links and other
		// spellings of one file are one file to the lock, as they are to an OS lock
		String identity = LocalLocks.identityOf(canonical);
		// this JVM's regions of the file are one set whichever lock directory each request found
		return LockManager.get().tryAcquire(this, "fileproxy|" + identity, position, size, shared,
				new LocalLocks.Backend(new File(dir, LocalLocks.hashOf(identity) + ".lock")));
	}

	/** A FileOutputStream that is {@link us.bringardner.parley.files.Syncable}: sync() is fsync. */
	static final class SyncableFileOutputStream extends FileOutputStream implements us.bringardner.parley.files.Syncable {
		SyncableFileOutputStream(File file, boolean append) throws FileNotFoundException {
			super(file, append);
		}

		@Override
		public void sync() throws IOException {
			flush();
			getFD().sync();
		}
	}

}
