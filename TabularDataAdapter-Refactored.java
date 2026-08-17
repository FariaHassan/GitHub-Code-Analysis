import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

/**
 * TabularDataAdapter.java
 *
 * Utility component that converts ordinary Java data structures (arrays,
 * maps, JavaBeans, and lists of JavaBeans) into JMX {@link TabularData},
 * the standard Open MBean format used to expose structured management
 * information at runtime.
 *
 * SOLID notes:
 *  - Single Responsibility: each public method converts exactly one kind
 *    of input (array, map, single bean, bean list). Shared reflection and
 *    error-wrapping logic lives in small private helpers so no method
 *    carries more than one reason to change.
 *  - Open/Closed: header formatting is delegated to the {@link
 *    HeaderFormatter} strategy. New naming conventions can be supported by
 *    supplying a different formatter to the constructor, without modifying
 *    this class.
 *  - Liskov Substitution: any {@link HeaderFormatter} implementation can
 *    be substituted because the class only relies on the single abstract
 *    method defined by the interface's contract.
 *  - Interface Segregation: {@code HeaderFormatter} exposes a single
 *    method, so implementers are never forced to depend on behavior they
 *    don't use.
 *  - Dependency Inversion: the class depends on the {@code HeaderFormatter}
 *    abstraction rather than a hard-coded formatting algorithm; a default
 *    implementation is provided but is not the only option.
 */
public class TabularDataAdapter {

    private static final String ARRAY_INDEX_COLUMN = "Index";
    private static final String ARRAY_VALUE_COLUMN = "Value";
    private static final String MAP_KEY_COLUMN = "Key";
    private static final String MAP_VALUE_COLUMN = "Value";
    private static final String CLASS_PROPERTY = "class";

    /**
     * Strategy for turning a raw bean property name into a human-readable
     * column header. Kept as a small, single-method abstraction so the
     * formatting rule can be swapped without touching the adapter itself.
     */
    @FunctionalInterface
    public interface HeaderFormatter {
        String format(String propertyName);
    }

    private final HeaderFormatter headerFormatter;

    /**
     * Creates an adapter using the default header formatting strategy,
     * which turns camelCase property names into title-cased, space
     * separated headers (e.g. "firstName" becomes "First Name").
     */
    public TabularDataAdapter() {
        this(TabularDataAdapter::defaultFormatHeader);
    }

    /**
     * Creates an adapter using a custom header formatting strategy.
     *
     * @param headerFormatter the strategy used to format column headers
     */
    public TabularDataAdapter(HeaderFormatter headerFormatter) {
        this.headerFormatter = Objects.requireNonNull(headerFormatter, "headerFormatter must not be null");
    }

    /**
     * Converts an array of objects into a two-column TabularData table,
     * with one row per array element holding its index and string form.
     *
     * @param items     the array to convert
     * @param typeName  the name used for both the row and table types
     * @return a TabularData table with "Index" and "Value" columns
     * @throws OpenDataException if the table cannot be constructed
     */
    public TabularData fromArray(Object[] items, String typeName) throws OpenDataException {
        return fromArray(items, typeName, typeName);
    }

    /**
     * Converts an array of objects into a two-column TabularData table,
     * with one row per array element holding its index and string form.
     *
     * @param items       the array to convert
     * @param typeName    the name used for the row and table types
     * @param description a human-readable description of the table
     * @return a TabularData table with "Index" and "Value" columns
     * @throws OpenDataException if the table cannot be constructed
     */
    public TabularData fromArray(Object[] items, String typeName, String description) throws OpenDataException {
        Objects.requireNonNull(items, "items must not be null");

        CompositeType rowType = new CompositeType(
                typeName,
                description,
                new String[] { ARRAY_INDEX_COLUMN, ARRAY_VALUE_COLUMN },
                new String[] { ARRAY_INDEX_COLUMN, ARRAY_VALUE_COLUMN },
                new OpenType<?>[] { SimpleType.INTEGER, SimpleType.STRING });
        TabularType tabularType = new TabularType(typeName, description, rowType, new String[] { ARRAY_INDEX_COLUMN });
        TabularDataSupport table = new TabularDataSupport(tabularType);

        for (int i = 0; i < items.length; i++) {
            CompositeData row = new CompositeDataSupport(
                    rowType,
                    new String[] { ARRAY_INDEX_COLUMN, ARRAY_VALUE_COLUMN },
                    new Object[] { i, asString(items[i]) });
            table.put(row);
        }
        return table;
    }

    /**
     * Converts a Map into a two-column TabularData table, with one row per
     * entry holding its key and value as strings.
     *
     * @param map      the map to convert
     * @param typeName the name used for the row and table types
     * @return a TabularData table with "Key" and "Value" columns
     * @throws OpenDataException if the table cannot be constructed
     */
    public TabularData fromMap(Map<?, ?> map, String typeName) throws OpenDataException {
        return fromMap(map, typeName, typeName);
    }

