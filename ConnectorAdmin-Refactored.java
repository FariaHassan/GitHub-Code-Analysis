package quickfixj.jmx.mbean.connector;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Acceptor;
import quickfix.Connector;
import quickfix.Initiator;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;

import quickfixj.jmx.mbean.JmxSupport;
import quickfixj.jmx.mbean.session.SessionAdmin;
import quickfixj.jmx.mbean.session.SessionJmxExporter;

/**
 * Abstract base class providing common JMX management functionality shared
 * by all FIX connector MBeans. Concrete subclasses such as
 * {@code SocketAcceptorAdmin} and {@code SocketInitiatorAdmin} extend this
 * class to expose connector-specific behavior while reusing the session
 * registration, lifecycle, and monitoring logic implemented here.
 *
 * <p>Responsibilities are intentionally split into small collaborators so the
 * class respects SOLID design:
 * <ul>
 *   <li>{@link ConnectorAdmin} — orchestrates lifecycle and JMX registration
 *       callbacks only (Single Responsibility).</li>
 *   <li>{@link SessionRegistrar} — an abstraction for registering/unregistering
 *       per-session MBeans (Dependency Inversion: {@code ConnectorAdmin}
 *       depends on this interface, not on a concrete implementation).</li>
 *   <li>{@link DefaultSessionRegistrar} — the concrete JMX-based implementation
 *       of {@link SessionRegistrar} (Single Responsibility, Open/Closed: new
 *       registrars can be substituted without changing {@code ConnectorAdmin}).</li>
 *   <li>{@link ConnectorTypeResolver} — isolates the logic that decides whether
 *       a {@link Connector} is an acceptor or initiator (Single Responsibility,
 *       Open/Closed for future connector kinds).</li>
 *   <li>{@link ConnectorSessionSnapshot} — a plain, read-only data holder used
 *       to build {@link TabularData} rows (Single Responsibility: data only).</li>
 * </ul>
 */
public abstract class ConnectorAdmin implements ConnectorAdminMBean, MBeanRegistration {

    /** Distinguishes whether the wrapped connector is an acceptor or initiator. */
    public enum ConnectorType {
        ACCEPTOR, INITIATOR
    }

    // ------------------------------------------------------------------
    // Connector type resolution (Single Responsibility / Open-Closed)
    // ------------------------------------------------------------------

    /**
     * Resolves the {@link ConnectorType} for a given {@link Connector}.
     * Isolated so the resolution rule can be extended or replaced without
     * touching {@link ConnectorAdmin} itself.
     */
    private static final class ConnectorTypeResolver {

        ConnectorType resolve(Connector connector) {
            if (connector instanceof Acceptor) {
                return ConnectorType.ACCEPTOR;
            } else if (connector instanceof Initiator) {
                return ConnectorType.INITIATOR;
            }
            throw new IllegalArgumentException(
                    "Connector must be an instance of Acceptor or Initiator: " + connector.getClass());
        }
    }

    // ------------------------------------------------------------------
    // Session registration abstraction (Dependency Inversion)
    // ------------------------------------------------------------------

    /**
     * Abstraction over registering and unregistering the JMX MBeans that
     * represent individual FIX sessions belonging to a connector.
     * {@link ConnectorAdmin} depends only on this interface, never on a
     * concrete registration strategy.
     */
    interface SessionRegistrar {

        /** Registers an MBean for the given session if not already registered. */
        void registerSession(SessionID sessionID);

        /** Registers MBeans for every currently known session of the connector. */
        void registerAllSessions();

        /** Unregisters every session MBean that has been registered so far. */
        void unregisterAllSessions();

        /** Returns the JMX object name assigned to a session, if registered. */
        Optional<ObjectName> getObjectName(SessionID sessionID);

        /** Returns an immutable view of all currently registered session names. */
        Map<SessionID, ObjectName> getRegisteredSessions();
    }

    /**
     * Default {@link SessionRegistrar} implementation backed by a
     * {@link MBeanServer} and a {@link SessionJmxExporter}. Owns the
     * bookkeeping map of {@link SessionID} to {@link ObjectName} and all
     * registration/unregistration logic, keeping that concern out of
     * {@link ConnectorAdmin}.
     */
    private static final class DefaultSessionRegistrar implements SessionRegistrar {

        private final Logger log = LoggerFactory.getLogger(DefaultSessionRegistrar.class);

        private final Connector connector;
        private final MBeanServer mbeanServer;
        private final ObjectName connectorObjectName;
        private final SessionJmxExporter sessionExporter;

