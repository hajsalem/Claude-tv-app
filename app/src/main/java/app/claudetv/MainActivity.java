package app.claudetv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Fullscreen claude.ai for Android TV.
 *
 * Remote controls:
 *   D-pad            move focus (and scroll the chat when there's nothing to move to)
 *   OK               click / open keyboard in the prompt box
 *   Hold OK or Menu  app menu (voice, new chat, pointer mode, text size, reload...)
 *   Blue key         toggle pointer mode (on-screen mouse for things the D-pad can't reach)
 *   Mic / Search     dictate a prompt
 *   Ch+/Ch-, FF/RW   page up / page down
 *   Back             leave the prompt box, go back, then exit
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://claude.ai/new";
    private static final String RECENTS_URL = "https://claude.ai/recents";
    private static final String SETTINGS_URL = "https://claude.ai/settings";
    private static final int REQ_VOICE = 1;
    private static final int REQ_FILE = 2;
    private static final int REQ_MIC_PERMISSION_VOICE = 3;
    private static final int REQ_MIC_PERMISSION_WEB = 4;

    // Desktop-style UA built from the real WebView UA (same Chrome version and
    // CPU, minus the "Android ... wv ... Mobile" bits). Gets the roomy desktop
    // layout and avoids sign-in providers rejecting embedded WebViews, while
    // staying consistent enough with the engine not to look like a bot.
    private String desktopUa;

    private WebView webView;
    private SharedPreferences prefs;
    private String navScript;
    private boolean centerLongPressed;
    private long lastBackPress;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private SpeechRecognizer recognizer;

    // Pointer mode: an on-screen mouse for things the D-pad can't reach
    // (e.g. the Cloudflare "verify you are human" checkbox).
    private CursorView cursor;
    private boolean pointerMode;
    private boolean pointerAuto;
    private float px, py;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        navScript = readAsset("tvnav.js");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUi();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(0x26, 0x26, 0x24));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cursor = new CursorView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(prefs.getInt("textZoom", 100));
        desktopUa = buildDesktopUa(s.getUserAgentString());
        if (prefs.getBoolean("desktopUa", true)) {
            s.setUserAgentString(desktopUa);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "ClaudeTV");
        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            Uri data = getIntent() != null ? getIntent().getData() : null;
            webView.loadUrl(data != null && isClaudeHost(data.getHost()) ? data.toString() : HOME_URL);
            if (!prefs.getBoolean("hintShown", false)) {
                Toast.makeText(this, "Tip: hold OK (or press Menu) for voice input and options",
                        Toast.LENGTH_LONG).show();
                prefs.edit().putBoolean("hintShown", true).apply();
            }
        }
        webView.requestFocus();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;

        if (pointerMode && handlePointerKey(event, code, down, up)) return true;

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // OK goes through our JS so it clicks the focused thing
                // instead of typing Enter (which would send a half-written prompt).
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        centerLongPressed = false;
                    } else if (!centerLongPressed && event.getRepeatCount() >= 8) {
                        centerLongPressed = true;
                        showMenu();
                    }
                } else if (up) {
                    if (!centerLongPressed) activate();
                    centerLongPressed = false;
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (up) showMenu();
                return true;

            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (up) startVoice();
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) js("window.__tvnav && __tvnav.scrollPage('up')");
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) js("window.__tvnav && __tvnav.scrollPage('down')");
                return true;

            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_BUTTON_X:
                if (up) setPointerMode(!pointerMode, false);
                return true;

            case KeyEvent.KEYCODE_PROG_GREEN:
                if (up) webView.loadUrl(HOME_URL);
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (up) handleBack();
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onSearchRequested() {
        startVoice();
        return true;
    }

    private void activate() {
        webView.evaluateJavascript("window.__tvnav ? __tvnav.activate() : 'none'", result -> {
            if (result != null && result.contains("editable")) {
                openKeyboardOnFocusedField();
            } else if (result != null && result.contains("none")) {
                // Script not injected (e.g. error page): fall back to a real Enter.
                webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            }
        });
    }

    private void handleBack() {
        webView.evaluateJavascript("window.__tvnav ? __tvnav.blur() : false", left -> {
            if ("true".equals(left)) {
                hideKeyboard();
                return;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showKeyboard() {
        webView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        final int zoom = prefs.getInt("textZoom", 100);
        final boolean autoSend = prefs.getBoolean("autoSend", false);
        final boolean desktop = prefs.getBoolean("desktopUa", true);
        String[] items = {
                "🎤  Voice input",
                "✏️  New chat",
                "💬  All chats",
                "⚙️  Account settings",
                "⌨️  Jump to prompt box",
                "🖱️  Pointer mode: " + (pointerMode ? "ON" : "OFF"),
                "🔍  Bigger text (now " + zoom + "%)",
                "🔎  Smaller text",
                "📨  Send voice prompts automatically: " + (autoSend ? "ON" : "OFF"),
                "🖥️  Desktop layout: " + (desktop ? "ON" : "OFF"),
                "🔄  Reload",
                "🚪  Exit",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Claude")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: startVoice(); break;
                        case 1: webView.loadUrl(HOME_URL); break;
                        case 2: webView.loadUrl(RECENTS_URL); break;
                        case 3: webView.loadUrl(SETTINGS_URL); break;
                        case 4: js("window.__tvnav && __tvnav.focusInput()");
                            handler.postDelayed(this::openKeyboardOnFocusedField, 150); break;
                        case 5: setPointerMode(!pointerMode, false); break;
                        case 6: setTextZoom(Math.min(zoom + 15, 250)); break;
                        case 7: setTextZoom(Math.max(zoom - 15, 70)); break;
                        case 8:
                            prefs.edit().putBoolean("autoSend", !autoSend).apply();
                            toast("Auto-send " + (!autoSend ? "on" : "off"));
                            break;
                        case 9:
                            prefs.edit().putBoolean("desktopUa", !desktop).apply();
                            webView.getSettings().setUserAgentString(!desktop ? desktopUa : null);
                            webView.reload();
                            break;
                        case 10: webView.reload(); break;
                        case 11: finish(); break;
                    }
                })
                .show();
    }

    private void setTextZoom(int z) {
        prefs.edit().putInt("textZoom", z).apply();
        webView.getSettings().setTextZoom(z);
        toast("Text size " + z + "%");
    }

    // ----------------------------------------------------------------- voice

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Claude…");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            startInAppRecognizer();
        }
    }

    // Fallback for devices without a voice-search activity.
    private void startInAppRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognition on this device");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION_VOICE);
            return;
        }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        final Toast listening = Toast.makeText(this, "🎤 Listening…", Toast.LENGTH_LONG);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { listening.show(); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { listening.cancel(); }
            @Override public void onError(int error) { listening.cancel(); toast("Didn't catch that"); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) insertText(r.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizer.startListening(i);
    }

    private void insertText(String text) {
        boolean autoSend = prefs.getBoolean("autoSend", false);
        js("window.__tvnav && __tvnav.insertText(" + JSONObject.quote(text) + ", " + autoSend + ")");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) insertText(r.get(0));
            }
        } else if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC_PERMISSION_VOICE) {
            if (granted) startInAppRecognizer();
        } else if (requestCode == REQ_MIC_PERMISSION_WEB && pendingWebPermission != null) {
            if (granted) {
                pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }
    }

    // ------------------------------------------------------------- web stuff

    private class Bridge {
        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(MainActivity.this::startVoice);
        }

        @JavascriptInterface
        public void openMenu() {
            runOnUiThread(MainActivity.this::showMenu);
        }
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            // Keep http(s) in the app (a TV may not even have a browser);
            // drop intent://, market:// and friends.
            return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            hideSystemUi();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectNav();
            CookieManager.getInstance().flush();
            checkForChallenge(8);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            // claude.ai is a single-page app; re-check after client-side navigation.
            injectNav();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String html = "<html><body style='background:#262624;color:#eee;font-family:sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                    + "<div style='text-align:center'><h1>Can't reach Claude</h1>"
                    + "<p>" + error.getDescription() + "</p>"
                    + "<button autofocus onclick=\"location.href='" + HOME_URL + "'\" "
                    + "style='font-size:28px;padding:16px 40px;border-radius:12px;border:0;"
                    + "background:#d97757;color:#fff'>Try again</button></div></body></html>";
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                Uri origin = request.getOrigin();
                boolean wantsMic = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                }
                if (!wantsMic || origin == null || !isClaudeHost(origin.getHost())) {
                    request.deny();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingWebPermission = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION_WEB);
                } else {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("No file picker on this TV");
                return false;
            }
        }
    }

    // --------------------------------------------------------- pointer mode

    private static final String FIND_CHALLENGE_JS =
            "window.__tvnav ? JSON.stringify(__tvnav.findChallenge()) : 'null'";

    /** Poll a few times after a load: the Cloudflare widget appears after a moment. */
    private void checkForChallenge(final int tries) {
        webView.evaluateJavascript(FIND_CHALLENGE_JS, result -> {
            JSONObject c = parseJsonResult(result);
            if (c != null) {
                if (!pointerMode) setPointerMode(true, true);
                if (c.has("x")) {
                    float scale = webView.getWidth() / (float) Math.max(1, c.optDouble("vw", 1));
                    movePointer((float) c.optDouble("x") * scale, (float) c.optDouble("y") * scale);
                    if (!c.optBoolean("widget") && tries > 0) {
                        handler.postDelayed(() -> checkForChallenge(tries - 1), 700);
                    }
                } else if (tries > 0) {
                    handler.postDelayed(() -> checkForChallenge(tries - 1), 700);
                }
            } else if (pointerMode && pointerAuto) {
                setPointerMode(false, false);
            } else if (tries > 6) {
                // Widget may not be in the DOM yet right after load.
                handler.postDelayed(() -> checkForChallenge(tries - 1), 700);
            }
        });
    }

    private static JSONObject parseJsonResult(String result) {
        // evaluateJavascript hands back a JSON-encoded JS string, e.g. "\"{...}\"".
        if (result == null || result.equals("null") || result.equals("\"null\"")) return null;
        try {
            String inner = new org.json.JSONArray("[" + result + "]").getString(0);
            if (inner.equals("null")) return null;
            return new JSONObject(inner);
        } catch (Exception e) {
            return null;
        }
    }

    private void setPointerMode(boolean on, boolean auto) {
        pointerMode = on;
        pointerAuto = on && auto;
        cursor.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            if (px == 0 && py == 0) movePointer(webView.getWidth() / 2f, webView.getHeight() / 2f);
            else movePointer(px, py);
            Toast.makeText(this, auto
                    ? "Verification page: move the pointer with the arrows and press OK on the checkbox"
                    : "Pointer mode on: arrows move, OK clicks, Back exits",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void movePointer(float x, float y) {
        px = Math.max(0, Math.min(webView.getWidth() - 1, x));
        py = Math.max(0, Math.min(webView.getHeight() - 1, y));
        cursor.moveTo(px, py);
        // Real hover moves, so the page (and the verification widget) sees a mouse.
        sendMouse(MotionEvent.ACTION_HOVER_MOVE, 0);
    }

    private boolean handlePointerKey(KeyEvent event, int code, boolean down, boolean up) {
        float d = getResources().getDisplayMetrics().density;
        float step = Math.min(8 + event.getRepeatCount() * 3, 40) * d;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (py <= 2) sendMouse(MotionEvent.ACTION_SCROLL, 1.5f);
                    else movePointer(px, py - step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (py >= webView.getHeight() - 3) sendMouse(MotionEvent.ACTION_SCROLL, -1.5f);
                    else movePointer(px, py + step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) movePointer(px - step, py);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) movePointer(px + step, py);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        centerLongPressed = false;
                    } else if (!centerLongPressed && event.getRepeatCount() >= 8) {
                        centerLongPressed = true;
                        showMenu();
                    }
                } else if (up) {
                    if (!centerLongPressed) tapAtPointer();
                    centerLongPressed = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up) {
                    setPointerMode(false, false);
                    toast("Pointer mode off");
                }
                return true;
        }
        return false;
    }

    private void sendMouse(int action, float vscroll) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = px;
        pc.y = py;
        pc.pressure = 0;
        pc.size = 1;
        if (action == MotionEvent.ACTION_SCROLL) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        MotionEvent e = MotionEvent.obtain(t, t, action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        webView.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** A real (trusted) tap at the pointer, which the page can't tell from a finger. */
    private void tapAtPointer() {
        tapAt(px, py);
    }

    /**
     * WebView drops the keyboard again if we just call showSoftInput(): it only
     * keeps it up when the text box was focused by a real tap. So tap it.
     */
    private void openKeyboardOnFocusedField() {
        webView.evaluateJavascript(
                "window.__tvnav ? JSON.stringify(__tvnav.editablePoint()) : 'null'", result -> {
                    JSONObject p = parseJsonResult(result);
                    if (p == null) {
                        showKeyboard();
                        return;
                    }
                    float scale = webView.getWidth() / (float) Math.max(1, p.optDouble("vw", 1));
                    tapAt((float) p.optDouble("x") * scale, (float) p.optDouble("y") * scale);
                    // Belt and braces for WebViews that still don't raise it.
                    handler.postDelayed(() -> {
                        if (!imeVisible()) showKeyboard();
                    }, 600);
                });
    }

    private boolean imeVisible() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets in = webView.getRootWindowInsets();
            return in != null && in.isVisible(android.view.WindowInsets.Type.ime());
        }
        return true; // can't tell on older Android; the tap normally works there
    }

    /** A real (trusted) tap, which the page can't tell from a finger. */
    private void tapAt(final float x, final float y) {
        final long downTime = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        webView.dispatchTouchEvent(downEv);
        downEv.recycle();
        handler.postDelayed(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent upEv = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_UP, x, y, 0);
            upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            webView.dispatchTouchEvent(upEv);
            upEv.recycle();
        }, 70);
    }

    static String buildDesktopUa(String webViewUa) {
        String chrome = "Chrome/130.0.0.0";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(webViewUa);
        if (m.find()) chrome = m.group();
        String cpu = "x86_64";
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) cpu = "aarch64";
        else if (arch.contains("arm")) cpu = "armv8l";
        return "Mozilla/5.0 (X11; Linux " + cpu + ") AppleWebKit/537.36 (KHTML, like Gecko) "
                + chrome + " Safari/537.36";
    }

    private void injectNav() {
        if (navScript != null) webView.evaluateJavascript(navScript, null);
    }

    private void js(String code) {
        webView.evaluateJavascript(code, null);
    }

    private static boolean isClaudeHost(String host) {
        return host != null && (host.equals("claude.ai") || host.endsWith(".claude.ai"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        webView.destroy();
        super.onDestroy();
    }
}
