package app.duckblast;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Phone-side connection to the TV. Aim updates are coalesced (only the newest
 * position is sent) so a slow Wi-Fi moment never builds up a backlog of stale
 * aim points; FIRE and CAL lines are queued and always delivered in order.
 */
final class RemoteLink {
    interface Listener {
        void onConnected();
        void onLine(String line);
        void onDisconnected(String why);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private float aimX = 0.5f, aimY = 0.5f;
    private boolean aimDirty;
    private volatile boolean running;
    private Socket socket;

    RemoteLink(Listener listener) {
        this.listener = listener;
    }

    void connect(String host, int port, String name) {
        running = true;
        new Thread(() -> run(host, port, name), "duck-link").start();
    }

    void close() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    boolean isRunning() {
        return running;
    }

    void aim(float x, float y) {
        synchronized (lock) {
            aimX = x;
            aimY = y;
            aimDirty = true;
            lock.notifyAll();
        }
    }

    void fire(float x, float y) {
        send(String.format(Locale.US, "FIRE %.4f %.4f", x, y));
    }

    void send(String line) {
        synchronized (lock) {
            queue.add(line);
            lock.notifyAll();
        }
    }

    private void run(String host, int port, String name) {
        String why = "Disconnected";
        try {
            Socket s = new Socket();
            socket = s;
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 4000);
            OutputStream out = s.getOutputStream();
            out.write(("HELLO " + name.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ui.post(listener::onConnected);

            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        final String l = line;
                        ui.post(() -> listener.onLine(l));
                    }
                } catch (IOException ignored) {
                }
                running = false;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }, "duck-link-read");
            reader.start();

            long lastPing = System.currentTimeMillis();
            while (running) {
                String line = null;
                String aim = null;
                synchronized (lock) {
                    while (running && queue.isEmpty() && !aimDirty) {
                        lock.wait(1000);
                        if (System.currentTimeMillis() - lastPing > 2000) break;
                    }
                    if (aimDirty) {
                        aim = String.format(Locale.US, "AIM %.4f %.4f", aimX, aimY);
                        aimDirty = false;
                    }
                    line = queue.poll();
                }
                if (!running) break;
                StringBuilder sb = new StringBuilder();
                if (aim != null) sb.append(aim).append('\n');
                if (line != null) sb.append(line).append('\n');
                if (sb.length() == 0) {
                    sb.append("PING\n");
                }
                lastPing = System.currentTimeMillis();
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            why = "Couldn't reach the TV (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            why = "Stopped";
        } finally {
            running = false;
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
        }
        final String w = why;
        ui.post(() -> listener.onDisconnected(w));
    }
}
