/**
 * Created by Zortrox on 3/23/2017.
 */

import sun.reflect.annotation.ExceptionProxy;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Exchanger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class GremlinProxy extends NetObject {

    private InetAddress serverIP;
    private int serverPort;
    private Semaphore mtxPerc = new Semaphore(1);
    private double dropRate = 0.5;

    private Semaphore mtxHash = new Semaphore(1);
    private Map<Integer, Integer> hashClientPorts = new HashMap<>();
    private Map<Integer, InetAddress> hashClientIPs = new HashMap<>();
    private Map<Integer, LinkedBlockingQueue<Message>> hashMessages = new HashMap<>();
    private Map<Integer, Integer> hashServerIPs = new HashMap<>();

    private Semaphore mtxPort = new Semaphore(1);
    private int nextPort = 6000;

    private Random rand = new Random(System.currentTimeMillis());

    public static void main(String[] args) {
        GremlinProxy proxy = new GremlinProxy("Gremlin Proxy");

		int port = Integer.parseInt(JOptionPane.showInputDialog("Port to Listen On", "6000"));
        proxy.connect(port);
    }

    private GremlinProxy(String title) {
        super(title);

        try {
			String host = showServerPopup("IP:Port to Send Files", "127.0.0.1:5000");
			serverPort = Integer.parseInt(host.substring(host.indexOf(':') + 1));
			serverIP = InetAddress.getByName(host.substring(0, host.indexOf(':')));

            JSlider slider = new JSlider(JSlider.HORIZONTAL, 0, 100, (int)(dropRate * 100));
            slider.setMajorTickSpacing(10);
            slider.setMinorTickSpacing(1);
            slider.setPaintTicks(true);
            slider.setPaintLabels(true);
            slider.addChangeListener(new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    int newVal = ((JSlider)e.getSource()).getValue();
                    try {
                        mtxPerc.acquire();
                        dropRate = ((double)newVal) / 100;
                        mtxPerc.release();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });

            JLabel label = new JLabel("Drop Rate", SwingConstants.CENTER);
            label.setBorder(new EmptyBorder(0,0,10,0));

            JPanel pnlSouth = new JPanel(new BorderLayout());
            pnlSouth.add(label, BorderLayout.NORTH);
            pnlSouth.add(slider, BorderLayout.SOUTH);

            frame.add(pnlSouth, BorderLayout.SOUTH);
            frame.revalidate();
            frame.repaint();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void routeMessage(Message msg) {
        try {
            Integer sendPort;
            InetAddress sendIP;
			Integer incomingPort = msg.mPort;

            mtxHash.acquire();
            if (msg.mIP == null) {
				//if from the server (only time mIP can equal null)
                sendPort = hashClientPorts.get(incomingPort);
                sendIP = hashClientIPs.get(incomingPort);
            } else {
            	//if from the client
                sendPort = hashServerIPs.get(incomingPort);
                sendIP = serverIP;

                if (sendPort == null) {
                    //create new connection
                    mtxPort.acquire();
                    Integer currentPort = ++nextPort;
                    mtxPort.release();

                    hashClientPorts.put(currentPort, msg.mPort);
                    hashClientIPs.put(currentPort, msg.mIP);
                    hashMessages.put(msg.mPort, new LinkedBlockingQueue<Message>());
                    hashServerIPs.put(incomingPort, serverPort);

                    connect(currentPort, hashMessages.get(msg.mPort));
                    sendPort = serverPort;
                }
            }
            mtxHash.release();

            if (sendPort != null) {
				msg.mIP = sendIP;
				msg.mPort = sendPort;

				//forwards messages & drop ones to server (based on chance)
				if (!(sendIP.equals(serverIP) && sendPort.equals(serverPort))) {
					//if going from the server to the clients
					qMessages.put(msg);
				} else {
					//drop packets going from the clients to server
					mtxPerc.acquire();
					double rate = dropRate;
					mtxPerc.release();
					if (rand.nextDouble() >= rate) {
						hashMessages.get(incomingPort).put(msg);
						writeMessage("Forwarded packet from port " + incomingPort);
					} else {
						writeMessage("Dropped packet from port " + incomingPort);
					}

				}

			}

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void processUDPData(DatagramPacket packet, Message msg) {
    	//copy data directly to a Message
		msg.mData = packet.getData();
		msg.mPort = packet.getPort();
		msg.mIP = packet.getAddress();
    }
}
