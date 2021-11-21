import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TimeZone;


public class Client {
    private static final int SERVER_PORT = 8888; //порт сервера
    private static final String HOST = "127.0.0.1";
    private final static int MIN_NAME_SIZE = 2;
    private final static int MAX_NAME_SIZE = 30;
    private final static int MAX_FILE_SIZE = 5242880; // 5Mb

    private static Socket socket; //сокет для общения с сервером
    private static BufferedReader reader; //ридер для чтение с консоли
    private static DataInputStream input; //чтение из сокета
    private static DataOutputStream output; //запись в сокет

    private String clientName;

    public static void main(String[] args) {
        new Client().launch();
    }

    private void launch() {
        try {
            reader = new BufferedReader(new InputStreamReader(System.in));
            TimeZone tz = TimeZone.getDefault();

            while (true) {
                socket = new Socket(HOST, SERVER_PORT); //попытка подключения к серверу
                input = new DataInputStream(socket.getInputStream()); //получение входного потока с сервера
                output = new DataOutputStream(socket.getOutputStream()); //получение выходного потока с сервера

                enterUserName(); //ввод имени
                output.writeUTF(clientName.trim() + " " + tz.getID() + "\n"); //отправка имени и временной зоны на сервер
                output.flush();

                if (input.readUTF().equals("/refuse")){
                    System.out.println("Это имя уже занято");
                } else {
                    System.out.println("Вы подключены");
                    break;
                }
            }

            new ReceiveMessage().start(); //запуск потока чтения сообщений с сервера
            new SendMessage().start(); //запуск потока чтения сообщений клиента
        } catch (IOException e) {
            System.out.println("Ошибка подключения");
            System.exit(-1);
        }
    }

    public void enterUserName() {
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

    private void closeSocket() {
        try {
            if (!socket.isClosed()) {
                input.close();
                output.close();
                socket.close();
            }
        } catch (IOException e) {
            System.out.println("Ошибка при закрытии сокета");
            System.exit(-1);
        }
    }


    private class ReceiveMessage extends Thread {
        @Override
        public void run() {
            try {
                while (true) {
                    String msg = input.readUTF();
                    if (msg.split(" ", 2)[0].equals("/file")){
                        receiveFileOnClient(msg.split(" ", 2)[1]);
                    } else {
                        if (msg.equals("/stop")) {
                            System.out.println("Работа сервера остановлена");
                            closeSocket();
                            System.exit(-1);
                            break;
                        } else {
                            String[] partsOfMsg = msg.split(";", 3);
                            String text = String.format("<%s> [%s]: %s", partsOfMsg[0], partsOfMsg[1], partsOfMsg[2]);
                            System.out.println(text);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Проблемы при работе с сервером");
                closeSocket();
                System.exit(-1);
            }
        }

        private void receiveFileOnClient(String receivedFile) throws IOException{
            try {
                int bytes;
                new File("C:\\chat\\" + clientName).mkdirs();
                FileOutputStream fileOutputStream = new FileOutputStream("C:\\chat\\" + clientName + "\\" + receivedFile);
                long fileSize = input.readLong();
                byte[] buffer = new byte[4 * 1024];

                while (fileSize > 0 && (bytes = input.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                    fileOutputStream.write(buffer, 0, bytes);
                    fileSize -= bytes;
                }
                fileOutputStream.close();
                System.out.println("Получен файл " + receivedFile);
            } catch (FileNotFoundException e) {
                System.out.println("Ошибка при получении файла");
            }
        }
    }

    private class SendMessage extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    String message = reader.readLine().trim(); //чтение сообщения клиента
                    if (message.split(" ",2)[0].equals("/file")){
                        sendFileOnClient(message.split(" ",2)[1]);
                    } else {
                        if(!message.equals("")) {
                            output.writeUTF(message);
                            if (message.equals("/quit")) {
                                closeSocket();
                                System.exit(-1);
                                break;
                            }
                            output.flush();
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Ошибка отправки сообщения");
                    closeSocket();
                    System.exit(-1);
                }
            }
        }

        public void sendFileOnClient(String fileName) throws IOException{
            try {
                File file = new File(fileName);
                long length = file.length();
                if (length > MAX_FILE_SIZE){
                    System.out.println("Привышен размер файла (максимум 5Мб)");
                } else {
                    int bytes;
                    byte[] buffer = new byte[4*1024];
                    FileInputStream fileInputStream = new FileInputStream(file);

                    Path name = Paths.get(fileName).getFileName();
                    output.writeUTF("/file " + name);
                    output.flush();

                    output.writeLong(length);
                    output.flush();

                    while ((bytes=fileInputStream.read(buffer))>0) {
                        output.write(buffer, 0, bytes);
                    }
                    fileInputStream.close();
                }
            } catch (FileNotFoundException e){
                System.out.println("Файл не найден. Укажите файл как: Путь к файлу\\имя файла");
            }
        }
    }
}
