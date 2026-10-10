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
 * ~version~V000.01.19-V000.01.07-V000.01.00-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.files.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceGroup;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;


/**
 * @author Tony Bringardner
 * Acts as a proxy to a File Object to expose the FileSource interface
 */
public class MemoryFileSource implements FileSource {

	private static final long serialVersionUID = 1L;

	public enum FileType {Undefined,Directory,File};

	boolean isRoot = false;
	private String name;
	private volatile FileType fileType=FileType.Undefined;

	private MemoryFileSourceFactory theCreator ;
	private FileSourceGroup group;
	private FileSourceUser owner;
	private volatile MemoryFileSource parent;
	/**
	 * For a symbolic link: the file it points to, which may not exist (a hard link leaves this
	 * null). Nodes are per path, so a link to a path that is deleted and made again follows it.
	 */
	volatile MemoryFileSource symlinkTarget;
	/**
	 * For a hard link: the node that holds the content this is one more name for. The names
	 * that point at a node that holds content are listed in {@link #hardLinks}.
	 */
	volatile MemoryFileSource hardTarget;
	private List<MemoryFileSource> hardLinks = new ArrayList<>();
	/** Children that exist (or have existing descendants / are links). Held strongly. */
	private Map<String,MemoryFileSource> kidsMap = new TreeMap<>();
	/**
	 * Children that were only looked up (createFileSource on a path that doesn't
	 * exist). Held weakly so lookups don't grow the tree forever, while anyone
	 * still holding the object keeps getting the same instance for that path.
	 */
	private transient Map<String,WeakReference<MemoryFileSource>> placeholders;   // WeakReference isn't serializable
	private int placeholderPurgeAt = 64;
	private boolean canOwnerRead=true;
	private boolean canOwnerWrite=true;
	/** as a file created with the usual umask: only directories start executable */
	private boolean canExecute=false;
	private boolean canGroupRead=true;
	private boolean canGroupWrite=true;
	private boolean canGroupExecute=true;
	private boolean canOtherRead=true;
	private boolean canOtherWrite=true;
	private boolean canOtherExecute=true;
	private boolean deleted;
	private volatile byte[] data;
	private String cananicalPath;
	private long lastAccessed=System.currentTimeMillis();
	private long lastModified=System.currentTimeMillis();
	private long createDate=System.currentTimeMillis();

	public MemoryFileSource(MemoryFileSource parent,String name,MemoryFileSourceFactory creator) {
		this.name = name;
		this.parent = parent;
		this.theCreator = creator;
		if( parent == null ) {
			fileType = FileType.Directory;
			canOwnerRead = canOwnerWrite = true;
			canExecute = true;
		}

	}

	private Map<String,WeakReference<MemoryFileSource>> placeholders() {
		if( placeholders == null ) {
			placeholders = new HashMap<>();
		}
		return placeholders;
	}

	/**
	 * All structural changes (the child maps, parent links, file data) are
	 * made while holding one lock per memory file system -- its factory --
	 * because an operation such as rename or delete touches several nodes.
	 * (Nothing was synchronized, so concurrent use could corrupt the tree.)
	 */
	private Object lock() {
		return theCreator != null ? theCreator : this;
	}

	MemoryFileSource getChildByName(String name) {
		synchronized (lock()) {
			MemoryFileSource ret = kidsMap.get(name);
			if( ret == null ) {
				WeakReference<MemoryFileSource> ref = placeholders().get(name);
				if( ref != null ) {
					ret = ref.get();
					if( ret == null ) {
						placeholders().remove(name);
					}
				}
			}
			return ret;
		}
	}

	/** This node is a link: a symbolic link (which may point at nothing), or one more name for a file. */
	private boolean isLinkEntry() {
		return symlinkTarget != null || hardTarget != null;
	}

	/** This node is in its directory's list: it exists, or it is a link. */
	private boolean isEntry() {
		return fileType != FileType.Undefined || isLinkEntry();
	}

	/** The child of this node with this name, made (as a lookup that doesn't exist) if it isn't there. */
	MemoryFileSource childNamed(String childName) {
		synchronized (lock()) {
			MemoryFileSource kid = getChildByName(childName);
			if( kid == null ) {
				kid = new MemoryFileSource(this, childName, theCreator);
				addChild(kid);
			}
			return kid;
		}
	}

	/**
	 * The node whose state answers for this one. For a symbolic link it is what it points to; for
	 * a hard link the node that holds the content; for a path below a linked directory the same
	 * path below the directory it links to. A node that is none of those answers for itself, and
	 * so does every node until the first link is made. (Such a node keeps its own path, name and
	 * place in its directory; only what is in the file, what it is and who may use it come from
	 * the resolved one.) A loop of links resolves to the node it started at, which doesn't exist.
	 */
	private MemoryFileSource resolve() {
		return resolve(0);
	}

