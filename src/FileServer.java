/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileServer extends NetObject {

    public static void main(String[] args) {
        FileServer server = new FileServer("File Server");
        server.listen(5000);
        server.receiveFiles();
    }

    private FileServer(String title) {
        super(title);
    }

    private void receiveFiles() {


        Path file = Paths.get(filePath + filename);

        try {
            //receiveUDPData((DatagramSocket) socket, msg);

            Files.write(file, msg.mData);
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
