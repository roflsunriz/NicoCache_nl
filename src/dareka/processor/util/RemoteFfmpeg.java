package dareka.processor.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import dareka.common.Logger;

public class RemoteFfmpeg {

    private static final int PROTOCOL_VERSION = 1;

    private static final int MSG_PING = 0;
    private static final int MSG_ERROR = 1;
    private static final int MSG_DONE = 2;
    private static final int MSG_OUTPUT = 3;

    public static final String INPUT_FILENAME_PLACEHOLDER = "{input:0}";
    public static final String OUTPUT_FILENAME_PLACEHOLDER = "{output:0}";

    public static boolean convert(File inputFile, File outputFile, String[] args_template,
            StringBuilder output) {
        Socket socket;
        try {
            SocketChannel channel = SocketChannel.open();
            socket = channel.socket();
            socket.connect(new InetSocketAddress(
                    System.getProperty("ffmpegServerHost"),
                    Integer.getInteger("ffmpegServerPort")));
        } catch (IOException ex) {
            Logger.warning("Failed to connect ffmpeg server.");
            return false;
        }

        try {
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

            dos.writeInt(PROTOCOL_VERSION);
            if (!dis.readBoolean()) {
                Logger.warning("ffmpeg server: Protocol version mismatch.");
            }

            auth(dis, dos);
            if (!dis.readBoolean()) {
                Logger.warning("ffmpeg server: Password mismatch.");
                return false;
            }

            sendArgsTemplate(dos, args_template);

            dos.writeInt(1); // nInputFiles
            dos.writeInt(1); // nOutputFiles

            if (!sendFile(socket, dos, inputFile)) return false;

            boolean error = false;
            PING_LOOP: while (true) {
                byte message = dis.readByte();
                switch (message) {
                    case MSG_PING: break;
                    case MSG_DONE: break PING_LOOP;
                    case MSG_ERROR: {
                        error = true;
                        break PING_LOOP;
                    }
                    case MSG_OUTPUT: {
                        String chunk = dis.readUTF();
                        if (output != null) {
                            output.append(chunk);
                        }
                        break;
                    }
                    default: {
                        Logger.warning("ffmpeg server: unknown response");
                        return false;
                    }
                }
            }

            if (error) return false;
            if (!recvFile(socket, dis, outputFile)) return false;
            return true;
        } catch (IOException ex) {
            Logger.debugWithThread(ex);
        } finally {
            try {
                socket.close();
            } catch (IOException ex) {
            }
        }
        return false;
    }

    private static void auth(DataInputStream dis, DataOutputStream dos) throws IOException {
        byte[] challenge = new byte[16];
        dis.read(challenge, 0, challenge.length);

        // 本来はHMACのほうが良いけどそこまでちゃんとやる必要性も……
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
        }
        md.update(challenge);
        md.update(System.getProperty("ffmpegServerPassword").getBytes("UTF-8"));
        byte[] response = md.digest();
        dos.write(response, 0, 32);
    }

    private static void sendArgsTemplate(DataOutputStream dos, String[] args_template) throws IOException {
        int argc = args_template.length;
        dos.writeInt(argc);
        for (int i = 0; i < argc; i++) {
            dos.writeUTF(args_template[i]);
        }
    }


    private static boolean recvFile(Socket socket, DataInputStream dis, File file) throws IOException {
        long inputFileSize = dis.readLong();

        try (FileChannel ch = new RandomAccessFile(file, "rw").getChannel()) {
            for (long pos = 0; pos < inputFileSize; ) {
                long l = ch.transferFrom(socket.getChannel(), pos, inputFileSize - pos);
                if (l == 0) return false;
                pos += l;
            }
        } catch (FileNotFoundException ex) {
            Logger.error(ex);
            return false;
        }
        return true;
    }
    private static boolean sendFile(Socket socket, DataOutputStream dos, File file) throws IOException {
        long outputFileSize = file.length();
        dos.writeLong(outputFileSize);

        try (FileChannel ch = new RandomAccessFile(file, "r").getChannel()) {
            for (long pos = 0; pos < outputFileSize; ) {
                long l = ch.transferTo(pos, outputFileSize - pos, socket.getChannel());
                if (l == 0) return false;
                pos += l;
            }
        } catch (FileNotFoundException ex) {
            Logger.error(ex);
            return false;
        }
        return true;
    }
}
