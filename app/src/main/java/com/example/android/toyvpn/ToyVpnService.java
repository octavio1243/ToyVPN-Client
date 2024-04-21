
package com.example.android.toyvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;

public class ToyVpnService extends VpnService implements Handler.Callback, Runnable {
    private static final String TAG = "ToyVpnService";

    private String mServerAddress;
    private String mServerPort;

    private Handler mHandler;
    private Thread mThread;

    private ParcelFileDescriptor mInterface;

    private static final int MAX_PACKET_SIZE = 65535; //Short.MAX_VALUE;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = new Handler(this);
        }

        // Stop the previous session by interrupting the thread.
        if (mThread != null) {
            mThread.interrupt();
        }

        // Extract information from the intent.
        String prefix = getPackageName();
        mServerAddress = intent.getStringExtra(prefix + ".ADDRESS");
        mServerPort = intent.getStringExtra(prefix + ".PORT");

        // Start a new session by creating a new thread.
        mThread = new Thread(this, "ToyVpnThread");
        mThread.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mThread != null) {
            mThread.interrupt();
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        if (message != null) {
            Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override
    public synchronized void run() {
        try {
            Log.i(TAG, "Starting");

            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            InetSocketAddress server = new InetSocketAddress(
                    mServerAddress, Integer.parseInt(mServerPort));

            // We try to create the tunnel for several times. The better way
            // is to work with ConnectivityManager, such as trying only when
            // the network is avaiable. Here we just use a counter to keep
            // things simple.
            for (int attempt = 0; attempt < 10; ++attempt) {
                mHandler.sendEmptyMessage(R.string.connecting);

                // Reset the counter if we were connected.
                if (run(server)) {
                    attempt = 0;
                }

                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000);
            }
            Log.i(TAG, "Giving up");
        } catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        } finally {
            try {
                mInterface.close();
            } catch (Exception e) {
                // ignore
            }
            mInterface = null;
            mHandler.sendEmptyMessage(R.string.disconnected);
            Log.i(TAG, "Exiting");
        }
    }

    private void getInfoPacket(byte[] datagram, int length) throws UnknownHostException {
        int protocol =  datagram[9] & 0xFF;
        InetAddress srcAddress = InetAddress.getByAddress(Arrays.copyOfRange(datagram, 12, 16));
        InetAddress dstAddress = InetAddress.getByAddress(Arrays.copyOfRange(datagram, 16, 20));
        if (protocol ==6){
            int sourcePort = ((datagram[20] & 0xFF) << 8) | (datagram[21] & 0xFF);
            int destinationPort = ((datagram[22] & 0xFF) << 8) | (datagram[23] & 0xFF);
            Log.d("APP","[TCP] "+srcAddress+":"+sourcePort+" -> "+dstAddress+":"+destinationPort+" | Lenght: "+length);
        }
        if (protocol == 17){
            int sourcePort = ((datagram[20] & 0xFF) << 8) | (datagram[21] & 0xFF);
            int destinationPort = ((datagram[22] & 0xFF) << 8) | (datagram[23] & 0xFF);
            Log.d("APP","[UDP] "+srcAddress+":"+sourcePort+" -> "+dstAddress+":"+destinationPort+" | Lenght: "+length);
        }
    }

    private boolean run(InetSocketAddress server) throws Exception {
        DatagramChannel tunnel = null;
        boolean connected = false;
        try {
            // Create a DatagramChannel as the VPN tunnel.
            tunnel = DatagramChannel.open();

            // Protect the tunnel before connecting to avoid loopback.

            if (!protect(tunnel.socket())) {
                Log.e("APP","Error porque el tunel no está protegido");
                throw new IllegalStateException("Cannot protect the tunnel");
            }


            // Connect to the server.
            tunnel.connect(server);

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            tunnel.configureBlocking(false);

            /*
            String message = "Tunel creado con éxito";
            byte[] messageBytes = message.getBytes();
            ByteBuffer buffer = ByteBuffer.wrap(messageBytes);
            tunnel.write(buffer);
            */

            // Authenticate and configure the virtual network interface.
            //handshake(tunnel);
            Builder builder = new Builder();
            mInterface = builder.setSession("VPNtoSocket")
                    //.setMtu(65535)
                    .addAddress("10.8.0.2", 32)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)
                    //.addRoute("172.25.0.1", 16)
                    .establish();

            // Now we are connected. Set the flag and show the message.
            connected = true;
            mHandler.sendEmptyMessage(R.string.connected);

            // Packets to be sent are queued in this input stream.
            FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());

            // Packets received need to be written to this output stream.
            FileOutputStream out = new FileOutputStream(mInterface.getFileDescriptor());

            // Allocate the buffer for a single packet.
            ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

            // We use a timer to determine the status of the tunnel. It
            // works on both sides. A positive value means sending, and
            // any other means receiving. We start with receiving.
            int timer = 0;

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Assume that we did not make any progress in this iteration.
                boolean idle = true;

                // Read the outgoing packet from the input stream.
                int length = in.read(packet.array());
                if (length > 0) {
                    getInfoPacket(packet.array(),length);
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length);

                    packet.rewind();
                    tunnel.write(packet);
                    packet.clear();

                    // There might be more outgoing packets.
                    idle = false;

                    // If we were receiving, switch to sending.
                    if (timer < 1) {
                        timer = 1;
                    }
                }


                // Read the incoming packet from the tunnel.
                length = tunnel.read(packet);
                if (length > 0) {
                    getInfoPacket(packet.array(),length);
                    packet.rewind();
                    out.write(packet.array(), 0, length);
                    packet.clear();

                    // There might be more incoming packets.
                    idle = false;

                    // If we were sending, switch to receiving.
                    if (timer > 0) {
                        timer = 0;
                    }
                }

                // If we are idle or waiting for the network, sleep for a
                // fraction of time to avoid busy looping.
                if (idle) {
                    Thread.sleep(100);

                    // Increase the timer. This is inaccurate but good enough,
                    // since everything is operated in non-blocking mode.
                    timer += (timer > 0) ? 100 : -100;

                    // We are receiving for a long time but not sending.
                    if (timer < -15000) {
                        // Send empty control messages.
                        packet.put((byte) 0).limit(1);
                        for (int i = 0; i < 3; ++i) {
                            packet.position(0);
                            tunnel.write(packet);
                        }
                        packet.clear();

                        // Switch to sending.
                        timer = 1;
                    }

                    // We are sending for a long time but not receiving.
                    if (timer > 20000) {
                        throw new IllegalStateException("Timed out");
                    }
                }

            }
        }catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        } finally {
            try {
                if (tunnel != null) tunnel.close();
            } catch (Exception e) {
                Log.v(TAG, e.toString());
            }
        }
        return connected;
    }


}
