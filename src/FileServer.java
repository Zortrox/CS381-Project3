/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class FileServer extends NetObject {

    private Semaphore mtxHash = new Semaphore(1);
    private Map<String, Integer> hashPacket = new HashMap<>();

    public static void main(String[] args) {
        FileServer server = new FileServer("File Server");
        server.listen(5000);
    }

    private FileServer(String title) {
        super(title);
    }

    private Thread receiveFile(int idxFile) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Message initMsg = arrReceived.get(idxFile).take();
                    //create new file
                    Path file = createFile(initMsg.mData);
                    if (file != null) {
                        int fileSize = getFilesize(initMsg.mData);
                        writeMessage("File receiving: " + file.getFileName() + " - " + fileSize + " bytes");

                        //ack the file
                        //qMessages.put();

                        //get sizes
                        int numChunks = (int) Math.ceil(((double) fileSize) / PKT_FILEDAT_SIZE);
                        int numWindows = (int) Math.ceil(((double) numChunks) / WINDOW_SIZE);

                        int windowPos = 0;
                        int indexPos = 0;
                        for (int i = 0; i < numWindows; i = windowPos) {
                            for (int j = indexPos; j < WINDOW_SIZE; j++) {
                                int loc = i * WINDOW_SIZE + j;
                                int size = Math.min(fileSize - loc * PKT_FILEDAT_SIZE, PKT_FILEDAT_SIZE);

                                Message msgPart = new Message(initMsg);
                                msgPart.mData = wrapFileData(fileData, fileIndex, loc, size);
                                writeMessage("Sending File " + fileIndex + " - Part " + loc);
                                qMessages.put(msgPart);

                                if (size < PKT_FILEDAT_SIZE) j = PKT_FILEDAT_SIZE;
                            }
                        }

                        Files.write(file, msg.mData);
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private Path createFile(byte[] data) {
        ByteBuffer bbLen = getBytes(data, PKT_FILENAME_LEN, PKT_FILEDATA_LEN, PKT_FILEDATA_LEN + PKT_FILENAME_LEN);
        ByteBuffer bbName = getBytes(data, bbLen.getInt(),
                PKT_FILEDATA_LEN + PKT_FILENAME_LEN, PKT_FILEDATA_LEN + PKT_FILENAME_LEN + bbLen.getInt());
        String filename = new String(bbName.array());
        //String baseFilename = filename;

        Path file = null;
        boolean isGood = false;
        while (!isGood) {
            try {
                //create the empty file
                file = Paths.get(filePath + filename);
                Files.createFile(file);
                isGood = true;
            } catch (FileAlreadyExistsException x) {
                //create file with sequential number at end
                //filename = baseFilename + next;
            } catch (IOException x) {
                //some other error occurred
                return null;
            }
        }

        return file;
    }

    private int getFilesize(byte[] data) {
        ByteBuffer bbLen = getBytes(data, PKT_FILEDATA_LEN, 0, PKT_FILEDATA_LEN);
        return bbLen.getInt();
    }

    public void routeMessage(Message msg) {
        try {
            String strKey = msg.mPort + "-" + msg.mFileNum;
            mtxHash.acquire();
            Integer idxFile = hashPacket.get(strKey);
            mtxArray.acquire();
            if (idxFile != null) {
                arrReceived.get(idxFile).put(msg);
            } else {
                idxFile = arrReceived.size();
                hashPacket.put(strKey, idxFile);
                arrReceived.add(new LinkedBlockingQueue<>());
                arrReceived.get(idxFile).put(msg);

                //start the receiving file thread
                receiveFile(idxFile).start();
            }
            mtxArray.release();
            mtxHash.release();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
