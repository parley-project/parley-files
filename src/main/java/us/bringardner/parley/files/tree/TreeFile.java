/**
 * <PRE>
 *
 * Copyright Tony Bringardner 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 *	@author Tony Bringardner
 */
package us.bringardner.parley.files.tree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;

/**
 * A file in a tree (VirtualFileSourceFactory, such as fsh's) as the tree names it: a file of a
 * connected file system (connect ... /mem), the folder a connection is at, or a local folder a
 * connection is inside of. Its path is the tree's (/mem/notes, not the connected system's
 * /notes), and its parent, its children and the files under it are the tree's, so the
 * connections under it are listed, and cd .. leaves a connection for the folder it's in. Everything else is the file's own (real()).
 *
 * The local files no connection is in or under are the local file system's own: only these are
 * TreeFiles. {@link LocalFiles#of(FileSource)} is the local file a FileSource is, for both.
 */
public final class TreeFile implements FileSource {

	private static final long serialVersionUID = 1L;

	private final VirtualFileSourceFactory tree;
	private final String path;
	private final FileSource real;
	private final boolean connectionPoint;

	TreeFile(VirtualFileSourceFactory tree, String path, FileSource real, boolean connectionPoint) {
		this.tree = tree;
		this.path = path;
		this.real = real;
		this.connectionPoint = connectionPoint;
	}

	/** the file this is: the connected file system's, or the local file */
	public FileSource real() {
		return real;
	}

	/** f's own file: real() of a TreeFile, else f */
	public static FileSource unwrap(FileSource f) {
		return f instanceof TreeFile ? ((TreeFile) f).real : f;
	}

	/** a file system is connected at this file's path */
	public boolean isConnectionPoint() {
		return connectionPoint;
	}

