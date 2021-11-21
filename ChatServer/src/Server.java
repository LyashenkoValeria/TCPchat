import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class Server {
    private static final int PORT = 8888;
    private ServerSocket serverSocket;
    private DataInputStream input;
    private DataOutputStream output;
    private static BufferedReader readerServer;
    private List<ClientConnection> clientList = new ArrayList<>();
    private List<String> nameList = new ArrayList<>();

    //Поток для чтения команд с консоли и остановки сервера
    Runnable readServerCommand = () -> {
        try {
            readerServer = new BufferedReader(new InputStreamReader(System.in));
            while (true) {
                String command = readerServer.readLine();
                if (command.equals("/stop")) {
                    for (ClientConnection client : clientList) {
                        output = new DataOutputStream(client.socket.getOutputStream());
                        output.writeUTF("/stop");
                        output.flush();
                        System.out.println(String.format("Клиент %s отключен", client.clientName));
                        client.socket.close();
                    }
                    System.out.println("Сервер закончил работу");
                    serverSocket.close();
                    System.exit(1);
                }
            }
        } catch (IOException e) {
            System.out.println("Ошибка чтения команды серверу");
        }
    };

    public static void main(String[] args) {
        new Server().launch();
    }

    private void launch() {
        System.out.println("Сервер начал работу");
        try {
            serverSocket = new ServerSocket(PORT, 5);
            Thread commandThread = new Thread(readServerCommand);
            commandThread.start();
            while (true) {
                Socket clientSocket = serverSocket.accept(); //ожидание подключения

                //добавление нового соедиение
                input = new DataInputStream(clientSocket.getInputStream());
                String nameLine = input.readUTF();
                String name = nameLine.split(" ")[0];

                if (nameList.contains(name)) {
                    output = new DataOutputStream(clientSocket.getOutputStream());
                    output.writeUTF("/refuse");
                    output.flush();
                } else {
                    ClientConnection newClient = new ClientConnection(clientSocket, name, nameLine.split(" ")[1].trim());
                    System.out.println("Подключен новый клиент " + name);
                    newClient.start();
                    nameList.add(name);
                    clientList.add(newClient);
                    for (ClientConnection client : clientList) {
                        client.send("Клиент " + name + " подключился", name);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Ошибка подключения сервера");
            System.exit(1);
        } finally {
            try {
                System.out.println("Отключение сервера");
                serverSocket.close();
            } catch (IOException e) {
            }
        }
    }


    private class ClientConnection extends Thread {
        private Socket socket;
        private String clientName;
        private String timeZone;
        private DataInputStream inputClient;
        private DataOutputStream outputClient;


        public ClientConnection(Socket socket, String clientName, String timeZone) {
            this.socket = socket;
            this.clientName = clientName;
            this.timeZone = timeZone;

            try {
                inputClient = new DataInputStream(socket.getInputStream());
                outputClient = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    String msg = inputClient.readUTF();
                    if (msg.split(" ", 2)[0].equals("/file")) {
                        String name = msg.split(" ", 2)[1];
                        receiveFileOnServer(name);
                    } else {
                        if (msg.equals("/quit")) {
                            closeSocket();
                            break;
                        }
                        for (ClientConnection client : clientList) {
                            client.send(msg, this.clientName);
                        }
                    }
                }

            } catch (IOException e) {
            }
        }

        private void send(String message, String sender) {
            try {
                LocalTime localTime = LocalTime.now(ZoneId.of(timeZone));
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
                outputClient.writeUTF(localTime.format(formatter) + ";" + sender + ";" + message);
                outputClient.flush();
            } catch (IOException e) {
                System.out.println("Ошибка при отправке сообщения другим клиентам");
                System.exit(1);
            }
        }

        private void receiveFileOnServer(String receivedFile) throws IOException {
            try {
                int bytes;
                FileOutputStream fileOutputStream = new FileOutputStream(receivedFile);
                long fileSize = inputClient.readLong();
                byte[] buffer = new byte[4 * 1024];

                while (fileSize > 0 && (bytes = inputClient.read(buffer, 0, (int) Math.min(buffer.length, fileSize))) != -1) {
                    fileOutputStream.write(buffer, 0, bytes);
                    fileSize -= bytes;
                }
                fileOutputStream.close();
                System.out.println("Получен файл " + receivedFile);
                for (ClientConnection client : clientList) {
                    if (client != this) client.sendFileOnServer(receivedFile, this.clientName);
                }
            } catch (FileNotFoundException e) {
                System.out.println("Файл не найден");
            }
        }

        private void sendFileOnServer(String sentFile, String sender) throws IOException {
            try {
                File file = new File(sentFile);
                long length = file.length();
                int bytes;
                byte[] buffer = new byte[4 * 1024];
                FileInputStream fileInputStream = new FileInputStream(file);

                outputClient.writeUTF("/file " + sentFile);
                outputClient.flush();

                outputClient.writeLong(length);
                outputClient.flush();

                while ((bytes = fileInputStream.read(buffer)) > 0) {
                    outputClient.write(buffer, 0, bytes);
                }
                fileInputStream.close();
            } catch (FileNotFoundException e) {
                System.out.println("Файл не найден");
                send("Ошибка при отправке файла клиента " + sender, sender);
            }
        }

        private void closeSocket() {
            try {
                System.out.println(String.format("Клиент %s отключен", this.clientName));
                clientList.remove(this);
                for (ClientConnection client : clientList) {
                    client.send("Клиент " + this.clientName + " отключился", this.clientName);
                }
                nameList.remove(clientName);
                inputClient.close();
                outputClient.close();
                socket.close();
            } catch (IOException e) {
                System.out.println("Ошибка при закрытии сокета");
                System.exit(1);
            }
        }
    }
}
