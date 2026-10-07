# Changelog

## parley-files 1.0.0 (unreleased)

BjlFileSystem is now **parley-files**, part of the Parley library family. The code is the same as
BjlFileSystem 1.0.2-SNAPSHOT; only names changed. As a new artifact it starts again at 1.0.0.

### Changed (needs a code change)

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
  `ProgressMonitor`. The memory file system's version no longer fails when given `null`.
- `CommandLinePropertyEditor` asks for the settings that apply, with their labels and choices, never
  shows a secret's value, and doesn't accept invalid values. `Terminal`'s connect dialog now connects
  with the values entered in it (they used to be replaced by the ones it started with).