	@Override
	public String toString() {
		return path;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof TreeFile && ((TreeFile) obj).path.equals(path) && ((TreeFile) obj).real.equals(real);
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	@Override
	public int compareTo(Object o) {
		return path.compareTo(o instanceof FileSource ? ((FileSource) o).getAbsolutePath() : String.valueOf(o));
	}

	@Override
	public String getAbsolutePath() {
		return path;
	}

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public boolean isAbsolute() {
		return true;
	}

	@Override
	public FileSource getAbsoluteFile() {
		return this;
	}

	@Override
	public FileSource getCanonicalFile() throws IOException {
		return real.getCanonicalFile();
	}

	@Override
	public String getName() {
		if( path.equals("/")) {
			return path;
		}
		return path.substring(path.lastIndexOf('/')+1);
	}

	@Override
	public String getTitle() throws IOException {
		return real.getTitle();
	}

	@Override
	public String getParent() {
		return VirtualFileSourceFactory.parentPath(path);
	}

	@Override
	public FileSource getParentFile() throws IOException {
		String parent = getParent();
		return parent == null ? null : tree.createFileSource(parent);
	}

	@Override
	public FileSource getChild(String name) throws IOException {
		if( name.startsWith("/")) {
			return tree.createFileSource(name);
		}
		return tree.createFileSource(VirtualFileSourceFactory.join(path, name));
	}

	/**
	 * The files in this folder, as the tree has them: a connection here is listed (in
	 * place of a file of the same name), a folder with connections under it is a TreeFile.
	 */
	@Override
	public FileSource[] listFiles() throws IOException {
		return tree(real.listFiles());
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		return tree(real.listFiles(progress));
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		List<FileSource> ret = new ArrayList<>();
		for(FileSource f : listFiles()) {
			if( filter.accept(f)) {
				ret.add(f);
			}
		}
		return ret.toArray(new FileSource[ret.size()]);
	}

	@Override
	public String[] list() throws IOException {
		return names(listFiles());
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		return names(listFiles(filter));
	}

	private static String [] names(FileSource [] files) {
		String [] ret = new String[files.length];
		for(int idx = 0; idx < files.length; idx++) {
			ret[idx] = files[idx].getName();
		}
		return ret;
	}

	private FileSource [] tree(FileSource [] kids) throws IOException {
		if( kids == null ) {
			return null;
		}
		Map<String, FileSource> ret = new LinkedHashMap<>();
		for(FileSource kid : kids) {
			String kidPath = VirtualFileSourceFactory.join(path, kid.getName());
			ret.put(kid.getName(), tree.inTree(kidPath, kid));
		}
		for(String name : tree.connectionsIn(path)) {
			if( !ret.containsKey(name)) {
				ret.put(name, tree.createFileSource(VirtualFileSourceFactory.join(path, name)));
			}
		}
		return ret.values().toArray(new FileSource[ret.size()]);
	}

	/** (a connection's folder is not deleted: disconnect it) */
	@Override
	public boolean delete() throws IOException {
		return !connectionPoint && real.delete();
	}

	@Override
	public boolean renameTo(FileSource dest) throws IOException {
		return !connectionPoint && real.renameTo(unwrap(dest));
	}

	@Override
	public boolean renameTo(FileSource dest, boolean replaceExisting) throws IOException {
		return !connectionPoint && real.renameTo(unwrap(dest), replaceExisting);
	}

	// everything else is the file's own

	@Override
	public long getCreateDate() throws java.io.IOException {
		return real.getCreateDate();
	}

	@Override
	public java.lang.String getContentType() {
		return real.getContentType();
	}

	@Override
	public boolean canRead() throws java.io.IOException {
		return real.canRead();
	}

	@Override
	public boolean canWrite() throws java.io.IOException {
		return real.canWrite();
	}

	@Override
	public boolean canExecute() throws java.io.IOException {
		return real.canExecute();
	}

	@Override
	public boolean canOwnerRead() throws java.io.IOException {
		return real.canOwnerRead();
	}

	@Override
	public boolean canOwnerWrite() throws java.io.IOException {
		return real.canOwnerWrite();
	}

	@Override
	public boolean canOwnerExecute() throws java.io.IOException {
		return real.canOwnerExecute();
	}

	@Override
	public boolean canGroupRead() throws java.io.IOException {
		return real.canGroupRead();
	}

	@Override
	public boolean canGroupWrite() throws java.io.IOException {
		return real.canGroupWrite();
	}

	@Override
	public boolean canGroupExecute() throws java.io.IOException {
		return real.canGroupExecute();
	}

	@Override
	public boolean canOtherRead() throws java.io.IOException {
		return real.canOtherRead();
	}

	@Override
	public boolean canOtherWrite() throws java.io.IOException {
		return real.canOtherWrite();
	}

	@Override
	public boolean canOtherExecute() throws java.io.IOException {
		return real.canOtherExecute();
	}

	@Override
	public boolean createNewFile() throws java.io.IOException {
		return real.createNewFile();
	}

	@Override
	public boolean exists() throws java.io.IOException {
		return real.exists();
	}

	@Override
	public us.bringardner.parley.files.FileSourceFactory getFileSourceFactory() {
		return real.getFileSourceFactory();
	}

	@Override
	public java.lang.String getCanonicalPath() throws java.io.IOException {
		return real.getCanonicalPath();
	}

	@Override
	public byte[] head(int a0) throws java.io.IOException {
		return real.head(a0);
	}

	@Override
	public byte[] tail(int a0) throws java.io.IOException {
		return real.tail(a0);
	}

	@Override
	public boolean isDirectory() throws java.io.IOException {
		return real.isDirectory();
	}

	@Override
	public boolean isFile() throws java.io.IOException {
		return real.isFile();
	}

	@Override
	public boolean isHidden() throws java.io.IOException {
		return real.isHidden();
	}

	@Override
	public long length() throws java.io.IOException {
		return real.length();
	}

	@Override
	public long lastAccessTime() throws java.io.IOException {
		return real.lastAccessTime();
	}

	@Override
	public long creationTime() throws java.io.IOException {
		return real.creationTime();
	}

	@Override
	public long lastModified() throws java.io.IOException {
		return real.lastModified();
	}

	@Override
	public boolean mkdir() throws java.io.IOException {
		return real.mkdir();
	}

	@Override
	public boolean mkdirs() throws java.io.IOException {
		return real.mkdirs();
	}

	@Override
	public boolean setLastModifiedTime(long a0) throws java.io.IOException {
		return real.setLastModifiedTime(a0);
	}

	@Override
	public boolean setLastAccessTime(long a0) throws java.io.IOException {
		return real.setLastAccessTime(a0);
	}

	@Override
	public boolean setCreateTime(long a0) throws java.io.IOException {
		return real.setCreateTime(a0);
	}

	@Override
	public boolean setExecutable(boolean a0) throws java.io.IOException {
		return real.setExecutable(a0);
	}

	@Override
	public boolean setReadable(boolean a0) throws java.io.IOException {
		return real.setReadable(a0);
	}

	@Override
	public boolean setWritable(boolean a0) throws java.io.IOException {
		return real.setWritable(a0);
	}

	@Override
	public boolean setExecutable(boolean a0, boolean a1) throws java.io.IOException {
		return real.setExecutable(a0, a1);
	}

	@Override
	public boolean setReadable(boolean a0, boolean a1) throws java.io.IOException {
		return real.setReadable(a0, a1);
	}

	@Override
	public boolean setWritable(boolean a0, boolean a1) throws java.io.IOException {
		return real.setWritable(a0, a1);
	}

	@Override
	public boolean setOwnerExecutable(boolean a0) throws java.io.IOException {
		return real.setOwnerExecutable(a0);
	}

	@Override
	public boolean setOwnerReadable(boolean a0) throws java.io.IOException {
		return real.setOwnerReadable(a0);
	}

	@Override
	public boolean setOwnerWritable(boolean a0) throws java.io.IOException {
		return real.setOwnerWritable(a0);
	}

	@Override
	public boolean setGroupExecutable(boolean a0) throws java.io.IOException {
		return real.setGroupExecutable(a0);
	}

	@Override
	public boolean setGroupReadable(boolean a0) throws java.io.IOException {
		return real.setGroupReadable(a0);
	}

	@Override
	public boolean setGroupWritable(boolean a0) throws java.io.IOException {
		return real.setGroupWritable(a0);
	}

	@Override
	public boolean setOtherExecutable(boolean a0) throws java.io.IOException {
		return real.setOtherExecutable(a0);
	}

	@Override
	public boolean setOtherReadable(boolean a0) throws java.io.IOException {
		return real.setOtherReadable(a0);
	}

	@Override
	public boolean setOtherWritable(boolean a0) throws java.io.IOException {
		return real.setOtherWritable(a0);
	}

	@Override
	public boolean setReadOnly() throws java.io.IOException {
		return real.setReadOnly();
	}

	@Override
	public void dereferenceChilderen() {
		real.dereferenceChilderen();
	}

	@Override
	public java.io.InputStream getInputStream() throws java.io.IOException {
		return real.getInputStream();
	}

	@Override
	public java.io.OutputStream getOutputStream() throws java.io.IOException {
		return real.getOutputStream();
	}

	@Override
	public java.io.OutputStream getOutputStream(boolean a0) throws java.io.IOException {
		return real.getOutputStream(a0);
	}

	@Override
	public us.bringardner.parley.files.IRandomAccessStream getRandomAccessStream(java.lang.String a0) throws java.io.IOException {
		return real.getRandomAccessStream(a0);
	}

	@Override
	public java.util.Set<us.bringardner.parley.files.StreamOption<?>> supportedStreamOptions() {
		return real.supportedStreamOptions();
	}

	@Override
	public us.bringardner.parley.files.StreamOptions getStreamDefaults() {
		return real.getStreamDefaults();
	}

	@Override
	public java.io.InputStream getInputStream(us.bringardner.parley.files.StreamOptions a0) throws java.io.IOException {
		return real.getInputStream(a0);
	}

	@Override
	public java.io.InputStream getInputStream(long a0, us.bringardner.parley.files.StreamOptions a1) throws java.io.IOException {
		return real.getInputStream(a0, a1);
	}

	@Override
	public java.io.OutputStream getOutputStream(boolean a0, us.bringardner.parley.files.StreamOptions a1) throws java.io.IOException {
		return real.getOutputStream(a0, a1);
	}

	@Override
	public us.bringardner.parley.files.IRandomAccessStream getRandomAccessStream(java.lang.String a0, us.bringardner.parley.files.StreamOptions a1) throws java.io.IOException {
		return real.getRandomAccessStream(a0, a1);
	}

	@Override
	public us.bringardner.parley.files.ISeekableInputStream getSeekableInputStream(us.bringardner.parley.files.StreamOptions a0) throws java.io.IOException {
		return real.getSeekableInputStream(a0);
	}

	@Override
	public java.net.URL toURL() throws java.net.MalformedURLException {
		return real.toURL();
	}

	@Override
	public boolean isVersionSupported() throws java.io.IOException {
		return real.isVersionSupported();
	}

	@Override
	public long getVersion() throws java.io.IOException {
		return real.getVersion();
	}

	@Override
	public long getVersionDate() throws java.io.IOException {
		return real.getVersionDate();
	}

	@Override
	public boolean setVersionDate(long a0) throws java.io.IOException {
		return real.setVersionDate(a0);
	}

	@Override
	public boolean setVersion(long a0, boolean a1) throws java.io.IOException {
		return real.setVersion(a0, a1);
	}

	@Override
	public long getMaxVersion() throws java.io.IOException {
		return real.getMaxVersion();
	}

	@Override
	public java.io.InputStream getInputStream(long a0) throws java.io.IOException {
		return real.getInputStream(a0);
	}

	@Override
	public void refresh() throws java.io.IOException {
		real.refresh();
	}

	@Override
	public us.bringardner.parley.files.FileSource getLinkedTo() throws java.io.IOException {
		return real.getLinkedTo();
	}

	@Override
	public us.bringardner.parley.files.ISeekableInputStream getSeekableInputStream() throws java.io.IOException {
		return real.getSeekableInputStream();
	}

	@Override
	public java.nio.file.attribute.GroupPrincipal getGroup() throws java.io.IOException {
		return real.getGroup();
	}

	@Override
	public boolean setGroup(java.nio.file.attribute.GroupPrincipal a0) throws java.io.IOException {
		return real.setGroup(a0);
	}

	@Override
	public java.nio.file.attribute.UserPrincipal getOwner() throws java.io.IOException {
		return real.getOwner();
	}

	@Override
	public boolean setOwner(java.nio.file.attribute.UserPrincipal a0) throws java.io.IOException {
		return real.setOwner(a0);
	}

	@Override
	public boolean setLastModified(long a0) throws java.io.IOException {
		return real.setLastModified(a0);
	}

	@Override
	public java.nio.file.Path toPath() {
		return real.toPath();
	}

	@Override
	public java.net.URI toURI() {
		return real.toURI();
	}

	@Override
	public long getTotalSpace() throws java.io.IOException {
		return real.getTotalSpace();
	}

	@Override
	public void deleteOnExit() {
		real.deleteOnExit();
	}

	@Override
	public long getFreeSpace() throws java.io.IOException {
		return real.getFreeSpace();
	}

	@Override
	public long getUsableSpace() throws java.io.IOException {
		return real.getUsableSpace();
	}

	@Override
	public us.bringardner.parley.files.lock.LockScope getLockScope() {
		return real.getLockScope();
	}

	@Override
	public us.bringardner.parley.files.FileSourceCapabilities getCapabilities() {
		return real.getCapabilities();
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock tryLock() throws java.io.IOException {
		return real.tryLock();
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock tryLock(boolean a0) throws java.io.IOException {
		return real.tryLock(a0);
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock tryLock(long a0, long a1, boolean a2) throws java.io.IOException {
		return real.tryLock(a0, a1, a2);
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock lock() throws java.io.IOException {
		return real.lock();
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock lock(boolean a0) throws java.io.IOException {
		return real.lock(a0);
	}

	@Override
	public us.bringardner.parley.files.lock.FileSourceLock lock(long a0, long a1, boolean a2) throws java.io.IOException {
		return real.lock(a0, a1, a2);
	}

}
