package main;

import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;

class Connection {

    private static final int BUFFER_SIZE = 8192;

    private enum Status {
        NO_CONNECTION,
        AUTHENTICATED,
        CONNECTED
    }

    private DatagramChannel dnsChannel;
    private Map<Integer, Connection> dnsMap;

    private Selector selector;
    private SocketChannel clientChannel;
    private SocketChannel serverChannel;
    private Status status = Status.NO_CONNECTION;
    private ByteBuffer fcBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private ByteBuffer cfBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private int fcReadBytes = 0;
    private int cfReadBytes = 0;
    private Map<SelectionKey, Connection> connectionServerMap;
    private int listenPort;
    private int serverPort;

    Connection(Selector selector,
               SocketChannel clientChannel,
               Map<SelectionKey, Connection> connectionServerMap,
               int listenPort,
               DatagramChannel dnsSocket,
               Map<Integer, Connection> dnsMap
    ) {
        this.clientChannel = clientChannel;
        this.selector = selector;
        this.connectionServerMap = connectionServerMap;
        this.listenPort = listenPort;
        this.dnsChannel = dnsSocket;
        this.dnsMap = dnsMap;
    }

    private void connectToServer(InetSocketAddress serverSocketAddress) throws IOException {
        SocketChannel serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.connect(serverSocketAddress);
        SelectionKey key = serverChannel.register(selector,
                SelectionKey.OP_READ | SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE);
        connectionServerMap.put(key, this);
        this.serverChannel = serverChannel;
    }

    int readFromServer() {
        if (clientChannel.isConnected() && status == Status.CONNECTED) {
            if (fcReadBytes != 0) {
                return 0;
            }
            try {
                fcReadBytes = serverChannel.read(fcBuffer);
            } catch (IOException ex) {
                return -1;
            }
            if (fcReadBytes == -1) {
                return -1;
            }
        }
        return 0;
    }

    int writeToClient() {
        if (clientChannel.isConnected()) {
            if (fcReadBytes == 0) {
                return 0;
            }
            if (fcReadBytes < 0) {
                return -1;
            }
            try {
                int written = clientChannel.write(ByteBuffer.wrap(fcBuffer.array(), 0, fcReadBytes));
            } catch (IOException ex) {
                return -1;
            }
            if (status == Status.NO_CONNECTION) {
                status = Status.AUTHENTICATED;
                cfReadBytes = 0;
                cfBuffer.clear();
            } else if (status == Status.AUTHENTICATED) {
                status = Status.CONNECTED;
                cfBuffer.clear();
                cfReadBytes = 0;
            }
            fcReadBytes = 0;
            fcBuffer.clear();
            return 0;
        }
        return -1;
    }

    int writeToServer() {
        if (serverChannel.isConnected() && status == Status.CONNECTED) {
            if (cfReadBytes == 0) {
                return 0;
            }
            if (cfReadBytes < 0) {
                return -1;
            }
            int written;
            try {
                written = serverChannel.write(ByteBuffer.wrap(cfBuffer.array(), 0, cfReadBytes));
            } catch (IOException ex) {
                return -1;
            }
            if (written == -1) {
                return -1;
            }

            cfReadBytes = 0;
            cfBuffer.clear();
        }
        return 0;
    }

    int readFromClient() throws IOException {
        if (clientChannel.isConnected()) {
            if (cfReadBytes != 0) {
                return 0;
            }
            if (status == Status.NO_CONNECTION) {
                return readAndAuth();
            } else if (status == Status.AUTHENTICATED) {
                return readAndConnect();
            } else if (status == Status.CONNECTED) {
                try {
                    cfReadBytes = clientChannel.read(cfBuffer);
                } catch (IOException ex) {
                    return -1;
                }
                if (cfReadBytes == -1) {
                    return -1;
                }
            }
            return 0;
        }
        return -1;
    }


    void sendConnectionConfirmation() {
        byte[] arr = {
                0x05, // version
                0x00, // status "connection established"
                0x00, // reserved byte
                0x01, // type of address
                0x7F, 0x00, 0x00, 0x01, // 127.0.0.1 (not sure what does it do)
                (byte) ((listenPort >> 8) & 0xFF), (byte) (listenPort & 0xFF)  // port (not sure about this one as well)
        };
        fcBuffer.clear();
        fcBuffer.put(arr);
        fcReadBytes = 10;
    }

