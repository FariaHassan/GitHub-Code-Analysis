package org.quickfixj.jmx.openmbean;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * CompositeDataFactory.java
 *
 * Reusable builder for JMX {@link CompositeData} objects. Callers
 * accumulate item name/value pairs against a predefined {@link
 * CompositeType} via setValue(), then materialize the result with
 * createCompositeData(). clear() resets the builder for reuse without
 * reallocating internal storage.
 *
 * SOLID notes:
 *  - Single Responsibility: this class does exactly one thing — collect
 *    named values and assemble them into a CompositeData record matching
 *    a known CompositeType. It does not decide what the type looks like
 *    (that's CompositeType's job) or how the result gets used.
 *  - Open/Closed: new value kinds are supported by adding a new
 *    setValue() overload; existing behavior (validation, storage,
 *    creation) never needs to change to support them.
 *  - Liskov Substitution: not applicable — the class has no subtypes to
 *    honor a substitutability contract for.
 *  - Interface Segregation: not applicable — a small concrete builder
 *    with no client-specific interface to split; introducing one here
 *    would add indirection without a second implementation to justify it.
 *  - Dependency Inversion: the class depends only on the CompositeType
 *    abstraction supplied by the caller, not on how that type was built
 *    or where its items come from.
 */
public class CompositeDataFactory {

    private final CompositeType compositeType;
    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Creates a factory bound to a fixed composite type. Every value later
     * set through this instance must correspond to an item defined on this
     * type.
     *
     * @param compositeType the structure new CompositeData records must match
     */
    public CompositeDataFactory(CompositeType compositeType) {
        this.compositeType = Objects.requireNonNull(compositeType, "compositeType must not be null");
    }

    /**
     * Records an arbitrary value under the given item name.
     *
     * @param itemName the composite item this value belongs to
     * @param value    the value to store; may be null
     * @return this factory, for chained calls
     * @throws IllegalArgumentException if itemName is not part of this
     *                                  factory's composite type
     */
    public CompositeDataFactory setValue(String itemName, Object value) {
        validateItemName(itemName);
        values.put(itemName, value);
        return this;
    }

    /**
     * Records a numeric value under the given item name.
     *
     * @param itemName the composite item this value belongs to
     * @param value    the numeric value to store
     * @return this factory, for chained calls
     * @throws IllegalArgumentException if itemName is not part of this
     *                                  factory's composite type
     */
    public CompositeDataFactory setValue(String itemName, Number value) {
        return setValue(itemName, (Object) value);
    }

    /**
     * Records a boolean value under the given item name.
     *
     * @param itemName the composite item this value belongs to
     * @param value    the boolean value to store
     * @return this factory, for chained calls
     * @throws IllegalArgumentException if itemName is not part of this
     *                                  factory's composite type
     */
    public CompositeDataFactory setValue(String itemName, boolean value) {
        return setValue(itemName, (Object) Boolean.valueOf(value));
    }

    /**
     * Builds a CompositeData record from every value collected so far.
     * Item names are read from the composite type itself, so ordering is
     * always correct regardless of the order setValue() was called in.
     * Items never set are passed through as null.
     *
     * @return the constructed CompositeData record
     * @throws OpenDataException if the collected values don't satisfy the
     *                           composite type's item constraints
     */
    public CompositeData createCompositeData() throws OpenDataException {
        String[] itemNames = compositeType.keySet().toArray(new String[0]);
        Object[] itemValues = new Object[itemNames.length];
        for (int i = 0; i < itemNames.length; i++) {
            itemValues[i] = values.get(itemNames[i]);
        }
        return new CompositeDataSupport(compositeType, itemNames, itemValues);
    }

    /**
     * Clears all collected values so this factory can be reused to build
     * another CompositeData record for the same composite type, without
     * allocating a new instance.
     */
    public void clear() {
        values.clear();
    }

    /**
     * Ensures a caller only sets values for items that actually exist on
     * this factory's composite type, failing fast rather than silently
     * accepting data that createCompositeData() would later reject or
     * misplace.
     *
     * @param itemName the item name to validate
     * @throws IllegalArgumentException if the name isn't a defined item
     */
    private void validateItemName(String itemName) {
        Objects.requireNonNull(itemName, "itemName must not be null");
        if (!compositeType.containsKey(itemName)) {
            throw new IllegalArgumentException(
                    "Unknown item name '" + itemName + "' for composite type " + compositeType.getTypeName());
        }
    }
}