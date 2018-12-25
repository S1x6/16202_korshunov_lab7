package portforwarder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class PortForwarder {
    private Map<SelectionKey, Connection> connectionClientMap = new HashMap<>();
    private Map<SelectionKey, Connection> connectionServerMap = new HashMap<>();
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private int l_port;

    public PortForwarder(String args[]) throws IOException {

        l_port = Integer.valueOf(args[0]);

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(l_port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void start() throws IOException {

        while (true) {
            selector.select();
            for (SelectionKey key : selector.selectedKeys()) {
                if (key.isValid()) {
                    if (key.isAcceptable()) {
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

    private void accept() throws IOException {
        SocketChannel clientChannel;
        if((clientChannel = serverChannel.accept()) != null) {
            clientChannel.configureBlocking(false);
            SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            connectionClientMap.put(key, new Connection(selector, clientChannel, connectionServerMap, l_port));
            //connections.add(new Connection(selector, clientChannel));
            //sessionStorages.add(new SessionStorage(serverChannel, clientChannel, bufferSize));
            //sessionStorages.add(new SessionStorage(clientChannel, serverChannel, bufferSize));
        }
    }

    private void connect(SelectionKey key) throws IOException {
        ((SocketChannel) key.channel()).finishConnect();
        connectionServerMap.get(key).sendConnectionConfirmation();
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
        if (ret != 0) {
            connectionServerMap.remove(key);
            connectionClientMap.remove(key);
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
        if (ret != 0) {
            connectionServerMap.remove(key);
            connectionClientMap.remove(key);
            key.cancel();
        }
    }
}
