import java.io.UnsupportedEncodingException;
import java.util.Objects;

/**
 * Field.java
 *
 * Generic base class representing a single tag=value field within a FIX
 * protocol message. Responsible for storing the tag/value pair, formatting
 * it into the standard FIX wire representation, encoding it into bytes,
 * and computing the derived length/checksum used during message assembly.
 *
 * SOLID notes:
 *  - Single Responsibility: this class only knows how to store, format,
 *    encode and compare ONE field. It has nothing to do with building or
 *    parsing a whole FIX message.
 *  - Open/Closed: the class is closed for modification but open for
 *    extension. Subclasses customize behavior purely by overriding the
 *    protected objectAsString() hook instead of touching calculate(),
 *    toString(), getLength() or getChecksum().
 *  - Liskov Substitution: any subclass can be used wherever a Field is
 *    expected because it never narrows the contract of the public API.
 *  - Interface Segregation: the public surface is deliberately small
 *    (getTag/getField/getObject/setObject/toString/getLength/getChecksum)
 *    so callers only depend on what they actually need.
 *  - Dependency Inversion: the class depends only on the abstract notion
 *    of "how to turn the object into a string" (objectAsString), not on
 *    any concrete value type, so higher level code and subclasses can vary
 *    independently.
 *
 * @param <T> the type of the value stored in this field
 */
public class Field<T> {

    /** Character set used when encoding the formatted field into bytes. */
    private static final String DEFAULT_CHARSET = "UTF-8";

    /** The FIX tag number that identifies this field. */
    private int tag;

    /** The generic value held by this field. */
    private T object;

    /** Cached "tag=value" string representation. */
    private String stringData;

    /** Cached encoded byte representation of stringData. */
    private byte[] encodedData;

    /** Tracks whether the cached representation is still valid. */
    private boolean calculated;

    /**
     * Creates a field with the given tag and initial value.
     *
     * @param tag    the FIX tag number
     * @param object the initial value of the field
     */
    public Field(int tag, T object) {
        this.tag = tag;
        this.object = object;
        invalidate();
    }

    /**
     * Returns the FIX tag number for this field.
     *
     * @return the tag number
     */
    public int getTag() {
        return tag;
    }

    /**
     * Returns the fully formatted "tag=value" representation of this field,
     * recalculating and caching it if it has not been computed yet.
     *
     * @return the formatted field string
     */
    public String getField() {
        return toString();
    }

    /**
     * Returns the raw value stored in this field.
     *
     * @return the field's value
     */
    public T getObject() {
        return object;
    }

    /**
     * Replaces the value stored in this field and invalidates any cached
     * formatted/encoded data so it is recalculated on next access.
     *
     * @param object the new value for this field
     */
    public void setObject(T object) {
        this.object = object;
        invalidate();
    }

    /**
     * Replaces the tag for this field and invalidates any cached
     * formatted/encoded data so it is recalculated on next access.
     *
     * @param tag the new tag number
     */
    public void setTag(int tag) {
        this.tag = tag;
        invalidate();
    }

    /**
     * Marks the cached string/byte representation as stale. Called
     * whenever the tag or value changes.
     */
    private void invalidate() {
        this.calculated = false;
        this.stringData = null;
        this.encodedData = null;
    }

    /**
     * Builds the "tag=value" representation of this field and encodes it
     * into bytes using the configured character set, caching both results
     * so subsequent calls avoid repeating the work.
     */
    private void calculate() {
        if (calculated) {
            return;
        }

        stringData = tag + "=" + objectAsString(object);

        try {
            encodedData = stringData.getBytes(DEFAULT_CHARSET);
        } catch (UnsupportedEncodingException e) {
            // Fall back to the platform default charset if the configured
            // one is somehow unavailable, rather than losing the field.
            encodedData = stringData.getBytes();
        }

        calculated = true;
    }

    /**
     * Converts the stored value into its string form for use in the
     * "tag=value" representation. Subclasses can override this to control
     * how specific data types (dates, decimals, booleans, etc.) are
     * rendered, without needing to touch any other part of the class.
     *
     * @param value the value to convert
     * @return the string representation of the value
     */
    protected String objectAsString(T value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Returns the encoded byte length of this field, recalculating the
     * cached representation first if necessary.
     *
     * @return the number of bytes in the encoded field
     */
    public int getLength() {
        calculate();
        return encodedData.length;
    }

    /**
     * Returns the checksum contribution of this field, computed as the sum
     * of its encoded bytes modulo 256, as required by the FIX checksum
     * algorithm. Relies on the cached encoded representation.
     *
     * @return the checksum value for this field, in the range 0-255
     */
    public int getChecksum() {
        calculate();
        int sum = 0;
        for (byte b : encodedData) {
            sum += (b & 0xFF);
        }
        return sum % 256;
    }

    /**
     * Returns the formatted "tag=value" representation of this field,
     * calculating and caching it on first use.
     *
     * @return the formatted field string
     */
    @Override
    public String toString() {
        calculate();
        return stringData;
    }

    /**
     * Two fields are equal when they share the same tag and an equal
     * value.
     *
     * @param obj the object to compare against
     * @return true if obj is a Field with the same tag and value
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Field)) {
            return false;
        }
        Field<?> other = (Field<?>) obj;
        return tag == other.tag && Objects.equals(object, other.object);
    }

    /**
     * Computes a hash code consistent with equals(), derived from the tag
     * and the stored value.
     *
     * @return the hash code for this field
     */
    @Override
    public int hashCode() {
        return Objects.hash(tag, object);
    }
}
