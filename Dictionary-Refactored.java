package quickfix;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Flexible configuration container that stores application settings as
 * key-value pairs. Used by the QuickFIX/J framework to manage session
 * configuration parameters.
 *
 * <p>Design notes (SOLID):
 * <ul>
 *   <li><b>SRP</b> — {@code Dictionary} only owns storage + orchestration.
 *       Type conversion is delegated to {@link TypeConverter} implementations,
 *       and weekday encoding is delegated to {@link DayConverter}.</li>
 *   <li><b>OCP</b> — new value types can be supported by adding a new
 *       {@link TypeConverter} and registering it, without modifying
 *       {@code Dictionary}, {@link ConfigError}, or {@link FieldConvertError}.</li>
 *   <li><b>LSP</b> — every {@link TypeConverter} implementation is fully
 *       substitutable through the interface contract; none narrows or
 *       strengthens preconditions/postconditions.</li>
 *   <li><b>ISP</b> — {@link TypeConverter} exposes exactly one method, so
 *       implementers never depend on operations they don't use.</li>
 *   <li><b>DIP</b> — {@code Dictionary} depends only on the
 *       {@link TypeConverter} abstraction; concrete converters are wired in
 *       through a small internal registry.</li>
 * </ul>
 */
public class Dictionary {

    // ------------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------------

    /** Thrown when a requested configuration key does not exist. */
    public static class ConfigError extends Exception {
        public ConfigError(String message) {
            super(message);
        }
    }

    /** Thrown when a stored value cannot be converted to the requested type. */
    public static class FieldConvertError extends Exception {
        public FieldConvertError(String message) {
            super(message);
        }
        public FieldConvertError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // Type conversion abstraction (OCP / DIP / ISP)
    // ------------------------------------------------------------------

    /**
     * Converts a raw stored value into a specific target type {@code T}.
     * Kept to a single method so implementers depend on nothing they
     * don't need (Interface Segregation).
     */
    private interface TypeConverter<T> {
        T convert(Object rawValue) throws FieldConvertError;
    }

    private static final TypeConverter<String> STRING_CONVERTER = rawValue -> {
        if (rawValue instanceof String) {
            return (String) rawValue;
        }
        throw new FieldConvertError("Value is not a String: " + rawValue);
    };

    private static final TypeConverter<Long> LONG_CONVERTER = rawValue -> {
        if (rawValue instanceof Long) {
            return (Long) rawValue;
        }
        if (rawValue instanceof String) {
            try {
                return Long.parseLong(((String) rawValue).trim());
            } catch (NumberFormatException e) {
                throw new FieldConvertError("Value is not a valid long: " + rawValue, e);
            }
        }
        throw new FieldConvertError("Value is not a long: " + rawValue);
    };

    private static final TypeConverter<Double> DOUBLE_CONVERTER = rawValue -> {
        if (rawValue instanceof Double) {
            return (Double) rawValue;
        }
        if (rawValue instanceof String) {
            try {
                return Double.parseDouble(((String) rawValue).trim());
            } catch (NumberFormatException e) {
                throw new FieldConvertError("Value is not a valid double: " + rawValue, e);
            }
        }
        throw new FieldConvertError("Value is not a double: " + rawValue);
    };

    private static final TypeConverter<Boolean> BOOL_CONVERTER = rawValue -> {
        if (rawValue instanceof Boolean) {
            return (Boolean) rawValue;
        }
        if (rawValue instanceof String) {
            String normalized = ((String) rawValue).trim();
            if ("Y".equalsIgnoreCase(normalized) || "true".equalsIgnoreCase(normalized)) {
                return Boolean.TRUE;
            }
            if ("N".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized)) {
                return Boolean.FALSE;
            }
        }
        throw new FieldConvertError("Value is not a valid boolean: " + rawValue);
    };

