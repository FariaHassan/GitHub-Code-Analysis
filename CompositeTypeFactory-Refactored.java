package org.quickfixj.jmx.openmbean;

import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CompositeTypeFactory.java
 *
 * Reusable builder for JMX {@link CompositeType} definitions. Callers
 * describe each attribute of the composite via defineItem(), then
 * materialize the finished type with createCompositeType(). The resulting
 * CompositeType is later used by classes such as CompositeDataFactory and
 * TabularDataAdapter to validate and organize structured management data.
 *
 * SOLID notes:
 *  - Single Responsibility: this class only describes the *shape* of
 *    composite data — names, descriptions, and OpenTypes of its
 *    attributes. It has no knowledge of actual values (CompositeDataFactory's
 *    job) or how the type gets used afterward.
 *  - Open/Closed: new ways of specifying an item (e.g. a future overload
 *    accepting a default value or validation rule) can be added as new
 *    defineItem() overloads without changing existing storage or
 *    creation logic.
 *  - Liskov Substitution: not applicable — no subtypes to honor a
 *    substitutability contract for.
 *  - Interface Segregation: not applicable — a small concrete builder
 *    with a single client-facing role; no interface split would add
 *    value without a second implementation to justify it.
 *  - Dependency Inversion: the class depends only on the OpenType
 *    abstraction supplied by the caller for each item, not on any
 *    concrete OpenType subclass or how it was constructed.
 */
public class CompositeTypeFactory {

    private final String typeName;
    private final String typeDescription;
    private final Map<String, Item> items = new LinkedHashMap<>();

    /**
     * Item is a private, immutable grouping of one attribute's
     * description and OpenType, keyed externally by its name. Bundling
     * these two fields together (rather than keeping parallel
     * description/type lists) makes it structurally impossible for an
     * attribute's description and type to drift out of sync with each
     * other or with its name.
     */
    private static final class Item {
        final String description;
        final OpenType<?> type;

        Item(String description, OpenType<?> type) {
            this.description = description;
            this.type = type;
        }
    }

    /**
     * Creates a factory for a composite type with the given name and
     * description. Attributes are added afterward via defineItem().
     *
     * @param typeName        the name of the composite type to build
     * @param typeDescription a human-readable description of the type
     */
    public CompositeTypeFactory(String typeName, String typeDescription) {
        this.typeName = Objects.requireNonNull(typeName, "typeName must not be null");
        this.typeDescription = Objects.requireNonNull(typeDescription, "typeDescription must not be null");
    }

    /**
     * Defines an attribute whose description is identical to its name.
     *
     * @param itemName the attribute name
     * @param openType the OpenType describing the attribute's data type
     * @return this factory, for chained calls
     * @throws IllegalArgumentException if itemName was already defined
     */
    public CompositeTypeFactory defineItem(String itemName, OpenType<?> openType) {
        return defineItem(itemName, itemName, openType);
    }

    /**
     * Defines an attribute with a separate name and description.
     *
     * @param itemName        the attribute name
     * @param itemDescription a human-readable description of the attribute
     * @param openType        the OpenType describing the attribute's data type
     * @return this factory, for chained calls
     * @throws IllegalArgumentException if itemName was already defined
     */
    public CompositeTypeFactory defineItem(String itemName, String itemDescription, OpenType<?> openType) {
        Objects.requireNonNull(itemName, "itemName must not be null");
        Objects.requireNonNull(itemDescription, "itemDescription must not be null");
        Objects.requireNonNull(openType, "openType must not be null");
        if (items.containsKey(itemName)) {
            throw new IllegalArgumentException("Item '" + itemName + "' is already defined for type " + typeName);
        }
        items.put(itemName, new Item(itemDescription, openType));
        return this;
    }

    /**
     * Builds a CompositeType from every attribute defined so far.
     *
     * @return the constructed CompositeType
     * @throws OpenDataException if no items were defined, or the
     *                           collected attributes don't satisfy
     *                           CompositeType's validation rules
     */
    public CompositeType createCompositeType() throws OpenDataException {
        if (items.isEmpty()) {
            throw new OpenDataException("Cannot create CompositeType '" + typeName + "' with no items defined");
        }

        int size = items.size();
        String[] itemNames = new String[size];
        String[] itemDescriptions = new String[size];
        OpenType<?>[] itemTypes = new OpenType<?>[size];

        List<String> names = new ArrayList<>(items.keySet());
        for (int i = 0; i < size; i++) {
            String name = names.get(i);
            Item item = items.get(name);
            itemNames[i] = name;
            itemDescriptions[i] = item.description;
            itemTypes[i] = item.type;
        }

        return new CompositeType(typeName, typeDescription, itemNames, itemDescriptions, itemTypes);
    }

    /**
     * Clears all previously defined items so this factory can be reused
     * to build a different CompositeType with the same name and
     * description, without allocating a new instance.
     */
    public void clear() {
        items.clear();
    }
}