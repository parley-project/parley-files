package us.bringardner.parley.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/**
 * Typed stream options: keys anyone can define, a small immutable map of values, and open
 * methods on FileSource that a source which doesn't know them treats as the plain ones.
 */
public class StreamOptionsTest {

	/** An option nobody in this module knows about, as another module would define one. */
	static final StreamOption<Integer> READ_AHEAD = StreamOption.positiveInt("test.readAhead");
	static final StreamOption<Boolean> VERIFY = StreamOption.of("test.verify", Boolean.class);
	static final StreamOption<String> LABEL = StreamOption.of("test.label", String.class);

	// ------------------------------------------------------------ keys

	@Test
	void aKeyIsANameAndAType() {
		assertEquals("bufferSize", StreamOption.BUFFER_SIZE.name());
		assertEquals(Integer.class, StreamOption.BUFFER_SIZE.type());
		assertEquals(StreamOption.positiveInt("bufferSize"), StreamOption.BUFFER_SIZE, "same name and type");
		assertNotEquals(StreamOption.of("bufferSize", Long.class), StreamOption.BUFFER_SIZE, "same name, other type");
		assertThrows(IllegalArgumentException.class, () -> StreamOption.of(" ", String.class));
		assertThrows(IllegalArgumentException.class, () -> StreamOption.of("x", List.class), "no way to read it from text");
	}

	@Test
	void aKeyReadsItsValueFromText() {
		assertEquals(65536, StreamOption.BUFFER_SIZE.parse(" 65536 "));
		assertEquals(Boolean.TRUE, VERIFY.parse("TRUE"));
		assertEquals("a b", LABEL.parse("a b"));
		assertThrows(IllegalArgumentException.class, () -> StreamOption.BUFFER_SIZE.parse("lots"));
		assertThrows(IllegalArgumentException.class, () -> StreamOption.BUFFER_SIZE.parse("0"));
		assertThrows(IllegalArgumentException.class, () -> VERIFY.parse("maybe"));
	}

	@Test
	void aKeyRefusesAValueOfTheWrongTypeOrRange() {
		assertThrows(IllegalArgumentException.class, () -> StreamOption.BUFFER_SIZE.check("big"));
		assertThrows(IllegalArgumentException.class, () -> StreamOption.BUFFER_SIZE.check(-1));
		assertThrows(IllegalArgumentException.class, () -> StreamOption.BUFFER_SIZE.check(0));
		assertEquals(4096, StreamOption.BUFFER_SIZE.check(4096));
	}

	// ------------------------------------------------------------ values

	@Test
	void nothingIsSetByDefault() {
		assertTrue(StreamOptions.NONE.isEmpty());
		assertNull(StreamOptions.NONE.get(StreamOption.BUFFER_SIZE));
		assertEquals(0, StreamOptions.NONE.bufferSize());
		assertEquals(0, StreamOptions.NONE.chunkSize());
		assertSame(StreamOptions.NONE, StreamOptions.orNone(null));
	}

	@Test
	void valuesAreImmutableAndTyped() {
		StreamOptions a = StreamOptions.NONE.with(StreamOption.BUFFER_SIZE, 65536);
		StreamOptions b = a.with(READ_AHEAD, 8).with(VERIFY, true);
		assertEquals(65536, a.bufferSize());
		assertFalse(a.isSet(READ_AHEAD), "with() made a new one");
		assertEquals(8, b.get(READ_AHEAD));
		assertEquals(Boolean.TRUE, b.get(VERIFY));
		assertEquals(65536, b.bufferSize());
		assertTrue(StreamOptions.NONE.isEmpty());
		assertNotEquals(a, b);
		assertEquals(a, StreamOptions.buffer(65536));
		assertEquals(a.hashCode(), StreamOptions.buffer(65536).hashCode());
		assertEquals(7, a.get(READ_AHEAD, 7), "the fallback for what isn't set");
		assertThrows(UnsupportedOperationException.class, () -> b.asMap().clear());
	}

	@Test
	void aValueThePlaceRefusesIsRefusedWhenItIsSet() {
		assertThrows(IllegalArgumentException.class, () -> StreamOptions.buffer(0));
		assertThrows(IllegalArgumentException.class, () -> StreamOptions.NONE.withChunkSize(-5));
		@SuppressWarnings({ "unchecked", "rawtypes" })
		StreamOption<Object> raw = (StreamOption) StreamOption.BUFFER_SIZE;
		assertThrows(IllegalArgumentException.class, () -> StreamOptions.NONE.with(raw, "a string"));
	}

	@Test
	void withoutUnsets() {
		StreamOptions a = StreamOptions.buffer(100).with(READ_AHEAD, 2);
		assertEquals(StreamOptions.buffer(100), a.without(READ_AHEAD));
		assertSame(StreamOptions.NONE, StreamOptions.buffer(100).without(StreamOption.BUFFER_SIZE));
		assertSame(a, a.without(VERIFY));
	}

