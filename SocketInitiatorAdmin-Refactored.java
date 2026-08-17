package quickfix.mina.initiator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import javax.management.JMException;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;

import quickfix.SessionID;
import quickfix.mina.ConnectorAdmin;
import quickfix.mina.SessionJmxExporter;
import quickfix.mina.acceptor.JmxExporter;
import quickfix.mina.acceptor.JmxSupport;
import quickfix.mina.acceptor.SocketAcceptorAdmin.TabularDataAdapterConverter;
import quickfix.mina.acceptor.SocketAcceptorAdmin.TabularDataConverter;

/**
 * SOLID অনুসরণ করে রিফ্যাক্টর করা ভার্সন:
 * - SRP: address ফরম্যাট করা, row তৈরি করা, এবং MBean লজিক আলাদা ক্লাসে ভাগ করা হয়েছে।
 * - OCP: নতুন address formatting strategy যোগ করতে চাইলে বিদ্যমান কোড পরিবর্তন না করেই
 *   SocketAddressFormatter implement করা যায়।
 * - LSP: SocketAddressFormatter-এর যেকোনো implementation একে অপরের বদলে ব্যবহারযোগ্য।
 * - ISP: SocketAddressFormatter ইন্টারফেসে মাত্র একটি মেথড, তাই কোনো ক্লাসকে অপ্রয়োজনীয়
 *   মেথড implement করতে হয় না।
 * - DIP: SessionInitiatorAddressRow এবং InitiatorAddressRowFactory কংক্রিট ফরম্যাটিং লজিকের
 *   উপর নির্ভর না করে SocketAddressFormatter অ্যাবস্ট্রাকশনের উপর নির্ভর করে (constructor injection)।
 */
public class SocketInitiatorAdmin extends ConnectorAdmin implements SocketInitiatorAdminMBean {

    private final AbstractSocketInitiator initiator;
    private final TabularDataConverter<SessionInitiatorAddressRow> addressTableConverter;
    private final InitiatorAddressRowFactory rowFactory;

    public SocketInitiatorAdmin(JmxExporter jmxExporter, AbstractSocketInitiator initiator, ObjectName connectorName,
            SessionJmxExporter sessionExporter) {
        this(jmxExporter, initiator, connectorName, sessionExporter,
                new TabularDataAdapterConverter<>(SessionInitiatorAddressRow.class, "sessionId",
                        "Initiator Session Addresses"));
    }

    public SocketInitiatorAdmin(JmxExporter jmxExporter, AbstractSocketInitiator initiator, ObjectName connectorName,
            SessionJmxExporter sessionExporter, TabularDataConverter<SessionInitiatorAddressRow> addressTableConverter) {
        this(jmxExporter, initiator, connectorName, sessionExporter, addressTableConverter,
                new DefaultSocketAddressFormatter());
    }

    public SocketInitiatorAdmin(JmxExporter jmxExporter, AbstractSocketInitiator initiator, ObjectName connectorName,
            SessionJmxExporter sessionExporter, TabularDataConverter<SessionInitiatorAddressRow> addressTableConverter,
            SocketAddressFormatter addressFormatter) {
        super(jmxExporter, initiator, connectorName, sessionExporter);
        this.initiator = initiator;
        this.addressTableConverter = addressTableConverter;
        this.rowFactory = new InitiatorAddressRowFactory(getSessionJmxExporter(), addressFormatter);
    }

    @Override
    public TabularData getInitiatorAddresses() throws IOException {
        try {
            return addressTableConverter.convert(buildAddressRows());
        } catch (JMException e) {
            throw JmxSupport.toIOException(e);
        }
    }

    private List<SessionInitiatorAddressRow> buildAddressRows() {
        List<SessionInitiatorAddressRow> rows = new ArrayList<>();
        for (IoSessionInitiator ioSessionInitiator : initiator.getInitiators()) {
            rows.add(rowFactory.createRow(ioSessionInitiator));
        }
        return rows;
    }

    @Override
    public int getQueueSize() {
        return initiator.getQueueSize();
    }

