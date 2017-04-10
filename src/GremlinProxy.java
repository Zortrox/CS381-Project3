/**
 * Created by Zortrox on 3/23/2017.
 */

import sun.reflect.annotation.ExceptionProxy;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Semaphore;

public class GremlinProxy extends NetObject {

    private InetAddress serverIP;
    private Semaphore mtxPerc = new Semaphore(1);
    private double dropRate = 0.5;

    private Semaphore mtxHash = new Semaphore(1);
    private Map<Integer, Integer> hashServerPorts = new HashMap<>();
    private Map<Integer, Integer> hashClientPorts = new HashMap<>();
    private Map<Integer, InetAddress> hashClientIPs = new HashMap<>();

    private Semaphore mtxPort = new Semaphore(1);
    private int nextPort = 6000;

    public static void main(String[] args) {
        GremlinProxy proxy = new GremlinProxy("Gremlin Proxy");
        proxy.connect(6000);
    }

    private GremlinProxy(String title) {
        super(title);

        try {
            serverIP = InetAddress.getByName("127.0.0.1");

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

    private Thread newConnection(int port) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                connect(port);
            }
        });
    }

    public void routeMessage(Message msg) {
        try {
            Integer sendPort;
            InetAddress sendIP;

            mtxHash.acquire();
            if (msg.mIP.equals(serverIP)) {
                sendPort = hashServerPorts.get(msg.mPort);
                sendIP = hashClientIPs.get(msg.mPort);
            } else {
                sendPort = hashClientPorts.get(msg.mPort);
                sendIP = serverIP;

                if (sendPort == null) {
                    //create new connection
                    mtxPort.acquire();
                    Integer currentPort = ++nextPort;
                    mtxPort.release();

                    //newConnection(currentPort).start();


                    hashClientPorts.put(msg.mPort, currentPort);
                    hashServerPorts.put(currentPort, msg.mPort);
                    hashClientIPs.put(currentPort, msg.mIP);

                    connect(currentPort);
                }
            }
            mtxHash.release();



        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