    /**
     * Converts a Map into a two-column TabularData table, with one row per
     * entry holding its key and value as strings.
     *
     * @param map         the map to convert
     * @param typeName    the name used for the row and table types
     * @param description a human-readable description of the table
     * @return a TabularData table with "Key" and "Value" columns
     * @throws OpenDataException if the table cannot be constructed
     */
    public TabularData fromMap(Map<?, ?> map, String typeName, String description) throws OpenDataException {
        Objects.requireNonNull(map, "map must not be null");

        CompositeType rowType = new CompositeType(
                typeName,
                description,
                new String[] { MAP_KEY_COLUMN, MAP_VALUE_COLUMN },
                new String[] { MAP_KEY_COLUMN, MAP_VALUE_COLUMN },
                new OpenType<?>[] { SimpleType.STRING, SimpleType.STRING });
        TabularType tabularType = new TabularType(typeName, description, rowType, new String[] { MAP_KEY_COLUMN });
        TabularDataSupport table = new TabularDataSupport(tabularType);

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            CompositeData row = new CompositeDataSupport(
                    rowType,
                    new String[] { MAP_KEY_COLUMN, MAP_VALUE_COLUMN },
                    new Object[] { asString(entry.getKey()), asString(entry.getValue()) });
            table.put(row);
        }
        return table;
    }

    /**
     * Converts a single JavaBean into a CompositeData record by
     * introspecting its readable properties. The default "class" property
     * is always ignored.
     *
     * @param bean     the bean to convert
     * @param typeName the name used for the composite type
     * @return a CompositeData record describing the bean
     * @throws OpenDataException if the bean cannot be introspected or read
     */
    public CompositeData fromBean(Object bean, String typeName) throws OpenDataException {
        return fromBean(bean, typeName, typeName);
    }

    /**
     * Converts a single JavaBean into a CompositeData record by
     * introspecting its readable properties. The default "class" property
     * is always ignored.
     *
     * @param bean        the bean to convert
     * @param typeName    the name used for the composite type
     * @param description a human-readable description of the composite type
     * @return a CompositeData record describing the bean
     * @throws OpenDataException if the bean cannot be introspected or read
     */
    public CompositeData fromBean(Object bean, String typeName, String description) throws OpenDataException {
        Objects.requireNonNull(bean, "bean must not be null");

        PropertyDescriptor[] properties = getReadableProperties(bean.getClass());
        CompositeType compositeType = buildCompositeType(typeName, description, properties);
        return buildCompositeData(bean, properties, compositeType);
    }

    /**
     * Converts a collection of JavaBeans into a TabularData table, inferring
     * the bean type from the first non-null element. Every bean becomes one
     * row, keyed by its first discovered property.
     *
     * @param beans    the beans to convert; must contain at least one
     *                 non-null element
     * @param typeName the name used for the row and table types
     * @return a TabularData table with one row per bean
     * @throws OpenDataException if the bean type cannot be inferred, or the
     *                           beans cannot be introspected or read
     */
    public TabularData fromBeanList(Collection<?> beans, String typeName) throws OpenDataException {
        return fromBeanList(beans, inferBeanClass(beans), typeName, typeName);
    }

    /**
     * Converts a collection of JavaBeans into a TabularData table. Every
     * bean becomes one row, keyed by its first discovered property.
     *
     * @param beans       the beans to convert
     * @param beanClass   the JavaBean type whose properties define the
     *                    table columns
     * @param typeName    the name used for the row and table types
     * @param description a human-readable description of the table
     * @return a TabularData table with one row per bean
     * @throws OpenDataException if the beans cannot be introspected or read
     */
    public TabularData fromBeanList(Collection<?> beans, Class<?> beanClass, String typeName, String description)
            throws OpenDataException {
        Objects.requireNonNull(beans, "beans must not be null");
        Objects.requireNonNull(beanClass, "beanClass must not be null");

        PropertyDescriptor[] properties = getReadableProperties(beanClass);
        CompositeType rowType = buildCompositeType(typeName, description, properties);
        String[] indexColumns = properties.length > 0
                ? new String[] { properties[0].getName() }
                : new String[0];
        TabularType tabularType = new TabularType(typeName, description, rowType, indexColumns);
        TabularDataSupport table = new TabularDataSupport(tabularType);

        for (Object bean : beans) {
            table.put(buildCompositeData(bean, properties, rowType));
        }
        return table;
    }

    /**
     * Builds the ordered, human-readable column headers for a set of bean
     * properties by delegating each name to the configured
     * {@link HeaderFormatter}.
     *
     * @param properties the properties to build headers for
     * @return the formatted headers, in the same order as properties
     */
    private String[] createTableHeaders(PropertyDescriptor[] properties) {
        String[] headers = new String[properties.length];
        for (int i = 0; i < properties.length; i++) {
            headers[i] = headerFormatter.format(properties[i].getName());
        }
        return headers;
    }

    /**
     * Default header formatting rule: capitalizes the first letter and
     * inserts a space before every subsequent uppercase letter, so
     * "firstName" becomes "First Name".
     *
     * @param propertyName the raw JavaBean property name
     * @return the formatted header
     */
    private static String defaultFormatHeader(String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            return propertyName;
        }
        StringBuilder header = new StringBuilder();
        header.append(Character.toUpperCase(propertyName.charAt(0)));
        for (int i = 1; i < propertyName.length(); i++) {
            char c = propertyName.charAt(i);
            if (Character.isUpperCase(c)) {
                header.append(' ');
            }
            header.append(c);
        }
        return header.toString();
    }

    /**
     * Discovers the readable properties of a bean class via {@link
     * Introspector}, excluding the default "class" property.
     *
     * @param beanClass the class to introspect
     * @return the readable property descriptors
     * @throws OpenDataException if introspection fails
     */
    private PropertyDescriptor[] getReadableProperties(Class<?> beanClass) throws OpenDataException {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(beanClass);
            List<PropertyDescriptor> readable = new ArrayList<>();
            for (PropertyDescriptor descriptor : beanInfo.getPropertyDescriptors()) {
                if (descriptor.getReadMethod() != null && !CLASS_PROPERTY.equals(descriptor.getName())) {
                    readable.add(descriptor);
                }
            }
            return readable.toArray(new PropertyDescriptor[0]);
        } catch (IntrospectionException e) {
            throw wrapAsOpenDataException("Unable to introspect bean class " + beanClass.getName(), e);
        }
    }

    /**
     * Builds the CompositeType shared by every row produced for a given set
     * of bean properties, using formatted headers as item descriptions.
     *
     * @param typeName    the name of the composite type
     * @param description a human-readable description of the composite type
     * @param properties  the bean properties that become composite items
     * @return the constructed CompositeType
     * @throws OpenDataException if the composite type cannot be built
     */
    private CompositeType buildCompositeType(String typeName, String description, PropertyDescriptor[] properties)
            throws OpenDataException {
        String[] itemNames = new String[properties.length];
        for (int i = 0; i < properties.length; i++) {
            itemNames[i] = properties[i].getName();
        }
        String[] itemDescriptions = createTableHeaders(properties);
        OpenType<?>[] itemTypes = new OpenType<?>[properties.length];
        java.util.Arrays.fill(itemTypes, SimpleType.STRING);

        return new CompositeType(typeName, description, itemNames, itemDescriptions, itemTypes);
    }

    /**
     * Reads every given property off a bean and assembles a CompositeData
     * record matching the supplied CompositeType.
     *
     * @param bean          the bean instance to read
     * @param properties    the properties to read, in composite type order
     * @param compositeType the composite type the record must conform to
     * @return the constructed CompositeData record
     * @throws OpenDataException if a property cannot be read
     */
    private CompositeData buildCompositeData(Object bean, PropertyDescriptor[] properties, CompositeType compositeType)
            throws OpenDataException {
        String[] itemNames = new String[properties.length];
        Object[] itemValues = new Object[properties.length];
        for (int i = 0; i < properties.length; i++) {
            itemNames[i] = properties[i].getName();
            itemValues[i] = readProperty(bean, properties[i]);
        }
        return new CompositeDataSupport(compositeType, itemNames, itemValues);
    }

    /**
     * Invokes a property's read method and returns its string form,
     * wrapping any reflection failure as an OpenDataException.
     *
     * @param bean     the bean instance to read from
     * @param property the property to read
     * @return the string form of the property's value, or null
     * @throws OpenDataException if the property cannot be read
     */
    private String readProperty(Object bean, PropertyDescriptor property) throws OpenDataException {
        try {
            Method readMethod = property.getReadMethod();
            Object value = readMethod.invoke(bean);
            return asString(value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw wrapAsOpenDataException(
                    "Unable to read property '" + property.getName() + "' from bean " + bean.getClass().getName(), e);
        }
    }

    /**
     * Infers the common bean class of a collection by returning the class
     * of its first non-null element.
     *
     * @param beans the collection to inspect
     * @return the inferred bean class
     * @throws OpenDataException if the collection is null, empty, or
     *                           contains only null elements
     */
    private Class<?> inferBeanClass(Collection<?> beans) throws OpenDataException {
        if (beans == null || beans.isEmpty()) {
            throw new OpenDataException(
                    "Cannot infer bean type from an empty or null collection; use the overload that accepts an explicit bean class");
        }
        for (Object bean : beans) {
            if (bean != null) {
                return bean.getClass();
            }
        }
        throw new OpenDataException("Cannot infer bean type because every element in the collection is null");
    }

    /**
     * Null-safe conversion of an arbitrary value to its string form.
     *
     * @param value the value to convert
     * @return the string form of the value, or null if the value is null
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Wraps a low-level reflection failure inside the standard Open MBean
     * exception type, preserving the original cause while hiding
     * implementation details from callers.
     *
     * @param message a description of what operation failed
     * @param cause   the underlying reflection exception
     * @return an OpenDataException carrying the given message and cause
     */
    private static OpenDataException wrapAsOpenDataException(String message, Exception cause) {
        OpenDataException exception = new OpenDataException(message + ": " + cause.getMessage());
        exception.initCause(cause);
        return exception;
    }
}
