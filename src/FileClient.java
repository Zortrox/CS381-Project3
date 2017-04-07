/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static javax.swing.JFileChooser.APPROVE_OPTION;

public class FileClient extends NetObject {

    //return variables for ack waiting
    private static final int IDX_WINDOW_POS = 0;
    private static final int IDX_SEQUENCE_POS = 1;

    //waiting time before resending
    private static final long POLL_TIME = 500;

    public static void main(String[] args) {
        FileClient client = new FileClient("File Client");

        client.connect(4500);

        client.sendFile("127.0.0.1", 5000);
    }

    private FileClient(String title) {
        super(title);
    }

    private void sendFile(String IP, int port) {
        JFileChooser fileChooser = new JFileChooser();
        int opt = fileChooser.showOpenDialog(frame);

        if (opt == APPROVE_OPTION) {
            final Path file = fileChooser.getSelectedFile().toPath();

            Thread thrSend = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mtxArray.acquire();
                        int fileIndex = arrReceived.size();
                        arrReceived.add(new LinkedBlockingQueue<>());
                        mtxArray.release();

                        //file info
                        byte[] fileData = Files.readAllBytes(file);
                        String fileName = file.getFileName().toString();

                        //send initial file packet
                        Message msg = new Message();
                        msg.mIP = InetAddress.getByName(IP);
                        msg.mPort = port;
                        msg.mData = wrapFileInfo(fileName, fileIndex, fileData.length);

                        boolean resendMsg = true;
                        while (resendMsg) {
                            writeMessage("Sending File " + fileIndex + ": " + fileName);
                            qMessages.put(msg);

                            Message msgReceive = arrReceived.get(fileIndex).poll(POLL_TIME, TimeUnit.MILLISECONDS);
                            if (msgReceive != null) {
                                resendMsg = false;
                            }
                        }

                        int numChunks = (int)Math.ceil(((double)fileData.length) / PKT_FILEDAT_SIZE);
                        int numWindows = (int)Math.ceil(((double)numChunks) / WINDOW_SIZE);

                        int indexPos = 0;
                        for (int windowPos = 0; windowPos < numWindows;) {
                            for (int j = indexPos; j < WINDOW_SIZE; j++) {
                                int loc = windowPos * WINDOW_SIZE + j;
                                int size = Math.min(fileData.length - loc * PKT_FILEDAT_SIZE, PKT_FILEDAT_SIZE);

                                Message msgPart = new Message(msg);
                                msgPart.mData = wrapFileData(fileData, fileIndex, loc, size);
                                writeMessage("Sending File " + fileIndex + " - Part " + loc);
                                qMessages.put(msgPart);

                                //stop the window loop if end of file has been reached
                                if (size < PKT_FILEDAT_SIZE) j = PKT_FILEDAT_SIZE;
                            }

                            int[] vars = waitForAck(fileIndex, windowPos, indexPos);
                            windowPos = vars[IDX_WINDOW_POS];
                            indexPos = vars[IDX_SEQUENCE_POS];
                        }

                        writeMessage("File " + fileIndex + " completed: " + fileName);
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
            thrSend.start();
        }
    }

