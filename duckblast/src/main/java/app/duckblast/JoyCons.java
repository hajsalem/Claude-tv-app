package app.duckblast;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Joy-Cons (or any Bluetooth gamepad) paired directly with the TV.
 *
 * Buttons: ZR / R / ZL / L / A fire, Y / X / B recenter, stick click changes
 * how the motion sensor maps to the screen, + starts, − switches controller mode.
 * Aiming uses the controller's own gyroscope when Android exposes it to apps
 * (Android 12+, depends on the TV), otherwise the stick.
 */
final class JoyCons implements InputManager.InputDeviceListener {
    private static final float PX_PER_RAD = (GameView.W / 2f) / (float) Math.toRadians(20); // ±20° spans the screen

    private final GameView game;
    private final InputManager inputManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<Pad> pads = new SparseArray<>();

    /** One connected controller and its motion sensors. */
    private final class Pad implements SensorEventListener {
        final int id;
        final String name;
        SensorManager sensors;
        Sensor gyro, accel;
        final float[] up = {0, 0, 1};   // low-passed gravity direction, device axes
        boolean haveUp;
        long lastNs;
        int preset;                     // which horizontal device axis points forward (+ sign)

        Pad(int id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean startSensors() {
            if (Build.VERSION.SDK_INT < 31) return false;
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null) return false;
            try {
                sensors = dev.getSensorManager();
                gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (gyro == null) return false;
                sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, main);
                if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, main);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        void stopSensors() {
            if (sensors != null) {
                try {
                    sensors.unregisterListener(this);
                } catch (RuntimeException ignored) {
                }
            }
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float n = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
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

            // "Up" comes from gravity when the controller reports it; without an
            // accelerometer, the preset also guesses which axis is up.
            float[] u = up;
            if (accel == null) {
                u = new float[3];
                u[(preset / 4) % 3] = 1;
            }
            // Forward = one of the two device axes closest to horizontal.
            int upAxis = largest(u);
            int a1 = (upAxis + 1) % 3, a2 = (upAxis + 2) % 3;
            float[] f = new float[3];
            f[(preset & 1) == 0 ? a1 : a2] = (preset & 2) == 0 ? 1 : -1;
            float d = dot(f, u);
            for (int i = 0; i < 3; i++) f[i] -= d * u[i];
            normalize(f);
            float[] right = cross(f, u);

            float yawRight = -dot(w, u);   // turning right
            float pitchUp = dot(w, right); // nose up
            game.padGyro(id, yawRight * dt * PX_PER_RAD, -pitchUp * dt * PX_PER_RAD);
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {}
    }

    JoyCons(Context c, GameView game) {
        this.game = game;
        this.inputManager = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
    }

    void start() {
        inputManager.registerInputDeviceListener(this, main);
    }

    void stop() {
        inputManager.unregisterInputDeviceListener(this);
        for (int i = 0; i < pads.size(); i++) {
            pads.valueAt(i).stopSensors();
            game.pad(GameView.PAD_LEAVE, pads.keyAt(i), null, false);
        }
        pads.clear();
    }

    static boolean isPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private Pad padFor(int id) {
        Pad p = pads.get(id);
        if (p == null) {
            InputDevice dev = InputDevice.getDevice(id);
            p = new Pad(id, dev != null ? dev.getName() : "Joy-Con");
            pads.put(id, p);
        }
        return p;
    }

    /** Returns true if the key came from a gamepad and was handled here. */
    boolean onKeyDown(KeyEvent e) {
        if (!isPad(e.getSource())) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (e.getRepeatCount() == 0) game.toggleMode();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START) {
            if (e.getRepeatCount() == 0) game.remoteStart();
            return true;
        }
        if (!game.isJoyconMode()) return false; // phone mode: let the pad drive the TV-remote crosshair
        if (e.getRepeatCount() != 0) return true;
        Pad p = padFor(e.getDeviceId());
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_A:
                ensureJoined(p);
                game.pad(GameView.PAD_FIRE, p.id, p.name, p.gyro != null);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_B:
                ensureJoined(p);
                game.pad(GameView.PAD_RECENTER, p.id, null, false);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                if (p.gyro != null) {
                    int count = p.accel != null ? 4 : 12;
                    p.preset = (p.preset + 1) % count;
                    game.pad(GameView.PAD_MSG, p.id, "Motion aim setting " + (p.preset + 1) + " of " + count, false);
                    game.pad(GameView.PAD_RECENTER, p.id, null, false);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true; // the stick does the aiming; don't drive the TV-remote crosshair
            default:
                return KeyEvent.isGamepadButton(code);
        }
    }

    private void ensureJoined(Pad p) {
        if (p.sensors == null && p.gyro == null) p.startSensors();
        game.pad(GameView.PAD_JOIN, p.id, p.name, p.gyro != null);
    }

    boolean onMotion(MotionEvent e) {
        if (!isPad(e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        if (!game.isJoyconMode()) return false;
        InputDevice dev = e.getDevice();
        float x = axis(e, dev, MotionEvent.AXIS_X), y = axis(e, dev, MotionEvent.AXIS_Y);
        float rx = axis(e, dev, MotionEvent.AXIS_Z), ry = axis(e, dev, MotionEvent.AXIS_RZ);
        if (Math.abs(rx) + Math.abs(ry) > Math.abs(x) + Math.abs(y)) {
            x = rx;
            y = ry;
        }
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (x == 0 && y == 0) {
            x = hx;
            y = hy;
        }
        game.padStick(e.getDeviceId(), x, y);
        return true;
    }

    private static float axis(MotionEvent e, InputDevice dev, int a) {
        float v = e.getAxisValue(a);
        float flat = 0.15f;
        if (dev != null) {
            InputDevice.MotionRange r = dev.getMotionRange(a, e.getSource());
            if (r != null) flat = Math.max(flat, r.getFlat());
        }
        return Math.abs(v) > flat ? v : 0;
    }

    @Override public void onInputDeviceAdded(int id) {}

    @Override public void onInputDeviceChanged(int id) {}

    @Override
    public void onInputDeviceRemoved(int id) {
        Pad p = pads.get(id);
        if (p == null) return;
        p.stopSensors();
        pads.remove(id);
        game.pad(GameView.PAD_LEAVE, id, null, false);
    }

    // ---- small vector helpers
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
