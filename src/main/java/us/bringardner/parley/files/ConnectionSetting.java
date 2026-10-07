/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files;

import java.util.List;
import java.util.Objects;

/**
 * One setting a {@link FileSourceFactory} needs to connect (a host, a port, a password ...),
 * described so any UI can draw a field for it: a Swing form, a JavaFX form or a prompt on a
 * terminal. Factories describe their settings with {@link FileSourceFactory#getConnectionSettings()}
 * instead of supplying UI components.
 * <p>
 * Build one with the static methods and the "with" methods, for example
 * {@code ConnectionSetting.integer("port", "Port", 1L, 65535L).withDefault("22").asRequired()}.
 */
public final class ConnectionSetting {

	public enum Kind {
		/** One line of text. */
		TEXT,
		/** A password or similar: masked, and never shown or saved. */
		SECRET,
		/** Several lines of secret text, such as a pasted private key. */
		MULTILINE_SECRET,
		/** A whole number, between min and max if they're set. */
		INTEGER,
		/** "true" or "false". */
		BOOLEAN,
		/** One of the choices. */
		CHOICE,
		/** The path of a file on this computer (a UI can offer to browse for it). */
		LOCAL_FILE,
		/** Not shown or edited (the factory sets it itself). */
		HIDDEN
	}

	private final String key;
	private final String label;
	private final Kind kind;
	private final String defaultValue;
	private final String description;
	private final boolean required;
	private final boolean advanced;
	private final List<String> choices;
	private final Long min;
	private final Long max;
	private final String visibleWhenKey;
	private final String visibleWhenValue;

	public ConnectionSetting(String key, String label, Kind kind, String defaultValue, String description,
			boolean required, boolean advanced, List<String> choices, Long min, Long max,
			String visibleWhenKey, String visibleWhenValue) {
		if( key == null || key.isEmpty()) {
			throw new IllegalArgumentException("A setting needs a key");
		}
		this.key = key;
		this.label = label == null || label.isEmpty() ? key : label;
		this.kind = kind == null ? Kind.TEXT : kind;
		this.defaultValue = defaultValue == null ? "" : defaultValue;
		this.description = description == null ? "" : description;
		this.required = required;
		this.advanced = advanced;
		this.choices = choices == null ? List.of() : List.copyOf(choices);
		this.min = min;
		this.max = max;
		this.visibleWhenKey = visibleWhenKey;
		this.visibleWhenValue = visibleWhenValue;
	}

	/** The property name used with {@link FileSourceFactory#connect(java.util.Properties)}. */
	public String key() { return key; }
	/** What the user sees. */
	public String label() { return label; }
	/** How the value is edited. */
	public Kind kind() { return kind; }
	/** The value when there's none yet ("" for none). */
	public String defaultValue() { return defaultValue; }
	/** Help text (a tooltip), or "". */
	public String description() { return description; }
	/** True if it can't be left empty (while it applies). */
	public boolean required() { return required; }
	/** True for settings most connections leave alone (shown separately). */
	public boolean advanced() { return advanced; }
	/** The allowed values, for {@link Kind#CHOICE}. */
	public List<String> choices() { return choices; }
	/** The smallest value, for {@link Kind#INTEGER}, or null. */
	public Long min() { return min; }
	/** The largest value, for {@link Kind#INTEGER}, or null. */
	public Long max() { return max; }
	/** Another setting that decides whether this one applies, or null. */
	public String visibleWhenKey() { return visibleWhenKey; }
	/** The value visibleWhenKey must have for this one to apply. */
	public String visibleWhenValue() { return visibleWhenValue; }

	@Override
	public boolean equals(Object o) {
		if( !(o instanceof ConnectionSetting)) {
			return false;
		}
		ConnectionSetting s = (ConnectionSetting) o;
		return key.equals(s.key) && label.equals(s.label) && kind == s.kind && defaultValue.equals(s.defaultValue)
				&& description.equals(s.description) && required == s.required && advanced == s.advanced
				&& choices.equals(s.choices) && Objects.equals(min, s.min) && Objects.equals(max, s.max)
				&& Objects.equals(visibleWhenKey, s.visibleWhenKey) && Objects.equals(visibleWhenValue, s.visibleWhenValue);
	}