    private int[] waitForAck(int fileIndex, int windowPos, int indexPos) {
        int[] returnVars = new int[2];

        long pollTime = POLL_TIME;
        int maxSequence = windowPos * WINDOW_SIZE;
        boolean waitToResend = true;
        while (waitToResend) {
            try {
                //limit poll time for all packets in sequence
                long timeBefore = System.nanoTime();
                Message msgReceive = arrReceived.get(fileIndex).poll(pollTime, TimeUnit.MILLISECONDS);
                long timeAfter = System.nanoTime();

                if (msgReceive == null) waitToResend = false;
                else {
                    //lower polltime to limit waiting
                    pollTime -= (timeAfter - timeBefore) / 1000000; //convert to milliseconds
                    if (pollTime <= 0) {
                        //resend only if queue is empty
                        //will read everything in the queue since packets may come in bursts
                        if (arrReceived.get(fileIndex).size() == 0) {
                            waitToResend = false;
                        }
                    }
                    else {
                        int sequence = msgReceive.mSqun;
                        //only increase sequence if more data has been acked
                        if (sequence > maxSequence) maxSequence = sequence;
                        if (maxSequence >= windowPos * WINDOW_SIZE + WINDOW_SIZE) {
                            windowPos++;
                            indexPos = 0;
                            arrReceived.get(fileIndex).clear();
                            waitToResend = false;
                        } else {
                            indexPos = maxSequence;
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        returnVars[IDX_WINDOW_POS] = windowPos;
        returnVars[IDX_SEQUENCE_POS] = indexPos;

        return returnVars;
    }

    public void routeMessage(Message msg) {
        try {
            arrReceived.get(msg.mFileNum).put(msg);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private byte[] wrapFileInfo(String filename, int idxFile, int filesize) {
        byte[] wrappedData = new byte[PKT_TYPE_SIZE + PKT_FILENUM_SIZE +
                PKT_FILEDATA_LEN + PKT_FILENAME_LEN + filename.length()];

        ByteBuffer bbIndex = ByteBuffer.allocate(PKT_FILENUM_SIZE);
        bbIndex.putInt(idxFile);
        byte[] dataIndex = bbIndex.array();

        ByteBuffer bbSize = ByteBuffer.allocate(PKT_FILEDATA_LEN);
        bbSize.putInt(filesize);
        byte[] dataSize = bbSize.array();

        ByteBuffer bbFile = ByteBuffer.allocate(PKT_FILENAME_LEN);
        bbFile.putInt(filename.length());
        byte[] dataFile = bbFile.array();

        //add type, file number, filesize, filename length, and filename to array
        wrappedData[0] = MSG_INIT;
        System.arraycopy(dataIndex, 0, wrappedData, PKT_TYPE_SIZE, PKT_FILENUM_SIZE);
        System.arraycopy(dataSize, 0, wrappedData, PKT_TYPE_SIZE + PKT_FILENUM_SIZE, PKT_FILEDATA_LEN);
        System.arraycopy(dataFile, 0, wrappedData,
                PKT_TYPE_SIZE + PKT_FILENUM_SIZE + PKT_FILEDATA_LEN, PKT_FILENAME_LEN);
        System.arraycopy(filename.getBytes(), 0, wrappedData,
                PKT_TYPE_SIZE + PKT_FILENUM_SIZE + PKT_FILEDATA_LEN + PKT_FILENAME_LEN, filename.length());

        return wrappedData;
    }

    private byte[] wrapFileData(byte[] data, int fileNum, int sequenceNum, int dataLength) {
        byte[] wrappedData = new byte[PKT_FILENUM_SIZE + PKT_SQUN_SIZE + PKT_FILEDAT_SIZE];

        ByteBuffer bbFile = ByteBuffer.allocate(PKT_FILENUM_SIZE);
        bbFile.putInt(fileNum);

        ByteBuffer bbSqun = ByteBuffer.allocate(PKT_SQUN_SIZE);
        bbSqun.putInt(sequenceNum);

        int dataGroup = sequenceNum * PKT_FILEDAT_SIZE;
        wrappedData[0] = MSG_DATA;
        System.arraycopy(bbFile.array(), 0, wrappedData, PKT_TYPE_SIZE, PKT_FILENUM_SIZE);
        System.arraycopy(bbSqun.array(), 0, wrappedData, PKT_TYPE_SIZE + PKT_FILENUM_SIZE, PKT_SQUN_SIZE);
        System.arraycopy(data, dataGroup, wrappedData, PKT_TYPE_SIZE + PKT_FILENUM_SIZE + PKT_SQUN_SIZE, dataLength);

        return wrappedData;
    }
}