	private MemoryFileSource resolve(int depth) {
		if( theCreator == null || !theCreator.hasLinks || depth > 40 ) {
			return this;
		}
		synchronized (lock()) {
			MemoryFileSource t = symlinkTarget;
			if( t != null ) {
				return t.resolve(depth+1);
			}
			t = hardTarget;
			if( t != null ) {
				return t;
			}
			if( parent == null ) {
				return this;
			}
			MemoryFileSource p = parent.resolve(depth+1);
			if( p == parent ) {
				return this;
			}
			return p.childNamed(name).resolve(depth+1);
		}
	}

	/** Makes this a link to existing (see {@link MemoryFileSourceFactory#createLink}). */
	void makeLinkTo(MemoryFileSource existing, boolean hard) throws IOException {
		synchronized (lock()) {
			if( existing.lock() != lock() ) {
				throw new IOException("Can't link to a file in another memory file system: " + existing.getAbsolutePath());
			}
			if( theCreator != null ) {
				theCreator.hasLinks = true;
			}
			if( isRoot || parent == null || exists() || isLinkEntry() ) {
				throw new java.nio.file.FileAlreadyExistsException(getAbsolutePath());
			}
			if( !parent.isDirectory() ) {
				throw new java.nio.file.NoSuchFileException(getAbsolutePath());
			}
			if( hard ) {
				MemoryFileSource owner = existing.resolve();
				if( !owner.isFile() ) {
					throw new java.nio.file.NoSuchFileException(existing.getAbsolutePath());
				}
				hardTarget = owner;
				owner.hardLinks.add(this);
			} else {
				symlinkTarget = existing;
			}
			updateRetention();
		}
	}

	/** Takes this link out of its directory: the file it points to, or shares, is untouched. */
	private void removeLinkEntry() {
		if( hardTarget != null ) {
			hardTarget.hardLinks.remove(this);
			hardTarget = null;
		}
		symlinkTarget = null;
		updateRetention();
	}

	/**
	 * Called when this node's content is going away (deleted or moved): if other names share
	 * it, the first of them takes it over, so the file outlives the name that held it.
	 */
	private void passContentToAHardLink() {
		if( hardLinks.isEmpty() ) {
			return;
		}
		MemoryFileSource heir = hardLinks.remove(0);
		heir.hardTarget = null;
		heir.data = data;
		heir.fileType = fileType;
		heir.canOwnerRead = canOwnerRead;
		heir.canOwnerWrite = canOwnerWrite;
		heir.canExecute = canExecute;
		heir.canGroupRead = canGroupRead;
		heir.canGroupWrite = canGroupWrite;
		heir.canGroupExecute = canGroupExecute;
		heir.canOtherRead = canOtherRead;
		heir.canOtherWrite = canOtherWrite;
		heir.canOtherExecute = canOtherExecute;
		heir.owner = owner;
		heir.group = group;
		heir.lastModified = lastModified;
		heir.lastAccessed = lastAccessed;
		heir.createDate = createDate;
		heir.deleted = false;
		for(MemoryFileSource other : hardLinks) {
			other.hardTarget = heir;
			heir.hardLinks.add(other);
		}
		hardLinks.clear();
		heir.updateRetention();
	}

	/** A node is kept in its parent's tree only if it exists, is a link, or has kept children. */
	private boolean shouldRetain() {
		return parent == null || isRoot || fileType != FileType.Undefined || isLinkEntry() || !kidsMap.isEmpty();
	}

	/**
	 * Move this node between the parent's strong (kidsMap) and weak (placeholders)
	 * maps after its state changed, and propagate up the tree.
	 */
	void updateRetention() {
		synchronized (lock()) {
			if( parent == null ) {
				return;
			}
			if( shouldRetain() ) {
				if( parent.kidsMap.get(name) != this ) {
					parent.kidsMap.put(name, this);
					parent.placeholders().remove(name);
					parent.updateRetention();
				}
			} else if( parent.kidsMap.get(name) == this ) {
				parent.kidsMap.remove(name);
				parent.addPlaceholder(this);
				parent.updateRetention();
			}
		}
	}

	private void addPlaceholder(MemoryFileSource kid) {
		placeholders().put(kid.getName(), new WeakReference<>(kid));
		if( placeholders().size() > placeholderPurgeAt ) {
			placeholders().values().removeIf(r -> r.get() == null);
			placeholderPurgeAt = Math.max(64, placeholders().size() * 2);
		}
	}

	/** Live placeholder children (for moving them on rename). */
	private List<MemoryFileSource> livePlaceholders() {
		List<MemoryFileSource> ret = new ArrayList<>();
		for(WeakReference<MemoryFileSource> ref : placeholders().values()) {
			MemoryFileSource kid = ref.get();
			if( kid != null ) {
				ret.add(kid);
			}
		}
		return ret;
	}