        private final Map<SessionID, ObjectName> sessionObjectNames = new HashMap<>();

        DefaultSessionRegistrar(Connector connector, MBeanServer mbeanServer,
                ObjectName connectorObjectName, SessionJmxExporter sessionExporter) {
            this.connector = connector;
            this.mbeanServer = mbeanServer;
            this.connectorObjectName = connectorObjectName;
            this.sessionExporter = sessionExporter;
        }

        @Override
        public void registerSession(SessionID sessionID) {
            if (sessionObjectNames.containsKey(sessionID)) {
                return;
            }
            try {
                Session session = Session.lookupSession(sessionID);
                if (session == null) {
                    return;
                }
                SessionAdmin sessionAdmin = new SessionAdmin(session, connectorObjectName);
                ObjectName sessionObjectName = sessionExporter.export(mbeanServer, sessionAdmin);
                sessionObjectNames.put(sessionID, sessionObjectName);
                log.info("Registered session MBean {} for session {}", sessionObjectName, sessionID);
            } catch (InstanceAlreadyExistsException e) {
                log.debug("Session MBean already registered for session {}", sessionID);
            } catch (Exception e) {
                log.error("Failed to register session MBean for session {}", sessionID, e);
            }
        }

        @Override
        public void registerAllSessions() {
            for (SessionID sessionID : connector.getSessions()) {
                registerSession(sessionID);
            }
        }

        @Override
        public void unregisterAllSessions() {
            for (Map.Entry<SessionID, ObjectName> entry : new HashMap<>(sessionObjectNames).entrySet()) {
                unregisterSession(entry.getKey(), entry.getValue());
            }
            sessionObjectNames.clear();
        }

        private void unregisterSession(SessionID sessionID, ObjectName sessionObjectName) {
            try {
                if (mbeanServer.isRegistered(sessionObjectName)) {
                    mbeanServer.unregisterMBean(sessionObjectName);
                    log.info("Unregistered session MBean {} for session {}", sessionObjectName, sessionID);
                }
            } catch (Exception e) {
                log.error("Failed to unregister session MBean for session {}", sessionID, e);
            }
        }

        @Override
        public Optional<ObjectName> getObjectName(SessionID sessionID) {
            return Optional.ofNullable(sessionObjectNames.get(sessionID));
        }

        @Override
        public Map<SessionID, ObjectName> getRegisteredSessions() {
            return Collections.unmodifiableMap(sessionObjectNames);
        }
    }

    // ------------------------------------------------------------------
    // Session data model (Single Responsibility: plain data holder)
    // ------------------------------------------------------------------

    /**
     * Read-only snapshot describing a single FIX session managed by a
     * connector, used purely to build {@link CompositeData} rows for
     * {@link TabularData} exposure. Holds no registration logic.
     */
    public static final class ConnectorSessionSnapshot {

        private final SessionID sessionID;
        private final ObjectName sessionObjectName;
        private final boolean loggedOn;
        private final String remoteAddress;

        ConnectorSessionSnapshot(SessionID sessionID, ObjectName sessionObjectName,
                boolean loggedOn, String remoteAddress) {
            this.sessionID = sessionID;
            this.sessionObjectName = sessionObjectName;
            this.loggedOn = loggedOn;
            this.remoteAddress = remoteAddress;
        }

        static ConnectorSessionSnapshot forSession(SessionID sessionID, ObjectName sessionObjectName) {
            Session session = Session.lookupSession(sessionID);
            boolean loggedOn = session != null && session.isLoggedOn();
            String remoteAddress = (session != null && session.getResponder() != null)
                    ? session.getResponder().getRemoteAddress()
                    : null;
            return new ConnectorSessionSnapshot(sessionID, sessionObjectName, loggedOn, remoteAddress);
        }

        public String getSessionID() {
            return sessionID.toString();
        }

        public ObjectName getSessionObjectName() {
            return sessionObjectName;
        }

        public boolean isLoggedOn() {
            return loggedOn;
        }

        public String getRemoteAddress() {
            return remoteAddress;
        }
    }

    // ------------------------------------------------------------------
    // ConnectorAdmin fields
    // ------------------------------------------------------------------

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Connector connector;
    private final SessionSettings settings;
    private final ObjectName connectorObjectName;
    private final ConnectorType connectorType;
    private final SessionRegistrar sessionRegistrar;

    /** Listens for newly created sessions so they can be registered dynamically. */
    private final PropertyChangeListener sessionSettingsListener = new SessionSettingsListener();

