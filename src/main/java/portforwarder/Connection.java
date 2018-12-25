package portforwarder;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

class Connection {

    private static final int BUFFER_SIZE = 8192;

    private enum Status {
        NO_CONNECTION,
        AUTHENTICATED,
        CONNECTED
    }

    private Selector selector;
    private SocketChannel clientChannel;
    private SocketChannel serverChannel;
    private Status status = Status.NO_CONNECTION;
    private ByteBuffer fcBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private ByteBuffer cfBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    private int fcReadBytes = 0;
    private int cfReadBytes = 0;

    Connection(Selector selector, SocketChannel clientChannel) {
        this.clientChannel = clientChannel;
        this.selector = selector;
    }

    void connectToServer(SocketAddress serverSocketAddress) throws IOException {
        SocketChannel serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.connect(serverSocketAddress);
        serverChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
    }

    int readFromServer() {
        return 0;
    }

    int writeToClient() throws IOException {
        if (clientChannel.isConnected()) {
            if (fcReadBytes == 0) {
                return 0;
            }
            int written = clientChannel.write(ByteBuffer.wrap(fcBuffer.array(), 0, fcReadBytes));
            System.out.println("Written: " + String.valueOf(written));
            System.out.println(new String(fcBuffer.array()));
            if (status == Status.NO_CONNECTION) {
                status = Status.AUTHENTICATED;
                cfReadBytes = 0;
                cfBuffer.clear();
            }
            fcReadBytes = 0;
            fcBuffer.clear();
            return 0;
        }
        return -1;
    }

    int writeToServer() {
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
                //return readAllFromClient();
            }
            return 0;
        }
        return -1;
    }

//    private int readAllFromClient() throws IOException {
//            cfReadBytes = clientChannel.read(cfBuffer);
//            if (cfReadBytes > 0) {
//                int written = clientChannel.write(ByteBuffer.wrap(buffer.array(), 0, bytesRead));
//                System.out.println("Written: " + String.valueOf(written));
//                for (int i = 0; i < bytesRead; ++i) {
//                    System.out.println((int) buffer.array()[i]);
//                }
//                System.out.println();
//            }
//            if (bytesRead == -1) {
//                serverChannel.close();
//                fcBuffer.clear();
//                return -1;
//            } else {
//                return 0;
//            }
//    }


    private int readAndConnect() throws IOException {
        cfReadBytes = clientChannel.read(cfBuffer);
        if (cfReadBytes > 0) {
            if (!validateSecondRequest(cfBuffer)) {
                return -1;
            }
        }
        return 0;
    }

    private int readAndAuth() throws IOException {
        cfReadBytes = clientChannel.read(cfBuffer);
        if (cfReadBytes > 0) {
            boolean canAuth = false;
            byte num;
            if (cfBuffer.array()[0] == (byte) 0x05 &&
                    (num = cfBuffer.array()[1]) > (byte) 0x00) {
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
//                responseArray[1] = 0x00; // already assigned
            }
            fcBuffer.clear();
            fcBuffer.put(responseArray);
            fcReadBytes = 2;
            //int written = clientChannel.write(ByteBuffer.wrap(responseArray, 0, 2));
            //System.out.println("Sent readAndAuth data: " + (canAuth ? "can readAndAuth (" : "cannot readAndAuth (") + written + "/3)");
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
        return true;
    }
}
