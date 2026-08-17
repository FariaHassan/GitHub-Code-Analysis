package quickfix;

/**
 * BytesField.java
 *
 * A specialized {@link Field} implementation for binary FIX message
 * values (byte[]). Adds no new state beyond what Field already provides —
 * it only teaches the framework how to convert a byte array to and from
 * its FIX wire representation.
 *
 * SOLID notes:
 *  - Single Responsibility: this class's only job is binary-specific
 *    conversion (objectAsString). Storage, tag handling, and message
 *    assembly remain the responsibility of the parent Field class.
 *  - Open/Closed: it extends Field's behavior for a new data type without
 *    modifying Field itself — Field stays closed for modification, open
 *    for extension via subclassing.
 *  - Liskov Substitution: a BytesField can be used anywhere a
 *    Field&lt;byte[]&gt; is expected; it honors the parent's contract and
 *    only overrides objectAsString to supply type-appropriate encoding,
 *    without changing the meaning of any inherited method.
 *  - Interface Segregation: it exposes only the two accessors relevant to
 *    binary data (getValue/setValue) rather than a broad, unrelated API.
 *  - Dependency Inversion: encoding is delegated to the CharsetSupport
 *    abstraction rather than a hardcoded charset, so the class depends on
 *    a configurable policy, not a fixed implementation detail.
 */
public class BytesField extends Field<byte[]> {

    /**
     * Creates an empty binary field for the given tag.
     *
     * @param field the FIX tag number
     */
    public BytesField(int field) {
        super(field, new byte[0]);
    }

    /**
     * Creates a binary field for the given tag, initialized with data.
     *
     * @param field the FIX tag number
     * @param data  the initial byte array value
     */
    public BytesField(int field, byte[] data) {
        super(field, data);
    }

    /**
     * Replaces the field's binary content.
     *
     * @param data the new byte array value
     */
    public void setValue(byte[] data) {
        setObject(data);
    }

    /**
     * Returns the field's stored binary content.
     *
     * @return the byte array value
     */
    public byte[] getValue() {
        return getObject();
    }

    /**
     * Converts the stored byte array into its FIX wire-format string
     * using the framework's configured character encoding, instead of
     * relying on Field's default object-to-string conversion.
     *
     * @return the encoded string representation of the binary value
     */
    @Override
    protected String objectAsString() {
        return new String(getValue(), CharsetSupport.getCharsetInstance());
    }
}