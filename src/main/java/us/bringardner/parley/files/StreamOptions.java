package us.bringardner.parley.files;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The settings one stream is opened with: an immutable map from {@link StreamOption} to a value.
 * An option that isn't in it is not set, and the stream then uses what its source would use
 * anyway (see {@link FileSource#getStreamDefaults()}).
 * <p>
 * These belong to the stream. A file system can't be asked to remember them for a file, so
 * they are given when the stream is opened, for example
 * {@code file.getInputStream(StreamOptions.buffer(1024*1024))}, and they only describe how
 * this stream works. Nothing about them is stored with the data, so the data can be read
 * back with any values.
 * <p>
 * They are advisory: a source uses the options it understands ({@link
 * FileSource#supportedStreamOptions()}) and ignores the rest (an in-memory file has nothing to
 * buffer), and may keep a value within limits that suit it.
 */
public final class StreamOptions {

	/** Nothing set: every setting comes from the source's defaults. */
	public static final StreamOptions NONE = new StreamOptions(Collections.emptyMap());

	private final Map<StreamOption<?>, Object> values;

	private StreamOptions(Map<StreamOption<?>, Object> values) {
		this.values = values;
	}

	/**
	 * @return these options with option set to value (replacing any value it had)
	 * @throws IllegalArgumentException if value isn't one the option accepts
	 */
	public <T> StreamOptions with(StreamOption<T> option, T value) {
		Objects.requireNonNull(option, "option");
		option.check(value);
		Map<StreamOption<?>, Object> copy = new LinkedHashMap<>(values);
		copy.put(option, value);
		return new StreamOptions(Collections.unmodifiableMap(copy));
	}

	/** @return these options without option, so it is not set */
	public StreamOptions without(StreamOption<?> option) {
		if( !values.containsKey(option) ) {
			return this;
		}
		Map<StreamOption<?>, Object> copy = new LinkedHashMap<>(values);
		copy.remove(option);
		return copy.isEmpty() ? NONE : new StreamOptions(Collections.unmodifiableMap(copy));
	}

	/** @return the value of option, or null if it isn't set */
	public <T> T get(StreamOption<T> option) {
		Object v = values.get(option);
		return v == null ? null : option.type().cast(v);
	}

	/** @return the value of option, or fallback if it isn't set */
	public <T> T get(StreamOption<T> option, T fallback) {
		T v = get(option);
		return v == null ? fallback : v;
	}

	public boolean isSet(StreamOption<?> option) {
		return values.containsKey(option);
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	/** @return what is set, as an unmodifiable map */
	public Map<StreamOption<?>, Object> asMap() {
		return values;
	}

	/**
	 * These options, with the ones that aren't set taken from {@code fallback}.
	 * @param fallback may be null (nothing to fall back on)
	 */
	public StreamOptions orElse(StreamOptions fallback) {
		if( fallback == null || fallback.values.isEmpty() ) {
			return this;
		}
		if( values.isEmpty() ) {
			return fallback;
		}
		Map<StreamOption<?>, Object> copy = new LinkedHashMap<>(fallback.values);
		copy.putAll(values);
		return new StreamOptions(Collections.unmodifiableMap(copy));
	}

	/**
	 * Options from text, such as properties: the entries whose key is the name of one of
	 * {@code known}. Entries for other names are left out, and an empty value means not set.
	 *
	 * @throws IllegalArgumentException if the value of a known option can't be read
	 */
	public static StreamOptions parse(Map<String, String> text, Collection<StreamOption<?>> known) {
		StreamOptions ret = NONE;
		if( text == null || known == null ) {
			return ret;
		}
		for(StreamOption<?> option : known) {
			String v = text.get(option.name());
			if( v != null && !v.trim().isEmpty() ) {
				ret = ret.withParsed(option, v);
			}
		}
		return ret;
	}

	/**
	 * Like {@link #parse(Map, Collection)}, but a name that isn't one of {@code known} is an
	 * error instead of being skipped, so a typo in a file or a command line is reported and not
	 * silently ignored. The message names the closest known option when there is one.
	 *
	 * @throws IllegalArgumentException for an unknown name, or a value that can't be read
	 */
	public static StreamOptions parseStrict(Map<String, String> text, Collection<StreamOption<?>> known) {
		if( text != null && !text.isEmpty() ) {
			List<String> unknown = new ArrayList<>();
			for(String name : new TreeSet<>(text.keySet())) {
				boolean found = false;
				if( known != null ) {
					for(StreamOption<?> option : known) {
						if( option.name().equals(name) ) {
							found = true;
							break;
						}
					}
				}
				if( !found ) {
					unknown.add(describeUnknown(name, known));
				}
			}
			if( !unknown.isEmpty() ) {
				StringBuilder msg = new StringBuilder("Unknown stream option");
				msg.append(unknown.size() > 1 ? "s: " : ": ").append(String.join("; ", unknown));
				msg.append(". Known: ");
				if( known == null || known.isEmpty() ) {
					msg.append("none");
				} else {
					List<String> names = new ArrayList<>();
					for(StreamOption<?> option : known) {
						names.add(option.name());
					}
					msg.append(String.join(", ", names));
				}
				throw new IllegalArgumentException(msg.toString());
			}
		}
		return parse(text, known);
	}

	private static String describeUnknown(String name, Collection<StreamOption<?>> known) {
		String best = null;
		int bestDistance = Integer.MAX_VALUE;
		if( known != null ) {
			for(StreamOption<?> option : known) {
				int d = distance(name.toLowerCase(), option.name().toLowerCase());
				if( d < bestDistance ) {
					bestDistance = d;
					best = option.name();
				}
			}
		}
		// close enough to be a slip of the fingers: up to a third of the name wrong, at most 3
		if( best != null && bestDistance <= Math.min(3, Math.max(1, best.length() / 3)) ) {
			return "'" + name + "' (did you mean '" + best + "'?)";
		}
		return "'" + name + "'";
	}

	/** The number of single character edits between a and b. */
	private static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for(int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for(int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for(int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	private <T> StreamOptions withParsed(StreamOption<T> option, String text) {
		return with(option, option.parse(text));
	}

	// ---- the two options everything knows about

	/** @return options with only the buffer size set */
	public static StreamOptions buffer(int size) {
		return NONE.withBufferSize(size);
	}

	/** @return options with only the chunk size set */
	public static StreamOptions chunk(int size) {
		return NONE.withChunkSize(size);
	}

	public StreamOptions withBufferSize(int size) {
		return with(StreamOption.BUFFER_SIZE, size);
	}

	public StreamOptions withChunkSize(int size) {
		return with(StreamOption.CHUNK_SIZE, size);
	}

	/** @return the buffer size, or 0 if it isn't set */
	public int bufferSize() {
		return get(StreamOption.BUFFER_SIZE, 0);
	}

	/** @return the chunk size, or 0 if it isn't set */
	public int chunkSize() {
		return get(StreamOption.CHUNK_SIZE, 0);
	}

	/** @return null is the same as {@link #NONE} */
	public static StreamOptions orNone(StreamOptions options) {
		return options == null ? NONE : options;
	}

	@Override
	public boolean equals(Object o) {
		if( this == o ) {
			return true;
		}
		return o instanceof StreamOptions && values.equals(((StreamOptions) o).values);
	}

	@Override
	public int hashCode() {
		return values.hashCode();
	}

	@Override
	public String toString() {
		return "StreamOptions" + values;
	}
}