	/** True if this file is 'ancestor' or is somewhere below it. */
	private boolean isDescendantOf(MemoryFileSource ancestor) {
		for(MemoryFileSource f = this; f != null; f = f.parent) {
			if( f == ancestor ) {
				return true;
			}
		}
		return false;
	}

	/** Forget the cached path of this file and everything below it (after a move). */
	private void clearPathCache() {
		cananicalPath = null;
		for(MemoryFileSource kid : kidsMap.values()) {
			kid.clearPathCache();
		}
		for(MemoryFileSource kid : livePlaceholders()) {
			kid.clearPathCache();
		}
	}

	public FileType getFileType() {
		return fileType;
	}

	/* (non-Javadoc)
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	public int compareTo(Object o) {
		if (o instanceof FileSource) {
			int ret = getAbsolutePath().compareTo(((FileSource) o).getAbsolutePath());
			return ret != 0 ? ret : getClass().getName().compareTo(o.getClass().getName());
		}
		return getAbsolutePath().compareTo(String.valueOf(o));
	}

	
	/** As java.io.File: nothing can be read from, written to or run at a path that doesn't exist. */
	@Override
	public boolean canRead() throws IOException {
		return exists() && FileSource.super.canRead();
	}

	@Override
	public boolean canWrite() throws IOException {
		return exists() && FileSource.super.canWrite();
	}

	@Override
	public boolean canExecute() throws IOException {
		return exists() && FileSource.super.canExecute();
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return resolve().canOwnerRead;
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return resolve().canOwnerWrite;
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return resolve().canExecute;
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return resolve().canGroupRead;
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return resolve().canGroupWrite;
	}
	@Override
	public boolean canGroupExecute() throws IOException {
		return resolve().canGroupExecute;
	}


	@Override
	public boolean canOtherRead() throws IOException {
		return resolve().canOtherRead;
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return resolve().canOtherWrite;
	}
	@Override
	public boolean canOtherExecute() throws IOException {
		return resolve().canOtherExecute;
	}


	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#createNewFile()
	 */
	/**
	 * Same failures as java.io.FileInputStream: a missing file, a directory or
	 * no read permission is a FileNotFoundException. (Reading a missing file
	 * used to return an empty stream, and permission problems threw
	 * IllegalAccessError, which is a java.lang.Error.)
	 */
	private void checkReadable() throws IOException {
		if( !exists() ) {
			throw new FileNotFoundException(getAbsolutePath()+" (No such file or directory)");
		}
		if( isDirectory() ) {
			throw new FileNotFoundException(getAbsolutePath()+" (Is a directory)");
		}
		if( !canRead() ) {
			throw new FileNotFoundException(getAbsolutePath()+" (Permission denied)");
		}
	}