	@Test
	void orElseTakesWhatIsNotSetFromTheFallback() {
		StreamOptions mine = StreamOptions.buffer(8192).with(VERIFY, false);
		StreamOptions fallback = StreamOptions.buffer(1).withChunkSize(2048).with(READ_AHEAD, 4);
		StreamOptions got = mine.orElse(fallback);
		assertEquals(8192, got.bufferSize(), "mine wins");
		assertEquals(2048, got.chunkSize(), "the fallback fills the gap");
		assertEquals(4, got.get(READ_AHEAD));
		assertEquals(Boolean.FALSE, got.get(VERIFY));
		assertSame(mine, mine.orElse(null));
		assertSame(fallback, StreamOptions.NONE.orElse(fallback));
	}

	@Test
	void optionsComeFromTextForTheKeysYouKnow() {
		// the names come from the keys, so there is no second copy of the string to mistype
		Map<String, String> text = new HashMap<>();
		text.put(StreamOption.BUFFER_SIZE.name(), "262144");
		text.put(READ_AHEAD.name(), "8");
		text.put(VERIFY.name(), "");          // empty is not set
		text.put("somethingElse", "ignored");  // not one of the known
		StreamOptions o = StreamOptions.parse(text, List.of(StreamOption.BUFFER_SIZE, READ_AHEAD, VERIFY));
		assertEquals(262144, o.bufferSize());
		assertEquals(8, o.get(READ_AHEAD));
		assertFalse(o.isSet(VERIFY));
		assertEquals(2, o.asMap().size());

		text.put(StreamOption.BUFFER_SIZE.name(), "lots");
		assertThrows(IllegalArgumentException.class,
				() -> StreamOptions.parse(text, List.of(StreamOption.BUFFER_SIZE)));
		assertTrue(StreamOptions.parse(null, null).isEmpty());
	}

	@Test
	void strictParsingReportsANameItDoesNotKnow() {
		List<StreamOption<?>> known = List.of(StreamOption.BUFFER_SIZE, StreamOption.CHUNK_SIZE, READ_AHEAD);

		Map<String, String> good = new HashMap<>();
		good.put(StreamOption.BUFFER_SIZE.name(), "4096");
		good.put(READ_AHEAD.name(), "");
		StreamOptions o = StreamOptions.parseStrict(good, known);
		assertEquals(4096, o.bufferSize());
		assertFalse(o.isSet(READ_AHEAD), "empty is not set");

		Map<String, String> typo = new HashMap<>();
		typo.put("bufferSzie", "4096");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> StreamOptions.parseStrict(typo, known));
		assertTrue(e.getMessage().contains("'bufferSzie' (did you mean '" + StreamOption.BUFFER_SIZE.name() + "'?)"), e.getMessage());
		assertTrue(e.getMessage().contains(StreamOption.CHUNK_SIZE.name()), "lists what is known: " + e.getMessage());

		// a different case is the same slip
		typo.clear();
		typo.put("BUFFERSIZE", "4096");
		e = assertThrows(IllegalArgumentException.class, () -> StreamOptions.parseStrict(typo, known));
		assertTrue(e.getMessage().contains("did you mean '" + StreamOption.BUFFER_SIZE.name() + "'"), e.getMessage());

		// nothing near it: no suggestion, and every unknown name is listed
		typo.clear();
		typo.put("compression", "zlib");
		typo.put("x", "1");
		e = assertThrows(IllegalArgumentException.class, () -> StreamOptions.parseStrict(typo, known));
		assertTrue(e.getMessage().contains("'compression'") && e.getMessage().contains("'x'"), e.getMessage());
		assertFalse(e.getMessage().contains("did you mean"), e.getMessage());

		// the lenient one still skips it
		assertTrue(StreamOptions.parse(typo, known).isEmpty());
		assertTrue(StreamOptions.parseStrict(null, known).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> StreamOptions.parseStrict(typo, null));
	}

	// ------------------------------------------------------------ on a FileSource

	@Test
	void aSourceThatDoesntKnowThemBehavesAsBefore() throws Exception {
		FileSource file = new MemoryFileSourceFactory().createFileSource("/streamOptions.txt");
		assertEquals(StreamOptions.NONE, file.getStreamDefaults());
		assertTrue(file.supportedStreamOptions().isEmpty());

		// an option nobody here knows is ignored
		StreamOptions o = StreamOptions.buffer(1024).withChunkSize(512).with(READ_AHEAD, 3);
		byte[] data = "the same bytes whatever the options".getBytes(StandardCharsets.UTF_8);
		try (OutputStream out = file.getOutputStream(false, o)) {
			out.write(data);
		}
		try (InputStream in = file.getInputStream(o)) {
			assertArrayEquals(data, in.readAllBytes());
		}
		try (InputStream in = file.getInputStream(5, o)) {
			assertArrayEquals(Arrays.copyOfRange(data, 5, data.length), in.readAllBytes());
		}
		try (InputStream in = file.getInputStream((StreamOptions) null)) {
			assertArrayEquals(data, in.readAllBytes(), "null is the defaults");
		}
	}
}
