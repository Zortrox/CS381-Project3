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
	}

	byte[] mData;
	InetAddress mIP;
	int mPort;
}

public class NetObject {
	private String filePath;

	//Swing stuff
	public JFrame frame = null;
	public JTextArea txtMessages = null;
	public JScrollPane scrollPane = null;

	//packet size/subsizes in bytes
	public static final int PACKET_SIZE = 1024;
	public static final int PKT_IP_SIZE = 5;
	public static final int PKT_PORT_SIZE = 5;
	public static final int PKT_SQUN_SIZE = 8;
	public static final int PKT_FILENUM_SIZE = 5;
	public static final int PKT_FILEDAT_SIZE = PACKET_SIZE
			- PKT_IP_SIZE - PKT_PORT_SIZE - PKT_SQUN_SIZE - PKT_FILENUM_SIZE;

	public static final int WINDOW_SIZE = 15;

	//message types
	private static final byte MSG_INIT = 0;	//init connection
	private static final byte MSG_TEXT = 1;	//sending text
	private static final byte MSG_FILE = 2;	//sending file

	//network objects
	private DatagramSocket listenSocket;
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
			listenSocket = new DatagramSocket(port);

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
							processMessage(msg);
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
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	//determine what to do with the received packet and data
	private void processMessage(Message msg) {}

	private void processUDPData(DatagramPacket packet, Message msg) {
		//store packet data
		msg.mData = packet.getData();

		//get size of receiving data
		byte[] byteSize = new byte[4];
		ByteBuffer bufSize = ByteBuffer.wrap(Arrays.copyOfRange(msg.mData, 0, byteSize.length));
		int dataSize = bufSize.getInt();

		//get sent data from packet
		msg.mData = Arrays.copyOfRange(msg.mData, byteSize.length + 1, byteSize.length + 1 + dataSize);

		//get location from packet
		msg.mPort = packet.getPort();
		msg.mIP = packet.getAddress();
	}

	private void receiveUDPData(DatagramSocket socket, Message msg) throws Exception{
		msg.mData = new byte[PACKET_SIZE];
		DatagramPacket receivePacket = new DatagramPacket(msg.mData, msg.mData.length);
		socket.receive(receivePacket);

		processUDPData(receivePacket, msg);
	}

	private void sendUDPData(DatagramSocket socket, Message msg) throws Exception{
		//get size of data
		ByteBuffer b = ByteBuffer.allocate(4);
		b.putInt(msg.mData.length);
		byte[] dataSize = b.array();

		//create array of all data
		byte[] data = new byte[dataSize.length + 1 + msg.mData.length];
		System.arraycopy(dataSize, 0, data, 0, dataSize.length);
		System.arraycopy(msg.mData, 0, data, dataSize.length + 1, msg.mData.length);

		//send data
		DatagramPacket sendPacket = new DatagramPacket(data, data.length, msg.mIP, msg.mPort);
		socket.send(sendPacket);
	}

	public void receiveFile(Object socket, Message msg, String filename) {
		Path file = Paths.get(filePath + filename);

		try {
			receiveUDPData((DatagramSocket) socket, msg);

			Files.write(file, msg.mData);
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public void writeMessage(String msg) {
		try {
			mtxMessages.acquire();
			txtMessages.append(msg + "\n");
			scrollPane.scrollRectToVisible(txtMessages.getBounds());
			mtxMessages.release();
		} catch (Exception e) {
			//e.printStackTrace();
		}
	}
}