	public boolean createNewFile() throws IOException {
		synchronized (lock()) {
			// Same contract as java.io.File.createNewFile(): false if it already
			// exists, IOException if the parent directory doesn't exist.
			if( isLinkEntry() ) {
				return false;   // a link (even one that points at nothing) is already there
			}
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.createNewFile();   // below a linked directory
			}
			if( exists() ) {
				return false;
			}
			if( parent == null || !parent.isDirectory() ) {
				throw new IOException("No such file or directory: "+getParent());
			}
			data = new byte[0];
			fileType = FileType.File;
			canOwnerRead = canOwnerWrite = true;
			canExecute = false;
			deleted = false;
			lastModified = lastAccessed = createDate = System.currentTimeMillis();
			updateRetention();
			return true;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#delete()
	 */
	public boolean delete() {
		synchronized (lock()) {
			// Same contract as java.io.File.delete(): false if it doesn't exist or
			// is a directory that isn't empty. (It used to always return true, never
			// unlinked the node and happily "deleted" non-empty directories.)
			if( isRoot || parent == null ) {
				return false;
			}
			if( isLinkEntry() ) {
				// deleting a link removes the link itself, never what it points to
				removeLinkEntry();
				return true;
			}
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.delete();   // below a linked directory
			}
			if( !exists() ) {
				return false;
			}
			if( isDirectory() ) {
				for(MemoryFileSource kid : kidsMap.values()) {
					if( kid.isEntry() || !kid.kidsMap.isEmpty() ) {
						return false;   // not empty (a link in it counts)
					}
				}
				kidsMap.clear();
			}
			passContentToAHardLink();
			deleted = true;
			data = null;
			fileType = FileType.Undefined;
			updateRetention();
			return true;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#exists()
	 */
	public boolean exists() {
		return resolve().fileType != FileType.Undefined;
	}

	/**
	 * @return this file's own location: its parents' names and its name, with no links
	 * resolved. (This used to be getCanonicalPath(), which made a symbolic link's
	 * canonical path its own location instead of its target's.)
	 */
	private String lexicalPath() {
		synchronized (lock()) {
			if( cananicalPath == null ) {
				StringBuilder tmp = new StringBuilder();
				if( parent != null ) {
					tmp.append(parent.lexicalPath());
				} else {
					return "/";
				}
				tmp.append('/');
				if( (name.isEmpty() || !name.equals("/"))) {
					tmp.append(name);
				}
				cananicalPath = tmp.toString().trim();
				if( cananicalPath.startsWith("//")) {
					cananicalPath = cananicalPath.substring(1);
				}
			}
			return cananicalPath;
		}
	}

	/**
	 * The canonical path, as java.io.File defines it: a symbolic link resolves to its
	 * target (and a path through a linked directory to the target's path); a hard link
	 * is just another name, so it keeps its own path.
	 */
	public String getCanonicalPath() throws IOException {
		return canonicalPath(0);
	}

	private String canonicalPath(int depth) throws IOException {
		if( depth > 40 ) {
			throw new IOException("Too many levels of symbolic links: "+lexicalPath());
		}
		MemoryFileSource target = symlinkTarget;
		if( target != null ) {
			return target.canonicalPath(depth+1);
		}
		if( parent == null ) {
			return "/";
		}
		String p = parent.canonicalPath(depth+1);
		String ret = (p.endsWith("/") ? p : p+"/") + ((name.isEmpty() || name.equals("/")) ? "" : name);
		return ret.startsWith("//") ? ret.substring(1) : ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getName()
	 */
	public String getName() {
		return name;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getParent()
	 */
	public String getParent() {
		// The parent's path, as java.io.File.getParent() returns. (It used to
		// return only the parent's name, e.g. "b" instead of "/a/b".)
		return parent == null ? null:parent.getAbsolutePath();
	}


	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getParentFile()
	 */
	public FileSource getParentFile() {		
		return parent;
	}



	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isDirectory()
	 */
	public boolean isDirectory() {
		return resolve().fileType == FileType.Directory;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isFile()
	 */
	public boolean isFile() {
		return resolve().fileType == FileType.File;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#length()
	 */
	public long length() {
		byte[] bytes = resolve().data;
		return bytes == null ? 0 : bytes.length;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#lastModified()
	 */
	public long lastModified() {
		// as java.io.File: 0 for a path that doesn't exist (the field is only a start value then)
		MemoryFileSource r = resolve();
		return r.fileType != FileType.Undefined ? r.lastModified : 0L;
	}

	/**
	 * What is in this directory: what exists, and the links (also those that point at nothing). A
	 * linked directory lists what is in the directory it links to, under its own path.
	 */
	private List<MemoryFileSource> entries() {
		MemoryFileSource r = resolve();
		List<MemoryFileSource> ret = new ArrayList<>();
		for(MemoryFileSource kid : r.kidsMap.values()) {
			if( kid.isEntry() ) {
				ret.add(r == this ? kid : childNamed(kid.getName()));
			}
		}
		return ret;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#listFiles()
	 */
	public FileSource[] listFiles() throws IOException {
		return listFiles((FileSourceFilter)null);
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#listFiles(us.bringardner.parley.files.FileSourceFilter)
	 */
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		synchronized (lock()) {
			// as java.io.File: null when this isn't a directory (missing, or a plain file)
			if( !isDirectory() ) {
				return null;
			}
			if( !canRead() ) {
				// was IllegalAccessError, a java.lang.Error that callers' catch (Exception) misses
				throw new AccessDeniedException(getAbsolutePath());
			}
			MemoryFileSource [] ret = null;
			ArrayList<MemoryFileSource> list = new ArrayList<MemoryFileSource>();

			for(MemoryFileSource file : entries() ) {
				if(filter==null || filter.accept(file)){
					list.add(file);
				}
			}

			ret = new MemoryFileSource[list.size()];

			for(int idx=0; idx < ret.length; idx++ ) {
				ret[idx] = (MemoryFileSource)list.get(idx);
			}

			return ret;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#mkdir()
	 */
	public boolean mkdir()  {
		synchronized (lock()) {
			// as java.io.File: false if it already exists (this also reset an
			// existing directory's owner permissions)
			if( isLinkEntry() ) {
				return false;   // a link is already there, even one that points at nothing
			}
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.mkdir();   // below a linked directory
			}
			if( isFile() || isDirectory() ) {
				return false;
			}
			// a directory can only go in a directory (a plain file "exists" too)
			if(parent!=null && !parent.isDirectory()) {
				return false;
			}
			fileType = FileType.Directory;
			canOwnerRead = canOwnerWrite = true;
			canExecute = true;
			updateRetention();

			return true;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#mkdirs()
	 */
	public boolean mkdirs() throws IOException {
		synchronized (lock()) {
			// as java.io.File: true only if it was created (with any parents it needed); false when
			// there is already a directory, or a file, at the path
			if( exists() ) {
				return false;
			}
			return ensureDirectory();
		}
	}

	/** True if there is a directory at this path afterwards: it was there, or it and its parents were made. */
	private boolean ensureDirectory() throws IOException {
		synchronized (lock()) {
			if( isLinkEntry() ) {
				return isDirectory();
			}
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.ensureDirectory();
			}
			if( isDirectory() ) {
				return true;
			}
			if( exists() ) {
				// a file is in the way
				return false;
			}
			if( parent != null && !parent.ensureDirectory() ) {
				return false;
			}
			return mkdir();
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#renameTo(us.bringardner.parley.files.FileSource)
	 */
	public boolean renameTo(FileSource dest) throws IOException {
		synchronized (lock()) {
			boolean ret = false;
			if( isLinkEntry() ) {
				return renameLinkEntry(dest);
			}
			MemoryFileSource below = resolve();
			if( below != this ) {
				return below.renameTo(dest);   // below a linked directory
			}
			if( exists() && !isRoot && dest != null && equals(dest) ) {
				// as java.io.File: renaming a file to itself is a success that changes nothing
				return true;
			}
			if( exists() && 
					!isRoot && 
					canOwnerWrite() )  {
				if (dest instanceof MemoryFileSource && ((MemoryFileSource) dest).theCreator == theCreator) {
					// (only within one memory file system; moving a node into another
					// factory's tree would leave it with the wrong factory and lock)
					MemoryFileSource newFile = (MemoryFileSource) dest;
					if( !newFile.exists() && !newFile.isLinkEntry() &&
							// as java.io.File: the directory it goes in has to be there (this made it)
							newFile.parent != null && newFile.parent.isDirectory() &&
							!equals(newFile) &&
							!newFile.isRoot &&
							!newFile.isDescendantOf(this) ) {

						newFile.data= data;
						newFile.canOwnerRead = canOwnerRead;
						newFile.canOwnerWrite = canOwnerWrite;
						newFile.canExecute = canExecute;
						newFile.canGroupRead = canGroupRead;
						newFile.canGroupWrite = canGroupWrite;
						newFile.canGroupExecute = canGroupExecute;
						newFile.canOtherRead = canOtherRead;
						newFile.canOtherWrite = canOtherWrite;
						newFile.canOtherExecute = canOtherExecute;
						newFile.fileType = fileType;
						newFile.group = group;
						newFile.lastModified = lastModified;
						newFile.lastAccessed = lastAccessed;
						newFile.createDate = createDate;
						newFile.owner = owner;
						newFile.isRoot = isRoot;
						newFile.deleted = false;

						// Move the children too (previously a renamed directory lost them).
						for(MemoryFileSource kid : kidsMap.values()) {
							kid.parent = newFile;
							kid.clearPathCache();
							newFile.kidsMap.put(kid.getName(), kid);
						}
						kidsMap.clear();
						for(MemoryFileSource kid : livePlaceholders()) {
							kid.parent = newFile;
							kid.clearPathCache();
							newFile.addPlaceholder(kid);
						}
						placeholders().clear();

						// other names for this file now share the new one
						for(MemoryFileSource other : hardLinks) {
							other.hardTarget = newFile;
							newFile.hardLinks.add(other);
						}
						hardLinks.clear();

						data = null;
						fileType = FileType.Undefined;
						canOwnerRead = canOwnerWrite = false;
						newFile.updateRetention();
						updateRetention();
						ret = true;

					}

				}
			}

			return ret; 
		}
	}

	/** Moves a link to another name; what it points at or shares is untouched. */
	private boolean renameLinkEntry(FileSource dest) {
		if( !(dest instanceof MemoryFileSource) ) {
			return false;
		}
		MemoryFileSource newFile = (MemoryFileSource) dest;
		if( newFile == this || equals(newFile) ) {
			return true;
		}
		if( newFile.lock() != lock() || newFile.isRoot || newFile.parent == null || newFile.exists()
				|| newFile.isLinkEntry() || !newFile.parent.isDirectory() ) {
			return false;
		}
		newFile.symlinkTarget = symlinkTarget;
		if( hardTarget != null ) {
			newFile.hardTarget = hardTarget;
			hardTarget.hardLinks.remove(this);
			hardTarget.hardLinks.add(newFile);
		}
		symlinkTarget = null;
		hardTarget = null;
		newFile.updateRetention();
		updateRetention();
		return true;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setLastModified(long)
	 */
	public boolean setLastModifiedTime(long time) {
		// as java.io.File.setLastModified: false for a path that doesn't exist
		MemoryFileSource r = resolve();
		if( r != this ) {
			return r.setLastModifiedTime(time);
		}
		if( !exists() ) {
			return false;
		}
		lastModified = time;
		return true;
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setReadOnly()
	 */
	public boolean setReadOnly() {
		// as java.io.File.setReadOnly: nobody can write it; false for a path that doesn't exist
		MemoryFileSource r = resolve();
		if( r != this ) {
			return r.setReadOnly();
		}
		if( !exists() ) {
			return false;
		}
		canOwnerWrite = canGroupWrite = canOtherWrite = false;
		return true;
	}


	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getInputStream()
	 */
	public InputStream getInputStream() throws IOException {
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.getInputStream();
			}
			checkReadable();
			ByteArrayInputStream ret = new ByteArrayInputStream(data == null ? new byte[0] : data);
			lastAccessed = System.currentTimeMillis();

			return ret;
		}
	}

	/** A FileOutputStream doesn't make the directories above the file, and a file isn't one. */
	private void checkParentIsDirectory() throws FileNotFoundException {
		try {
			FileSource parent = getParentFile();
			if( parent != null && !parent.isDirectory() ) {
				throw new FileNotFoundException(getAbsolutePath() + " (No such file or directory)");
			}
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new FileNotFoundException(getAbsolutePath() + " (" + e.getMessage() + ")");
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getOutputStream()
	 */
	public OutputStream getOutputStream() throws FileNotFoundException {
		synchronized (lock()) {
			checkParentIsDirectory();
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.getOutputStream();
			}
			if(fileType != FileType.Undefined &&  !canOwnerWrite ) {
				throw new FileNotFoundException(getAbsolutePath()+" (Permission denied)");
			}
			if( exists() && fileType==FileType.Directory) {
				throw new FileNotFoundException();
			}

			ByteArrayOutputStream ret = new ByteArrayOutputStream() {
				@Override
				public void close() throws IOException {
					super.close();
					synchronized (lock()) {
						data = super.toByteArray();
					}
				}
			};
			fileType = FileType.File;
			canOwnerRead = canOwnerWrite = true;
			canExecute = false;
			updateRetention();
			lastAccessed = System.currentTimeMillis();
			lastModified = System.currentTimeMillis();

			return ret;
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getOutputStream(boolean)
	 */
	public OutputStream getOutputStream(boolean append) throws FileNotFoundException {
		if( !append ) {
			// as a FileOutputStream does: replace what is there
			return getOutputStream();
		}
		synchronized (lock()) {
			checkParentIsDirectory();
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.getOutputStream(true);
			}
			if( exists() && fileType==FileType.Directory) {
				throw new FileNotFoundException(getAbsolutePath()+" (Is a directory)");
			}
			if( fileType != FileType.Undefined && !canOwnerWrite ) {
				throw new FileNotFoundException(getAbsolutePath()+" (Permission denied)");
			}

			if( data == null ) {
				data = new byte[0];
				fileType = FileType.File;
				canOwnerRead = canOwnerWrite = true;
				canExecute = false;
				updateRetention();
			}

			// buffer only the appended bytes (it used to be sized to the existing data)
			ByteArrayOutputStream ret = new ByteArrayOutputStream(256) {
				@Override
				public void close() throws IOException {
					super.close();
					byte [] tmp = super.toByteArray();
					synchronized (lock()) {
						if( tmp.length>0) {
							if( data.length == 0 ) {
								data = tmp;
							} else {
								byte [] tmp2 = Arrays.copyOf(data, data.length+tmp.length);
								System.arraycopy(tmp, 0, tmp2, data.length, tmp.length);
								data = tmp2;
							}
						}
					}
				}
			};
			lastAccessed = System.currentTimeMillis();
			lastModified = System.currentTimeMillis();
			return ret;
		}
	}



	public String toString() {
		return getAbsolutePath();
	}

	/* Return a Factory that can be used to create a factory of this type.
	 * @see us.bringardner.parley.files.FileSource#getFileSourceFactory()
	 */
	public FileSourceFactory getFileSourceFactory() {
		if( theCreator == null ){
			theCreator = new MemoryFileSourceFactory();
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
			// the file's own location (a symbolic link's URL is the link, not its target)
			path = getAbsolutePath();
		} catch (RuntimeException e) {
			throw new MalformedURLException("Can't get path");
		}
		// Include the factory's session id: without it, resolving the URL
		// created a brand-new, empty memory file system.
		MemoryFileSourceFactory factory = (MemoryFileSourceFactory) getFileSourceFactory();
		if( factory.getSessionId() < 0 ) {
			try {
				factory.connect();   // registers the session
			} catch (IOException e) {
				throw new MalformedURLException("Can't register memory file system session: "+e);
			}
		}
		ret = new URL(FileSourceFactory.FILE_SOURCE_PROTOCOL+":"+path
				+"?"+FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+MemoryFileSourceFactory.FACTORY_ID
				+"&"+FileSourceFactory.QUERY_STRING_SESSION_ID+"="+factory.getSessionId());

		return ret;
	}

	//14th

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getChild(java.lang.String)
	 */
	public FileSource getChild(String path) throws IOException {
		String myPath = getAbsolutePath();
		return theCreator.createFileSource(myPath+(""+theCreator.getSeperatorChar())+path);
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getCreateDate()
	 */
	public long getCreateDate() {
		return createDate;
	}


	byte[] getData() {
		synchronized (lock()) {
			return data;
		}
	}

	void setData(byte[] data) {
		synchronized (lock()) {
			this.data = data;
		}
	}

	public static String getContentType(String name) {
		String ret = null;
		if( name !=null ) {
			int idx=name.lastIndexOf('.');
			if( idx > 0 ){
				String ext = name.substring(idx+1);
				ret = MemoryFileSourceFactory.getType(ext);
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
		synchronized (lock()) {
			// as java.io.File: null when this isn't a directory (missing, or a plain file)
			if( !isDirectory() ) {
				return null;
			}
			// Only list children that exist (same set as listFiles()).
			ArrayList<String> ret = new ArrayList<String>();
			for (MemoryFileSource kid : entries()) {
				ret.add(kid.getName());
			}

			return ret.toArray(new String[ret.size()]); 
		}
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#list(us.bringardner.parley.files.FileSourceFilter)
	 */
	public String[] list(FileSourceFilter filter) throws IOException {
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
		// The file's own location; a symbolic link is NOT resolved here
		return lexicalPath();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#getPath()
	 */
	public String getPath() {
		return getAbsolutePath();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#isVersionSupported()
	 */
	public boolean isVersionSupported() {
		// No version support
		return false;
	}

	@Override
	public FileSourceUser getOwner() throws IOException {
		MemoryFileSource r = resolve();
		if(r.owner == null ) {
			r.owner = theCreator.whoAmI();
		}

		return r.owner;
	}

	@Override
	public FileSourceGroup getGroup() throws IOException {
		MemoryFileSource r = resolve();
		if( r.group == null ) {
			r.group = r.getOwner().getGroup(); 
			}
		return r.group;
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
		return lastModified();
	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSource#setVersionDate()
	 */
	public boolean setVersionDate(long time) {
		return false;
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
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.getInputStream(startingPos);
			}
			// (This used to turn a missing file into an existing, empty one.)
			checkReadable();
			if( startingPos < 0 ) {
				throw new IOException("Negative starting position "+startingPos);
			}
			byte[] bytes = data == null ? new byte[0] : data;
			int start = (int) Math.min(startingPos, bytes.length);
			ByteArrayInputStream ret = new ByteArrayInputStream(bytes, start, bytes.length - start);
			lastAccessed = System.currentTimeMillis();

			return ret;
		}
	}

	@Override
	public boolean equals(Object obj) {
		boolean ret = false;
		if (obj instanceof MemoryFileSource) {			
			ret = getAbsolutePath().equals(((MemoryFileSource) obj).getAbsolutePath());			
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
		// Nothing to do for local files

	}

	@Override
	public String getTitle() {
		return "Memory";
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		// this will be instantaneous so no need for progress monitor
		FileSource [] ret = listFiles();
		if( progress != null ) {
			progress.setProgress(progress.getMaximum());
		}
		return ret;
	}

	@Override
	public FileSource getLinkedTo() {
		return symlinkTarget;
	}

	@Override
	public boolean isHidden() {
		return name.startsWith(".");
	}



	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.getSeekableInputStream();
			}
			checkReadable();
			final byte[] snapshot = data == null ? new byte[0] : data;

			final MemoryFileSource owner = this;

			return new ISeekableInputStream() {
				int filePointer = 0;

				byte [] myData = Arrays.copyOf(snapshot, snapshot.length);
				@Override
				public void seek(long pos) throws IOException {
					// Same rules as RandomAccessFile: negative is an error, and seeking
					// at or past the end is allowed (the next read returns -1).
					if( pos < 0 ) {
						throw new IOException("Negative seek offset");
					}
					filePointer = (int) Math.min(pos, myData.length);
				}

				@Override
				public int read(byte[] data) throws IOException {

					return read(data, 0, data.length);
				}

				@Override
				public int read(byte[] data, int off, int len) throws IOException {
					Objects.checkFromIndexSize(off, len, data.length);
					if( len == 0 ) {
						return 0;
					}
					int available = myData.length - filePointer;
					if( available <= 0 ) {
						return -1;
					}
					int count = Math.min(len, available);
					System.arraycopy(myData, filePointer, data, off, count);
					filePointer += count;
					return count;
				}

				@Override
				public int read() throws IOException {
					int ret = -1;
					if( filePointer < myData.length) {
						ret = myData[filePointer++] & 0xFF;  // unsigned, so bytes >= 0x80 aren't mistaken for EOF
					}
					return ret;
				}

				@Override
				public long length() throws IOException {

					return myData.length;
				}

				/**
				 * A view of this stream starting at the current file pointer.
				 * Reading from it advances this stream's pointer, and closing it
				 * closes this stream (same behavior as FileProxySeekableInputStream).
				 */
				@Override
				public InputStream getInputStream() throws IOException {
					final ISeekableInputStream seekable = this;
					return new InputStream() {
						@Override
						public int read() throws IOException {
							return seekable.read();
						}

						@Override
						public int read(byte[] b, int off, int len) throws IOException {
							return seekable.read(b, off, len);
						}

						@Override
						public long skip(long n) throws IOException {
							int remaining = Math.max(0, myData.length - filePointer);
							if( n <= 0 || remaining == 0 ) {
								return 0;
							}
							int skipped = (int) Math.min(n, remaining);
							filePointer += skipped;
							return skipped;
						}

						@Override
						public int available() {
							return Math.max(0, myData.length - filePointer);
						}

						@Override
						public void close() throws IOException {
							seekable.close();
						}
					};
				}

				@Override
				public long getFilePointer() throws IOException {
					return filePointer;
				}

				@Override
				public FileSource getFile() throws IOException {
					return owner;
				}

				@Override
				public void close() throws IOException {
					filePointer = myData.length+1;				
				}
			};
		}
	}



	public void addChild(MemoryFileSource file) {
		synchronized (lock()) {
			addPlaceholder(file);
			file.updateRetention();
		}
	}

	// The java.io.File style setters: owner only unless ownerOnly is false, and false for a path
	// that doesn't exist. (setExecutable(b) used to ignore b, and the two-argument forms set the
	// group and other bits from a comparison, not from b.)

	@Override
	public boolean setExecutable(boolean b) {
		return setExecutable(b, true);
	}

	@Override
	public boolean setReadable(boolean b) {
		return setReadable(b, true);
	}

	@Override
	public boolean setWritable(boolean b) {
		return setWritable(b, true);
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) {
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.setExecutable(b, ownerOnly);
			}
			if( !exists() ) {
				return false;
			}
			canExecute = b;
			if( !ownerOnly ) {
				canGroupExecute = b;
				canOtherExecute = b;
			}
			return true;
		}
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) {
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.setReadable(b, ownerOnly);
			}
			if( !exists() ) {
				return false;
			}
			canOwnerRead = b;
			if( !ownerOnly ) {
				canGroupRead = b;
				canOtherRead = b;
			}
			return true;
		}
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) {
		synchronized (lock()) {
			MemoryFileSource r = resolve();
			if( r != this ) {
				return r.setWritable(b, ownerOnly);
			}
			if( !exists() ) {
				return false;
			}
			canOwnerWrite = b;
			if( !ownerOnly ) {
				canGroupWrite = b;
				canOtherWrite = b;
			}
			return true;
		}
	}

	@Override
	public boolean setOwnerReadable(boolean b) throws IOException {
		resolve().canOwnerRead = b;
		return true;
	}

	@Override
	public boolean setOtherWritable(boolean b) throws IOException {
		resolve().canOtherWrite = b;
		return true;
	}

	@Override
	public boolean setOwnerWritable(boolean b) throws IOException {
		resolve().canOwnerWrite = b;
		return true;
	}

	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		resolve().canGroupExecute = b;
		return true;
	}
	@Override
	public boolean setGroupReadable(boolean b) throws IOException {
		resolve().canGroupRead = b;
		return true;
	}

	@Override
	public boolean setGroupWritable(boolean b) throws IOException {
		resolve().canGroupWrite = b;
		return true;
	}

	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		resolve().canOtherExecute = b;
		return true;
	}
	@Override
	public boolean setOtherReadable(boolean b) throws IOException {
		resolve().canOtherRead = b;
		return true;
	}
	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		resolve().canExecute = b;
		return true;
	}

	@Override
	public long lastAccessTime() throws IOException {		
		return resolve().lastAccessed;
	}

	@Override
	public long creationTime() throws IOException {
		return resolve().createDate;
	}


	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		resolve().lastAccessed = time;
		return true;
	}

	@Override
	public boolean setCreateTime(long time) throws IOException {
		resolve().createDate = time;
		return true;
	}

	@Override
	public boolean setGroup(GroupPrincipal group1) throws IOException {
		boolean ret = false;
		if (group1 instanceof FileSourceGroup) {
			// was also getOwner().setGroup(group), which changed the owner's
			// primary group, and so the group of every file it owns
			resolve().group = (FileSourceGroup) group1;
			ret = true;
		}
		
		return ret;
	}

	@Override
	public boolean setOwner(UserPrincipal owner1) throws IOException {
		boolean ret = false;
		if (owner1 instanceof FileSourceUser) {
			resolve().owner = (FileSourceUser) owner1;
			ret = true;
		}
		
		return ret;
	}

	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		return new FileSourceRandomAccessStream(new MemoryRandomAccessIoController(resolve()), mode);
	}

}
