# Changelog

## parley-files 1.0.0 (2026-10-16)

BjlFileSystem is now **parley-files**, part of the Parley library family. The code is the same as
BjlFileSystem 1.0.2-SNAPSHOT; only names changed. As a new artifact it starts again at 1.0.0.

### Changed (needs a code change)

- `FileSourceFactory.getSeperatorChar()` and `getPathSeperatorChar()` are now
  `getSeparatorChar()` and `getPathSeparatorChar()` (spelling); file systems implement the new names.
- `browse.RecentFile`'s fields are private: use `getId()`, `getProperties()`, `getPath()` and
  `isLocal()` instead of `id`, `prop`, `path` and `isLocal`.
- Maven coordinates: `us.bringardner:bjl_file_system` is now `us.bringardner.parley:parley-files`.
- Packages: `us.bringardner.io.filesource` (and `.fileproxy`, `.memory`, `.java.file`) is now
  `us.bringardner.parley.files`.
- The shared test suite (the `tests` jar) moves from `us.bringardner.io.filesource.test` to
  `us.bringardner.parley.files.test`.
- `ServiceLoader` registrations: implementations must register in
  `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files` (none was set before).
- Property names that start with a class name change with the package.
- Dependencies: `bjl_core` and `bjl_io` are now `parley-core` and `parley-io`.
- The Swing user interface is a separate module, **parley-files-swing**, in the package
  `us.bringardner.parley.files.swing`, so parley-files no longer needs Swing or
  `us.bringardner:bringardner-swing-widgets`. It holds `FileSourceChooserDialog`, `ConnectionSettingsPanel`,
  `FactoryPropertiesDialog`, `RecentFileMenu`, `BackupDialog`, `FileSourceExamineDialog`,
  `FileSourceTransferable` and `ProgressMonitorProgress`. Add a dependency on parley-files-swing
  and change the imports.
- `FileSourceChooserDialog.copyInto` is now `us.bringardner.parley.files.browse.FileCopy.copyInto`,
  so any UI can use it.
- The `filesource:` URL handler moves to `us.bringardner.parley.files.filesource.Handler`. The JDK
  finds URL handlers as `<prefix>.<protocol>.Handler`, so it has to live in a package named
  `filesource`; one prefix, `us.bringardner.parley.files`, now finds it and the per-factory handlers
  (`memory:`, `fileproxy:`). `FileSourceFactory.getAllHandlerPkgs()` returns just that prefix.
- The `filesource:` URL scheme and the `java.nio.file` provider are unchanged.
- Factories no longer supply Swing panels. `FileSourceFactory.getEditPropertiesComponent()` (which
  returned a `java.awt.Component`) is gone; factories describe their connection settings with
  `getConnectionSettings()` (a list of `ConnectionSetting`: key, label, kind such as text, secret,
  number, choice or local file, default, required, advanced, and when it applies) and check values
  with `validateConnection(Properties)`. The default describes `getConnectProperties()` as before.
  `ConnectionSettings` has the shared logic (defaults, which settings apply, validation, the values to
  connect with), so Swing, JavaFX and terminal UIs behave the same.
- `PropertyPanel` is replaced by `ConnectionSettingsPanel`, a Swing form drawn from any factory's
  settings. `FactoryPropertiesDialog` uses it, checks the values before connecting, shows its messages
  on the event thread, and `showDialog(factory)` now edits the factory it's given (it used to start
  with the first registered one).
- `FileSource.listFiles(javax.swing.ProgressMonitor)` is now `listFiles(FileSourceProgress)`, so
  `FileSource` and the factories don't depend on Swing. `ProgressMonitorProgress` adapts a Swing
  `ProgressMonitor`; it's built on swing-widgets' `ProgressMonitorUpdater`. The memory file system's version no longer fails when given `null`.
- `CommandLinePropertyEditor` asks for the settings that apply, with their labels and choices, never
  shows a secret's value, and doesn't accept invalid values. `Terminal`'s connect dialog now connects
  with the values entered in it (they used to be replaced by the ones it started with).
- `Terminal` is console only: it no longer uses Swing. `connect` always asks for the settings as
  text (`CommandLinePropertyEditor`) instead of opening `FactoryPropertiesDialog` when there's a
  display, and errors are written to the Terminal's output instead of shown in a dialog. The unused
  `headless` command is gone.
- New package `us.bringardner.parley.files.browse`, the UI-free part of a file chooser or browser,
  shared by Swing and JavaFX UIs: `FileEntry` (a file's details, read once, so a view doesn't ask a
  remote file system on every repaint), `DirectoryListing` (listing with progress and cancel, sort
  orders, hidden files, which entries can be picked for a `SelectionMode` and filter, a directory's
  ancestors) and `NavigationHistory` (back and forward). parley-files-fx is built on it.
- `browse.RecentFile`: an entry in a recent files list, the UI-free part of parley-files-swing's and
  parley-files-fx's `RecentFileMenu`: the saved form (secret values never saved), asking for
  secrets and connecting (`open`, or `fillMissingSecrets` then `getFile` for a UI that connects in
  the background), the label, dropping local files that are gone, and migrating the encrypted list
  1.0.1 and earlier saved (`migrateLegacyList`). It was `RecentFileMenu.ListEntry`.

### Added: a FileSource acts like a java.io.File

A FileSource is meant to look and act like a `java.io.File` (and its random access like a
`RandomAccessFile`): it can have more, never less. `FileLikeBehaviorTests`, in the tests jar, runs the same
cases on a `java.io.File` and on a backend and fails on any difference. A backend extends it and gives a
tree to work in (`newTree()`, `sourceFor(path)`); hooks say what a backend can't do:
`permissionsOfExistingPathsAreComparable()`, `supportsSymbolicLinks()`, `supportsHardLinks()`,
`supportsRandomAccess()`, `supportsSeekableInputStream()`, `modifiedTimeResolutionMillis()` and
`knownDifferences()` (which prints, and doesn't fail, a difference that hasn't been fixed yet; keep it empty).

