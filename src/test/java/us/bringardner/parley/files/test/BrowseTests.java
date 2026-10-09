package us.bringardner.parley.files.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.browse.DirectoryListing;
import us.bringardner.parley.files.browse.FileEntry;
import us.bringardner.parley.files.browse.NavigationHistory;
import us.bringardner.parley.files.browse.SelectionMode;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The UI-free browsing model shared by the Swing and JavaFX choosers and browsers. */
public class BrowseTests {

	private MemoryFileSourceFactory factory;
	private FileSource dir;

	@BeforeEach
	public void setUp() throws IOException {
		factory = new MemoryFileSourceFactory();
		factory.connect();
		dir = factory.createFileSource("/browse-"+System.nanoTime());
		dir.mkdirs();
		write(dir.getChild("b.TXT"), 30);
		write(dir.getChild("a.log"), 5);
		write(dir.getChild("C.txt"), 10);
		write(dir.getChild(".hidden"), 1);
		dir.getChild("sub").mkdir();
		dir.getChild(".git").mkdir();
	}

	private static void write(FileSource file, int bytes) throws IOException {
		try(OutputStream out = file.getOutputStream()) {
			out.write(new byte[bytes]);
		}
	}

	private static List<String> names(List<FileEntry> entries) {
		return entries.stream().map(FileEntry::getName).collect(Collectors.toList());
	}

	@Test
	public void listsByNameWithTheirDetails() throws IOException {
		List<FileEntry> all = DirectoryListing.list(dir, null);
		assertEquals(List.of(".git", ".hidden", "a.log", "b.TXT", "C.txt", "sub"), names(all));
		FileEntry b = all.get(3);
		assertEquals(30, b.getLength());
		assertFalse(b.isDirectory());
		assertEquals("TXT", b.getExtension());
		assertEquals("b", b.getDisplayName(false));
		assertEquals("b.TXT", b.getDisplayName(true));
		FileEntry sub = all.get(5);
		assertTrue(sub.isDirectory());
		assertEquals(0, sub.getLength());
		assertEquals("sub", sub.getDisplayName(false));
		assertNull(sub.getError());
	}

	@Test
	public void hiddenOnesOnlyWhenAskedFor() throws IOException {
		List<FileEntry> all = DirectoryListing.list(dir, null);
		assertEquals(List.of("a.log", "b.TXT", "C.txt", "sub"), names(DirectoryListing.shown(all, false)));
		assertEquals(6, DirectoryListing.shown(all, true).size());
		assertEquals(List.of("sub"), names(DirectoryListing.directories(dir, false, null)));
		assertEquals(List.of(".git", "sub"), names(DirectoryListing.directories(dir, true, null)));
	}

	@Test
	public void sortOrders() throws IOException {
		List<FileEntry> all = DirectoryListing.shown(DirectoryListing.list(dir, null), false);
		List<FileEntry> bySize = new ArrayList<>(all);
		bySize.sort(DirectoryListing.directoriesFirst(DirectoryListing.BY_SIZE));
		assertEquals(List.of("sub", "a.log", "C.txt", "b.TXT"), names(bySize));
	}

	@Test
	public void whatCanBePicked() throws IOException {
		List<FileEntry> all = DirectoryListing.shown(DirectoryListing.list(dir, null), false);
		FileEntry log = all.get(0);
		FileEntry sub = all.get(3);
		us.bringardner.parley.files.FileSourceFilter txt = f->f.getName().toLowerCase().endsWith(".txt");

		assertTrue(DirectoryListing.isSelectable(log, SelectionMode.FILES, null));
		assertFalse(DirectoryListing.isSelectable(log, SelectionMode.FILES, txt));
		assertFalse(DirectoryListing.isSelectable(sub, SelectionMode.FILES, null));
		assertTrue(DirectoryListing.isSelectable(sub, SelectionMode.DIRECTORIES, txt));
		assertFalse(DirectoryListing.isSelectable(log, SelectionMode.DIRECTORIES, null));
		assertTrue(DirectoryListing.isSelectable(sub, SelectionMode.FILES_AND_DIRECTORIES, null));
	}

	@Test
	public void listingCanBeCanceled() {
		FileSourceProgress stop = new FileSourceProgress() {
			int max;
			@Override public void setMaximum(int m) { max = m; }
			@Override public int getMaximum() { return max; }
			@Override public void setProgress(int value) { }
			@Override public boolean isCanceled() { return true; }
		};
		assertThrows(CancellationException.class, ()->DirectoryListing.list(dir, stop));
	}

	@Test
	public void progressIsReported() throws IOException {
		List<Integer> seen = new ArrayList<>();
		int[] max = {0};
		FileSourceProgress watch = new FileSourceProgress() {
			@Override public void setMaximum(int m) { max[0] = m; }
			@Override public int getMaximum() { return max[0]; }
			@Override public void setProgress(int value) { seen.add(value); }
		};
		DirectoryListing.list(dir, watch);
		assertEquals(6, max[0]);
		assertEquals(Integer.valueOf(6), seen.get(seen.size()-1));
	}

	@Test
	public void detailsAreReadOnce() throws Exception {
		FileSource real = dir.getChild("b.TXT");
		Map<String,Integer> calls = new ConcurrentHashMap<>();
		FileSource counted = (FileSource) Proxy.newProxyInstance(FileSource.class.getClassLoader(),
				new Class<?>[] {FileSource.class}, (proxy, method, args)->{
					calls.merge(method.getName(), 1, Integer::sum);
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		FileEntry e = FileEntry.of(counted);
		for(int i=0; i < 3; i++) {
			e.getLength();
			e.getLastModified();
			e.isDirectory();
			e.isHidden();
		}
		assertEquals(1, calls.get("length"));
		assertEquals(1, calls.get("lastModified"));
		assertEquals(1, calls.get("isDirectory"));
		assertEquals(1, calls.get("isHidden"));
	}

	@Test
	public void ancestorsTopFirst() throws IOException {
		FileSource deep = dir.getChild("sub").getChild("deeper");
		deep.mkdir();
		List<FileSource> path = DirectoryListing.ancestors(deep);
		assertEquals(deep.getAbsolutePath(), path.get(path.size()-1).getAbsolutePath());
		assertEquals(dir.getAbsolutePath(), path.get(path.size()-3).getAbsolutePath());
		assertNull(path.get(0).getParentFile());
	}

	@Test
	public void history() throws IOException {
		NavigationHistory h = new NavigationHistory();
		FileSource a = dir;
		FileSource b = dir.getChild("sub");
		FileSource c = factory.createFileSource(b.getAbsolutePath());
		assertFalse(h.canGoBack());
		h.go(a);
		h.go(b);
		// the same path again isn't a new step
		h.go(c);
		assertTrue(h.canGoBack());
		assertSame(a, h.back());
		assertTrue(h.canGoForward());
		assertSame(b, h.forward());
		h.back();
		h.go(dir.getChild(".git"));
		// going somewhere new drops forward history
		assertFalse(h.canGoForward());
		assertSame(a, h.back());
		assertFalse(h.canGoBack());
		assertSame(a, h.back());
	}

	@Test
	public void historyIsLimited() throws IOException {
		NavigationHistory h = new NavigationHistory(2);
		for(String n : new String[] {"1", "2", "3", "4"}) {
			FileSource d = dir.getChild(n);
			d.mkdir();
			h.go(d);
		}
		h.back();
		h.back();
		assertFalse(h.canGoBack());
		assertEquals("2", h.current().getName());
	}
}
