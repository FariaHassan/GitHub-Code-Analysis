package quickfix.mina.acceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;

import quickfix.SessionID;
import quickfix.mina.ConnectorAdmin;
import quickfix.mina.SessionJmxExporter;

public class SocketAcceptorAdmin extends ConnectorAdmin implements SocketAcceptorAdminMBean {

    private final AbstractSocketAcceptor acceptor;
    private final TabularDataConverter<SessionAcceptorAddressRow> addressTableConverter;

    public SocketAcceptorAdmin(JmxExporter jmxExporter, AbstractSocketAcceptor acceptor, ObjectName connectorName,
            SessionJmxExporter sessionExporter) {
        this(jmxExporter, acceptor, connectorName, sessionExporter,
                new TabularDataAdapterConverter<>(SessionAcceptorAddressRow.class, "sessionId",
                        "Acceptor Session Addresses"));
    }

    public SocketAcceptorAdmin(JmxExporter jmxExporter, AbstractSocketAcceptor acceptor, ObjectName connectorName,
            SessionJmxExporter sessionExporter, TabularDataConverter<SessionAcceptorAddressRow> addressTableConverter) {
        super(jmxExporter, acceptor, connectorName, sessionExporter);
        this.acceptor = acceptor;
        this.addressTableConverter = addressTableConverter;
    }

    public TabularData getAcceptorAddresses() throws IOException {
        try {
            return addressTableConverter.convert(buildAddressRows());
        } catch (JMException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    private List<SessionAcceptorAddressRow> buildAddressRows() {
        Map<SessionID, SocketAddress> sessionAddresses = acceptor.getAcceptorAddresses();

        List<SessionAcceptorAddressRow> rows = new ArrayList<>();
        for (Map.Entry<SessionID, SocketAddress> entry : sessionAddresses.entrySet()) {
            SessionID sessionID = entry.getKey();
            SocketAddress address = entry.getValue();
            ObjectName sessionName = getSessionJmxExporter().lookupSessionName(sessionID);
            rows.add(new SessionAcceptorAddressRow(sessionID, address, sessionName));
        }
        return rows;
    }

    public int getQueueSize() {
        return acceptor.getQueueSize();
    }

    public interface TabularDataConverter<T> {
        TabularData convert(List<T> rows) throws JMException;
    }

    public static class TabularDataAdapterConverter<T> implements TabularDataConverter<T> {

        private final TabularDataAdapter<T> adapter;

        public TabularDataAdapterConverter(Class<T> rowType, String indexProperty, String tabularTypeName) {
            this.adapter = new TabularDataAdapter<>(rowType, indexProperty, tabularTypeName);
        }

        @Override
        public TabularData convert(List<T> rows) throws JMException {
            return adapter.fromList(rows);
        }
    }

    public static class SessionAcceptorAddressRow {

        private final SessionID sessionID;
        private final SocketAddress address;
        private final ObjectName sessionName;

        public SessionAcceptorAddressRow(SessionID sessionID, SocketAddress address, ObjectName sessionName) {
            this.sessionID = sessionID;
            this.address = address;
            this.sessionName = sessionName;
        }

        public String getSessionId() {
            return sessionID.toString();
        }

        public String getSessionName() {
            return sessionName != null ? sessionName.toString() : null;
        }

        public String getAddress() {
            if (address instanceof InetSocketAddress) {
                InetSocketAddress inetAddress = (InetSocketAddress) address;
                return inetAddress.getAddress().getHostAddress() + ":" + inetAddress.getPort();
            }
            return address != null ? address.toString() : null;
        }
    }
}