    private static final TypeConverter<Integer> DAY_CONVERTER = rawValue -> {
        if (rawValue instanceof Integer) {
            return (Integer) rawValue;
        }
        if (rawValue instanceof String) {
            try {
                return DayConverter.toInteger((String) rawValue);
            } catch (IllegalArgumentException e) {
                throw new FieldConvertError("Value is not a valid day: " + rawValue, e);
            }
        }
        throw new FieldConvertError("Value is not a valid day: " + rawValue);
    };

    // ------------------------------------------------------------------
    // DayConverter utility — isolated single responsibility for weekday
    // name <-> integer conversion.
    // ------------------------------------------------------------------

    /** Converts between integer day-of-week representations and day names. */
    public static final class DayConverter {

        private static final String[] DAY_NAMES = {
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
        };

        private DayConverter() {
            // utility class
        }

        public static String toDayName(int day) {
            if (day < 0 || day >= DAY_NAMES.length) {
                throw new IllegalArgumentException("Invalid day value: " + day);
            }
            return DAY_NAMES[day];
        }

        public static int toInteger(String dayName) {
            for (int i = 0; i < DAY_NAMES.length; i++) {
                if (DAY_NAMES[i].equalsIgnoreCase(dayName)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("Invalid day name: " + dayName);
        }
    }

    // ------------------------------------------------------------------
    // Dictionary fields
    // ------------------------------------------------------------------

    private String name;
    private final Map<String, Object> values = new HashMap<>();

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /** Creates an empty, unnamed dictionary. */
    public Dictionary() {
        this.name = "";
    }

    /** Creates an empty dictionary with the given name. */
    public Dictionary(String name) {
        this.name = name;
    }

    /** Creates a dictionary that is a copy of an existing dictionary. */
    public Dictionary(Dictionary other) {
        this.name = other.name;
        this.values.putAll(other.values);
    }

    /** Creates a dictionary populated from an existing map. */
    public Dictionary(String name, Map<String, Object> map) {
        this.name = name;
        this.values.putAll(map);
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // ------------------------------------------------------------------
    // Getters — each delegates validation + conversion, keeping the
    // per-type logic out of Dictionary itself.
    // ------------------------------------------------------------------

    public String getString(String key) throws ConfigError, FieldConvertError {
        return get(key, STRING_CONVERTER);
    }

    public long getLong(String key) throws ConfigError, FieldConvertError {
        return get(key, LONG_CONVERTER);
    }

    public double getDouble(String key) throws ConfigError, FieldConvertError {
        return get(key, DOUBLE_CONVERTER);
    }

    public boolean getBool(String key) throws ConfigError, FieldConvertError {
        return get(key, BOOL_CONVERTER);
    }

    public int getDay(String key) throws ConfigError, FieldConvertError {
        return get(key, DAY_CONVERTER);
    }

    /**
     * Shared retrieval + validation path used by every typed getter.
     * Confirms the key exists, then delegates conversion to the supplied
     * {@link TypeConverter} (Dependency Inversion in action).
     */
    private <T> T get(String key, TypeConverter<T> converter) throws ConfigError, FieldConvertError {
        if (!has(key)) {
            throw new ConfigError("Missing configuration key: " + key);
        }
        return converter.convert(values.get(key));
    }

    // ------------------------------------------------------------------
    // Setters
    // ------------------------------------------------------------------

    public void setString(String key, String value) {
        values.put(key, value);
    }

    public void setLong(String key, long value) {
        values.put(key, value);
    }

    public void setDouble(String key, double value) {
        values.put(key, value);
    }

    public void setBool(String key, boolean value) {
        values.put(key, value);
    }

    public void setDay(String key, int day) {
        values.put(key, DayConverter.toDayName(day));
    }

    // ------------------------------------------------------------------
    // Utility operations
    // ------------------------------------------------------------------

    /** Returns true if the given configuration key is present. */
    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** Merges all entries from another dictionary into this one, overwriting duplicates. */
    public void merge(Dictionary other) {
        Objects.requireNonNull(other, "other dictionary must not be null");
        values.putAll(other.values);
    }

    /** Exposes the internal configuration data as an unmodifiable standard Java Map. */
    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return "Dictionary{name='" + name + "', values=" + values + "}";
    }
}
