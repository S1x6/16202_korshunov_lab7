package portforwarder;

import org.xbill.DNS.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Map;

public class PortForwarder {
    private Map<SelectionKey, Connection> connectionClientMap = new HashMap<>();
    private Map<SelectionKey, Connection> connectionServerMap = new HashMap<>();
    private Map<String, Connection> connectionDnsMap = new HashMap<>();
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private int l_port;
    private DatagramChannel dnsSocketChannel;
    private SelectionKey dnsSocketKey;

    public PortForwarder(String args[]) throws IOException {

        l_port = Integer.valueOf(args[0]);
        String dnsServers[] = ResolverConfig.getCurrentConfig().servers(); // get addresses of accessible DNS servers
        InetAddress dnsServerAddress = InetAddress.getByName(dnsServers[0]);
        dnsSocketChannel = DatagramChannel.open(); // open UDP socket for DNS lookup
    //    DatagramPacket rPacket = new DatagramPacket(new byte[2048], 2048);
        //dnsSocket.receive(rPacket);
  //      Message rcvMsg = new Message(rPacket.getData());
//        System.out.println(rcvMsg.getSectionArray(1)[0].rdataToString());

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(l_port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        dnsSocketChannel.configureBlocking(false);
        dnsSocketChannel.socket().bind(new InetSocketAddress(l_port + 1));
        dnsSocketKey = dnsSocketChannel.register(selector, SelectionKey.OP_READ);
        dnsSocketChannel.connect(new InetSocketAddress(dnsServerAddress, 53));  // default DNS server port
    }

    public void start() throws IOException {
        long l = 0L;
        while (true) {
            int a = selector.select();
            l++;
            System.out.println(l + ": Selected: " + selector.selectedKeys().size() + " Changed: " + a + " Keys: " + selector.keys().size());
            for (SelectionKey key : selector.selectedKeys()) {
                if (key.isValid()) {
                    if (key == dnsSocketKey) {
                        if (key.isReadable()) {
                            setResolvedDns();
                        }
                    } else if (key.isAcceptable()) {
                        accept();
                    } else if (key.isConnectable()) {
                        connect(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                }
            }
        }
    }

    private void setResolvedDns() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        dnsSocketChannel.read(buffer);
        Message rcvMsg = new Message(buffer.array());
        if (rcvMsg.getSectionArray(1).length == 0) {
            return;
        }
        String domain = rcvMsg.getSectionArray(1)[0].getName().toString();
        connectionDnsMap.get(domain).onDnsResolved(rcvMsg.getSectionArray(1)[0].rdataToString());
    }

    private void accept() throws IOException {
        SocketChannel clientChannel;
        if ((clientChannel = serverChannel.accept()) != null) {
            clientChannel.configureBlocking(false);
            SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            connectionClientMap.put(key,
                    new Connection(
                            selector,
                            clientChannel,
                            connectionServerMap,
                            l_port,
                            dnsSocketChannel,
                            connectionDnsMap));
        }
    }

    private void connect(SelectionKey key) throws IOException {
        try {
            ((SocketChannel) key.channel()).finishConnect();
        } catch (ConnectException ex) {
            connectionServerMap.get(key).sendConnectionStatus((byte)0x05);
            return;
        }
        connectionServerMap.get(key).sendConnectionStatus((byte)0x00);
    }

    private void read(SelectionKey key) throws IOException {
        int ret;
        if (connectionClientMap.containsKey(key)) {
            ret = connectionClientMap.get(key).readFromClient();
        } else if (connectionServerMap.containsKey(key)) {
            ret = connectionServerMap.get(key).readFromServer();
        } else {
            ret = 0;
        }
        closeKey(key, ret);
    }

    private void closeKey(SelectionKey key, int ret) throws IOException {
        if (ret != 0) {
            connectionServerMap.remove(key);
            connectionClientMap.remove(key);
            key.cancel();
            key.channel().close();
        }
    }

    private void write(SelectionKey key) throws IOException {
        int ret;
        if (connectionClientMap.containsKey(key)) {
            ret = connectionClientMap.get(key).writeToClient();
        } else if (connectionServerMap.containsKey(key)) {
            ret = connectionServerMap.get(key).writeToServer();
        } else {
            ret = 0;
        }
        closeKey(key, ret);
    }
}