	@Override
	public int hashCode() {
		return Objects.hash(key, label, kind, defaultValue, required, advanced, choices, min, max, visibleWhenKey, visibleWhenValue);
	}

	@Override
	public String toString() {
		return key+" ("+kind+(required ? ", required" : "")+(advanced ? ", advanced" : "")+")";
	}

	private static ConnectionSetting of(String key, String label, Kind kind) {
		return new ConnectionSetting(key, label, kind, "", "", false, false, List.of(), null, null, null, null);
	}

	public static ConnectionSetting text(String key, String label) {
		return of(key, label, Kind.TEXT);
	}

	public static ConnectionSetting secret(String key, String label) {
		return of(key, label, Kind.SECRET);
	}

	public static ConnectionSetting multilineSecret(String key, String label) {
		return of(key, label, Kind.MULTILINE_SECRET);
	}

	/** @param min the smallest value, or null; @param max the largest, or null */
	public static ConnectionSetting integer(String key, String label, Long min, Long max) {
		return new ConnectionSetting(key, label, Kind.INTEGER, "", "", false, false, List.of(), min, max, null, null);
	}

	public static ConnectionSetting bool(String key, String label) {
		return of(key, label, Kind.BOOLEAN).withDefault("false");
	}

	/** The first choice is the default. */
	public static ConnectionSetting choice(String key, String label, String... choices) {
		List<String> list = List.of(choices);
		return new ConnectionSetting(key, label, Kind.CHOICE, list.isEmpty() ? "" : list.get(0), "", false, false,
				list, null, null, null, null);
	}

	public static ConnectionSetting localFile(String key, String label) {
		return of(key, label, Kind.LOCAL_FILE);
	}

	public static ConnectionSetting hidden(String key) {
		return of(key, key, Kind.HIDDEN);
	}

	public ConnectionSetting withDefault(String value) {
		return new ConnectionSetting(key, label, kind, value, description, required, advanced, choices, min, max, visibleWhenKey, visibleWhenValue);
	}

	public ConnectionSetting withDescription(String text) {
		return new ConnectionSetting(key, label, kind, defaultValue, text, required, advanced, choices, min, max, visibleWhenKey, visibleWhenValue);
	}

	public ConnectionSetting asRequired() {
		return new ConnectionSetting(key, label, kind, defaultValue, description, true, advanced, choices, min, max, visibleWhenKey, visibleWhenValue);
	}

	public ConnectionSetting asAdvanced() {
		return new ConnectionSetting(key, label, kind, defaultValue, description, required, true, choices, min, max, visibleWhenKey, visibleWhenValue);
	}

	/** This setting applies only while the setting otherKey has the value value. */
	public ConnectionSetting visibleWhen(String otherKey, String value) {
		return new ConnectionSetting(key, label, kind, defaultValue, description, required, advanced, choices, min, max, otherKey, value);
	}

	/** True for kinds whose values must not be shown or saved. */
	public boolean isSecret() {
		return kind == Kind.SECRET || kind == Kind.MULTILINE_SECRET;
	}

	/**
	 * What's wrong with value for this setting, or null if it's fine. Only checks the value
	 * itself; whether the setting applies is {@link ConnectionSettings#isVisible}.
	 */
	public String check(String value) {
		String v = value == null ? "" : value.trim();
		if( v.isEmpty()) {
			return required ? label+" is required" : null;
		}
		switch (kind) {
		case INTEGER:
			long n;
			try {
				n = Long.parseLong(v);
			} catch (NumberFormatException e) {
				return label+" must be a whole number";
			}
			if( (min != null && n < min) || (max != null && n > max)) {
				return label+" must be "+(min != null && max != null ? "between "+min+" and "+max
						: min != null ? "at least "+min : "at most "+max);
			}
			return null;
		case BOOLEAN:
			return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") ? null : label+" must be true or false";
		case CHOICE:
			return choices.contains(v) ? null : label+" must be one of "+String.join(", ", choices);
		default:
			return null;
		}
	}
}
