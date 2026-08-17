package quickfix.mina.acceptor;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import quickfix.Acceptor;
import quickfix.Connector;
import quickfix.QFJException;
import quickfix.SessionID;
import quickfix.mina.acceptor.AbstractSocketAcceptor;
import quickfix.mina.initiator.AbstractSocketInitiator;

/**
 * Exposes FIX session connectors to the JMX management framework.
 * <p>
 * Responsible for registering connector-related MBeans and ensuring
 * that every connector receives a unique JMX {@link ObjectName}.
 */
public class ConnectorJmxExporter {

    private static final AtomicInteger connectorIndex = new AtomicInteger(0);

    private final JmxExporter jmxExporter;
    private final SessionJmxExporter sessionExporter;
    private final ConnectorNameStrategy connectorNameStrategy;
    private final Map<Class<? extends Connector>, ConnectorAdminFactory> adminFactories = new LinkedHashMap<>();

    public ConnectorJmxExporter(JmxExporter jmxExporter) {
        this(jmxExporter, new DefaultConnectorNameStrategy());
    }

    public ConnectorJmxExporter(JmxExporter jmxExporter, ConnectorNameStrategy connectorNameStrategy) {
        this.jmxExporter = jmxExporter;
        this.sessionExporter = new SessionJmxExporter(jmxExporter);
        this.connectorNameStrategy = connectorNameStrategy;

        registerAdminFactory(AbstractSocketAcceptor.class,
                (connector, sessions) -> new SocketAcceptorAdmin((AbstractSocketAcceptor) connector, sessions));
        registerAdminFactory(AbstractSocketInitiator.class,
                (connector, sessions) -> new SocketInitiatorAdmin((AbstractSocketInitiator) connector, sessions));
    }

    public final void registerAdminFactory(Class<? extends Connector> connectorType, ConnectorAdminFactory factory) {
        adminFactories.put(connectorType, factory);
    }

    public ObjectName register(Connector connector) {
        return register(connector, connectorIndex.incrementAndGet());
    }

    public ObjectName register(Connector connector, int connectorId) {
        try {
            ObjectName connectorName = connectorNameStrategy.createName(connector, connectorId);
            ConnectorAdmin connectorAdmin = createAdmin(connector);

            jmxExporter.register(connectorAdmin, connectorName);

            return connectorName;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new QFJException("Failed to register connector with JMX: " + e.getMessage(), e);
        }
    }

    private ConnectorAdmin createAdmin(Connector connector) {
        for (Map.Entry<Class<? extends Connector>, ConnectorAdminFactory> entry : adminFactories.entrySet()) {
            if (entry.getKey().isInstance(connector)) {
                return entry.getValue().createAdmin(connector, sessionExporter);
            }
        }
        throw new QFJException("Can't manage connector: " + connector);
    }

    public ObjectName lookupSessionName(SessionID sessionID) {
        return sessionExporter.lookupSessionName(sessionID);
    }

    public interface ConnectorNameStrategy {
        ObjectName createName(Connector connector, int connectorId) throws MalformedObjectNameException;
    }

    /**
     * Determines role via the {@link Acceptor} role interface rather than the
     * concrete {@code AbstractSocketAcceptor} class, so any acceptor
     * implementation is classified correctly without depending on one
     * specific implementation (DIP).
     */
    public static class DefaultConnectorNameStrategy implements ConnectorNameStrategy {
        @Override
        public ObjectName createName(Connector connector, int connectorId) throws MalformedObjectNameException {
            Hashtable<String, String> properties = new Hashtable<>();
            properties.put("type", "Connector");
            properties.put("role", connector instanceof Acceptor ? "Acceptor" : "Initiator");
            properties.put("id", Integer.toString(connectorId));
            return new ObjectName("quickfixj", properties);
        }
    }

    /**
     * Marker interface implemented by all connector management (MBean) objects.
     * Registration is typed against this instead of raw {@code Object}, so a
     * real, checkable contract is preserved (ISP).
     */
    public interface ConnectorAdmin {
    }

    @FunctionalInterface
    public interface ConnectorAdminFactory {
        ConnectorAdmin createAdmin(Connector connector, SessionJmxExporter sessionExporter);
    }
}