/**
 * Created by Zortrox on 3/23/2017.
 */

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static javax.swing.JFileChooser.APPROVE_OPTION;

public class FileClient extends NetObject {

    public static void main(String[] args) {
        FileClient client = new FileClient("File Client");

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
                        BlockingQueue<Message> queue = new LinkedBlockingQueue<>();
                        mtxArray.acquire();
                        int fileIndex = arrReceived.size();
                        arrReceived.add(queue);
                        mtxArray.release();

                        //file info
                        byte[] fileData = Files.readAllBytes(file);
                        String fileName = file.getFileName().toString();

                        writeMessage("Sending File: " + fileName);

                        //send initial file packet
                        Message msg = new Message();
                        msg.mIP = InetAddress.getByName(IP);
                        msg.mPort = port;
                        msg.mData = wrapFileInfo(fileName, fileData.length);
                        qMessages.put(msg);

                        int numChunks = (int)Math.ceil(((double)fileData.length) / PKT_FILEDAT_SIZE);
                        int numWindows = (int)Math.ceil(((double)numChunks) / WINDOW_SIZE);

                        int windowPos = 0;
                        int indexPos = 0;
                        for (int i = 0; i < numWindows; i = windowPos) {
                            for (int j = indexPos; j < WINDOW_SIZE; j++) {
                                int loc = i * WINDOW_SIZE + j;

                                Message msgPart = new Message(msg);
                                msgPart.mData = wrapFileData(fileData, fileIndex, loc);
                                qMessages.put(msgPart);
                            }

                            long pollTime = 500;
                            int maxSequence = i * WINDOW_SIZE;
                            while (true) {
                                try {
                                    long timeBefore = System.nanoTime();
                                    Message msgReceive = arrReceived.get(fileIndex).poll(pollTime, TimeUnit.MILLISECONDS);
                                    long timeAfter = System.nanoTime();

                                    if (msgReceive == null) break;
                                    else {
                                        pollTime -= (timeAfter - timeBefore) / 1000000;
                                        if (pollTime <= 0) {
                                            if (arrReceived.get(fileIndex).size() == 0) {
                                                break;
                                            }
                                        }
                                        else {
                                            int sequence = getPacketSequence(msgReceive.mData);
                                            if (sequence > maxSequence) maxSequence = sequence;
                                            if (maxSequence >= i * WINDOW_SIZE + WINDOW_SIZE - 1) {
                                                windowPos++;
                                                indexPos = 0;
                                                arrReceived.get(fileIndex).clear();
                                                break;
                                            } else {
                                                indexPos = maxSequence;
                                            }
                                        }
                                    }
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
            thrSend.start();
        }
    }

    private byte[] wrapFileInfo(String filename, int filesize) {
        int fnSize = 260;
        int fdSize = 8;
        byte[] wrappedData = new byte[fnSize + fdSize];

        ByteBuffer bbData = ByteBuffer.allocate(fdSize);
        bbData.putInt(filesize);
        byte[] dataSize = bbData.array();

        //add filename and filesize to data
        System.arraycopy(filename.getBytes(), 0, wrappedData, 0, filename.length());
        System.arraycopy(dataSize, 0, wrappedData, fnSize, fdSize);

        return wrappedData;
    }

    private byte[] wrapFileData(byte[] data, int fileNum, int sequenceNum) {
        byte[] wrappedData = new byte[PKT_FILENUM_SIZE + PKT_SQUN_SIZE + PKT_FILEDAT_SIZE];

        ByteBuffer bbFile = ByteBuffer.allocate(PKT_FILENUM_SIZE);
        bbFile.putInt(fileNum);

        ByteBuffer bbSqun = ByteBuffer.allocate(PKT_SQUN_SIZE);
        bbSqun.putInt(sequenceNum);

        System.arraycopy(bbFile.array(), 0, wrappedData, 0, PKT_FILENUM_SIZE);
        System.arraycopy(bbSqun.array(), 0, wrappedData, PKT_FILENUM_SIZE, PKT_SQUN_SIZE);
        System.arraycopy(data, sequenceNum, wrappedData, PKT_FILENUM_SIZE + PKT_SQUN_SIZE, PKT_FILEDAT_SIZE);

        return wrappedData;
    }

    private int getPacketSequence(byte[] data) {
        ByteBuffer bbSqun = ByteBuffer.allocate(PKT_SQUN_SIZE);
        byte[] sequence = Arrays.copyOfRange(data, PKT_FILENUM_SIZE, PKT_FILENUM_SIZE + PKT_SQUN_SIZE);
        bbSqun.put(sequence);

        return bbSqun.getInt();
    }
}
