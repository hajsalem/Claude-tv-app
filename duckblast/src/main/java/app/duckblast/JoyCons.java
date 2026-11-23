package app.duckblast;

import android.content.Context;
import android.hardware.input.InputManager;
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

    /** One connected controller and its motion sensor. */
    private final class Pad {
        final int id;
        final String name;
        PadMotion motion;
        boolean tried;

        Pad(int id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean hasMotion() {
            return motion != null;
        }

        void startSensors() {
            if (tried) return;
            tried = true;
            PadMotion m = new PadMotion(id, (yaw, pitch) -> game.padGyro(id, yaw * PX_PER_RAD, -pitch * PX_PER_RAD), main);
            if (m.start()) motion = m;
        }

        void stopSensors() {
            if (motion != null) motion.stop();
        }
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
                game.pad(GameView.PAD_FIRE, p.id, p.name, p.hasMotion());
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_B:
                ensureJoined(p);
                game.pad(GameView.PAD_RECENTER, p.id, null, false);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                if (p.hasMotion()) {
                    int n = p.motion.nextPreset();
                    game.pad(GameView.PAD_MSG, p.id, "Motion aim setting " + (n + 1) + " of " + p.motion.presetCount(), false);
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
        p.startSensors();
        game.pad(GameView.PAD_JOIN, p.id, p.name, p.hasMotion());
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
}
