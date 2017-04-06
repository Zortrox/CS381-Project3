/**
 * Created by Zortrox on 9/13/2016.
 */

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.xml.crypto.Data;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static javax.swing.JFileChooser.APPROVE_OPTION;

class Message {
	Message() {}
	Message(Message msg) {
		mIP = msg.mIP;
		mPort = msg.mPort;
		mType = msg.mType;
	}

	byte[] mData;
	int mFileNum;
	int mSqun;

	InetAddress mIP;
	int mPort;
	byte mType;
}

public class NetObject {
	public String filePath;

	//Swing stuff
	public JFrame frame = null;
	private JTextArea txtMessages = null;
	private JScrollPane scrollPane = null;

	//packet size/subsizes in bytes
	private static final int PACKET_SIZE = 1024;
	public static final int PKT_TYPE_SIZE = 1;
	public static final int PKT_FILENUM_SIZE = 5;
	public static final int PKT_SQUN_SIZE = 8;
	public static final int PKT_FILEDAT_SIZE = PACKET_SIZE - PKT_TYPE_SIZE - PKT_FILENUM_SIZE - PKT_SQUN_SIZE;

	public static final int PKT_FILENAME_LEN = 8;
	public static final int PKT_FILEDATA_LEN = 8;

	public static final int WINDOW_SIZE = 15;

	//message types
	public static final byte MSG_INIT = 0;	//initial file
	public static final byte MSG_DATA = 1;	//sending data

	//network objects
	private BlockingQueue<DatagramPacket> qPackets = new LinkedBlockingQueue<>();
	public BlockingQueue<Message> qMessages = new LinkedBlockingQueue<>();
	public ArrayList<BlockingQueue<Message>> arrReceived = new ArrayList<>();
	public Semaphore mtxArray = new Semaphore(1);
	private Semaphore mtxMessages = new Semaphore(1);

	NetObject() {}

	NetObject(String strTitle) {
		//get relative path
		filePath = new File("").getAbsolutePath();

		//create window
		frame = new JFrame(strTitle);
		frame.setSize(600, 400);
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		txtMessages = new JTextArea(1, 20);
		txtMessages.setEditable(false);
		scrollPane = new JScrollPane(txtMessages, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		DefaultCaret caret = (DefaultCaret) txtMessages.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
		frame.add(scrollPane);

		frame.setVisible(true);
	}

	//receiving data
	public boolean listen(final int port) {
		try {
			DatagramSocket listenSocket = new DatagramSocket(port);

			Thread thrListen = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						System.out.println("<server>: Listening for packets.");

						while (true) {
							byte[] receiveData = new byte[PACKET_SIZE];
							DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
							listenSocket.receive(receivePacket);

							qPackets.put(receivePacket);
						}
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			});
			thrListen.start();

			//process packets
			Thread thrProcess = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						while (true) {
							//receive data
							DatagramPacket receivePacket = qPackets.take();
							writeMessage("New Packet");
							Message msg = new Message();

							processUDPData(receivePacket, msg);
							routeMessage(msg);
						}
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			});
			thrProcess.start();
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}

		return true;
	}

	public void routeMessage(Message msg) {}

	//sending data
	public boolean connect() {
		try {
			DatagramSocket sendSocket = new DatagramSocket();
			Thread thrConnect = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						Message msg = qMessages.take();
						sendUDPData(sendSocket, msg);
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			});
			thrConnect.start();

			listen(sendSocket.getPort());
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	private void processUDPData(DatagramPacket packet, Message msg) {
		//store packet data
		msg.mData = packet.getData();

		//get packet type
		int idxFrom = 0;
		int idxTo = PKT_TYPE_SIZE;
		msg.mType = getBytes(msg.mData, PKT_TYPE_SIZE, idxFrom, idxTo).get();
		idxFrom += PKT_TYPE_SIZE;

		//get file number
		idxTo += PKT_FILENUM_SIZE;
		msg.mFileNum = getBytes(msg.mData, PKT_FILENUM_SIZE, idxFrom, idxTo).getInt();
		idxFrom += PKT_FILENUM_SIZE;

		if (msg.mType == MSG_INIT) {
			//get file size, filename length, and filename
			msg.mData = Arrays.copyOfRange(msg.mData, idxFrom, msg.mData.length - 1);
		} else if (msg.mType == MSG_DATA) {
			//get sequence number
			idxTo += PKT_SQUN_SIZE;
			msg.mSqun = getBytes(msg.mData, PKT_SQUN_SIZE, idxFrom, idxTo).getInt();
			idxFrom += PKT_SQUN_SIZE;

			//get file data
			msg.mData = Arrays.copyOfRange(msg.mData, idxFrom, msg.mData.length - 1);
		}

		//get location from packet
		msg.mPort = packet.getPort();
		msg.mIP = packet.getAddress();
	}

	public ByteBuffer getBytes(byte[] data, int size, int idxFrom, int idxTo) {
		ByteBuffer bbData = ByteBuffer.allocate(size);
		byte[] fileNum = Arrays.copyOfRange(data, idxFrom, idxTo);
		bbData.put(fileNum);

		return bbData;
	}

	private void sendUDPData(DatagramSocket socket, Message msg) throws Exception{
		DatagramPacket sendPacket = new DatagramPacket(msg.mData, msg.mData.length, msg.mIP, msg.mPort);
		socket.send(sendPacket);
	}

	public void writeMessage(String msg) {
		try {
			mtxMessages.acquire();
			txtMessages.append(msg + "\n");
			scrollPane.scrollRectToVisible(txtMessages.getBounds());
			mtxMessages.release();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
