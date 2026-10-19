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
 * ~version~V000.01.21-V000.01.16-V000.01.12-V000.01.00-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 13, 2004
 *
 */
package us.bringardner.parley.files.fileproxy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;


/**
 * FileSource factory for the local file system ({@link FileProxy}, over {@code java.io.File}).
 * <p>
 * <h2>Locks, atomic create and FileChannel</h2>
 * <ul>
 * <li><b>Locks:</b> {@code HOST} scope: every process on this machine that uses the same lock directory ({@value #LOCK_DIRECTORY_NAME}, see {@link #LOCK_DIRECTORY_PROPERTY}). Without either, the fallback directory is private to the user, so processes run as other users don't see the locks (a warning is logged). OS locks on a lock file, region for region, so range locks work across processes; a dead process's locks are freed at once. A lock is on the file, not its name: hard links and other spellings of one file are one file to the lock, and a renamed file keeps its locks (where the system has file keys: Linux, macOS; on Windows hard links are different files to the lock).</li>
 * <li><b>Atomic create:</b> {@code createNewFile()} and {@code mkdir()} are atomic (the operating system decides).</li>
 * <li><b>FileChannel:</b> a real JDK {@code FileChannel} on the file does the reading and writing; locks still come from the FileSource, so they agree with {@code FileSource.lock}.</li>
 * <li><b>map():</b> a real memory map, in every mode.</li>
 * <li><b>force():</b> syncs the file to the disk ({@code fsync}), as the JDK does. Output streams and random access streams are {@code Syncable}: {@code sync()} is {@code fsync} too.</li>
 * </ul>
 * See parley-files' README ("Locking") for the rules every file system keeps.
 *
 * @author Tony Bringardner
 */
public class FileProxyFactory extends FileSourceFactory {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public static final String LOCAL_INDICATOR="~";
	public static final String FACTORY_ID = "fileproxy";
	/**
	 * The name of a lock directory. A directory of this name in a locked path or above it holds that
	 * path's lock files, so an application that creates one in the root it serves shares its locks
	 * with every process serving that root.
	 * @since 1.1.0
	 */
	public static final String LOCK_DIRECTORY_NAME = ".parley-locks";
	/**
	 * System property: the lock directory for paths with no {@value #LOCK_DIRECTORY_NAME} directory
	 * in or above them. Default: {@code parley-locks-<user>} in the system temp directory, private
	 * to the user. Every process that shares those paths must use the same one.
	 * @since 1.1.0
	 */
	public static final String LOCK_DIRECTORY_PROPERTY = "parley.files.lockDirectory";
	private volatile FileSource [] roots;
	private volatile FileSource currentDirectory;

	/**
	 * 
	 */
	public FileProxyFactory() {
		super();	
	}


	/** Sets the current directory, which relative paths are resolved against.
	 * @see us.bringardner.parley.files.FileSourceFactory#setCurrentDirectory(us.bringardner.parley.files.FileSource)
	 */
	public void setCurrentDirectory(FileSource dir) {
		currentDirectory = dir;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#getTypeId()
	 */
	/**
	 * Every FileProxyFactory reads the same local disk.
	 */
	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		return other instanceof FileProxyFactory;
	}

