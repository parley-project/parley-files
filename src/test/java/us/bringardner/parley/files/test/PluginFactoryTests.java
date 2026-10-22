package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.plugin.PluginFolders;
import us.bringardner.parley.plugin.PluginReport;
import us.bringardner.parley.plugin.Plugins;

/** A file system in a plugin jar: FileSourceFactory finds it once the plugins are loaded. */
public class PluginFactoryTests {

	@TempDir
	Path tmp;

	@Test
	public void factoryFromAPlugin() throws Exception {
		// a factory of its own type, in a jar in a plugin folder
		File classes = tmp.resolve("classes").toFile();
		File src = new File(classes, "src/pt/PluginTestFactory.java");
		src.getParentFile().mkdirs();
		Files.write(src.toPath(), ("package pt; public class PluginTestFactory extends us.bringardner.parley.files.memory.MemoryFileSourceFactory {"
				+ " @Override public String getTypeId() { return \"plugintest\"; } }").getBytes(StandardCharsets.UTF_8));
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
				"-d", classes.getPath(), "-cp", System.getProperty("java.class.path"), src.getPath()));
		File folder = tmp.resolve("plugins").toFile();
		folder.mkdirs();
		Manifest m = new Manifest();
		m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		m.getMainAttributes().putValue(Plugins.MANIFEST_ATTRIBUTE, "1");
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(folder, "plugintest.jar")), m)) {
			out.putNextEntry(new JarEntry("pt/PluginTestFactory.class"));
			out.write(Files.readAllBytes(new File(classes, "pt/PluginTestFactory.class").toPath()));
			out.closeEntry();
			out.putNextEntry(new JarEntry("META-INF/services/"+FileSourceFactory.class.getName()));
			out.write("pt.PluginTestFactory\n".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		// (looked up before the plugins are there)
		assertFalse(FileSourceFactory.isRegisteredFactory("plugintest"));

		Properties sys = new Properties();
		sys.setProperty(PluginFolders.PROP_PATH, folder.getPath());
		ClassLoader before = Thread.currentThread().getContextClassLoader();
		try {
			PluginReport r = Plugins.load(new PluginFolders("test", false, null, sys, null, false, tmp.toString(), null), before);
			assertEquals(List.of(new File(folder, "plugintest.jar")), r.loaded());
			assertTrue(FileSourceFactory.isRegisteredFactory("plugintest"));
			FileSourceFactory f = FileSourceFactory.getFileSourceFactory("plugintest");
			assertNotNull(f);
			assertEquals("pt.PluginTestFactory", f.getClass().getName());
			// the built-in ones are still there
			assertTrue(FileSourceFactory.isRegisteredFactory("memory"));
		} finally {
			Thread.currentThread().setContextClassLoader(before);
		}
	}
}
