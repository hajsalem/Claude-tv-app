package app.duckblast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TCP server on the TV that phones connect to. Advertised on the local network
 * with mDNS/NSD so phones find it without typing an address. Everything a phone
 * sends becomes an {@link Event} on a queue that the game loop drains each frame.
 */
final class GameServer {
    private static final String TAG = "DuckBlast";

    static final int JOIN = 1, AIM = 2, FIRE = 3, CAL = 4, LEAVE = 5;

    static final class Event {
        final int type;
        final Client client;
        final float x, y;
        final String text;

        Event(int type, Client client, float x, float y, String text) {
            this.type = type;
            this.client = client;
            this.x = x;
            this.y = y;
            this.text = text;
        }
    }

    final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();

    private final Context context;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private volatile boolean running;
    private volatile int port;

    GameServer(Context context) {
        this.context = context.getApplicationContext();
    }

    int port() {
        return port;
    }

    void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "duck-accept");
        acceptThread.start();
    }

    void stop() {
        running = false;
        unregister();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        try {
            ServerSocket ss;
            try {
                ss = new ServerSocket(Net.PORT);
            } catch (IOException e) {
                ss = new ServerSocket(0); // fixed port busy; NSD still advertises the real one
            }
            ss.setReuseAddress(true);
            serverSocket = ss;
            port = ss.getLocalPort();
            register(port);
            while (running) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);
                new Client(s).start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "server stopped", e);
        }
    }

    private void register(int port) {
        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(Net.SERVICE_NAME);
            info.setServiceType(Net.SERVICE_TYPE);
            info.setPort(port);
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "NSD registered " + i.getServiceName()); }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int err) { Log.w(TAG, "NSD register failed " + err); }
                @Override public void onServiceUnregistered(NsdServiceInfo i) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int err) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) {
            Log.w(TAG, "NSD unavailable", e);
            registration = null;
        }
    }

    private void unregister() {
        try {
            if (nsd != null && registration != null) nsd.unregisterService(registration);
        } catch (RuntimeException ignored) {
        }
        registration = null;
    }

    /** First private IPv4 address of this device, for showing on screen. */
    static String localAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** One connected phone. Reads on one thread, writes on another. */
    final class Client {
        private final Socket socket;
        private final LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();
        private volatile boolean open = true;

        Client(Socket socket) {
            this.socket = socket;
        }

        void start() {
            new Thread(this::readLoop, "duck-read").start();
            new Thread(this::writeLoop, "duck-write").start();
        }

        void send(String line) {
            if (open) out.offer(line);
        }

        void close() {
            open = false;
            out.offer(""); // wake the writer
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        private void readLoop() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.trim().split(" ", 3);
                    switch (p[0]) {
                        case "HELLO":
                            events.add(new Event(JOIN, this, 0, 0, p.length > 1 ? line.substring(6).trim() : "Phone"));
                            break;
                        case "AIM":
                        case "FIRE":
                            if (p.length < 3) break;
                            events.add(new Event(p[0].equals("AIM") ? AIM : FIRE, this,
                                    Net.parseF(p[1], 0.5f), Net.parseF(p[2], 0.5f), null));
                            break;
                        case "CAL":
                            events.add(new Event(CAL, this, 0, 0, null));
                            break;
                        default:
                            break; // PING and unknown lines
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
                events.add(new Event(LEAVE, this, 0, 0, null));
            }
        }

        private void writeLoop() {
            try {
                OutputStream os = socket.getOutputStream();
                while (open) {
                    String line = out.take();
                    if (!open) break;
                    if (line.isEmpty()) continue;
                    os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                close();
            }
        }
    }
}