It covers: the answers for a missing path, a file and a directory; create, `mkdir`, `mkdirs`, `delete`,
`renameTo` (also onto something, onto itself, a missing parent); names and unusual names and the ways of
writing a path; modified times; permissions and their setters; opening streams on what can't be opened;
create, truncate and append; reads at the end; two readers at once; a file kept across changes made by
another handle; links (symbolic, hard, dangling, through a linked directory) against `java.nio.file.Files`;
`getCanonicalPath()` of links; random access and the seekable stream against `RandomAccessFile`; `listFiles`
and `list` with a filter; 15 file sizes around the usual block sizes; and the content type.
FileProxy, the memory file system, FTP, SFTP (three libraries) and JDBC all pass it.

### Added: stream options

- `StreamOption<T>` and `StreamOptions`: a typed, immutable set of what a stream is opened with. The two
  every file system knows are `StreamOption.BUFFER_SIZE` and `StreamOption.CHUNK_SIZE`; a backend adds its
  own (declare a key as a public constant). `FileSource.getStreamDefaults()` says what a stream gets when no
  options are given, `supportedStreamOptions()` which keys mean something to the backend, and the new
  overloads `getInputStream(StreamOptions)`, `getInputStream(long, StreamOptions)`,
  `getOutputStream(boolean, StreamOptions)`, `getRandomAccessStream(String, StreamOptions)` and
  `getSeekableInputStream(StreamOptions)` take them. A source that doesn't know a key ignores it, so the
  defaults call the plain methods and existing implementations keep working.
- The sizes belong to a stream, not to a file or a connection: they are given when the stream is opened.

### Added: copying

- `FileSourceCopy`: `blockSizeFor(from, to)` (the larger of what the two sources say they want, within 8 KB
  and 1 MB, 64 KB when neither says), `optionsFor(block)` and `copy(from, to)`. The terminal `cp`, the
  `java.nio.file` provider's `copy` and `FileCopy.copyInto` use it; they used fixed 10 KB loops and
  `transferTo`. A copy loop that must pause or cancel keeps its own loop and takes the size and options here.

### Added: a handle that was kept sees changes

- `FileSourceFactory.getChangeCount()` and `noteChange()`: a number that goes up each time something is
  changed through the factory. A FileSource that remembers what it was told (attributes, a listing)
  compares it with what it saw and asks again when it differs, so a handle kept across a change behaves
  like a `java.io.File` made before it. A change made by someone else is seen when the remembered answer
  expires, as each backend configures.

### Added: MIME types

- The extension table grew from 7 entries to about 75 (text, web, images, documents, archives, audio, video,
  fonts, mail). The extension is matched without regard to case (`IMG_0001.JPG`), and a leading dot is accepted.

### Changed (API and behavior)

- `FileSourceFactory.getType(String)` is now `getMimeType(String)`, the counterpart of `addMimeType`.
  `addMimeType` throws `IllegalArgumentException` for a missing extension or type (it was a
  `NullPointerException` from the map).
- `FileSource` equality: two FileSources for the same path of the same file system are `equal` and have the
  same `hashCode`, and order by path (`equals`, `hashCode` and `compareTo` are documented on `FileSource`).
- `FileProxy.renameTo` returns false when the file system refuses, as `java.io.File.renameTo` does; it
  threw.
- `FileSource.getContentType()` is the type for the name's extension, or null when there isn't one or it
  isn't in the table, in every backend (JDBC answered an empty string).

### Changed (the memory file system, in the way of java.io.File)

- `mkdirs()` is false for what is already there; `list()` and `listFiles()` are null for something that
  isn't a directory; `canRead`, `canWrite` and `canExecute` are false and `lastModified` is 0 for a path that
  doesn't exist; `renameTo` onto itself is true and into a missing directory is false.
- The permission setters are false for a path that doesn't exist, `setReadOnly` clears the write bits, the
  two argument forms set the group and other bits from the flag, and a new file isn't executable.
- Opening an output stream below a directory that doesn't exist, or below a file, throws
  `FileNotFoundException` (the file used to be made); `getOutputStream(false)` replaces the content (it
  appended).
- Links are real: a link looks up by its own path and answers for what it leads to, also below a linked
  directory; a hard link shares the content and outlives the name that held it; a directory with a link in
  it isn't empty. A link that leads nowhere keeps its own name as its canonical path.
- A directory's modified time changes when something is added to it or taken out.
- The seekable stream keeps the pointer where it was put after a seek past the end (reads return -1).

### Changed (FileProxy)

- A symbolic link has the permissions of what it leads to, as `java.io.File` and `chmod` see them. They
  were the link's own (`rwxr-xr-x` whatever the file is), and setting one bit wrote the link's bits onto the
  file.

### Changed (Windows)

- `WindowsPermissionManager`: the "other" group defaults to `Everyone` (it was `Other`, which is not a
  Windows group, so every other-permission check threw `UserPrincipalNotFoundException`). A group that
  can't be found now answers "not allowed" instead of throwing. Set the `OtherSid` system property for a
  localized name.
- Creating a symbolic link without the Windows privilege (no Developer Mode, not elevated) throws
  `SymbolicLinkSupport.SymbolicLinkPrivilegeException`, a `FileSystemException` that says what to do. It
  was the bare "A required privilege is not held by the client".

### Added

- `SymbolicLinkSupport.isAvailable()` and `isAvailableIn(Path)`: whether this process can create symbolic
  links. See the README, "Symbolic links on Windows".
- The symbolic link tests are skipped, with the reason, when the process can't create links.
