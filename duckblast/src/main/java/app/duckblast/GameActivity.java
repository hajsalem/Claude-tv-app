package app.duckblast;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * TV side. Hosts the game view and the server phones connect to. The TV remote
 * also works as a (slow) gun: arrows aim, OK fires, Play/Pause starts.
 */
public class GameActivity extends Activity {
    private GameServer server;
    private Sfx sfx;
    private GameView game;
    private SharedPreferences prefs;
    private boolean up, down, left, right;
    private long lastBack;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("duck", MODE_PRIVATE);
        server = new GameServer(this);
        server.start();
        sfx = new Sfx(this);
        game = new GameView(this, server, sfx, prefs.getInt("best", 0));
        setContentView(game);
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putInt("best", game.bestScore()).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        server.stop();
        sfx.release();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: up = true; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down = true; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left = true; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right = true; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (e.getRepeatCount() == 0) game.remoteFire();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (e.getRepeatCount() == 0) game.remoteStart();
                return true;
            default:
                return super.onKeyDown(code, e);
        }
        pushDirection();
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: up = false; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down = false; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left = false; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right = false; break;
            default: return super.onKeyUp(code, e);
        }
        pushDirection();
        return true;
    }

    private void pushDirection() {
        game.remoteDx = (right ? 1 : 0) - (left ? 1 : 0);
        game.remoteDy = (down ? 1 : 0) - (up ? 1 : 0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (game.back()) return; // in a game: back to the title screen
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
        }
    }
}
