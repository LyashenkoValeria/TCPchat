import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class Server {
    private static final int PORT = 8888;
    private ServerSocketChannel serverSocket;
    private Selector selector;
    private ByteBuffer buffer;
    private static BufferedReader readerServer;
    private Thread readerThread;
    private List<String> nameList = new ArrayList<>();
    private List<ClientChannel> clientList = new ArrayList<>();
    private final static int MAX_FILE_SIZE = 5242880; // 5Mb



    public static void main(String[] args) {
        new Server().launch();
    }

    private void launch() {
        System.out.println("Сервер начал работу");
        try {
            selector = Selector.open();
            serverSocket = ServerSocketChannel.open();
            serverSocket.socket().bind(new InetSocketAddress(PORT));
            serverSocket.configureBlocking(false);
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            buffer = ByteBuffer.allocate(MAX_FILE_SIZE);

            readerThread = new Thread(()-> {
                try {
                    readerServer = new BufferedReader(new InputStreamReader(System.in));
                    while (true) {
                        String command = readerServer.readLine();
                        if (command.equals("/stop")) {
                            for (ClientChannel client : clientList) {
                                client.send(command, client.clientName);
                                client.socketChannel.close();
                                System.out.println(String.format("Клиент %s отключен", client.clientName));
                            }
                            closeServer();
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Ошибка при чтении с консоли");
                }
            });
            readerThread.start();

            while (true) {
                selector.select();
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                SelectionKey key;
                while (keys.hasNext()) {
                    key = keys.next();
                    keys.remove();
                    if (key.isAcceptable()) {
                        accept(key);
                    }
                    if (key.isReadable()) {
                        read(key);
                    }
                }
            }

        } catch (IOException e) {
            System.out.println("Ошибка подключения сервера");
            System.exit(1);
        } finally {
            try {
                closeServer();
            } catch (IOException e) {
                System.out.println("Ошибка при закрытии сервера");
                System.exit(1);
            }
        }
    }

    private void accept(SelectionKey key) {
        try {
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
            SocketChannel clientChannel = serverSocketChannel.accept();
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            System.out.println("Ошибка подключения клиента к серверу");
            System.exit(1);
        }
    }

    private void read(SelectionKey key) {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        try {
            Arrays.fill(buffer.array(), (byte) 0);
            clientChannel.read(buffer);
            String msg = new String(buffer.array());
            switch (msg.split(" ")[0].trim()) {
                case "/name" -> {
                    String name = msg.split(" ")[1];
                    String connectionStr = "/success";

                    if (nameList.contains(name)) {
                        connectionStr = "/refuse";
                    }

                    buffer = ByteBuffer.allocate(connectionStr.length());
                    buffer.flip();
                    buffer = ByteBuffer.wrap(connectionStr.getBytes());
                    clientChannel.write(buffer);
                    buffer.clear();
                    buffer = ByteBuffer.allocate(100000);

                    if (connectionStr.equals("/success")) {
                        System.out.println("Подключен новый клиент " + name);
                        nameList.add(name);
                        ClientChannel newClient = new ClientChannel(clientChannel, name, msg.split(" ")[2].trim());
                        clientList.add(newClient);
                        for (ClientChannel client : clientList) {
                            client.send("Клиент " + name + " подключился", name);
                        }
                    }

                }
                case "/file" -> {
                    String[] partOfMsg = msg.split(" ");
                    receiveFileOnServer(partOfMsg, clientChannel);
                    buffer = ByteBuffer.allocate(100000);
                }
                case "/quit" -> {
                    for (ClientChannel client : clientList) {
                        if (client.socketChannel.equals(clientChannel)) {
                            client.closeSocket();
                            key.cancel();
                            break;
                        }
                    }
                }
                default -> {
                    String name = "";
                    for (ClientChannel client : clientList) {
                        if (client.socketChannel.equals(clientChannel)) {
                            name = client.clientName;
                            break;
                        }
                    }
                    for (ClientChannel client : clientList) {
                        client.send(msg, name);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Ошибка чтения данных полученных от клиента");
            ClientChannel disconnectedClient = null;
            for (ClientChannel client : clientList) {
                if (client.socketChannel.equals(clientChannel)) {
                    disconnectedClient = client;
                    break;
                }
            }
            if (disconnectedClient != null) {
                disconnectedClient.closeSocket();
            }
        }
    }

    private void receiveFileOnServer(String[] messageStr, SocketChannel channel) throws IOException {
        try {
            int nameSize = Integer.parseInt(messageStr[1]);
            StringBuilder receivedFile = new StringBuilder();
            int i = 2;
            while (receivedFile.length() < nameSize) {
                receivedFile.append(messageStr[i++]).append(" ");
            }

            int fileSize = Integer.parseInt(messageStr[i]);
            byte[] bufferFile = new byte[fileSize];
            FileOutputStream fileOutputStream = new FileOutputStream(receivedFile.toString().trim());
            buffer = ByteBuffer.allocate(bufferFile.length);
            buffer.clear();

            while (buffer.position() < bufferFile.length) {
                channel.read(buffer);
            }

            bufferFile = buffer.array();
            fileOutputStream.write(bufferFile);
            fileOutputStream.close();

            System.out.println("Получен файл " + receivedFile);

            for (ClientChannel client : clientList) {
                if (client.socketChannel != channel) client.sendFileOnServer(receivedFile.toString().trim(), client.clientName);
            }
        } catch (FileNotFoundException e) {
            System.out.println("Файл не найден");
        }
    }

    private void closeServer() throws IOException{
        System.out.println("Сервер закончил работу");
        serverSocket.close();
        readerThread.interrupt();
        System.exit(1);
    }

    private class ClientChannel {
        private SocketChannel socketChannel;
        private String clientName;
        private String timeZone;

        public ClientChannel(SocketChannel socketChannel, String clientName, String timeZone) {
            this.socketChannel = socketChannel;
            this.clientName = clientName;
            this.timeZone = timeZone;
        }

        private void send(String message, String sender) {
            try {
                LocalTime localTime = LocalTime.now(ZoneId.of(timeZone));
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                String formatMsg = localTime.format(formatter) + ";" + sender + ";" + message;
                buffer = ByteBuffer.wrap(formatMsg.getBytes());
                socketChannel.write(buffer);
                buffer.clear();
            } catch (IOException e) {
                System.out.println("Ошибка при отправке сообщения другим клиентам");
                System.exit(1);
            }
        }

        private void sendFileOnServer(String sentFile, String sender) throws IOException {
            try {
                File file = new File(sentFile);
                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] bufferFile = fileInputStream.readAllBytes();

                String fileMsg = "/file " + sentFile.length() + " " + sentFile + " " + bufferFile.length + " ";

                LocalTime localTime = LocalTime.now(ZoneId.of(timeZone));
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                String formatMsg = localTime.format(formatter) + ";" + sender + ";" + fileMsg;

                buffer.clear();
                buffer = ByteBuffer.wrap(formatMsg.getBytes());
                socketChannel.write(buffer);

                buffer = ByteBuffer.allocate(MAX_FILE_SIZE);
                buffer.clear();
                buffer = ByteBuffer.wrap(bufferFile);
                socketChannel.write(buffer);

                fileInputStream.close();
            } catch (FileNotFoundException e) {
                System.out.println("Файл не найден");
                send("Ошибка при отправке файла клиента " + sender, sender);
            }
        }

        private void closeSocket(){
            try {
                System.out.println(String.format("Клиент %s отключен", this.clientName));
                clientList.remove(this);
                for (ClientChannel client : clientList) {
                    client.send("Клиент " + this.clientName + " отключился", this.clientName);
                }
                nameList.remove(clientName);
                socketChannel.close();

            } catch (Exception e) {
                System.out.println("Ошибка при закрытии сокета");
                System.exit(-1);
            }
        }
    }
}