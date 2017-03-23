/**
 * Created by Zortrox on 9/13/2016.
 */

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;

class Message {
	byte mType = 0;
	byte[] mData;
	InetAddress mIP;
	int mPort;
}

public class NetObject {
	private String filePath;

	//Swing stuff
	private JTextArea txtMessages = null;
	private JScrollPane scrollPane = null;

	//packet size in bytes
	private static final int PACKET_SIZE = 1024;

	//message types
	private static final byte MSG_INIT = 0;	//init connection
	private static final byte MSG_TEXT = 1;	//sending text
	private static final byte MSG_FILE = 2;	//sending file

	NetObject(String strTitle) {
		//get relative path
		filePath = new File("").getAbsolutePath();

		//create window
		JFrame frame = new JFrame(strTitle);
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
		Thread thrListen = new Thread(new Runnable() {
			@Override
			public void run() {

			}
		});
		thrListen.start();

		//process packets
		Thread thrProcess = new Thread(new Runnable() {
			@Override
			public void run() {

			}
		});
		thrProcess.start();

		return true;
	}

	//sending data
	public boolean connect(String IP, final int port) {
		Thread thrConnect = new Thread(new Runnable() {
			@Override
			public void run() {

			}
		});
		thrConnect.start();

		return true;
	}

	private void processUDPData(DatagramPacket packet, Message msg) {
		//store packet data
		msg.mData = packet.getData();

		//get size of receiving data
		byte[] byteSize = new byte[4];
		ByteBuffer bufSize = ByteBuffer.wrap(Arrays.copyOfRange(msg.mData, 0, byteSize.length));
		int dataSize = bufSize.getInt();

		//get type of receiving data
		msg.mType = msg.mData[byteSize.length];

		//get sent data from packet
		msg.mData = Arrays.copyOfRange(msg.mData, byteSize.length + 1, byteSize.length + 1 + dataSize);

		//get location from packet
		msg.mPort = packet.getPort();
		msg.mIP = packet.getAddress();
	}

	public void receiveUDPData(DatagramSocket socket, Message msg) throws Exception{
		msg.mData = new byte[PACKET_SIZE];
		DatagramPacket receivePacket = new DatagramPacket(msg.mData, msg.mData.length);
		socket.receive(receivePacket);

		processUDPData(receivePacket, msg);
	}

	public void sendUDPData(DatagramSocket socket, Message msg) throws Exception{
		//get size of data
		ByteBuffer b = ByteBuffer.allocate(4);
		b.putInt(msg.mData.length);
		byte[] dataSize = b.array();

		//create array of all data
		byte[] data = new byte[dataSize.length + 1 + msg.mData.length];
		System.arraycopy(dataSize, 0, data, 0, dataSize.length);
		data[dataSize.length] = msg.mType;
		System.arraycopy(msg.mData, 0, data, dataSize.length + 1, msg.mData.length);

		//send data
		DatagramPacket sendPacket = new DatagramPacket(data, data.length, msg.mIP, msg.mPort);
		socket.send(sendPacket);
	}

	public void sendFile(Object socket, Message msg, String filename) {
		Path file = Paths.get(filePath + filename);
		try {
			byte[] fileData = Files.readAllBytes(file);

			msg.mType = MSG_FILE;
			msg.mData = fileData;

			sendUDPData((DatagramSocket) socket, msg);
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
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
			txtMessages.append(msg + "\n");
			scrollPane.scrollRectToVisible(txtMessages.getBounds());
		} catch (Exception e) {
			//e.printStackTrace();
		}
	}
}
