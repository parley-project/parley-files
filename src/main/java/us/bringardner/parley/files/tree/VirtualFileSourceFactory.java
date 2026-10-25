package us.bringardner.parley.files.tree;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * A file tree: the local file system, and file systems connected into it at paths (fsh's
 * connect memory /mem; a file system the browser connects at /sftp-build). fsh's shell and the
 * console browser on its own (pfv) each have one. A path is the connection's whose path is the
 * longest that it is in or under; a path no connection has is local.
 *
 * The connections are a table from the path each is at to the folder connected there. The table
 * is never changed, only replaced (connectAt and disconnectAt make a new one), so a thread that reads
 * it (listing a folder, completing a name) sees one table whole, and never takes a lock.
 *
 * The files of a connection, its folder, and the local folders a connection is under, are
 * TreeFiles (their path and their parent, children and files under them are this tree's); the
 * other local files are the local file system's own.
 */
public class VirtualFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;
	private static final String TYPE_ID = "virtual";

	/** the local file system's roots (/, or C:\ D:\ ...) */
	private final FileSource [] localRoots;
	/** where / is */
	private final FileSource firstRoot;
	private final FileSourceFactory local;
	private volatile FileSource currentDirectory;
	/** path -> the folder connected there, longest path first (never changed: replaced) */
	private volatile Map<String, FileSource> connections = Collections.emptyMap();

	public VirtualFileSourceFactory() throws IOException {
		local = FileSourceFactory.getDefaultFactory();
		localRoots = local.listRoots();
		firstRoot = localRoots[0];
		currentDirectory = local.getCurrentDirectory();
	}

	/** a tree with primary as / (tests) */
	public VirtualFileSourceFactory(FileSource primary) throws IOException {
		local = primary.getFileSourceFactory();
		firstRoot = primary;
		localRoots = new FileSource[] {primary};
		currentDirectory = local.getCurrentDirectory();
	}

	/**
	 * Connect dir at path: an absolute path ("mem" is /mem), in a local folder or in another
	 * connection, not / and not where a connection is already.
	 *
	 * @return false if dir is not a folder, or path can't have a connection
	 */
	public synchronized boolean connectAt(String path, FileSource dir) throws IOException {
		if( dir == null || !dir.exists() || !dir.isDirectory()) {
			return false;
		}
		String at = connectionPath(path);
		if( at == null || connections.containsKey(at)) {
			return false;
		}
		List<String> paths = new ArrayList<>(connections.keySet());
		paths.add(at);
		paths.sort((a, b)->a.length() != b.length() ? b.length()-a.length() : a.compareTo(b));
		Map<String, FileSource> next = new LinkedHashMap<>();
		for(String p : paths) {
			next.put(p, p.equals(at) ? dir : connections.get(p));
		}
		connections = Collections.unmodifiableMap(next);
		return true;
	}

	/**
	 * Takes away the connection at path (never /, the local file system).
	 *
	 * @return the folder that was connected there, or null if none was
	 */
	public synchronized FileSource disconnectAt(String path) {
		String at = connectionPath(path);
		if( at == null || !connections.containsKey(at)) {
			return null;
		}
		Map<String, FileSource> next = new LinkedHashMap<>(connections);
		FileSource ret = next.remove(at);
		connections = Collections.unmodifiableMap(next);
		return ret;
	}

	/** Whether a connection still uses factory. */
	public boolean uses(FileSourceFactory factory) {
		for(FileSource top : connections.values()) {
			if( top.getFileSourceFactory() == factory ) {
				return true;
			}
		}
		return false;
	}

	/** the connections: the path each is at, and the folder connected there, by path */
	public Map<String, FileSource> connections() {
		return Collections.unmodifiableMap(new TreeMap<>(connections));
	}

	/** the folder connected at path, or null */
	public FileSource connectionAt(String path) {
		String at = connectionPath(path);
		return at == null ? null : connections.get(at);
	}

	/**
	 * Whether path is on a Windows drive ("C:/a", "C:\a" or "C:"). Only on Windows: elsewhere
	 * "a:/b" is a relative path.
	 */
	public static boolean isDrivePath(String path) {
		return java.io.File.separatorChar == '\\' && path != null && path.length() > 1
				&& Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
				&& (path.length() == 2 || path.charAt(2) == '/' || path.charAt(2) == '\\');
	}

	/** a drive path as this tree has it: the drive letter in capitals and / between the names */
	private static String driveForm(String path) {
		return Character.toUpperCase(path.charAt(0))+path.substring(1).replace('\\', '/');
	}

	/**
	 * path as a connection's: absolute, logical, without the / at the end; null for / (or a
	 * drive's root, C:/). A path on a Windows drive keeps its drive, "C:/data".
	 */
	static String connectionPath(String path) {
		if( path == null || path.trim().isEmpty()) {
			return null;
		}
		if( isDrivePath(path)) {
			String ret = logicalPath(driveForm(path));
			return ret.length() <= 3 ? null : ret;
		}
		String ret = logicalPath(path.startsWith("/") ? path : "/"+path);
		return ret.equals("/") ? null : ret;
	}

	/** the names of the connections directly in folder (a path of this tree) */
	Set<String> connectionsIn(String folder) {
		Set<String> ret = new TreeSet<>();
		for(String p : connections.keySet()) {
			if( folder.equals(parentPath(p))) {
				ret.add(p.substring(p.lastIndexOf('/')+1));
			}
		}
		return ret;
	}

	/** a connection is under path (not at it) */
	private boolean connectionsUnder(String path, Map<String, FileSource> table) {
		for(String p : table.keySet()) {
			if( !p.equals(path) && FileSourceFactory.isSameOrDescendant(path, p)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * the parent of path ("/a" for "/a/b", "/" for "/a"), or null for /. On a drive, "C:/a" for
	 * "C:/a/b" and "C:/" for "C:/a"; a drive's root has none.
	 */
	public static String parentPath(String path) {
		if( path.equals("/")) {
			return null;
		}
		if( isDrivePath(path)) {
			if( path.length() <= 3 ) {
				return null;
			}
			int slash = path.lastIndexOf('/');
			return slash <= 2 ? path.substring(0, 3) : path.substring(0, slash);
		}
		int slash = path.lastIndexOf('/');
		return slash <= 0 ? "/" : path.substring(0, slash);
	}

	/** name in folder */
	static String join(String folder, String name) {
		return folder.endsWith("/") ? folder+name : folder+"/"+name;
	}

	/**
	 * The file at path, which a folder of this tree listed as file: the tree's if a connection is
	 * at path or under it, or path is in one; else file itself.
	 */
	FileSource inTree(String path, FileSource file) throws IOException {
		Map<String, FileSource> table = connections;
		FileSource top = table.get(path);
		if( top != null ) {
			return new TreeFile(this, path, top, true);
		}
		if( file instanceof TreeFile ) {
			return file;
		}
		if( connectionOf(path, table) != null || connectionsUnder(path, table)) {
			return new TreeFile(this, path, file, false);
		}
		return file;
	}

	/** the path of the connection path is at or in, or null (it is local) */
	private static String connectionOf(String path, Map<String, FileSource> table) {
		// (longest first: a connection at /data/x is inside the one at /data)
		for(String p : table.keySet()) {
			if( FileSourceFactory.isSameOrDescendant(p, path)) {
				return p;
			}
		}
		return null;
	}

	@Override
	public FileSource[] listRoots() throws IOException {
		// (the local ones first: [0] is /)
		List<FileSource> ret = new ArrayList<>();
		for(FileSource root : localRoots) {
			String rootPath = root.getAbsolutePath().replace('\\', '/');
			ret.add(inTree(isDrivePath(rootPath) ? driveForm(rootPath) : rootPath, root));
		}
		for(Map.Entry<String, FileSource> e : connections.entrySet()) {
			ret.add(new TreeFile(this, e.getKey(), e.getValue(), true));
		}
		return ret.toArray(new FileSource[ret.size()]);
	}

	@Override
	public FileSource getCurrentDirectory() throws IOException {
		return currentDirectory;
	}

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		currentDirectory = dir;
	}

	/**
	 * The path with repeated separators and "." removed and ".." taking away the
	 * element before it, never going above the start ("/", or a Windows drive such
	 * as "C:\\"). Keeps the path's own separator.
	 */
	public static String logicalPath(String path) {
		// (only on Windows, or in a path with a drive (C:\a), is \ a separator: elsewhere it is
		// part of a name, touch 'a\b')
		boolean windows = java.io.File.separatorChar == '\\'
				|| path.length() > 1 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
		char sep = windows && path.indexOf('\\') >= 0 && path.indexOf('/') < 0 ? '\\' : '/';
		String prefix = "";
		String rest = path;
		if( rest.length() > 1 && Character.isLetter(rest.charAt(0)) && rest.charAt(1) == ':') {
			prefix = rest.substring(0, 2);
			rest = rest.substring(2);
		}
		boolean absolute = rest.startsWith("/") || windows && rest.startsWith("\\");
		Deque<String> parts = new ArrayDeque<>();
		for(String part : rest.split(windows ? "[/\\\\]" : "/")) {
			if( part.isEmpty() || part.equals(".")) {
				continue;
			}
			if( part.equals("..")) {
				if( !parts.isEmpty() && !parts.peekLast().equals("..")) {
					parts.pollLast();
				} else if( !absolute) {
					parts.addLast(part);
				}
			} else {
				parts.addLast(part);
			}
		}
		String joined = String.join(String.valueOf(sep), parts);
		if( absolute ) {
			return prefix+sep+joined;
		}
		return prefix+joined;
	}

	@Override
	public FileSource createFileSource(String fullPath) throws IOException {
		boolean abs = fullPath.startsWith("/");
		if( isWindows()) {
			abs = fullPath.startsWith("/") || ( fullPath.length()>1 && Character.isAlphabetic(fullPath.charAt(0)) && fullPath.charAt(1) == ':');
		}
		String path = fullPath;
		FileSource cwd = currentDirectory;
		if( !abs && cwd != null ) {
			String dir = cwd.getAbsolutePath();
			char sep = cwd instanceof TreeFile ? '/' : cwd.getFileSourceFactory().getSeparatorChar();
			path = dir.endsWith(""+sep) ? dir+fullPath : dir+sep+fullPath;
		}
		// "." and ".." are resolved by name, as a shell's cd does (logical paths), so ".." can't
		// be mistaken for part of a name or run past the start of a connection
		path = logicalPath(path);
		// (a drive's path is the tree's as "C:/a", the way a connection's is kept)
		boolean drive = isDrivePath(path);
		if( drive ) {
			path = driveForm(path);
		}
		boolean inTree = drive || path.startsWith("/");

		Map<String, FileSource> table = connections;
		String at = inTree ? connectionOf(path, table) : null;
		if( at != null ) {
			FileSource top = table.get(at);
			String rest = path.substring(at.length());
			while( rest.startsWith("/")) {
				rest = rest.substring(1);
			}
			if( rest.isEmpty()) {
				return new TreeFile(this, at, top, true);
			}
			return new TreeFile(this, path, top.getChild(rest), false);
		}
		FileSource ret = path.equals("/") ? firstRoot : local.createFileSource(path);
		String retPath = ret.getAbsolutePath().replace('\\', '/');
		if( drive && isDrivePath(retPath)) {
			retPath = driveForm(retPath);
		}
		// (/ is the first root, whose own path on Windows is a drive's root, C:/)
		boolean driveRoot = path.equals("/") && ret == firstRoot && isDrivePath(retPath) && retPath.length() <= 3;
		if( !table.isEmpty() && inTree && connectionsUnder(path, table)
				&& (retPath.equals(path) || driveRoot)) {
			// (a local folder with a connection in it or under it)
			return new TreeFile(this, path, ret, false);
		}
		return ret;
	}

	@Override
	public String getTypeId() {
		return TYPE_ID;
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	protected boolean connectImpl() throws IOException {
		return true;
	}

	@Override
	protected void disConnectImpl() throws IOException {
	}

	@Override
	public FileSourceFactory createThreadSafeCopy() {
		return this;
	}

	@Override
	public Properties getConnectProperties() {
		return new Properties();
	}

	@Override
	public char getPathSeparatorChar() {
		return ';';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}

	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new IOException("createSymbolicLink not implemented");
	}

	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new IOException("createLink not implemented");
	}

	@Override
	public void setConnectionProperties(URL url) {
	}

	@Override
	public void setConnectionProperties(Properties prop) {

	}

	@Override
	public String getTitle() {
		return TYPE_ID;
	}

	@Override
	public String getURL()  {
		throw new RuntimeException("getURL not implemented");
	}

}
