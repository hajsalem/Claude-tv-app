package app.duckblast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phone side: turns the phone into a light gun. The gyro-fused orientation
 * sensor gives where the top edge of the phone is pointing; RECENTER marks the
 * middle of the TV and everything else is measured relative to that.
 */
public class RemoteActivity extends Activity implements SensorEventListener {

    private static final int ORANGE = 0xFFFF7A1A;

    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor rotation;
    private Vibrator vibrator;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private final Map<String, String> found = new LinkedHashMap<>(); // "host:port" -> label

    private RemoteLink link;
    private String linkTarget;
    private boolean autoConnect = true;

    // aim state
    private final float[] rot = new float[9];
    private final float[] rv = new float[4];
    private float yaw, pitch, yaw0, pitch0;
    private boolean haveOrientation, needRecenter = true;
    private float aimX = 0.5f, aimY = 0.5f;
    private float range;          // degrees from the centre to the left/right edge of the TV
    private boolean touchpad;     // no gyro, or the player chose drag-to-aim
    private long lastAimSent;

    // views
    private LinearLayout connectPanel, foundList, playPanel;
    private TextView connectStatus, playerLabel, scoreLabel, message;
    private EditText hostInput;
    private PadView pad;
    private int playerColor = ORANGE;
    private int score, ammo = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotation == null) rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        range = prefs.getFloat("range", 20f);
        touchpad = rotation == null || prefs.getBoolean("touchpad", false);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        showConnect();
        String last = prefs.getString("last", null);
        if (last != null && autoConnect) connectTo(last, "the last TV");
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopDiscovery();
        disconnect();
        sensors.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotation != null) sensors.registerListener(this, rotation, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        // connect screen
        ScrollView scroll = new ScrollView(this);
        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setPadding(dp(24), dp(40), dp(24), dp(24));
        scroll.addView(connectPanel);

        TextView title = text("DUCK BLAST", 34, 0xFFFFC23A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        connectPanel.addView(title);
        connectPanel.addView(text("Remote", 20, Color.WHITE));
        connectStatus = text("Looking for the TV on your Wi-Fi…", 16, 0xFFB8C2CC);
        connectStatus.setPadding(0, dp(24), 0, dp(12));
        connectPanel.addView(connectStatus);
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        connectPanel.addView(foundList);

        TextView manual = text("Or type the address shown on the TV:", 15, 0xFFB8C2CC);
        manual.setPadding(0, dp(28), 0, dp(6));
        connectPanel.addView(manual);
        LinearLayout row = new LinearLayout(this);
        hostInput = new EditText(this);
        hostInput.setHint("192.168.1.23");
        hostInput.setTextColor(Color.WHITE);
        hostInput.setHintTextColor(0xFF5A6570);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        String last = prefs.getString("last", null);
        if (last != null) hostInput.setText(last.endsWith(":" + Net.PORT) ? last.substring(0, last.lastIndexOf(':')) : last);
        hostInput.setOnEditorActionListener((v, id, e) -> {
            connectManual();
            return true;
        });
        row.addView(hostInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button go = button("Connect", ORANGE);
        go.setOnClickListener(v -> connectManual());
        row.addView(go);
        connectPanel.addView(row);

        TextView tips = text("• Open Duck Blast on the TV first.\n"
                + "• Phone and TV must be on the same Wi-Fi network.\n"
                + "• Up to 4 phones can play at once.", 14, 0xFF7F8A95);
        tips.setPadding(0, dp(28), 0, 0);
        connectPanel.addView(tips);
        root.addView(scroll);

        // play screen
        playPanel = new LinearLayout(this);
        playPanel.setOrientation(LinearLayout.VERTICAL);
        playPanel.setPadding(dp(16), dp(28), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        playerLabel = text("P?", 28, ORANGE);
        playerLabel.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(playerLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        scoreLabel = text("0", 24, Color.WHITE);
        scoreLabel.setTypeface(Typeface.DEFAULT_BOLD);
        scoreLabel.setGravity(Gravity.END);
        top.addView(scoreLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        playPanel.addView(top);

        message = text("", 15, 0xFFB8C2CC);
        message.setPadding(0, dp(6), 0, dp(10));
        message.setMinLines(2);
        playPanel.addView(message);

        LinearLayout buttons = new LinearLayout(this);
        Button recenter = button("RECENTER", 0xFF2A7BD8);
        recenter.setOnClickListener(v -> recenter());
        buttons.addView(recenter, new LinearLayout.LayoutParams(0, dp(64), 2));
        Button settings = button("⚙", 0xFF2A313A);
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1);
        lp.leftMargin = dp(10);
        buttons.addView(settings, lp);
        playPanel.addView(buttons);

        pad = new PadView(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        padLp.topMargin = dp(14);
        playPanel.addView(pad, padLp);
        root.addView(playPanel);

        setContentView(root);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setTextColor(Color.WHITE);
        bt.setTextSize(18);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        bt.setBackground(bg);
        bt.setPadding(dp(18), 0, dp(18), 0);
        return bt;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showConnect() {
        connectPanel.setVisibility(View.VISIBLE);
        ((View) connectPanel.getParent()).setVisibility(View.VISIBLE);
        playPanel.setVisibility(View.GONE);
        startDiscovery();
    }

    private void showPlay() {
        ((View) connectPanel.getParent()).setVisibility(View.GONE);
        playPanel.setVisibility(View.VISIBLE);
        pad.invalidate();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));

        TextView label = text("", 15, Color.WHITE);
        Runnable updateLabel = () -> label.setText(String.format(Locale.US,
                "Aim sensitivity: %s (%.0f° to the edge of the TV)",
                range < 14 ? "high" : range < 26 ? "normal" : "low", range));
        updateLabel.run();
        box.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(32);
        bar.setProgress(Math.round(range) - 8);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                range = 8 + v;
                prefs.edit().putFloat("range", range).apply();
                updateLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(bar);
        TextView hint = text("Sitting far from a small TV? Use higher sensitivity. "
                + "Close to a big one? Lower.", 13, 0xFF9AA5B0);
        hint.setPadding(0, dp(4), 0, dp(16));
        box.addView(hint);

        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Done", null)
                .setNegativeButton("Change TV", (x, y) -> {
                    autoConnect = false;
                    disconnect();
                    showConnect();
                });
        if (rotation != null) {
            d.setNeutralButton(touchpad ? "Aim with motion" : "Aim with touchpad", (x, y) -> {
                touchpad = !touchpad;
                prefs.edit().putBoolean("touchpad", touchpad).apply();
                if (!touchpad) recenter();
                pad.invalidate();
            });
        }
        d.show();
    }

    // ------------------------------------------------------------------ discovery

    private void startDiscovery() {
        if (discovery != null || nsd == null) return;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int err) {
                runOnUiThread(() -> connectStatus.setText("Can't search the network (" + err + "). Type the TV's address below."));
            }
            @Override public void onStopDiscoveryFailed(String type, int err) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                runOnUiThread(() -> {
                    toResolve.add(info);
                    resolveNext();
                });
            }
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        try {
            nsd.discoverServices(Net.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
        }
    }

    private void stopDiscovery() {
        if (discovery == null) return;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (RuntimeException ignored) {
        }
        discovery = null;
        toResolve.clear();
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty()) return;
        resolving = true;
        NsdServiceInfo next = toResolve.poll();
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int err) {
                    runOnUiThread(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override public void onServiceResolved(NsdServiceInfo info) {
                    runOnUiThread(() -> {
                        resolving = false;
                        if (info.getHost() != null) {
                            String target = info.getHost().getHostAddress() + ":" + info.getPort();
                            addFound(target, info.getServiceName());
                        }
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
        }
    }

    private void addFound(String target, String name) {
        if (found.containsKey(target)) return;
        found.put(target, name);
        Button bt = button("📺  " + name + "   " + target.substring(0, target.lastIndexOf(':')), 0xFF2A7BD8);
        bt.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bt.setOnClickListener(v -> connectTo(target, name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.bottomMargin = dp(10);
        foundList.addView(bt, lp);
        connectStatus.setText("Found " + found.size() + (found.size() == 1 ? " TV" : " TVs") + ". Tap one to join.");
        if (autoConnect && link == null) connectTo(target, name);
    }

    // ------------------------------------------------------------------ connection

    private void connectManual() {
        String h = hostInput.getText().toString().trim();
        if (h.isEmpty()) return;
        if (!h.contains(":")) h += ":" + Net.PORT;
        connectTo(h, h);
    }

    private void connectTo(String target, String label) {
        disconnect();
        int colon = target.lastIndexOf(':');
        String host = colon > 0 ? target.substring(0, colon) : target;
        int port = colon > 0 ? Net.parseI(target.substring(colon + 1), Net.PORT) : Net.PORT;
        connectStatus.setText("Connecting to " + label + "…");
        linkTarget = target;
        final RemoteLink[] self = new RemoteLink[1];
        self[0] = new RemoteLink(new RemoteLink.Listener() {
            @Override public void onConnected() {
                if (link != self[0]) return;
                prefs.edit().putString("last", target).apply();
                autoConnect = true;
                stopDiscovery();
                score = 0;
                ammo = -1;
                message.setText("Connected. Point at the target in the middle of the TV and tap RECENTER.");
                updateScore();
                showPlay();
                needRecenter = true;
            }

            @Override public void onLine(String line) {
                if (link == self[0]) handle(line);
            }

            @Override public void onDisconnected(String why) {
                if (link != self[0]) return;
                link = null;
                connectStatus.setText(why + ". Tap a TV or type its address.");
                showConnect();
            }
        });
        link = self[0];
        String name = Build.MODEL == null ? "Phone" : Build.MODEL;
        link.connect(host, port, name);
    }

    private void disconnect() {
        RemoteLink l = link;
        link = null;
        if (l != null) l.close();
    }

    private void handle(String line) {
        String[] p = line.split(" ", 3);
        switch (p[0]) {
            case "WELCOME":
                if (p.length >= 3) {
                    playerColor = Net.parseI(p[2], ORANGE) | 0xFF000000;
                    playerLabel.setText("Player " + p[1]);
                    playerLabel.setTextColor(playerColor);
                    pad.invalidate();
                }
                break;
            case "FULL":
                connectStatus.setText("That game already has 4 players.");
                break;
            case "STAT":
                if (p.length >= 3) {
                    score = Net.parseI(p[1], score);
                    ammo = Net.parseI(p[2], -1);
                    updateScore();
                }
                break;
            case "HIT":
                buzz(new long[]{0, 35, 50, 70});
                message.setText("HIT!  +" + (p.length > 1 ? p[1] : ""));
                break;
            case "EMPTY":
                buzz(new long[]{0, 8, 40, 8});
                message.setText("Out of shells! Wait for the next ducks.");
                break;
            case "MSG":
                message.setText(line.length() > 4 ? line.substring(4) : "");
                break;
            default:
                break;
        }
    }

    private void updateScore() {
        scoreLabel.setText(String.format(Locale.US, "%,d", score));
        pad.setAmmo(ammo);
    }

    // ------------------------------------------------------------------ aiming & firing

    void fire() {
        if (link == null) return;
        buzz(new long[]{0, 22});
        link.fire(aimX, aimY);
        pad.flash();
    }

    private void recenter() {
        if (haveOrientation) {
            yaw0 = yaw;
            pitch0 = pitch;
        }
        needRecenter = false;
        aimX = 0.5f;
        aimY = 0.5f;
        if (link != null) {
            link.aim(aimX, aimY);
            link.send("CAL");
        }
        buzz(new long[]{0, 15});
        pad.invalidate();
    }

    /** Touchpad mode: drag deltas in pixels. */
    void dragAim(float dx, float dy, float padW) {
        aimX = clamp(aimX + dx / (padW * 1.1f), -0.05f, 1.05f);
        aimY = clamp(aimY + dy / (padW * 1.1f * 0.5625f), -0.05f, 1.05f);
        if (link != null) link.aim(aimX, aimY);
        pad.invalidate();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        // Only pass x,y,z,w: some older devices throw on the extra accuracy value.
        int n = Math.min(e.values.length, 4);
        System.arraycopy(e.values, 0, rv, 0, n);
        SensorManager.getRotationMatrixFromVector(rot, rv);
        // Where the phone's top edge (device +Y) points, in world coordinates.
        float fx = rot[1], fy = rot[4], fz = rot[7];
        yaw = (float) Math.toDegrees(Math.atan2(fx, fy));
        pitch = (float) Math.toDegrees(Math.asin(clamp(fz, -1, 1)));
        haveOrientation = true;
        if (needRecenter) {
            yaw0 = yaw;
            pitch0 = pitch;
            needRecenter = false;
        }
        if (touchpad) return;

        float dyaw = yaw - yaw0;
        while (dyaw > 180) dyaw -= 360;
        while (dyaw < -180) dyaw += 360;
        float dpitch = pitch - pitch0;
        float rangeY = range * 0.5625f;
        aimX = clamp(0.5f + dyaw / (2 * range), -0.1f, 1.1f);
        aimY = clamp(0.5f - dpitch / (2 * rangeY), -0.1f, 1.1f);

        long now = System.nanoTime();
        if (link != null && now - lastAimSent > 8_000_000L) {
            lastAimSent = now;
            link.aim(aimX, aimY);
        }
        if (pad != null && now / 33_000_000L != lastPadFrame) {
            lastPadFrame = now / 33_000_000L;
            pad.invalidate();
        }
    }

    private long lastPadFrame;

    @Override
    public void onAccuracyChanged(Sensor s, int a) {}

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) && link != null) {
            if (e.getRepeatCount() == 0) fire();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP) && link != null) return true;
        return super.onKeyUp(code, e);
    }

    @SuppressWarnings("deprecation")
    private void buzz(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    boolean isTouchpad() {
        return touchpad;
    }

    float aimX() {
        return aimX;
    }

    float aimY() {
        return aimY;
    }

    int playerColor() {
        return playerColor;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