    void sendConnectionRefused() {
        byte[] arr = {
                0x05, // version
                0x05, // status "connection established"
                0x00, // reserved byte
                0x01, // type of address
                0x7F, 0x00, 0x00, 0x01, // 127.0.0.1 (not sure what does it do)
                (byte) ((listenPort >> 8) & 0xFF), (byte) (listenPort & 0xFF)  // port (not sure about this one as well)
        };
        fcBuffer.clear();
        fcBuffer.put(arr);
        fcReadBytes = 10;
    }

    private int readAndConnect() throws IOException {
        cfReadBytes = clientChannel.read(cfBuffer);
        if (cfReadBytes > 0) {
            if (!validateSecondRequest(cfBuffer)) {
                return -1;
            }
            byte[] array = cfBuffer.array();
            if (array[3] == 0x01) {
                StringBuilder sb = new StringBuilder();
                sb.append(unsignedByteToSignedInt(array[4]));
                sb.append(".");
                sb.append(unsignedByteToSignedInt(array[5]));
                sb.append(".");
                sb.append(unsignedByteToSignedInt(array[6]));
                sb.append(".");
                sb.append(unsignedByteToSignedInt(array[7]));
                int port = ((array[8] & 0xff) << 8) | (array[9] & 0xff); // make one int of two bytes
                InetSocketAddress address = new InetSocketAddress(sb.toString(), port);
                connectToServer(address);
            } else if (array[3] == 0x03) {
                System.out.println("DNS FOUND!!!");
                int length = array[4];
                StringBuilder sb = new StringBuilder();
                int i;
                for (i = 5; i < 5 + length; ++i) {
                    sb.append((char) array[i]);
                }
                sb.append('.'); // DNS message needs a trailing dot
                serverPort = ((array[i] & 0xff) << 8) | (array[i + 1] & 0xff); // make one int of two bytes

                Name name = org.xbill.DNS.Name.fromString(sb.toString(), Name.root);
                Record rec = Record.newRecord(name, Type.A, DClass.IN);
                Message msg = Message.newQuery(rec);
                dnsChannel.write(ByteBuffer.wrap(msg.toWire()));
                dnsMap.put(msg.getHeader().getID(), this);

            }


        }
        return 0;
    }

    void onDnsResolved(InetAddress adr) throws IOException {
        InetSocketAddress a = new InetSocketAddress(adr, serverPort);
        connectToServer(a);
    }

    private int readAndAuth() throws IOException {
        cfReadBytes = clientChannel.read(cfBuffer);
        if (cfReadBytes > 0) {
            boolean canAuth = false;
            byte num;
            if (cfBuffer.array()[0] == (byte) 0x05 && (num = cfBuffer.array()[1]) > (byte) 0x00) {
                for (byte i = 2; i < num + 2; ++i) {
                    if (cfBuffer.array()[i] == 0x00) {
                        canAuth = true;
                        break;
                    }
                }
            }
            byte[] responseArray = new byte[2];
            if (!canAuth) {
                responseArray[0] = 0x05;
                responseArray[1] = (byte) 0xFF;
            } else {
                responseArray[0] = 0x05;
            }
            fcBuffer.clear();
            fcBuffer.put(responseArray);
            fcReadBytes = 2;
        }
        if (cfReadBytes == -1) {
            serverChannel.close();
            cfBuffer.clear();
            return -1;
        } else {
            cfBuffer.clear();
            return 0;
        }
    }

    private boolean validateSecondRequest(ByteBuffer buffer) {
        if (buffer.array()[0] != 0x05) {
            System.out.println("This version of SOCKS is not supported: " + cfBuffer.array()[0]);
            return false;
        }
        if (buffer.array()[1] != 0x01) {
            System.out.println("This operation is not supported: " + cfBuffer.array()[1]);
            return false;
        }
        if (buffer.array()[2] != 0x00) {
            System.out.println("Wrong format request, third byte expected to be 0x00");
            return false;
        }
        if (buffer.array()[3] != 0x01 && buffer.array()[3] != 0x03) {
            System.out.println("Such type of host address is not supported");
            return false;
        }
        return true;
    }

    // need this because byte is always signed in Java, but we need to convert in to unsigned int (e.g. -128 -> 255)
    private static int unsignedByteToSignedInt(byte b) {
        return (int) b & 0xFF;
    }
}