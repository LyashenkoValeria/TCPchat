import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TimeZone;

public class Client {
    private static final int SERVER_PORT = 8888;
    private static final String HOST = "127.0.0.1";
    private final static int MIN_NAME_SIZE = 2;
    private final static int MAX_NAME_SIZE = 30;
    private final static int MAX_FILE_SIZE = 5242880; // 5Mb

    private Selector selector;
    private SocketChannel clientChannel;
    private boolean isConnected = false;
    private boolean readerThread = false;
    private ByteBuffer buffer;
    private String message = "";

    private Thread thread;
    private static BufferedReader reader; //ридер для чтение с консоли
    private TimeZone tz;
    private String clientName;


    public static void main(String[] args) {
        new Client().launch();
    }

    private void launch() {
        try {
            reader = new BufferedReader(new InputStreamReader(System.in));
            tz = TimeZone.getDefault();
            buffer = ByteBuffer.allocate(100000);

            selector = Selector.open();
            clientChannel = SocketChannel.open();
            clientChannel.configureBlocking(false);

            clientChannel.register(selector, SelectionKey.OP_CONNECT);
            clientChannel.connect(new InetSocketAddress(HOST, SERVER_PORT));

            while (true) {
                selector.select(100);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                SelectionKey key;

                while (keys.hasNext()) {
                    key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isConnectable()) {
                        connect(key);
                    }
                    if (key.isReadable()) {
                        read(key);
                    }
                    if (key.isWritable()) {
                        write(key);
                    }
                }
            }

        } catch (IOException e) {
            System.out.println("Ошибка подключения");
            System.exit(-1);
        }
    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        if (channel.isConnectionPending()) {
            channel.finishConnect();
        }
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_WRITE);
    }

    private void write(SelectionKey key) {
        try {
            SocketChannel clientSocket = (SocketChannel) key.channel();

            if (!isConnected) {
                enterUserName();
                String nameLine = "/name " + clientName.trim() + " " + tz.getID() + "\n";
                buffer.clear();
                buffer.limit(nameLine.length());
                buffer = ByteBuffer.wrap(nameLine.getBytes());
                clientSocket.write(buffer);
            } else {
                if (!readerThread){
                    thread = new Thread(()-> {
                        try {
                            while (true) {
                                message = reader.readLine().trim(); //чтение сообщения клиента
                                key.interestOps(SelectionKey.OP_WRITE);
                            }
                        } catch (IOException e) {
                            System.out.println("Ошибка при чтении с консоли");
                        }
                    });
                    thread.start();
                    readerThread = true;
                }
                sendMessage(clientSocket, key);
                message = "";
            }
            key.interestOps(SelectionKey.OP_READ);
        } catch (IOException e) {
            System.out.println("Ошибка отправки сообщения");
            closeSocket(key);
            System.exit(-1);
        }
    }

    private void enterUserName() {
        try {
            while (true) {
                System.out.print("Введите ваше имя: ");
                clientName = reader.readLine();
                if (clientName.length() < MIN_NAME_SIZE || clientName.length() > MAX_NAME_SIZE || clientName.contains(" ")) {
                    System.out.println("Неверно задано имя. Длина 2-30 символов, без пробелов");
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Ошибка при введении имени");
            System.exit(-1);
        }
    }

    private void sendMessage(SocketChannel channel,SelectionKey key) throws IOException{

        if (message.split(" ",2)[0].equals("/file")){
            sendFileOnClient(message.split(" ",2)[1], channel);
        } else {
            if(!message.equals("")) {
                buffer.limit(message.length());
                buffer.clear();
                buffer = ByteBuffer.wrap(message.getBytes());
                channel.write(buffer);
                if (message.equals("/quit")) {
                    closeSocket(key);
                    System.exit(-1);
                }
            }
        }
    }

    public void sendFileOnClient(String fileName,SocketChannel channel) throws IOException{
        try {
            File file = new File(fileName);
            long length = file.length();
            if (length > MAX_FILE_SIZE){
                System.out.println("Превышен размер файла (максимум 5Мб)");
            } else {
                FileInputStream fileInputStream = new FileInputStream(file);
                byte[] bufferFile = fileInputStream.readAllBytes();
                Path name = Paths.get(fileName).getFileName();

                String fileMsg = "/file " + name.toString().length() + " " + name + " " + bufferFile.length + " ";
                buffer.clear();
                buffer = ByteBuffer.wrap(fileMsg.getBytes());
                channel.write(buffer);

                buffer = ByteBuffer.allocate(MAX_FILE_SIZE);
                buffer.clear();
                buffer = ByteBuffer.wrap(bufferFile);
                channel.write(buffer);

                fileInputStream.close();
            }
        } catch (FileNotFoundException e){
            System.out.println("Файл не найден. Укажите файл как: Путь к файлу\\имя файла");
        }
    }

    private void read(SelectionKey key) {
        try {
            SocketChannel clientSocket = (SocketChannel) key.channel();
            if (!isConnected) {
                buffer = ByteBuffer.allocate(1000);
                buffer.clear();
                clientSocket.read(buffer);
                if (new String(buffer.array()).trim().equals("/refuse")) {
                    System.out.println("Это имя уже занято");
                } else {
                    isConnected = true;
                    System.out.println("Вы подключены");
                }
            } else {
                receiveMessage(clientSocket,key);
            }
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            System.out.println("Ошибка получения сообщения");
            closeSocket(key);
            System.exit(-1);
        }
    }

    private void receiveMessage(SocketChannel channel,SelectionKey key) throws IOException{
        Arrays.fill(buffer.array(), (byte) 0);
        buffer = ByteBuffer.allocate(100000);
        buffer.clear();
        channel.read(buffer);
        String msg = new String(buffer.array()).trim();
        if(!msg.equals("")) {
            String[] partsOfMsg = msg.split(";", 3);
            String[] fileFromServer = partsOfMsg[2].split(" ");
            if (fileFromServer[0].equals("/file")) {
                receiveFileOnClient(fileFromServer, channel);
            } else {
                if (partsOfMsg[2].equals("/stop")) {
                    System.out.println("Работа сервера остановлена");
                    closeSocket(key);
                    System.exit(-1);
                } else {
                    String text = String.format("<%s> [%s]: %s", partsOfMsg[0], partsOfMsg[1], partsOfMsg[2]);
                    System.out.println(text);
                }
            }
        }
    }

    private void receiveFileOnClient(String[] messageStr, SocketChannel channel) throws IOException{
        try {
            new File("C:\\chat\\" + clientName).mkdirs();

            int nameSize = Integer.parseInt(messageStr[1]);
            StringBuilder receivedFile = new StringBuilder();
            int i = 2;
            while (receivedFile.length() < nameSize) {
                receivedFile.append(messageStr[i++]).append(" ");
            }

            int fileSize = Integer.parseInt(messageStr[i]);
            byte[] bufferFile = new byte[fileSize];
            FileOutputStream fileOutputStream = new FileOutputStream("C:\\chat\\" + clientName + "\\" + receivedFile.toString().trim());
            buffer = ByteBuffer.allocate(bufferFile.length);
            buffer.clear();

            while (buffer.position() < bufferFile.length) {
                channel.read(buffer);
            }

            bufferFile = buffer.array();
            fileOutputStream.write(bufferFile);
            fileOutputStream.close();

            System.out.println("Получен файл " + receivedFile);
        } catch (FileNotFoundException e) {
            System.out.println("Ошибка при получении файла");
        }
    }


    private void closeSocket(SelectionKey key){
        try {
            clientChannel.close();
            key.cancel();
            thread.interrupt();
        } catch (IOException e) {
            System.out.println("Ошибка при закрытии сокета");
            System.exit(-1);
        }
    }
}
