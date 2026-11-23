package app.duckblast;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.view.InputDevice;

/**
 * Reads a game controller's own motion sensor (e.g. a Joy-Con) where Android
 * exposes it to apps (Android 12+, depends on the device), and turns it into
 * "turned right by X radians, tilted up by Y radians".
 *
 * Gravity decides which way is up, so it works however the controller sits in
 * a gun accessory. Which horizontal axis points forward can't be known, so
 * {@link #nextPreset()} cycles through the options.
 */
final class PadMotion implements SensorEventListener {
    interface Sink {
        void onTurn(float yawRightRad, float pitchUpRad);
    }

    private final int deviceId;
    private final Sink sink;
    private final Handler handler;
    private SensorManager sensors;
    private Sensor gyro, accel;
    private final float[] up = {0, 0, 1};
    private boolean haveUp;
    private long lastNs;
    private int preset;

    PadMotion(int deviceId, Sink sink, Handler handler) {
        this.deviceId = deviceId;
        this.sink = sink;
        this.handler = handler;
    }

    /** True if this controller has a motion sensor this app can read. */
    static boolean available(int deviceId) {
        if (Build.VERSION.SDK_INT < 31) return false;
        InputDevice dev = InputDevice.getDevice(deviceId);
        if (dev == null) return false;
        try {
            return dev.getSensorManager().getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean start() {
        if (Build.VERSION.SDK_INT < 31) return false;
        InputDevice dev = InputDevice.getDevice(deviceId);
        if (dev == null) return false;
        try {
            sensors = dev.getSensorManager();
            gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (gyro == null) return false;
            lastNs = 0;
            sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, handler);
            if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    void stop() {
        if (sensors == null) return;
        try {
            sensors.unregisterListener(this);
        } catch (RuntimeException ignored) {
        }
        sensors = null;
    }

    int presetCount() {
        return accel != null ? 4 : 12;
    }

    int nextPreset() {
        preset = (preset + 1) % presetCount();
        return preset;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float n = (float) Math.sqrt(dot(e.values, e.values));
            if (n < 1) return;
            float k = haveUp ? 0.05f : 1f;
            for (int i = 0; i < 3; i++) up[i] += (e.values[i] / n - up[i]) * k;
            normalize(up);
            haveUp = true;
            return;
        }
        if (lastNs == 0) {
            lastNs = e.timestamp;
            return;
        }
        float dt = Math.min(0.05f, (e.timestamp - lastNs) / 1e9f);
        lastNs = e.timestamp;
        float[] w = {e.values[0], e.values[1], e.values[2]};

        float[] u = up;
        if (accel == null) {        // no gravity reading: the preset also guesses "up"
            u = new float[3];
            u[(preset / 4) % 3] = 1;
        }
        int upAxis = largest(u);
        int a1 = (upAxis + 1) % 3, a2 = (upAxis + 2) % 3;
        float[] f = new float[3];   // forward = one of the two roughly horizontal axes
        f[(preset & 1) == 0 ? a1 : a2] = (preset & 2) == 0 ? 1 : -1;
        float d = dot(f, u);
        for (int i = 0; i < 3; i++) f[i] -= d * u[i];
        normalize(f);
        float[] right = cross(f, u);

        sink.onTurn(-dot(w, u) * dt, dot(w, right) * dt);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {}

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(float[] v) {
        float n = (float) Math.sqrt(dot(v, v));
        if (n > 1e-6f) for (int i = 0; i < 3; i++) v[i] /= n;
    }

    private static int largest(float[] v) {
        int best = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(v[i]) > Math.abs(v[best])) best = i;
        return best;
    }
}
