# parley-files

> parley-files is part of **Parley**, a family of Java libraries for implementing internet protocols.
> It was previously `us.bringardner:bjl_file_system` (BjlFileSystem), with packages under
> `us.bringardner.io.filesource`. Moving over means changing the dependency, replacing
> `us.bringardner.io.filesource` with `us.bringardner.parley.files` in imports, and renaming any
> `META-INF/services/us.bringardner.io.filesource.FileSourceFactory` registration file to
> `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.

**FileSource** is an interface that looks very much like `java.io.File`, but lets many implementations (local disk, in-memory, FTP, SFTP, a database, …) coexist in one program. Application code works with `FileSource` and doesn't need to know at compile time which kind of file system it's talking to.

The syntax deliberately stays close to `java.io.File`, so moving code between the two takes little effort.

- One API for local files, in-memory files and remote file systems
- Implementations are discovered at runtime with `ServiceLoader` and chosen by a short type id
- Files can be addressed by URL (`filesource:/path?sourcetype=memory`)
- Random access (`IRandomAccessStream`) and seekable input streams
- A `java.nio.file` provider, so `Files.readString`, `Files.newDirectoryStream(dir, "*.txt")` and friends work on any FileSource

## Requirements

- Java 11 or later
- [parley-core](https://github.com/tony-bringardner/parley-core) and [parley-io](https://github.com/tony-bringardner/parley-io) (pulled in automatically by Maven)

## Installation

The artifacts are published to GitHub Packages:

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add the package repositories to your `pom.xml` (parley-core and parley-io are published from their own repositories):

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/tony-bringardner/parley-files</url>
    </repository>
    <repository>
        <id>github-parley-core</id>
        <url>https://maven.pkg.github.com/tony-bringardner/parley-core</url>
    </repository>
    <repository>
        <id>github-parley-io</id>
        <url>https://maven.pkg.github.com/tony-bringardner/parley-io</url>
    </repository>
</repositories>
```

GitHub Packages requires authentication even for public packages. Create a personal access token with the `read:packages` scope, and add a `<server>` entry for each repository id to `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_TOKEN</password>
    </server>
    <!-- repeat for github-parley-core and github-parley-io -->
</servers>
```

## Quick start

```java
FileSourceFactory factory = FileSourceFactory.getDefaultFactory();   // local files

FileSource dir = factory.createFileSource("/tmp/example");
dir.mkdirs();

FileSource file = dir.getChild("hello.txt");
try (OutputStream out = file.getOutputStream()) {
    out.write("Hello, FileSource".getBytes(StandardCharsets.UTF_8));
}

for (FileSource f : dir.listFiles()) {
    System.out.println(f.getName() + "  " + f.length() + " bytes");
}

try (InputStream in = file.getInputStream()) {
    System.out.println(new String(in.readAllBytes(), StandardCharsets.UTF_8));
}
```