    /**
     * ISP + DIP: address ফরম্যাট করার জন্য ছোট, নির্দিষ্ট কাজের ইন্টারফেস।
     */
    public interface SocketAddressFormatter {
        String format(SocketAddress address);
    }

    /**
     * SRP: শুধু SocketAddress কে display string-এ রূপান্তর করাই এই ক্লাসের একমাত্র কাজ।
     * OCP: চাইলে অন্য কোনো ফরম্যাটিং লজিক (যেমন hostname দেখানো) নতুন implementation
     * হিসেবে যোগ করা যাবে, এই ক্লাস পরিবর্তন না করেই।
     */
    public static class DefaultSocketAddressFormatter implements SocketAddressFormatter {
        @Override
        public String format(SocketAddress address) {
            if (address instanceof InetSocketAddress) {
                InetSocketAddress inetAddress = (InetSocketAddress) address;
                return inetAddress.getAddress().getHostAddress() + ":" + inetAddress.getPort();
            }
            return address != null ? address.toString() : null;
        }
    }

    /**
     * SRP: IoSessionInitiator থেকে SessionInitiatorAddressRow তৈরি করাই এই ক্লাসের একমাত্র দায়িত্ব।
     * আগে এই লজিক SocketInitiatorAdmin এর ভেতরেই মিশে ছিল।
     */
    private static class InitiatorAddressRowFactory {
        private final SessionJmxExporter sessionJmxExporter;
        private final SocketAddressFormatter addressFormatter;

        InitiatorAddressRowFactory(SessionJmxExporter sessionJmxExporter, SocketAddressFormatter addressFormatter) {
            this.sessionJmxExporter = sessionJmxExporter;
            this.addressFormatter = addressFormatter;
        }

        SessionInitiatorAddressRow createRow(IoSessionInitiator ioSessionInitiator) {
            SessionID sessionID = ioSessionInitiator.getSessionID();
            SocketAddress localAddress = ioSessionInitiator.getLocalAddress();
            SocketAddress[] remoteAddresses = ioSessionInitiator.getSocketAddresses();
            ObjectName sessionName = sessionJmxExporter.lookupSessionName(sessionID);
            return new SessionInitiatorAddressRow(sessionID, localAddress, remoteAddresses, sessionName,
                    addressFormatter);
        }
    }

    /**
     * SRP: এখন এটি মূলত একটি ডেটা হোল্ডার + presentation accessor; নিজে আর address
     * ফরম্যাট করে না, বরং injected SocketAddressFormatter-কে সেই দায়িত্ব দেয় (DIP)।
     */
    public static class SessionInitiatorAddressRow {

        private final SessionID sessionID;
        private final SocketAddress localAddress;
        private final SocketAddress[] remoteAddresses;
        private final ObjectName sessionName;
        private final SocketAddressFormatter addressFormatter;

        public SessionInitiatorAddressRow(SessionID sessionID, SocketAddress localAddress,
                SocketAddress[] remoteAddresses, ObjectName sessionName) {
            this(sessionID, localAddress, remoteAddresses, sessionName, new DefaultSocketAddressFormatter());
        }

        public SessionInitiatorAddressRow(SessionID sessionID, SocketAddress localAddress,
                SocketAddress[] remoteAddresses, ObjectName sessionName, SocketAddressFormatter addressFormatter) {
            this.sessionID = sessionID;
            this.localAddress = localAddress;
            this.remoteAddresses = remoteAddresses;
            this.sessionName = sessionName;
            this.addressFormatter = addressFormatter;
        }

        public String getSessionId() {
            return sessionID.toString();
        }

        public String getSessionName() {
            return sessionName != null ? sessionName.toString() : null;
        }

        public String getLocalInitiatorAddress() {
            return addressFormatter.format(localAddress);
        }

        public String getInitiatorAddresses() {
            if (remoteAddresses == null || remoteAddresses.length == 0) {
                return "";
            }
            StringBuilder addresses = new StringBuilder();
            for (int i = 0; i < remoteAddresses.length; i++) {
                if (i > 0) {
                    addresses.append(",");
                }
                addresses.append(addressFormatter.format(remoteAddresses[i]));
            }
            return addresses.toString();
        }
    }
}
