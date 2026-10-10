package us.bringardner.parley.files;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One setting a stream can be opened with: a name and the type of its value, like
 * {@link java.net.SocketOption}. A source uses the options it understands and ignores the
 * rest, so anyone can define one (in their own module) without touching this one:
 * <pre>
 * static final StreamOption&lt;Integer&gt; READ_AHEAD = StreamOption.positiveInt("sftp.readAhead");
 * </pre>
 * and give them in a {@link StreamOptions}.
 * <p>
 * An option is a key. Two with the same name and type are the same option, so a name should
 * say whose it is when it isn't one defined here (a source's prefix, as above).
 * <p>
 * A key can read its value from text ({@link #parse(String)}), so a setting that comes from a
 * property or a configuration file can be turned into one.
 *
 * @param <T> the type of the value
 */
public final class StreamOption<T> {

	/**
	 * How much the stream moves at a time: the size of the buffer between the caller and the
	 * source, and what a loop that copies the stream should read and write per call. Bytes.
	 */
	public static final StreamOption<Integer> BUFFER_SIZE = positiveInt("bufferSize");

	/**
	 * How the data is stored or addressed in the source, for those where that is a choice: the
	 * size of the rows a database source cuts a write into, or the granularity of random
	 * access over a network. Bytes. It only affects how this stream reads or writes; data
	 * already stored is read the way it was written.
	 */
	public static final StreamOption<Integer> CHUNK_SIZE = positiveInt("chunkSize");

	private final String name;
	private final Class<T> type;
	private final Function<String, T> parser;
	private final Predicate<T> valid;
	private final String requirement;

	private StreamOption(String name, Class<T> type, Function<String, T> parser, Predicate<T> valid, String requirement) {
		this.name = name;
		this.type = type;
		this.parser = parser;
		this.valid = valid;
		this.requirement = requirement;
	}

	/**
	 * An option whose value can be any value of the type.
	 * @param type String, Integer, Long, Boolean or Double (the ones that can be read from text);
	 * for any other type use {@link #of(String, Class, Function, Predicate, String)}
	 */
	@SuppressWarnings("unchecked")
	public static <T> StreamOption<T> of(String name, Class<T> type) {
		Function<String, ?> parser;
		if( type == String.class ) {
			parser = s -> s;
		} else if( type == Integer.class ) {
			parser = s -> Integer.valueOf(s.trim());
		} else if( type == Long.class ) {
			parser = s -> Long.valueOf(s.trim());
		} else if( type == Boolean.class ) {
			parser = s -> {
				String t = s.trim();
				if( !t.equalsIgnoreCase("true") && !t.equalsIgnoreCase("false") ) {
					throw new IllegalArgumentException("not true or false: " + s);
				}
				return Boolean.valueOf(t);
			};
		} else if( type == Double.class ) {
			parser = s -> Double.valueOf(s.trim());
		} else {
			throw new IllegalArgumentException("Don't know how to read a " + type.getName()
					+ " from text; give a parser");
		}
		return new StreamOption<>(checkName(name), type, (Function<String, T>) parser, v -> true, "");
	}

	/**
	 * An option with its own way to read a value from text and its own rule for which values
	 * are acceptable.
	 * @param valid says whether a value is acceptable; may be null (any)
	 * @param requirement what a value must be, for the message when it isn't (such as
	 * "greater than 0"); may be null
	 */
	public static <T> StreamOption<T> of(String name, Class<T> type, Function<String, T> parser,
			Predicate<T> valid, String requirement) {
		return new StreamOption<>(checkName(name), Objects.requireNonNull(type, "type"),
				Objects.requireNonNull(parser, "parser"), valid == null ? v -> true : valid,
				requirement == null ? "" : requirement);
	}

	/** An option that is a whole number of at least 1 (a size in bytes, a depth, a count). */
	public static StreamOption<Integer> positiveInt(String name) {
		return of(name, Integer.class, s -> Integer.valueOf(s.trim()), v -> v > 0, "greater than 0");
	}

	private static String checkName(String name) {
		if( name == null || name.trim().isEmpty() ) {
			throw new IllegalArgumentException("An option needs a name");
		}
		return name.trim();
	}

	public String name() {
		return name;
	}

	public Class<T> type() {
		return type;
	}

	/**
	 * @return the value text stands for
	 * @throws IllegalArgumentException if it isn't a value of this option's type, or isn't acceptable
	 */
	public T parse(String text) {
		if( text == null ) {
			throw new IllegalArgumentException(name + " has no value");
		}
		T value;
		try {
			value = parser.apply(text);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + ": '" + text + "' is not a " + type.getSimpleName(), e);
		}
		return check(value);
	}

	/**
	 * @return value, if it is one this option accepts
	 * @throws IllegalArgumentException if it isn't
	 */
	public T check(Object value) {
		if( value == null ) {
			throw new IllegalArgumentException(name + " can't be null");
		}
		if( !type.isInstance(value) ) {
			throw new IllegalArgumentException(name + " takes a " + type.getSimpleName() + ", not a "
					+ value.getClass().getSimpleName());
		}
		T ret = type.cast(value);
		if( !valid.test(ret) ) {
			throw new IllegalArgumentException(name + " must be " + requirement + ": " + value);
		}
		return ret;
	}

	@Override
	public boolean equals(Object o) {
		if( this == o ) {
			return true;
		}
		if( !(o instanceof StreamOption) ) {
			return false;
		}
		StreamOption<?> s = (StreamOption<?>) o;
		return name.equals(s.name) && type == s.type;
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, type);
	}

	@Override
	public String toString() {
		return name + " (" + type.getSimpleName() + ")";
	}
}