	@Override
	public String getTypeId() {

		return FACTORY_ID;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#listRoots()
	 */
	public FileSource[] listRoots() throws IOException {

		if( isWindows() ) {
			// Drives come and go (USB sticks, network drives, VeraCrypt ...), so
			// don't cache the list on Windows. (It used to be computed once.)
			List<FileProxy> list = new ArrayList<FileProxy>();
			for(File drive : File.listRoots()) {
				if (drive.exists() && drive.isDirectory() ) {
					list.add(new FileProxy(drive,this));
				}
			}
			return list.toArray(new FileSource[list.size()]);
		}

		if(roots == null ) {
			synchronized(FileSourceFactory.class) {
				if(roots == null) {

					if (System.getProperty ("os.name").indexOf ("Windows") != -1) {
						List<FileProxy> list = new ArrayList<FileProxy>();
						for (char i = 'A'; i <= 'Z'; ++i) {

							File drive = new File(i+ ":\\");
							if (drive.exists() && drive.isDirectory() ) {
								list.add(new FileProxy(drive,this));
							}
						}

						roots = (FileSource[]) list.toArray(new FileSource[list.size()]);	
					} else {
						File root = new File("/");
						roots = new FileSource[] { new FileProxy(root, this) };
					}
				}
			}
		}


		return roots;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#isVersionSupported()
	 */
	public boolean isVersionSupported() {
		return false;
	}

	public Properties getConnectProperties() {
		//  No properties are required.
		return new Properties();
	}

	public boolean isConnected() {
		// Local FileSource is always connected.
		return true;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#setProperties(java.net.URL)
	 */
	@Override
	public void setConnectionProperties(URL url) {
		// Nothing to do	

	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#setConnectionProperties(java.util.Properties)
	 */
	@Override
	public void setConnectionProperties(Properties prop) {
		// Nothing to do		
	}

	@Override
	public FileSourceFactory createThreadSafeCopy() {
		// This factory is thread safe
		return this;
	}



	@Override
	public FileSource createFileSource(String fullPath) throws IOException {

		// java.io.File decides what's absolute on this OS; on Windows that
		// includes UNC paths (\\server\share), which used to be treated as relative.
		File file = new File(fullPath);
		String realPath = fullPath;
		
		if( !file.isAbsolute()) {
			FileSource cwd = getCurrentDirectory();			
			if( cwd != null ) {
				// Path.resolve also handles Windows root-relative paths (\foo -> C:\foo),
				// which new File(cwd, "\foo") got wrong.
				try {
					file = ((FileProxy) cwd).target.toPath().resolve(fullPath).toFile();
				} catch (InvalidPathException e) {
					throw new IOException("Invalid path: "+fullPath, e);
				}
				realPath = file.getAbsolutePath();
			}
		}
		
		
		File root=null;
		
		for(FileSource tmp: listRoots()) {
			String rootPath =tmp.getAbsolutePath();
			if( realPath.startsWith(rootPath)) {
				root = ((FileProxy) tmp).target;
				realPath = realPath.substring(rootPath.length());
				break;
			}
		}

		if(root !=null &&  !realPath.isEmpty()) {
			file = new File(root,realPath);
		}

		return new FileProxy(file,this);
	}



	@Override
	public String getTitle() {
		return "Local";
	}



	@Override
	public String getURL() {
		return FACTORY_ID+"://";
	}



	/** java.io.tmpdir, as java.io.File.createTempFile uses. */
	@Override
	public FileSource getTempDirectory() throws IOException {
		return new FileProxy(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile(), this);
	}

	@Override
	public FileSource getCurrentDirectory() throws IOException {
		if( currentDirectory == null ) {
			synchronized (this) {
				if( currentDirectory == null ) {
					File tmp = new File(".");
					currentDirectory = new FileProxy(tmp.getCanonicalFile(),this);

				}
			}
		}
		return currentDirectory;
	}


	@Override
	public char getPathSeparatorChar() {
		return File.pathSeparatorChar;
	}


	@Override
	public char getSeparatorChar() {
		return File.separatorChar;
	}


	@Override
	protected boolean connectImpl() throws IOException {
		// ignore
		return true;
	}


	@Override
	protected void disConnectImpl() {
		// ignore

	}


	@Override
	public FileSource createSymbolicLink(FileSource newLink, FileSource existing) throws IOException {
		FileSource ret = newLink;
		if (newLink instanceof FileProxy) {
			FileProxy sfp = (FileProxy) newLink;
			if (existing instanceof FileProxy) {
				FileProxy tfp = (FileProxy) existing;
				Path path;
				try {
					path = Files.createSymbolicLink(sfp.target.toPath(), tfp.target.toPath());
				} catch (IOException e) {
					// on Windows without Developer Mode this says what to do about it
					throw SymbolicLinkSupport.explain(e, sfp.target.toPath(), tfp.target.toPath());
				}

				ret = new FileProxy(path.toFile(), this); 
			}
		}

		return ret;
	}


	@Override
	public FileSource createLink(FileSource newLink, FileSource existing) throws IOException {
		FileSource ret = newLink;
		if (existing instanceof FileProxy) {
			FileProxy efp = (FileProxy) existing;
			if (newLink instanceof FileProxy) {
				FileProxy nlfp = (FileProxy) newLink;
				Path path = Files.createLink(nlfp.target.toPath(), efp.target.toPath());
				ret = new FileProxy(path.toFile(), this); 
			}
		}
		return ret;
	}



}
