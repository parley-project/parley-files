package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The extension to MIME type table every FileSource shares. */
public class MimeTypeTableTests {

	@Test
	void commonTypesAreKnown() {
		String[][] expected = {
			{"a.txt", "text/plain"}, {"page.html", "text/html"}, {"page.htm", "text/html"}, {"s.css", "text/css"},
			{"s.js", "text/javascript"}, {"d.csv", "text/csv"}, {"r.md", "text/markdown"},
			{"d.json", "application/json"}, {"d.xml", "application/xml"}, {"c.yaml", "application/yaml"}, {"c.yml", "application/yaml"},
			{"a.pdf", "application/pdf"}, {"a.zip", "application/zip"}, {"a.gz", "application/gzip"}, {"a.tar", "application/x-tar"},
			{"a.jar", "application/java-archive"},
			{"i.png", "image/png"}, {"i.jpg", "image/jpeg"}, {"i.jpeg", "image/jpeg"}, {"i.gif", "image/gif"},
			{"i.svg", "image/svg+xml"}, {"i.webp", "image/webp"}, {"i.tif", "image/tiff"}, {"i.tiff", "image/tiff"},
			{"d.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
			{"d.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
			{"d.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
			{"m.mp3", "audio/mpeg"}, {"m.wav", "audio/wav"}, {"v.mp4", "video/mp4"}, {"v.webm", "video/webm"},
			{"f.woff2", "font/woff2"}, {"m.eml", "message/rfc822"}, {"c.ics", "text/calendar"},
		};
		for(String[] e : expected) {
			assertEquals(e[1], FileProxy.getContentType(e[0]), e[0]);
		}
	}

	@Test
	void theCaseOfTheExtensionDoesNotMatter() {
		assertEquals("image/jpeg", FileProxy.getContentType("IMG_0001.JPG"));
		assertEquals("image/jpeg", FileProxy.getContentType("photo.JpEg"));
		assertEquals("application/pdf", FileProxy.getContentType("Report.PDF"));
		assertEquals("text/plain", FileProxy.getContentType("UPPER.TXT"));
		assertEquals("image/png", FileSourceFactory.getMimeType("PNG"));
		assertEquals("image/png", FileSourceFactory.getMimeType(".png"));
		// the memory file system and the others answer from the same table
		assertEquals("image/jpeg", MemoryFileSourceFactory.getMimeType("JPG"));
	}

	@Test
	void unknownIsNull() {
		assertNull(FileProxy.getContentType("data.unknownext"));
		assertNull(FileProxy.getContentType("noextension"));
		assertNull(FileProxy.getContentType(".hidden"), "a leading dot is not an extension");
		assertNull(FileProxy.getContentType("trailingdot."));
		assertNull(FileProxy.getContentType(null));
		assertNull(FileSourceFactory.getMimeType(null));
		assertNull(FileSourceFactory.getMimeType(""));
		assertNull(FileSourceFactory.getMimeType("."));
	}

	@Test
	void aTypeCanBeAddedAndReplaced() {
		try {
			FileSourceFactory.addMimeType("ZZTEST", "application/x-zztest");
			assertEquals("application/x-zztest", FileProxy.getContentType("a.zztest"));
			assertEquals("application/x-zztest", FileProxy.getContentType("a.ZZTEST"));
			FileSourceFactory.addMimeType(".zztest", "application/x-zztest2");
			assertEquals("application/x-zztest2", FileProxy.getContentType("a.zztest"), "replaced, a leading dot allowed");
			// a built in one can be replaced too
			FileSourceFactory.addMimeType("txt", "text/x-test");
			assertEquals("text/x-test", FileProxy.getContentType("a.TXT"));
		} finally {
			FileSourceFactory.addMimeType("txt", "text/plain");
		}
		assertEquals("text/plain", FileProxy.getContentType("a.txt"));
	}

	@Test
	void anExtensionAndATypeAreRequired() {
		assertThrows(IllegalArgumentException.class, () -> FileSourceFactory.addMimeType(null, "text/plain"));
		assertThrows(IllegalArgumentException.class, () -> FileSourceFactory.addMimeType("", "text/plain"));
		assertThrows(IllegalArgumentException.class, () -> FileSourceFactory.addMimeType(".", "text/plain"));
		assertThrows(IllegalArgumentException.class, () -> FileSourceFactory.addMimeType("zz", null));
	}
}