All `FileSource` objects are created by a `FileSourceFactory`. Everything else (`getChild`, `getParentFile`, `listFiles`, `exists`, `mkdirs`, `delete`, `renameTo`, permissions, times, …) works much as it does on `java.io.File`. To convert existing code, see [Migrating from File to FileSource](#migrating-from-file-to-filesource).

## Implementations

Built in:

| Type id | Class | What it is |
|---|---|---|
| `fileproxy` | `FileProxyFactory` / `FileProxy` | Local files, backed by `java.io.File` and `java.nio.file`. The default. |
| `memory` | `MemoryFileSourceFactory` / `MemoryFileSource` | A virtual file system held in memory. Handy for tests. Thread-safe. |

Separate projects, which plug in the same way once they're on the classpath:

| Project | Artifact | What it is |
|---|---|---|
| [parley-files-ftp](https://github.com/tony-bringardner/parley-files-ftp) | `us.bringardner.parley:parley-files-ftp` | FTP and FTPS servers |
| [parley-files-sftp](https://github.com/tony-bringardner/parley-files-sftp) | `us.bringardner.parley:parley-files-sftp` | SSH/SFTP servers |
| [parley-files-jdbc](https://github.com/tony-bringardner/parley-files-jdbc) | `us.bringardner.parley:parley-files-jdbc` | A file system stored in a database, via JDBC |

They were `bjl_file_system_ftp`, `bjl_file_system_sftp` and `bjl_file_system_jdbc` (BjlFileSystemFtp and so on).

Each is published to GitHub Packages from its own repository, so add a matching `<repository>` (and `<server>` entry) as in [Installation](#installation).

### Choosing an implementation at runtime

```java
FileSourceFactory memory = FileSourceFactory.getFileSourceFactory("memory");
memory.connect();
FileSource scratch = memory.createFileSource("/scratch/data.bin");

String[] available = FileSourceFactory.getRegisterdFactories();   // e.g. [fileproxy, memory]
```

Remote factories take their connection settings (host, user, …) through `setConnectionProperties(...)` or `connect(Properties)`; `getConnectProperties()` lists what a factory needs.

To change the default factory for the whole program, set the system property `-DFileSource.default=<type id>`, or call `FileSourceFactory.setDefaultFactory(...)`.

## URLs

Any FileSource can be addressed by a `filesource:` URL. The `sourcetype` parameter names the factory:

```java
FileSource f = FileSourceFactory.getFileSource("filesource:/tmp/example/hello.txt?sourcetype=fileproxy");

URL url = scratch.toURL();   // e.g. filesource:/scratch/data.bin?sourcetype=memory&sessionId=0
try (InputStream in = url.openStream()) {
    ...
}
```

The `sessionId` parameter ties a URL to an existing connected factory, so a memory file's URL resolves to the same in-memory file system. Loading the library registers the URL handler. If your application server (or another library) has already installed a `URLStreamHandlerFactory`, the handler is registered through `java.protocol.handler.pkgs` instead.

## Random access

```java
IRandomAccessStream ra = file.getRandomAccessStream("rw");
try {
    ra.seek(7);
    ra.write("filesource".getBytes(StandardCharsets.UTF_8));
} finally {
    ra.close();
}
```

`IRandomAccessStream` has the same methods as `java.io.RandomAccessFile` (`seek`, `getFilePointer`, `read`, `readFully`, `readInt`, `writeUTF`, `setLength`, …). For read-only access with seeking, use `getSeekableInputStream()`.

## Links

Symbolic links and hard links are made through the factory, and a link acts like what it leads to, as it
does with `java.io.File` and `java.nio.file.Files`:

```java
FileSourceFactory f = file.getFileSourceFactory();
FileSource link = f.createSymbolicLink(f.createFileSource("/shared/current"), file);   // a symbolic link to file
FileSource same = f.createLink(f.createFileSource("/shared/copy"), file);              // a hard link: another name for it

link.getLinkedTo();    // the FileSource it points to; null if this isn't a symbolic link (also null for a hard link)
link.isFile();         // what the link leads to: exists, isFile, isDirectory, length, list, permissions, streams
link.delete();         // deletes the link, never what it leads to
link.getCanonicalPath();   // the path of what it leads to
```

- **A link looks up by its own path.** `createFileSource("/shared/current")` is the link, in every file system
  that has links, and everything below a linked directory works too.
- **`delete()` and `renameTo()` act on the link.** The file it leads to is untouched.
- **A write through a link changes the file it leads to**, as a `FileOutputStream` does on a symbolic link
  (checked on every implementation that has links). The link stays a link. A *protocol* that can't see
  links has to decide what a write means for itself: the FTP server in
  [parley-ftp](https://github.com/tony-bringardner/parley-ftp#links) hides links from its clients and
  replaces the link with a regular file instead. That is the FTP server's rule, not this API's.
- **A link that leads nowhere** is there (it is listed and can be deleted) but `exists()` is false, and
  writing through it creates the target. Its canonical path is its own path, as `java.io.File`'s is.
- **A hard link shares the data.** Deleting the name that held it leaves the file in the others.
- **A directory with a link in it isn't empty.**
- **A new link must not already exist**, its directory must, and a hard link must be to a file; otherwise
  the call throws an `IOException` (the memory and JDBC file systems throw `FileAlreadyExistsException` and
  `NoSuchFileException`, as `java.nio.file.Files` does).
- **`getCanonicalPath()` resolves links**, so two names for one file have one canonical path, and
  `isChildOfMine()` sees that a link inside a directory that leads outside it isn't a child.

| Implementation | Symbolic links | Hard links | Notes |
|---|---|---|---|
| `fileproxy` | yes | yes | The operating system's. A link has the permissions of what it leads to. |
| `memory` | yes | yes | Links are nodes of the tree. |
| SFTP | yes | yes | The server's `symlink` and `hardlink` requests. |
| JDBC | yes | yes | Rows of type `link` and `hard`; the schema is unchanged. |
| FTP | no | no | The protocol has no command for it (`UnsupportedOperationException`). A server that says an entry is a link makes `getLinkedTo()` work, and a link answers for what it leads to. `getCanonicalPath()` is lexical. |

These are checked, link by link, against `java.nio.file.Files`, by `FileLikeBehaviorTests` in the tests jar.

## NIO file system provider

`file.toPath()` returns a `java.nio.file.Path` (a `FileSourcePath`) for any FileSource. The provider is registered for the `filesource` scheme, so `Paths.get(URI)` works too:

```java
Path path = file.toPath();
String text = Files.readString(path);
Files.writeString(path.resolveSibling("copy.txt"), text);

try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.toPath(), "*.txt")) {
    for (Path p : ds) {
        System.out.println(p.getFileName());
    }
}

Path fromUri = Paths.get(new URI("filesource:/tmp/example/hello.txt?sourcetype=fileproxy"));
```

Supported: streams and byte channels (`readAllBytes`, `readString`, `lines`, `write`, `newByteChannel`), the standard open options, `copy`, `move`, `delete`, `createDirectory`, `exists`/`notExists`/`isReadable`, basic, POSIX and owner attributes (read with `readAttributes`, set with `setAttribute` or the typed setters), symbolic and hard links (`createSymbolicLink`, `createLink`, `readSymbolicLink`), directory streams, file stores, a user/group lookup service, and `glob:`/`regex:` path matchers. Not supported: `FileChannel` and `AsynchronousFileChannel`, watch services (`newWatchService` throws `UnsupportedOperationException`), and `ATOMIC_MOVE`.

For remote file systems the lookup service only knows the connected user and that user's groups, and a file store's space figures are 0 because the size isn't known (as `java.io.File` reports); local files use the operating system's lookup and report real disk space.

## Writing your own implementation

1. Implement `FileSource` for your storage, and extend `FileSourceFactory`. The factory's `getTypeId()` is the id used in URLs and `getFileSourceFactory(...)`. The factory needs a public no-argument constructor.
2. If you support random access, return a `FileSourceRandomAccessStream` from `getRandomAccessStream(mode)`. For remote storage, `AbstractRandomAccessIoController` handles chunked reads and writes for you.
3. Register the factory in `META-INF/services/us.bringardner.parley.files.FileSourceFactory` (see [`resources/META-INF/services`](resources/META-INF/services) in this project):

   ```
   com.example.MyFileSourceFactory
   ```

The unit tests in `src/test/java`, starting with `AbstractTestClass`, are the best worked examples of what an implementation has to support.

A `FileSource` is meant to look and act like a `java.io.File`: it can have more, never less. The tests jar has
`FileLikeBehaviorTests`, which runs the same cases on a `java.io.File` and on your implementation and fails
on any difference (names, creating and deleting, renaming, permissions, times, streams and their errors,
links, random access, listings with a filter, file sizes around the usual block sizes). Extend it and give it
a tree to work in (`newTree()` and `sourceFor(path)`). The hooks, such as `supportsSymbolicLinks()` and
`supportsRandomAccess()`, say what your file system can't do.

## Migrating from File to FileSource

`FileSource` was designed to look like `java.io.File`, so most of a migration is mechanical: create files through a factory instead of `new File(...)`, change `File` to `FileSource` in your types, and replace `FileInputStream`/`FileOutputStream` with the file's own streams. Once that's done, the same code works on local files, in memory, or on an FTP/SFTP/JDBC file system, depending only on which factory created the `FileSource`.

### Step by step

1. **Get a factory once**, where your code decides where files live, and pass it (or a starting `FileSource`) down:

   ```java
   FileSourceFactory factory = FileSourceFactory.getDefaultFactory();   // local files, as before
   ```

2. **Replace `new File(...)`.** A path becomes `factory.createFileSource(path)`, and a child becomes `parent.getChild(name)`. Relative paths are resolved against `factory.getCurrentDirectory()`, which for local files is the process's working directory, as with `java.io.File`.

3. **Change `File` to `FileSource`** in fields, parameters and return types. Start at the lowest-level methods and work outward; see [Mixing both during a migration](#mixing-both-during-a-migration) to convert at the edges while you go.

4. **Handle `IOException`.** Unlike `java.io.File`, methods such as `exists()`, `isDirectory()`, `length()`, `listFiles()` and `getParentFile()` declare `IOException`, because on a remote file system each one may be a network call. Most methods that used a `File` already throw `IOException`, so usually this only means adding `throws IOException` to a few more signatures.

5. **Replace the stream classes** as in the table below.

### Equivalents

| `java.io.File` code | `FileSource` code |
|---|---|
| `new File("/data/in.txt")` | `factory.createFileSource("/data/in.txt")` |
| `new File(dir, "in.txt")` | `dir.getChild("in.txt")` |
| `file.getParentFile()`, `getName()`, `getParent()`, `getAbsolutePath()`, `getCanonicalPath()` | Same names |
| `file.getPath()`, `isAbsolute()`, `getAbsoluteFile()`, `getCanonicalFile()` | Same names (a `FileSource` is always absolute, so `getPath()` is the absolute path and `isAbsolute()` is true) |
| `exists()`, `isFile()`, `isDirectory()`, `isHidden()`, `length()`, `lastModified()` | Same names (they declare `IOException`) |
| `mkdir()`, `mkdirs()`, `createNewFile()`, `delete()` | Same names |
| `list()`, `listFiles()` | Same names |
| `listFiles(FileFilter)`, `list(FilenameFilter)` | `listFiles(FileSourceFilter)`, `list(FileSourceFilter)`; a lambda works, e.g. `dir.listFiles(f -> f.getName().endsWith(".log"))` |
| `canRead()`, `canWrite()`, `canExecute()`, `setReadable(...)`, `setWritable(...)`, `setExecutable(...)`, `setReadOnly()` | Same names, plus per-class ones such as `setGroupWritable(...)` and `canOtherRead()` |
| `file.setLastModified(time)` | Same name (or `setLastModifiedTime(time)`) |
| `file.renameTo(dest)` | `file.renameTo(dest)`, where `dest` is a `FileSource` from the same factory. It returns `false` when it can't rename, as `java.io.File` does, and doesn't replace an existing file (see [Behaviour worth knowing](#behaviour-worth-knowing)). |
| `File.listRoots()` | `factory.listRoots()` |
| `File.separatorChar`, `File.pathSeparatorChar` | `factory.getSeperatorChar()`, `factory.getPathSeperatorChar()` |
| `file.toURI()`, `file.toURL()` | Same names; they give a [`filesource:` URI/URL](#urls) that works for every implementation |
| `file.toPath()` | Same name; the `Path` works with `java.nio.file.Files` ([NIO file system provider](#nio-file-system-provider)) |
| `new FileInputStream(file)` | `file.getInputStream()` |
| `new FileOutputStream(file)`, `new FileOutputStream(file, true)` | `file.getOutputStream()`, `file.getOutputStream(true)` |
| `new FileReader(file, charset)` | `new FileSourceReader(file, charset)` |
| `new FileWriter(file, charset)` | `new FileSourceWriter(file, charset)` |
| `new FileWriter(file, charset, true)` (append) | `new OutputStreamWriter(file.getOutputStream(true), charset)` |
| `new RandomAccessFile(file, mode)` | `file.getRandomAccessStream(mode)` (see [Random access](#random-access)) |
| `Files.readAllBytes(file.toPath())` | Unchanged |
| `getTotalSpace()`, `getFreeSpace()`, `getUsableSpace()` | Same names. Real values for local files; 0 where the file system can't tell, which is what `java.io.File` returns in that case |
| `File.createTempFile(prefix, suffix)`, `File.createTempFile(prefix, suffix, dir)` | `factory.createTempFile(prefix, suffix)`, `factory.createTempFile(prefix, suffix, dir)`; also `factory.createTempDirectory(prefix)`. Without a directory they use `factory.getTempDirectory()`: `java.io.tmpdir` for local files, `/tmp` in memory, the current directory elsewhere unless the implementation overrides it |
| `file.deleteOnExit()` | Same name. Local files use `java.io.File.deleteOnExit()`. Other files are deleted when their factory disconnects, or at exit if it's still connected then (see [Behaviour worth knowing](#behaviour-worth-knowing)) |


### Example

Before:

```java
long totalLogSize(File dir) {
    long total = 0;
    File[] logs = dir.listFiles(f -> f.getName().endsWith(".log"));
    if (logs != null) {
        for (File f : logs) {
            total += f.length();
        }
    }
    return total;
}
```

After:

```java
long totalLogSize(FileSource dir) throws IOException {
    long total = 0;
    FileSource[] logs = dir.listFiles(f -> f.getName().endsWith(".log"));
    if (logs != null) {
        for (FileSource f : logs) {
            total += f.length();
        }
    }
    return total;
}
```

`FileSourceFilter` is a functional interface, so the lambda carries over unchanged. Its `getDescription()` (default "Filtered files") is only shown by the file chooser dialog; override it for filters you show there. One difference: `accept` can't throw `IOException`, so a filter that calls `isDirectory()`, `length()` and similar needs a `try`/`catch` inside the lambda.

The caller changes from `totalLogSize(new File("/var/log/app"))` to `totalLogSize(factory.createFileSource("/var/log/app"))`, and now works unchanged against a memory, FTP, SFTP or JDBC factory.

### Mixing both during a migration

You don't have to convert everything at once. At the boundary between converted and unconverted code:

```java
// java.io.File -> FileSource (local files)
FileSource fs = new FileProxy(file, FileSourceFactory.getDefaultFactory());
// or: FileSourceFactory.getDefaultFactory().createFileSource(file.getAbsolutePath())

// FileSource -> java.io.File (only meaningful for local files)
File back = (fs instanceof FileProxy) ? new File(fs.getAbsolutePath()) : null;
```

### Things that behave differently

- **Checked exceptions**: see step 4. A method that returned `false` for a missing file still does (`exists()`, `delete()`), but a failed connection is an `IOException` rather than a silent `false`.
- **`renameTo`** needs a destination from the same factory, and returns `false` when it can't rename (the destination exists, the source or the destination's directory is missing, no permission), as `java.io.File` does. On SFTP and JDBC a failed connection or database is an `IOException`, not `false`.
- **`equals`** compares absolute paths within one implementation; a local `FileSource` never equals a memory one with the same path.
- **Remote calls cost time.** On FTP, SFTP or JDBC, `length()`, `lastModified()` and friends may each be a round trip. Keep results in a local variable inside loops instead of asking the same file repeatedly; call `refresh()` when you need fresh values.

### Testing converted code

Converted code can be tested against the in-memory file system instead of real directories:

```java
FileSourceFactory memory = FileSourceFactory.getFileSourceFactory("memory");
memory.connect();
FileSource dir = memory.createFileSource("/logs");
dir.mkdirs();
try (OutputStream out = dir.getChild("a.log").getOutputStream()) {
    out.write(new byte[100]);
}
assertEquals(100, totalLogSize(dir));
```

## Behaviour worth knowing

- `renameTo` returns `false`, as `java.io.File.renameTo` does, when it can't rename: the destination belongs to another file system, the source or the destination's directory is missing, or it isn't permitted. It doesn't replace an existing file in the local, memory, SFTP and JDBC file systems (it returns `false`); **on FTP the server decides**, and many servers do replace. Use `Files.move(..., REPLACE_EXISTING)` to replace on purpose. SFTP and JDBC throw an `IOException` when the connection or database fails; FTP logs it and returns `false`.
- In both built-in implementations, reading a file that doesn't exist throws `FileNotFoundException`, like `java.io`.
- `FileSource` objects are `Serializable` (both built-in implementations support it).
- `createTempFile` relies on `createNewFile()` to guarantee a unique name. That's atomic for local and memory files; on FTP it checks and then creates, so two clients could in principle pick the same random name at the same moment.
- `deleteOnExit()` on a non-local file deletes it when its factory's `disConnect()` runs, because that's the last point a remote connection is certainly open; a factory that is never disconnected has its files deleted by a shutdown hook, if it's still connected then. As with `java.io.File`, files are deleted newest registration first, a directory only if it's empty, and failures are ignored. Local files use `java.io.File.deleteOnExit()` and are deleted only at exit.

## Building and testing

```
mvn test
```

The tests use JUnit 5. A few only run on Windows, or when your user belongs to more than one group, and are skipped otherwise.

`mvn test` also measures test coverage with [JaCoCo](https://www.jacoco.org/jacoco/); open `target/site/jacoco/index.html` for the report.

## Why not just java.nio.file?

I was very excited when `java.nio.file.FileSystem` arrived in Java SE 7 (July 2011). But in my opinion its API is overly complex and nowhere near as simple as `java.io.File`, so it falls short of what I'd consider the minimal requirements. FileSource keeps the `java.io.File` style and still gives you a `java.nio.file` provider when you need one.

## License

Apache License 2.0. See the headers in the source files.