    protected ConnectorAdmin(Connector connector, SessionSettings settings, MBeanServer mbeanServer,
            ObjectName connectorObjectName, SessionJmxExporter sessionExporter) {
        this(connector, settings, connectorObjectName,
                new DefaultSessionRegistrar(connector, mbeanServer, connectorObjectName, sessionExporter));
    }

    /**
     * Constructor allowing a custom {@link SessionRegistrar} to be injected,
     * e.g. for testing or alternative registration strategies (Dependency
     * Inversion / Open-Closed).
     */
    protected ConnectorAdmin(Connector connector, SessionSettings settings,
            ObjectName connectorObjectName, SessionRegistrar sessionRegistrar) {
        this.connector = connector;
        this.settings = settings;
        this.connectorObjectName = connectorObjectName;
        this.sessionRegistrar = sessionRegistrar;
        this.connectorType = new ConnectorTypeResolver().resolve(connector);
    }

    protected Connector getConnector() {
        return connector;
    }

    protected SessionSettings getSettings() {
        return settings;
    }

    public ConnectorType getConnectorType() {
        return connectorType;
    }

    public boolean isAcceptor() {
        return connectorType == ConnectorType.ACCEPTOR;
    }

    public boolean isInitiator() {
        return connectorType == ConnectorType.INITIATOR;
    }

    // ------------------------------------------------------------------
    // Session queries
    // ------------------------------------------------------------------

    /**
     * Returns tabular data describing every session currently associated
     * with the connector, including its identifier, JMX object name,
     * connection status, and remote address.
     */
    public TabularData getSessions() throws IOException {
        try {
            List<ConnectorSessionSnapshot> snapshots = new ArrayList<>();
            for (SessionID sessionID : connector.getSessions()) {
                ObjectName sessionObjectName = sessionRegistrar.getObjectName(sessionID).orElse(null);
                snapshots.add(ConnectorSessionSnapshot.forSession(sessionID, sessionObjectName));
            }
            return JmxSupport.toTabularData(snapshots);
        } catch (RuntimeException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    /**
     * Returns the JMX object names of sessions that are currently logged on,
     * allowing administrators to quickly identify active connections.
     */
    public List<ObjectName> getLoggedOnSessions() throws IOException {
        try {
            List<ObjectName> loggedOn = new ArrayList<>();
            for (SessionID sessionID : connector.getSessions()) {
                Session session = Session.lookupSession(sessionID);
                if (session != null && session.isLoggedOn()) {
                    sessionRegistrar.getObjectName(sessionID).ifPresent(loggedOn::add);
                }
            }
            return loggedOn;
        } catch (RuntimeException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle management
    // ------------------------------------------------------------------

    public void start() throws IOException {
        try {
            log.info("JMX operation: start connector {}", connectorObjectName);
            connector.start();
        } catch (RuntimeException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    public void stop() throws IOException {
        stop(false);
    }

    public void stop(boolean force) throws IOException {
        try {
            log.info("JMX operation: stop connector {} (force={})", connectorObjectName, force);
            connector.stop(force);
        } catch (RuntimeException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // JMX registration lifecycle
    // ------------------------------------------------------------------

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        return name != null ? name : connectorObjectName;
    }

    @Override
    public void postRegister(Boolean registrationDone) {
        if (registrationDone == null || !registrationDone) {
            return;
        }
        try {
            sessionRegistrar.registerAllSessions();
            settings.addPropertyChangeListener(sessionSettingsListener);
        } catch (Exception e) {
            log.error("Failed to register session MBeans for connector {}", connectorObjectName, e);
        }
    }

    @Override
    public void preDeregister() throws Exception {
        settings.removePropertyChangeListener(sessionSettingsListener);
    }

    @Override
    public void postDeregister() {
        sessionRegistrar.unregisterAllSessions();
    }

    // ------------------------------------------------------------------
    // Property change handling
    // ------------------------------------------------------------------

    /**
     * Listens for session-related property changes (e.g. newly configured
     * sessions being added to the settings) so that any new session is
     * automatically registered as a JMX MBean without requiring the
     * connector to be re-registered. Delegates the actual registration work
     * to {@link SessionRegistrar} (Single Responsibility: this class only
     * interprets the event).
     */
    private class SessionSettingsListener implements PropertyChangeListener {
        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            if (evt.getNewValue() instanceof SessionID) {
                sessionRegistrar.registerSession((SessionID) evt.getNewValue());
            } else {
                // A settings-wide change may have introduced new sessions;
                // reconcile the full session list defensively.
                sessionRegistrar.registerAllSessions();
            }
        }
    }
}
