/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class FileServer extends NetObject {

    private Semaphore mtxHash = new Semaphore(1);
    private Map<String, Integer> hashPacket = new HashMap<>();

    private String downloadFolder = "/downloads/";

    public static void main(String[] args) {
        FileServer server = new FileServer("File Server");

        int port = Integer.parseInt(JOptionPane.showInputDialog("Port to Listen On", "5000"));
        server.connect(port);
    }

    private FileServer(String title) {
        super(title);
    }

    private Thread receiveFile(final int idxFile) {
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
                        Message msgInfoAck = new Message(initMsg);
                        msgInfoAck.mData = ackFileInfo(initMsg.mFileNum);
                        qMessages.put(msgInfoAck);

                        //get number of sequences
                        byte[] fileData = new byte[fileSize];
                        int numChunks = (int) Math.ceil(((double) fileSize) / PKT_FILEDAT_SIZE);
                        int squnPos = 0;    //sequence received *up to*

                        boolean fileCompleted = false;
                        boolean[] arrContig = new boolean[numChunks];
                        while (!fileCompleted) {
                            //get the next part of the file
                            Message msg = arrReceived.get(idxFile).take();

                            //set that sequence has been received and add to data
                            if (!arrContig[msg.mSqun]) {
                                arrContig[msg.mSqun] = true;
                                int dataLen = PKT_FILEDAT_SIZE;
                                if (msg.mSqun == numChunks - 1) {
                                    dataLen = fileSize % PKT_FILEDAT_SIZE;
                                }

                                System.arraycopy(msg.mData, 0, fileData, msg.mSqun * PKT_FILEDAT_SIZE, dataLen);
                            }

                            //if sequence is larger than what's currently known
                            if (msg.mSqun >= squnPos) {
                                int windowMaxPos = Math.min(msg.mSqun / WINDOW_SIZE * WINDOW_SIZE + WINDOW_SIZE, numChunks);
                                boolean isContig = true;
                                for (int i = squnPos; i < windowMaxPos; i++) {
                                    if (!arrContig[i]) {
                                        isContig = false;
                                        squnPos = i;
                                        break;
                                    }
                                }
                                if (isContig) {
                                    squnPos = msg.mSqun + 1;
                                    if (squnPos >= numChunks) {
                                        fileCompleted = true;
                                    }
                                }
                            }

                            //ack the data sequence
                            Message msgDataAck = new Message(initMsg);
                            msgDataAck.mData = ackFileData(msg.mFileNum, squnPos);
                            qMessages.put(msgDataAck);
                        }

                        //write data to file
                        Files.write(file, fileData);
                        writeMessage("File " + file.getFileName() + " completed");

                        //remove the file from the hash so new files with same filenum can be added
                        mtxHash.acquire();
                        hashPacket.remove(createKey(initMsg.mPort, initMsg.mFileNum));
                        mtxHash.release();
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private byte[] ackFileInfo(int fileNum) {
        ByteBuffer bbIndex = ByteBuffer.allocate(PKT_FILENUM_SIZE);
        bbIndex.putInt(fileNum);

        byte[] wrappedData = new byte[PKT_TYPE_SIZE + PKT_FILENUM_SIZE];
        wrappedData[0] = MSG_INIT;
        System.arraycopy(bbIndex.array(), 0, wrappedData, PKT_TYPE_SIZE, PKT_FILENUM_SIZE);

        return wrappedData;
    }

    private byte[] ackFileData(int fileNum, int sequenceNum) {
        byte[] wrappedData = new byte[PKT_FILENUM_SIZE + PKT_SQUN_SIZE + PKT_FILEDAT_SIZE];

        ByteBuffer bbFile = ByteBuffer.allocate(PKT_FILENUM_SIZE);
        bbFile.putInt(fileNum);

        ByteBuffer bbSqun = ByteBuffer.allocate(PKT_SQUN_SIZE);
        bbSqun.putInt(sequenceNum);

        wrappedData[0] = MSG_DATA;
        System.arraycopy(bbFile.array(), 0, wrappedData, PKT_TYPE_SIZE, PKT_FILENUM_SIZE);
        System.arraycopy(bbSqun.array(), 0, wrappedData, PKT_TYPE_SIZE + PKT_FILENUM_SIZE, PKT_SQUN_SIZE);

        return wrappedData;
    }

    private Path createFile(byte[] data) {
        ByteBuffer bbLen = getBytes(data, PKT_FILENAME_LEN, PKT_FILEDATA_LEN, PKT_FILEDATA_LEN + PKT_FILENAME_LEN);
        ByteBuffer bbName = getBytes(data, bbLen.getInt(0),
                PKT_FILEDATA_LEN + PKT_FILENAME_LEN, PKT_FILEDATA_LEN + PKT_FILENAME_LEN + bbLen.getInt(0));
        String filename = new String(bbName.array());
        String baseFilename = filename;
        int nextFile = 2;

        Path file = null;
        boolean isGood = false;
        while (!isGood) {
            try {
                //create the downloads directory
                try {
                    Files.createDirectory(Paths.get(filePath + downloadFolder));
                }
                catch (Exception ex) {
                    //ex.printStackTrace();
                }

                //create the empty file
                file = Paths.get(filePath + downloadFolder + filename);
                Files.createFile(file);
                isGood = true;
            } catch (FileAlreadyExistsException ex) {
                //create file with sequential number at end
                filename = nextFilename(baseFilename, nextFile);
                nextFile++;
            } catch (IOException ex) {
                //some other error occurred
                return null;
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return file;
    }

    private String nextFilename(String filename, int next) {
        int endIndex = filename.lastIndexOf('.');
        int extEndIndex = endIndex;
        if (endIndex == -1) {
            endIndex = filename.length() - 1;
        }

        String strReturn = filename.substring(0, endIndex);
        strReturn += "(" + next + ")";

        if (extEndIndex != -1) {
            strReturn += filename.substring(extEndIndex, filename.length());
        }

        return strReturn;
    }

    private int getFilesize(byte[] data) {
        ByteBuffer bbLen = getBytes(data, PKT_FILEDATA_LEN, 0, PKT_FILEDATA_LEN);
        return bbLen.getInt(0);
    }

    public String createKey(int port, int fileNum) {
        return port + "-" + fileNum;
    }

    public void routeMessage(Message msg) {
        try {
            String strKey = createKey(msg.mPort, msg.mFileNum);
            mtxHash.acquire();
            Integer idxFile = hashPacket.get(strKey);
            mtxArray.acquire();
            if (idxFile != null) {
                arrReceived.get(idxFile).put(msg);
            } else {
                idxFile = arrReceived.size();
                hashPacket.put(strKey, idxFile);
                arrReceived.add(new LinkedBlockingQueue<Message>());
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
