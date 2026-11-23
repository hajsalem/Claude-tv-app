package app.duckblast;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The whole game: state, rules and drawing. Runs on its own thread; the world is
 * a fixed 1920x1080 canvas scaled to whatever the TV's surface is.
 *
 * Rules: each round has 10 ducks, released two at a time. Every player gets 3
 * shells per pair. Ducks fly away when time runs out or everyone is out of
 * shells. Hit enough ducks in a round to move on; each round they get faster.
 */
final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    static final float W = 1920, H = 1080;
    static final float HORIZON = 720;      // where the far bank meets the water
    static final float GRASS_TOP = 860;    // ducks rise from / fall into the reeds here
    static final int MAX_PLAYERS = 4;
    static final int DUCKS_PER_ROUND = 10;
    static final int WAVE_SIZE = 2;
    static final int SHELLS = 3;

    static final int[] PLAYER_COLORS = {0xFFFF5A36, 0xFF36C8FF, 0xFFFFD23A, 0xFFC86BFF};

    enum Phase { TITLE, ROUND_INTRO, WAVE, WAVE_END, ROUND_END, GAME_OVER }

    // ---------------------------------------------------------------- model

    static final class Player {
        int index;              // 0..3, decides colour and P-number
        int color;
        String name;
        GameServer.Client client; // null for the TV remote player and Joy-Cons
        int padId = -1;           // input device id for a Joy-Con player
        boolean padGyro;          // Joy-Con aims with its motion sensor (else its stick)
        float tx = W / 2, ty = H / 2; // target position (world)
        float x = W / 2, y = H / 2;   // smoothed, drawn position
        int score, ammo = SHELLS, roundHits;
        boolean calibrated;
        float lastSeen;         // game time of last aim update, for idle fade

        void send(String line) {
            if (client != null) client.send(line);
        }
    }

    static final int D_FLY = 0, D_HIT = 1, D_FALL = 2, D_ESCAPE = 3, D_GONE = 4;

    static final class Duck {
        int kind;               // 0 mallard, 1 brown, 2 golden
        int slot = -1;          // tally slot this round, -1 for practice ducks
        int state = D_FLY;
        float x, y, vx, vy, speed;
        float flap, turnIn, stateTime, spin, quackIn;
        boolean risen;
        Player shooter;
        boolean faceLeft;
    }

    static final class Popup { float x, y, t; String text; int color; }
    static final class Feather { float x, y, vx, vy, rot, vrot, life; int color; }
    static final class Ring { float x, y, t; int color; }
    static final class Cloud { float x, y, s, v; }
    static final class Reed { float x, h, phase, w; boolean cattail; }

    private final GameServer server;
    private final Sfx sfx;
    private final Random rnd = new Random();

    private final List<Player> players = new ArrayList<>();
    private final List<Duck> ducks = new ArrayList<>();
    private final List<Popup> popups = new ArrayList<>();
    private final List<Feather> feathers = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final Cloud[] clouds = new Cloud[7];
    private final Reed[] reeds = new Reed[70];
    private final float[] stars = new float[120];

    private Phase phase = Phase.TITLE;
    private float phaseTime, time, flash;
    private int round = 1, nextSlot;
    private final int[] tally = new int[DUCKS_PER_ROUND]; // 0 pending, 1 hit, 2 escaped
    private boolean roundPassed;
    private String banner;
    private float bannerTime;
    private int bestScore;

    // ---- controller mode: phones over Wi-Fi, or Joy-Cons paired to the TV
    private volatile boolean joyconMode;
    private volatile boolean modeToggle;

    static final int PAD_JOIN = 1, PAD_FIRE = 2, PAD_RECENTER = 3, PAD_LEAVE = 4, PAD_MSG = 5;

    static final class PadEvent {
        final int type, id;
        final String text;
        final boolean gyro;

        PadEvent(int type, int id, String text, boolean gyro) {
            this.type = type;
            this.id = id;
            this.text = text;
            this.gyro = gyro;
        }
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<PadEvent> padEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, float[]> padSticks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object gyroLock = new Object();
    private final java.util.HashMap<Integer, float[]> gyroMoves = new java.util.HashMap<>();

    // TV-remote crosshair (works without a phone)
    private Player remote;
    volatile int remoteDx, remoteDy;
    private float remoteHeld;
    private volatile boolean remoteFire, remoteStart;

    // ---------------------------------------------------------------- drawing state

    private Thread thread;
    private volatile boolean running;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Path farHill, nearHill, grass;
    private Shader[] skies = new Shader[3];
    private Shader water, vignette;
    private String address;
    private float addrRefresh;

    GameView(Context c, GameServer server, Sfx sfx, int best, boolean joycons) {
        super(c);
        this.joyconMode = joycons;
        this.server = server;
        this.sfx = sfx;
        this.bestScore = best;
        getHolder().addCallback(this);
        setFocusable(true);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        buildScenery();
        enterTitle();
    }

    int bestScore() {
        return bestScore;
    }

    // ---------------------------------------------------------------- thread

    @Override public void surfaceCreated(SurfaceHolder h) {
        running = true;
        thread = new Thread(this, "duck-game");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        running = false;
        try {
            if (thread != null) thread.join(500);
        } catch (InterruptedException ignored) {
        }
    }

    @Override public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            update(dt);
            SurfaceHolder h = getHolder();
            Canvas c = null;
            try {
                c = Build.VERSION.SDK_INT >= 26 ? h.lockHardwareCanvas() : h.lockCanvas();
                if (c != null) render(c);
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) {
                    try {
                        h.unlockCanvasAndPost(c);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            long spent = System.nanoTime() - now;
            if (spent < 8_000_000L) { // vsync normally paces us; this is a backstop
                try {
                    Thread.sleep((8_000_000L - spent) / 1_000_000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- remote input (UI thread)

    void remoteFire() {
        remoteFire = true;
    }

    boolean isJoyconMode() {
        return joyconMode;
    }

    /** Switch between phone and Joy-Con mode (only takes effect on the title / game-over screen). */
    void toggleMode() {
        modeToggle = true;
    }

    void pad(int type, int id, String text, boolean gyro) {
        padEvents.add(new PadEvent(type, id, text, gyro));
    }

    /** Joy-Con stick position, -1..1 on each axis. */
    void padStick(int id, float x, float y) {
        padSticks.put(id, new float[]{x, y});
    }

    /** Joy-Con motion since the last call, already converted to screen pixels. */
    void padGyro(int id, float dxPx, float dyPx) {
        synchronized (gyroLock) {
            float[] a = gyroMoves.get(id);
            if (a == null) gyroMoves.put(id, new float[]{dxPx, dyPx});
            else {
                a[0] += dxPx;
                a[1] += dyPx;
            }
        }
    }

    /** Play/Pause or Menu on the TV remote: start from the title screen. */
    void remoteStart() {
        remoteStart = true;
    }

    /** Back on the remote. Returns true if the game handled it (i.e. don't exit). */
    boolean back() {
        if (phase == Phase.TITLE) return false;
        backPressed = true;
        return true;
    }

    private volatile boolean backPressed;

    // ---------------------------------------------------------------- update

    private void update(float dt) {
        time += dt;
        phaseTime += dt;
        flash = Math.max(0, flash - dt * 4);
        bannerTime -= dt;

        drainNetwork();
        drainPads(dt);
        if (modeToggle) {
            modeToggle = false;
            if (phase == Phase.TITLE || phase == Phase.GAME_OVER) switchMode();
        }
        updateRemote(dt);
        if (backPressed) {
            backPressed = false;
            enterTitle();
        }
        if (remoteStart) {
            remoteStart = false;
            if (phase == Phase.TITLE || phase == Phase.GAME_OVER) startGame();
        }

        for (Player pl : players) {
            float k = pl.client == null ? 1 : Math.min(1, dt * 28);
            pl.x += (pl.tx - pl.x) * k;
            pl.y += (pl.ty - pl.y) * k;
        }

        for (Cloud c : clouds) {
            c.x += c.v * dt;
            if (c.x > W + 300) c.x = -300;
        }

        updateDucks(dt);
        updateEffects(dt);

        switch (phase) {
            case TITLE:
            case GAME_OVER:
                // practice ducks keep the screen alive and let players test their aim
                int practice = 0;
                for (Duck d : ducks) if (d.state != D_GONE) practice++;
                if (practice < 2 && rnd.nextFloat() < dt * 0.8f) spawnDuck(-1);
                break;
            case ROUND_INTRO:
                if (phaseTime > 2.2f) startWave();
                break;
            case WAVE:
                boolean anyFlying = false, allGone = true;
                for (Duck d : ducks) {
                    if (d.state == D_FLY) anyFlying = true;
                    if (d.state != D_GONE) allGone = false;
                }
                if (anyFlying && (phaseTime > waveLimit() || everyoneEmpty())) escapeAll();
                if (allGone) setPhase(Phase.WAVE_END);
                break;
            case WAVE_END:
                if (phaseTime > 1.1f) {
                    if (nextSlot >= DUCKS_PER_ROUND) endRound();
                    else startWave();
                }
                break;
            case ROUND_END:
                if (phaseTime > 3.6f) {
                    if (roundPassed) {
                        round++;
                        startRound();
                    } else {
                        gameOver();
                    }
                }
                break;
        }

        refreshAddress(dt);
    }

    private void drainNetwork() {
        GameServer.Event e;
        while ((e = server.events.poll()) != null) {
            Player pl = find(e.client);
            switch (e.type) {
                case GameServer.JOIN:
                    if (pl != null) break;
                    if (joyconMode) {
                        e.client.send("BYE This TV is set to Joy-Con mode. Switch it to Phone mode on the TV's title screen");
                        e.client.close();
                        break;
                    }
                    int idx = freeIndex();
                    if (idx < 0) {
                        e.client.send("FULL");
                        e.client.close();
                        break;
                    }
                    pl = new Player();
                    pl.index = idx;
                    pl.color = PLAYER_COLORS[idx];
                    pl.client = e.client;
                    pl.name = e.text == null || e.text.isEmpty() ? "Phone" : e.text;
                    pl.ammo = phase == Phase.WAVE ? 0 : SHELLS;
                    pl.lastSeen = time;
                    players.add(pl);
                    pl.send("WELCOME " + (idx + 1) + " " + pl.color);
                    pl.send(phase == Phase.TITLE
                            ? "MSG Point at the target on the TV and tap RECENTER"
                            : "MSG Joined! You get shells on the next pair of ducks");
                    sendStat(pl);
                    showBanner("P" + (idx + 1) + " " + pl.name + " joined");
                    sfx.play(sfx.ding);
                    break;
                case GameServer.AIM:
                case GameServer.FIRE:
                    if (pl == null) break;
                    pl.tx = clamp(e.x, -0.05f, 1.05f) * W;
                    pl.ty = clamp(e.y, -0.05f, 1.05f) * H;
                    pl.lastSeen = time;
                    if (e.type == GameServer.FIRE) {
                        pl.x = pl.tx;
                        pl.y = pl.ty;
                        fire(pl, pl.tx, pl.ty);
                    }
                    break;
                case GameServer.CAL:
                    if (pl != null) {
                        pl.calibrated = true;
                        pl.send("MSG Aim set. Shoot the START sign to play!");
                    }
                    break;
                case GameServer.LEAVE:
                    if (pl != null) {
                        players.remove(pl);
                        showBanner("P" + (pl.index + 1) + " left");
                    }
                    break;
            }
        }
    }

    private Player findPad(int id) {
        for (Player pl : players) if (pl.padId == id) return pl;
        return null;
    }

    private Player joinPad(int id, String name, boolean gyro) {
        Player pl = findPad(id);
        if (pl != null || !joyconMode) return pl;
        int idx = freeIndex();
        if (idx < 0) {
            showBanner("Already 4 players");
            return null;
        }
        pl = new Player();
        pl.index = idx;
        pl.color = PLAYER_COLORS[idx];
        pl.padId = id;
        pl.padGyro = gyro;
        pl.name = name == null || name.isEmpty() ? "Joy-Con" : name;
        pl.calibrated = !gyro;
        pl.ammo = phase == Phase.WAVE ? 0 : SHELLS;
        pl.lastSeen = time;
        players.add(pl);
        showBanner("P" + (idx + 1) + " Joy-Con joined" + (gyro ? ": aim at the target, press Y" : " (aims with the stick)"));
        sfx.play(sfx.ding);
        return pl;
    }

    private void drainPads(float dt) {
        PadEvent e;
        while ((e = padEvents.poll()) != null) {
            Player pl = findPad(e.id);
            switch (e.type) {
                case PAD_JOIN:
                    joinPad(e.id, e.text, e.gyro);
                    break;
                case PAD_FIRE:
                    if (pl == null) pl = joinPad(e.id, e.text, e.gyro);
                    if (pl != null) {
                        pl.lastSeen = time;
                        fire(pl, pl.x, pl.y);
                    }
                    break;
                case PAD_RECENTER:
                    if (pl != null) {
                        pl.tx = pl.x = W / 2;
                        pl.ty = pl.y = H / 2;
                        pl.calibrated = true;
                        pl.lastSeen = time;
                    }
                    break;
                case PAD_LEAVE:
                    if (pl != null) {
                        players.remove(pl);
                        showBanner("P" + (pl.index + 1) + " Joy-Con disconnected");
                    }
                    break;
                case PAD_MSG:
                    showBanner(e.text);
                    break;
            }
        }
        for (Player pl : players) {
            if (pl.padId < 0) continue;
            float[] st = padSticks.get(pl.padId);
            if (st != null && (st[0] != 0 || st[1] != 0)) {
                pl.tx += st[0] * 1100 * dt;
                pl.ty += st[1] * 1100 * dt;
                pl.lastSeen = time;
            }
            synchronized (gyroLock) {
                float[] g = gyroMoves.remove(pl.padId);
                if (g != null) {
                    pl.tx += g[0];
                    pl.ty += g[1];
                    if (Math.abs(g[0]) + Math.abs(g[1]) > 0.5f) pl.lastSeen = time;
                }
            }
            pl.tx = clamp(pl.tx, -40, W + 40);
            pl.ty = clamp(pl.ty, -40, H + 40);
            pl.x = pl.tx;
            pl.y = pl.ty;
        }
    }

    private void switchMode() {
        joyconMode = !joyconMode;
        for (Iterator<Player> it = players.iterator(); it.hasNext(); ) {
            Player pl = it.next();
            if (joyconMode && pl.client != null) {
                pl.client.send("BYE This TV switched to Joy-Con mode");
                pl.client.close();
                it.remove();
            } else if (!joyconMode && pl.padId >= 0) {
                it.remove();
            }
        }
        showBanner(joyconMode ? "Joy-Con mode" : "Phone mode");
        sfx.play(sfx.ding);
    }

    // Mode button on the title screen (top right): shoot it or press Menu / minus to switch.
    private static final float MODE_X = W - 330, MODE_Y = 34, MODE_W = 300, MODE_H = 64;

    private boolean modeContains(float x, float y) {
        return x > MODE_X - 20 && x < MODE_X + MODE_W + 20 && y > MODE_Y - 20 && y < MODE_Y + MODE_H + 20;
    }

    private void updateRemote(float dt) {
        int dx = remoteDx, dy = remoteDy;
        if ((dx != 0 || dy != 0 || remoteFire) && remote == null) {
            int idx = freeIndex();
            if (idx >= 0) {
                remote = new Player();
                remote.index = idx;
                remote.color = PLAYER_COLORS[idx];
                remote.name = "TV remote";
                remote.calibrated = true;
                remote.ammo = phase == Phase.WAVE ? 0 : SHELLS;
                players.add(remote);
                showBanner("P" + (idx + 1) + " TV remote joined");
            }
        }
        if (remote == null) {
            remoteFire = false;
            return;
        }
        if (dx != 0 || dy != 0) {
            remoteHeld += dt;
            float speed = remoteHeld > 0.35f ? 1500 : 700;
            remote.tx = clamp(remote.tx + dx * speed * dt, 0, W);
            remote.ty = clamp(remote.ty + dy * speed * dt, 0, H);
            remote.lastSeen = time;
        } else {
            remoteHeld = 0;
        }
        if (remoteFire) {
            remoteFire = false;
            remote.lastSeen = time;
            fire(remote, remote.x, remote.y);
        }
    }

    private void updateDucks(float dt) {
        float top = 70;
        for (Iterator<Duck> it = ducks.iterator(); it.hasNext(); ) {
            Duck d = it.next();
            d.stateTime += dt;
            switch (d.state) {
                case D_FLY:
                    d.flap += dt * (11 + d.speed / 70);
                    d.x += d.vx * dt;
                    d.y += d.vy * dt;
                    d.turnIn -= dt;
                    if (d.y < HORIZON - 80) d.risen = true;
                    if (d.turnIn <= 0) steer(d);
                    if (d.x < 70) d.vx = Math.abs(d.vx);
                    if (d.x > W - 70) d.vx = -Math.abs(d.vx);
                    if (d.y < top) d.vy = Math.abs(d.vy);
                    if (d.risen && d.y > HORIZON - 60) d.vy = -Math.abs(d.vy);
                    if (Math.abs(d.vx) > 20) d.faceLeft = d.vx < 0;
                    d.quackIn -= dt;
                    if (d.quackIn <= 0) {
                        d.quackIn = 1.5f + rnd.nextFloat() * 3f;
                        sfx.play(sfx.quack, 0.55f, 0.9f + rnd.nextFloat() * 0.25f + d.kind * 0.06f);
                    }
                    break;
                case D_HIT:
                    if (d.stateTime > 0.45f) {
                        d.state = D_FALL;
                        d.stateTime = 0;
                        d.vy = 60;
                        sfx.play(sfx.fall, 0.7f, 1f);
                    }
                    break;
                case D_FALL:
                    d.vy += 1500 * dt;
                    d.y += d.vy * dt;
                    d.spin += dt * 540;
                    if (d.y > GRASS_TOP + 60) {
                        d.state = D_GONE;
                        sfx.play(sfx.thud, 0.9f, 1f);
                        splash(d.x, GRASS_TOP + 20);
                    }
                    break;
                case D_ESCAPE:
                    d.flap += dt * 22;
                    d.vy = Math.max(d.vy - 900 * dt, -d.speed * 1.7f);
                    d.x += d.vx * dt;
                    d.y += d.vy * dt;
                    if (d.y < -120 || d.x < -150 || d.x > W + 150) d.state = D_GONE;
                    break;
                case D_GONE:
                    if (d.slot < 0 || phase != Phase.WAVE) it.remove();
                    break;
            }
        }
        // Practice ducks: drift away after a while so the title screen keeps changing.
        if (phase == Phase.TITLE || phase == Phase.GAME_OVER) {
            for (Duck d : ducks) {
                if (d.state == D_FLY && d.stateTime > 9) escape(d);
            }
        }
    }

    private void updateEffects(float dt) {
        for (Iterator<Popup> it = popups.iterator(); it.hasNext(); ) {
            Popup q = it.next();
            q.t += dt;
            q.y -= 60 * dt;
            if (q.t > 1.4f) it.remove();
        }
        for (Iterator<Feather> it = feathers.iterator(); it.hasNext(); ) {
            Feather f = it.next();
            f.life -= dt;
            f.vx *= (1 - dt * 1.8f);
            f.vy = f.vy * (1 - dt * 1.8f) + 220 * dt;
            f.x += f.vx * dt + (float) Math.sin(f.life * 6 + f.rot) * 30 * dt;
            f.y += f.vy * dt;
            f.rot += f.vrot * dt;
            if (f.life <= 0) it.remove();
        }
        for (Iterator<Ring> it = rings.iterator(); it.hasNext(); ) {
            Ring r = it.next();
            r.t += dt;
            if (r.t > 0.35f) it.remove();
        }
    }

    // ---------------------------------------------------------------- rules

    private void enterTitle() {
        setPhase(Phase.TITLE);
        ducks.clear();
        round = 1;
        for (Player pl : players) {
            pl.ammo = SHELLS;
            sendStat(pl);
        }
    }

    private void startGame() {
        round = 1;
        ducks.clear();
        for (Player pl : players) {
            pl.score = 0;
            pl.send("MSG Get ready!");
        }
        startRound();
    }

    private void startRound() {
        ducks.clear();
        nextSlot = 0;
        for (int i = 0; i < tally.length; i++) tally[i] = 0;
        for (Player pl : players) pl.roundHits = 0;
        setPhase(Phase.ROUND_INTRO);
        sfx.play(sfx.fanfare, 0.8f, 1f);
    }

    private void startWave() {
        ducks.clear();
        for (int i = 0; i < WAVE_SIZE && nextSlot < DUCKS_PER_ROUND; i++) spawnDuck(nextSlot++);
        for (Player pl : players) {
            pl.ammo = SHELLS;
            sendStat(pl);
        }
        setPhase(Phase.WAVE);
    }

    private void endRound() {
        int hits = hitsThisRound();
        roundPassed = hits >= required();
        if (roundPassed && hits == DUCKS_PER_ROUND) {
            for (Player pl : players) {
                if (pl.roundHits > 0) {
                    pl.score += 5000 * round;
                    sendStat(pl);
                    pl.send("MSG PERFECT ROUND! +" + (5000 * round));
                }
            }
        }
        for (Player pl : players) {
            pl.send(roundPassed ? "MSG Round " + round + " cleared!" : "MSG Round failed");
        }
        sfx.play(roundPassed ? sfx.fanfare : sfx.fail, 0.9f, 1f);
        setPhase(Phase.ROUND_END);
    }

    private void gameOver() {
        ducks.clear();
        for (Player pl : players) {
            bestScore = Math.max(bestScore, pl.score);
            pl.ammo = SHELLS;
            sendStat(pl);
            pl.send("MSG Game over - " + fmt(pl.score) + " pts. Shoot PLAY AGAIN");
        }
        setPhase(Phase.GAME_OVER);
    }

    private int required() {
        return Math.min(9, 5 + (round + 1) / 2);   // 6,6,7,7,8,8,9...
    }

    private float waveLimit() {
        return Math.max(4.2f, 8f - (round - 1) * 0.4f);
    }

    private int hitsThisRound() {
        int n = 0;
        for (int t : tally) if (t == 1) n++;
        return n;
    }

    private boolean everyoneEmpty() {
        if (players.isEmpty()) return false;
        for (Player pl : players) if (pl.ammo > 0) return false;
        return true;
    }

    private void fire(Player pl, float x, float y) {
        Ring r = new Ring();
        r.x = x;
        r.y = y;
        r.color = pl.color;
        rings.add(r);

        boolean practice = phase == Phase.TITLE || phase == Phase.GAME_OVER;

        if (practice && modeContains(x, y)) {
            sfx.play(sfx.shot);
            switchMode();
            return;
        }
        if (practice && signContains(x, y) && phaseTime > 1f) {
            sfx.play(sfx.shot);
            flash = 0.5f;
            pl.calibrated = true;
            startGame();
            return;
        }

        if (phase == Phase.WAVE) {
            if (pl.ammo <= 0) {
                sfx.play(sfx.click);
                pl.send("EMPTY");
                return;
            }
            pl.ammo--;
        }
        sfx.play(sfx.shot, 1f, 0.95f + rnd.nextFloat() * 0.1f);
        flash = 0.35f;

        Duck best = null;
        float bestD = Float.MAX_VALUE;
        float radius = 78 + (practice ? 15 : 0);
        for (Duck d : ducks) {
            if (d.state != D_FLY && d.state != D_ESCAPE) continue;
            float dx = d.x - x, dy = d.y - y;
            float dist = dx * dx + dy * dy;
            if (dist < radius * radius && dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        if (best != null) hit(best, pl, practice);
        sendStat(pl);
    }

    private void hit(Duck d, Player pl, boolean practice) {
        d.state = D_HIT;
        d.stateTime = 0;
        d.shooter = pl;
        sfx.play(sfx.quack, 0.9f, 1.35f);
        burst(d.x, d.y, d.kind);
        Popup q = new Popup();
        q.x = d.x;
        q.y = d.y - 70;
        q.color = pl.color;
        if (practice) {
            q.text = "NICE!";
        } else {
            int pts = points(d.kind);
            pl.score += pts;
            pl.roundHits++;
            if (d.slot >= 0) tally[d.slot] = 1;
            q.text = fmt(pts);
            pl.send("HIT " + pts);
        }
        popups.add(q);
    }

    private int points(int kind) {
        int base = kind == 2 ? 2500 : kind == 1 ? 1000 : 500;
        return base + (round - 1) * (kind == 2 ? 300 : 100);
    }

    private void escapeAll() {
        boolean any = false;
        for (Duck d : ducks) {
            if (d.state == D_FLY) {
                escape(d);
                if (d.slot >= 0) tally[d.slot] = 2;
                any = true;
            }
        }
        if (any) {
            showBanner("FLEW AWAY!");
            sfx.play(sfx.flap, 0.8f, 1f);
            sfx.play(sfx.quack, 0.7f, 0.8f);
        }
    }

    private void escape(Duck d) {
        d.state = D_ESCAPE;
        d.stateTime = 0;
        d.vx = (d.x < W / 2 ? -1 : 1) * d.speed * 0.25f;
        d.vy = -d.speed * 0.4f;
    }

    private void spawnDuck(int slot) {
        Duck d = new Duck();
        d.slot = slot;
        float r = rnd.nextFloat();
        d.kind = r < 0.08f + round * 0.005f ? 2 : r < 0.4f ? 1 : 0;
        float base = slot < 0 ? 230 : 270 + (round - 1) * 42;
        d.speed = Math.min(820, base * (d.kind == 2 ? 1.3f : d.kind == 1 ? 1.12f : 1f));
        d.x = 250 + rnd.nextFloat() * (W - 500);
        d.y = GRASS_TOP + 20;
        double a = Math.toRadians(-60 - rnd.nextFloat() * 60); // leave the reeds upward
        d.vx = (float) Math.cos(a) * d.speed;
        d.vy = (float) Math.sin(a) * d.speed;
        d.faceLeft = d.vx < 0;
        d.turnIn = 0.9f + rnd.nextFloat();
        d.quackIn = rnd.nextFloat() * 1.5f;
        d.flap = rnd.nextFloat() * 6;
        ducks.add(d);
    }

    private void steer(Duck d) {
        d.turnIn = 0.5f + rnd.nextFloat() * (1.3f - Math.min(0.6f, round * 0.05f));
        double a;
        if (d.y > HORIZON - 220) a = Math.toRadians(-30 - rnd.nextFloat() * 120);   // head up
        else if (d.y < 220) a = Math.toRadians(20 + rnd.nextFloat() * 140);         // head down
        else a = rnd.nextFloat() * Math.PI * 2;
        // mostly horizontal flight looks more natural
        d.vx = (float) Math.cos(a) * d.speed;
        d.vy = (float) Math.sin(a) * d.speed * 0.75f;
    }

    private void setPhase(Phase ph) {
        phase = ph;
        phaseTime = 0;
    }

    private void showBanner(String s) {
        banner = s;
        bannerTime = 1.8f;
    }

    private void sendStat(Player pl) {
        boolean unlimited = phase != Phase.WAVE;
        pl.send("STAT " + pl.score + " " + (unlimited ? -1 : pl.ammo));
    }

    private Player find(GameServer.Client c) {
        if (c == null) return null;
        for (Player pl : players) if (pl.client == c) return pl;
        return null;
    }

    private int freeIndex() {
        outer:
        for (int i = 0; i < MAX_PLAYERS; i++) {
            for (Player pl : players) if (pl.index == i) continue outer;
            return i;
        }
        return -1;
    }

    private void burst(float x, float y, int kind) {
        int[] cols = kind == 2 ? new int[]{0xFFFFE27A, 0xFFF2C230, 0xFFFFFFFF}
                : kind == 1 ? new int[]{0xFF8B5E3C, 0xFFC49A6C, 0xFFE8D8C0}
                : new int[]{0xFFA79E8B, 0xFF1E7A44, 0xFFE8E4D8};
        for (int i = 0; i < 16; i++) {
            Feather f = new Feather();
            f.x = x;
            f.y = y;
            double a = rnd.nextFloat() * Math.PI * 2;
            float s = 150 + rnd.nextFloat() * 350;
            f.vx = (float) Math.cos(a) * s;
            f.vy = (float) Math.sin(a) * s - 100;
            f.rot = rnd.nextFloat() * 360;
            f.vrot = (rnd.nextFloat() - 0.5f) * 600;
            f.life = 1.2f + rnd.nextFloat() * 0.8f;
            f.color = cols[rnd.nextInt(cols.length)];
            feathers.add(f);
        }
    }

    private void splash(float x, float y) {
        for (int i = 0; i < 10; i++) {
            Feather f = new Feather();
            f.x = x + (rnd.nextFloat() - 0.5f) * 40;
            f.y = y;
            f.vx = (rnd.nextFloat() - 0.5f) * 260;
            f.vy = -200 - rnd.nextFloat() * 260;
            f.life = 0.6f;
            f.color = 0xCCBFE8FF;
            feathers.add(f);
        }
    }

    // Sign you shoot to start (title / game over).
    private static final float SIGN_X = W / 2, SIGN_Y = 745, SIGN_W = 440, SIGN_H = 130;

    private boolean signContains(float x, float y) {
        return Math.abs(x - SIGN_X) < SIGN_W / 2 + 25 && Math.abs(y - SIGN_Y) < SIGN_H / 2 + 25;
    }

    private void refreshAddress(float dt) {
        addrRefresh -= dt;
        if (addrRefresh <= 0) {
            addrRefresh = 5;
            String ip = GameServer.localAddress();
            int port = server.port();
            address = ip == null ? null : port == 0 || port == Net.PORT ? ip : ip + ":" + port;
        }
    }

    // ---------------------------------------------------------------- rendering

    private void buildScenery() {
        Random r = new Random(42);
        for (int i = 0; i < clouds.length; i++) {
            Cloud c = new Cloud();
            c.x = r.nextFloat() * W;
            c.y = 80 + r.nextFloat() * 380;
            c.s = 0.6f + r.nextFloat() * 0.8f;
            c.v = 8 + r.nextFloat() * 18;
            clouds[i] = c;
        }
        for (int i = 0; i < reeds.length; i++) {
            Reed d = new Reed();
            d.x = r.nextFloat() * W;
            d.h = 70 + r.nextFloat() * 150;
            d.phase = r.nextFloat() * 6.28f;
            d.w = 4 + r.nextFloat() * 5;
            d.cattail = r.nextFloat() < 0.3f;
            reeds[i] = d;
        }
        for (int i = 0; i < stars.length; i += 2) {
            stars[i] = r.nextFloat() * W;
            stars[i + 1] = r.nextFloat() * (HORIZON - 150);
        }

        farHill = new Path();
        farHill.moveTo(0, HORIZON);
        farHill.lineTo(0, 560);
        farHill.cubicTo(300, 470, 520, 600, 800, 540);
        farHill.cubicTo(1050, 480, 1250, 470, 1500, 550);
        farHill.cubicTo(1700, 610, 1820, 520, W, 520);
        farHill.lineTo(W, HORIZON);
        farHill.close();

        nearHill = new Path();
        nearHill.moveTo(0, HORIZON);
        nearHill.lineTo(0, 650);
        nearHill.cubicTo(250, 600, 450, 620, 700, 660);
        nearHill.cubicTo(950, 700, 1150, 610, 1400, 630);
        nearHill.cubicTo(1650, 650, 1800, 690, W, 640);
        nearHill.lineTo(W, HORIZON);
        nearHill.close();

        grass = new Path();
        grass.moveTo(0, H);
        grass.lineTo(0, GRASS_TOP + 30);
        for (int x = 0; x <= W; x += 24) {
            grass.lineTo(x + 12, GRASS_TOP - 10 - r.nextFloat() * 40);
            grass.lineTo(x + 24, GRASS_TOP + 20 + r.nextFloat() * 10);
        }
        grass.lineTo(W, H);
        grass.close();

        int[][] sky = {
                {0xFF2F7FD6, 0xFF8FC8F2, 0xFFD8F0FF},   // day
                {0xFF3A3F8F, 0xFFC0607A, 0xFFFFB063},   // sunset
                {0xFF050B26, 0xFF14215A, 0xFF3B4A8A},   // night
        };
        for (int i = 0; i < 3; i++) {
            skies[i] = new LinearGradient(0, 0, 0, HORIZON, sky[i], new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP);
        }
        water = new LinearGradient(0, HORIZON, 0, H, 0xFF3C7FA8, 0xFF1D4E6E, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(W / 2, H / 2, W * 0.75f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP);
    }

    private int timeOfDay() {
        return phase == Phase.TITLE ? 0 : (round - 1) % 3;
    }

    private void render(Canvas c) {
        float sw = c.getWidth(), sh = c.getHeight();
        float scale = Math.min(sw / W, sh / H);
        c.drawColor(Color.BLACK);
        c.save();
        c.translate((sw - W * scale) / 2, (sh - H * scale) / 2);
        c.scale(scale, scale);
        c.clipRect(0, 0, W, H);

        int tod = timeOfDay();
        drawBackground(c, tod);
        for (Duck d : ducks) if (d.state != D_GONE) drawDuck(c, d);
        drawForeground(c, tod);
        drawEffects(c);

        if (phase == Phase.TITLE || phase == Phase.GAME_OVER) drawSign(c);
        drawOverlay(c);
        drawHud(c);
        for (Player pl : players) drawCrosshair(c, pl);

        if (flash > 0) {
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (flash * 90), 255, 250, 230));
            c.drawRect(0, 0, W, H, p);
        }
        c.restore();
    }

    private void drawBackground(Canvas c, int tod) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(skies[tod]);
        c.drawRect(0, 0, W, HORIZON, p);
        p.setShader(null);

        if (tod == 2) {
            p.setColor(0xCCFFFFFF);
            for (int i = 0; i < stars.length; i += 2) {
                float tw = 1.2f + (float) Math.sin(time * 2 + i) * 0.8f;
                c.drawCircle(stars[i], stars[i + 1], tw, p);
            }
            p.setColor(0xFFF4F1D8);
            c.drawCircle(1550, 170, 60, p);
            p.setColor(0xFF14215A);
            c.drawCircle(1575, 155, 52, p);
        } else {
            float sy = tod == 0 ? 160 : 520;
            p.setColor(tod == 0 ? 0x55FFF6C0 : 0x66FFD08A);
            c.drawCircle(1500, sy, 110, p);
            p.setColor(tod == 0 ? 0xFFFFF3B0 : 0xFFFFC266);
            c.drawCircle(1500, sy, 70, p);
        }

        int cloudCol = tod == 0 ? 0xF2FFFFFF : tod == 1 ? 0xE0FFD6C8 : 0x803A4A80;
        for (Cloud cl : clouds) {
            p.setColor(cloudCol);
            float s = cl.s;
            c.drawCircle(cl.x, cl.y, 46 * s, p);
            c.drawCircle(cl.x + 50 * s, cl.y - 22 * s, 58 * s, p);
            c.drawCircle(cl.x + 110 * s, cl.y, 48 * s, p);
            rect.set(cl.x - 40 * s, cl.y, cl.x + 150 * s, cl.y + 40 * s);
            c.drawRoundRect(rect, 20 * s, 20 * s, p);
        }

        int[][] hills = {{0xFF7FAF8F, 0xFF3E7A4A}, {0xFF9C6B7A, 0xFF5A4A52}, {0xFF22304F, 0xFF151F38}};
        p.setColor(hills[tod][0]);
        c.drawPath(farHill, p);
        // trees on the near bank
        int trunk = tod == 2 ? 0xFF0E1428 : 0xFF4A3524;
        int crown = tod == 0 ? 0xFF2E6B3A : tod == 1 ? 0xFF4A3A3A : 0xFF101A30;
        float[] tx = {130, 260, 520, 610, 1120, 1240, 1330, 1700, 1820};
        float[] ts = {1f, 0.8f, 1.1f, 0.75f, 0.9f, 1.15f, 0.8f, 1f, 0.85f};
        for (int i = 0; i < tx.length; i++) {
            float s = ts[i], bx = tx[i], by = 655;
            p.setColor(trunk);
            c.drawRect(bx - 8 * s, by - 60 * s, bx + 8 * s, by + 10, p);
            p.setColor(crown);
            c.drawCircle(bx, by - 95 * s, 55 * s, p);
            c.drawCircle(bx - 35 * s, by - 65 * s, 40 * s, p);
            c.drawCircle(bx + 35 * s, by - 65 * s, 40 * s, p);
        }
        p.setColor(hills[tod][1]);
        c.drawPath(nearHill, p);

        // water
        if (tod == 0) {
            p.setShader(water);
        } else {
            p.setColor(tod == 1 ? 0xFF6A4E78 : 0xFF0F1D3D);
        }
        c.drawRect(0, HORIZON, W, H, p);
        p.setShader(null);
        p.setColor(tod == 2 ? 0x22FFFFFF : 0x40FFFFFF);
        p.setStrokeWidth(3);
        for (int i = 0; i < 14; i++) {
            float yy = HORIZON + 12 + i * 11;
            float off = (time * (20 + i * 3) + i * 137) % 260;
            for (float xx = -260 + off; xx < W; xx += 260) {
                c.drawLine(xx, yy, xx + 60 + (i % 3) * 20, yy, p);
            }
        }
    }

    private void drawForeground(Canvas c, int tod) {
        int reedCol = tod == 0 ? 0xFF4C7A2E : tod == 1 ? 0xFF4A4A2A : 0xFF1C2A1E;
        int tailCol = tod == 2 ? 0xFF2A1E14 : 0xFF6B4226;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (Reed r : reeds) {
            float sway = (float) Math.sin(time * 1.3f + r.phase) * 10;
            float baseY = GRASS_TOP + 40;
            path.reset();
            path.moveTo(r.x, baseY);
            path.quadTo(r.x + sway * 0.3f, baseY - r.h * 0.5f, r.x + sway, baseY - r.h);
            p.setColor(reedCol);
            p.setStrokeWidth(r.w);
            c.drawPath(path, p);
            if (r.cattail) {
                p.setColor(tailCol);
                p.setStrokeWidth(r.w * 2.6f);
                c.drawLine(r.x + sway * 0.96f, baseY - r.h + 12, r.x + sway, baseY - r.h + 42, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(tod == 0 ? 0xFF3D6B24 : tod == 1 ? 0xFF3B3B22 : 0xFF141F16);
        c.drawPath(grass, p);
        p.setShader(vignette);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
    }

    private static final int[][] DUCK_COLORS = {
            // body, chest, head, wing, beak
            {0xFFB8B0A0, 0xFF6B3E26, 0xFF1E7A44, 0xFF7A6A55, 0xFFF2C230},
            {0xFF8B5E3C, 0xFFC49A6C, 0xFF6E4A2E, 0xFF5E3F27, 0xFFE07B2A},
            {0xFFF2C230, 0xFFFFE27A, 0xFFE0A800, 0xFFD49B12, 0xFFFF7A1A},
    };

    private void drawDuck(Canvas c, Duck d) {
        int[] col = DUCK_COLORS[d.kind];
        c.save();
        c.translate(d.x, d.y);
        if (d.faceLeft) c.scale(-1, 1);
        if (d.state == D_FALL) c.rotate(d.spin);
        if (d.state == D_HIT) c.rotate(-15);
        if (d.state == D_ESCAPE) c.rotate(-25);
        float s = 1.05f;
        c.scale(s, s);
        p.setStyle(Paint.Style.FILL);

        float wing = d.state == D_FLY || d.state == D_ESCAPE
                ? (float) Math.sin(d.flap) * 55 : d.state == D_HIT ? -70 : 30;

        // far wing (behind body)
        drawWing(c, -wing * 0.6f - 10, darker(col[3]));

        // tail
        path.reset();
        path.moveTo(-48, -6);
        path.lineTo(-82, -22);
        path.lineTo(-76, 10);
        path.close();
        p.setColor(darker(col[0]));
        c.drawPath(path, p);

        // body + chest
        p.setColor(col[0]);
        rect.set(-58, -26, 42, 26);
        c.drawOval(rect, p);
        p.setColor(col[1]);
        rect.set(4, -22, 46, 22);
        c.drawOval(rect, p);

        // head
        p.setColor(col[2]);
        c.drawCircle(48, -30, 21, p);
        rect.set(30, -32, 56, -6);
        c.drawOval(rect, p);
        if (d.kind == 0) { // white collar
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(0xFFF4F4F0);
            c.drawArc(28, -24, 58, 2, 200, 140, false, p);
            p.setStyle(Paint.Style.FILL);
        }

        // beak
        path.reset();
        path.moveTo(64, -36);
        path.quadTo(92, -34, 94, -26);
        path.quadTo(80, -21, 64, -24);
        path.close();
        p.setColor(col[4]);
        c.drawPath(path, p);

        // eye
        if (d.state == D_HIT || d.state == D_FALL) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f);
            p.setColor(Color.BLACK);
            c.drawLine(49, -40, 59, -30, p);
            c.drawLine(59, -40, 49, -30, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            p.setColor(Color.WHITE);
            c.drawCircle(54, -35, 6.5f, p);
            p.setColor(Color.BLACK);
            c.drawCircle(56, -35, 3.5f, p);
        }

        // near wing
        drawWing(c, wing, col[3]);
        if (d.kind == 0 && wing < 10) { // blue speculum stripe
            p.setColor(0xFF2E56C8);
            c.save();
            c.rotate(-wing, -8, -14);
            rect.set(-46, -26, -18, -18);
            c.drawRoundRect(rect, 4, 4, p);
            c.restore();
        }

        // feet when falling
        if (d.state == D_FALL || d.state == D_HIT) {
            p.setColor(0xFFFF8A2A);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            c.drawLine(-10, 22, -16, 44, p);
            c.drawLine(8, 22, 12, 44, p);
            p.setStyle(Paint.Style.FILL);
        }

        if (d.kind == 2) { // golden sparkle
            p.setColor(0xCCFFFFFF);
            float tw = (float) Math.abs(Math.sin(time * 6 + d.x * 0.01f));
            c.drawCircle(-30, -40, 3 + tw * 4, p);
            c.drawCircle(20, 34, 2 + (1 - tw) * 4, p);
        }
        c.restore();
    }

    private void drawWing(Canvas c, float angle, int color) {
        c.save();
        c.rotate(-angle, -8, -14);
        path.reset();
        path.moveTo(-2, -14);
        path.cubicTo(-20, -60, -60, -78, -88, -66);
        path.cubicTo(-62, -40, -44, -20, -30, -6);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
        c.restore();
    }

    private static int darker(int c) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) * 0.75f),
                (int) (Color.green(c) * 0.75f), (int) (Color.blue(c) * 0.75f));
    }

    private void drawEffects(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        for (Feather f : feathers) {
            p.setColor(f.color);
            p.setAlpha((int) (255 * Math.min(1, f.life * 2)));
            c.save();
            c.translate(f.x, f.y);
            c.rotate(f.rot);
            rect.set(-9, -3.5f, 9, 3.5f);
            c.drawOval(rect, p);
            c.restore();
        }
        p.setStyle(Paint.Style.STROKE);
        for (Ring r : rings) {
            float k = r.t / 0.35f;
            p.setColor(r.color);
            p.setAlpha((int) (255 * (1 - k)));
            p.setStrokeWidth(8 * (1 - k) + 1);
            c.drawCircle(r.x, r.y, 20 + 70 * k, p);
        }
        p.setStyle(Paint.Style.FILL);
        for (Popup q : popups) {
            float a = q.t < 1 ? 1 : 1 - (q.t - 1) / 0.4f;
            outlined(c, q.text, q.x, q.y, 54, q.color, a);
        }
    }

    private void drawSign(Canvas c) {
        float x = SIGN_X, y = SIGN_Y;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF5A3A1E);
        c.drawRect(x - 12, y, x + 12, GRASS_TOP + 40, p);
        float wob = (float) Math.sin(time * 2) * 2;
        c.save();
        c.rotate(wob, x, y);
        rect.set(x - SIGN_W / 2, y - SIGN_H / 2, x + SIGN_W / 2, y + SIGN_H / 2);
        p.setColor(0xFF3A2410);
        c.drawRoundRect(rect, 18, 18, p);
        rect.inset(8, 8);
        p.setColor(0xFFB07A44);
        c.drawRoundRect(rect, 12, 12, p);
        p.setColor(0x33000000);
        p.setStrokeWidth(3);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i < 4; i++) c.drawLine(rect.left + 10, rect.top + i * 28, rect.right - 10, rect.top + i * 28, p);
        p.setStyle(Paint.Style.FILL);
        outlined(c, phase == Phase.GAME_OVER ? "PLAY AGAIN" : "START", x, y + 24, 70, 0xFFFFF4D6, 1);
        c.restore();
        float pulse = 0.6f + 0.4f * (float) Math.sin(time * 4);
        outlined(c, "shoot the sign to begin", x, y + SIGN_H / 2 + 52, 30, 0xFFFFFFFF, pulse);
    }

    private void drawOverlay(Canvas c) {
        switch (phase) {
            case TITLE: {
                outlined(c, "DUCK BLAST", W / 2, 175, 150, 0xFFFFC23A, 1);
                String addr = address == null ? "(not connected to a network)" : address;
                String[] lines;
                if (joyconMode) {
                    outlined(c, "Grab a Joy-Con and blast the ducks", W / 2, 245, 40, 0xFFFFFFFF, 1);
                    lines = new String[]{
                            "1.  Pair each Joy-Con with this TV: Settings → Remotes & Accessories → Add",
                            "2.  Pull the trigger (ZR) to join. Fire with ZR, R, ZL or L",
                            "3.  Point at the target below and press  Y  to recenter (or aim with the stick)",
                    };
                } else {
                    outlined(c, "Point your phone at the TV and blast the ducks", W / 2, 245, 40, 0xFFFFFFFF, 1);
                    lines = new String[]{
                            "1.  Install Duck Blast on your Android phone and open  “Duck Blast Remote”",
                            "2.  Same Wi-Fi as this TV — it finds the TV by itself (or type  " + addr + ")",
                            "3.  Hold the phone like a remote, aim at the target below, tap  RECENTER",
                    };
                }
                text.setTextAlign(Paint.Align.LEFT);
                for (int i = 0; i < lines.length; i++) outlined(c, lines[i], 330, 315 + i * 48, 32, 0xFFEFF6FF, 1);
                text.setTextAlign(Paint.Align.CENTER);
                if (players.isEmpty()) {
                    outlined(c, "No TV remote handy? Arrows aim, OK fires.", W / 2, 1040, 28, 0xCCFFFFFF, 1);
                }
                drawCenterTarget(c);
                drawModeButton(c);
                text.setTextAlign(Paint.Align.LEFT);
                if (bestScore > 0) outlined(c, "BEST  " + fmt(bestScore), 40, 70, 34, 0xFFFFE27A, 1);
                text.setTextAlign(Paint.Align.CENTER);
                break;
            }
            case ROUND_INTRO: {
                panel(c, W / 2 - 330, 330, 660, 260);
                outlined(c, "ROUND " + round, W / 2, 440, 110, 0xFFFFC23A, 1);
                outlined(c, "Hit " + required() + " of " + DUCKS_PER_ROUND + " ducks", W / 2, 530, 46, 0xFFFFFFFF, 1);
                break;
            }
            case ROUND_END: {
                panel(c, W / 2 - 380, 300, 760, 320);
                int hits = hitsThisRound();
                outlined(c, hits == DUCKS_PER_ROUND ? "PERFECT!" : roundPassed ? "ROUND CLEAR!" : "TOO MANY GOT AWAY",
                        W / 2, 400, roundPassed ? 90 : 66, roundPassed ? 0xFF7CFF6A : 0xFFFF6A5A, 1);
                outlined(c, hits + " / " + DUCKS_PER_ROUND + " ducks  (needed " + required() + ")", W / 2, 480, 44, 0xFFFFFFFF, 1);
                if (hits == DUCKS_PER_ROUND) outlined(c, "+" + fmt(5000 * round) + " bonus", W / 2, 560, 44, 0xFFFFE27A, 1);
                break;
            }
            case GAME_OVER: {
                panel(c, W / 2 - 450, 110, 900, 140 + Math.max(1, players.size()) * 58);
                outlined(c, "GAME OVER", W / 2, 205, 100, 0xFFFF6A5A, 1);
                int i = 0;
                for (Player pl : players) {
                    outlined(c, "P" + (pl.index + 1) + "  " + pl.name + "   " + fmt(pl.score),
                            W / 2, 285 + i * 58, 44, pl.color, 1);
                    i++;
                }
                drawModeButton(c);
                text.setTextAlign(Paint.Align.LEFT);
                outlined(c, "BEST  " + fmt(bestScore), 40, 70, 34, 0xFFFFE27A, 1);
                text.setTextAlign(Paint.Align.CENTER);
                break;
            }
            default:
                break;
        }
        if (banner != null && bannerTime > 0) {
            float a = Math.min(1, bannerTime * 2);
            float by = phase == Phase.TITLE || phase == Phase.GAME_OVER ? 950 : 110;
            outlined(c, banner, W / 2, by, 56, 0xFFFFFFFF, a);
        }
    }

    private void drawModeButton(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC101418);
        rect.set(MODE_X, MODE_Y, MODE_X + MODE_W, MODE_Y + MODE_H);
        c.drawRoundRect(rect, 16, 16, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(joyconMode ? 0xFFFF5A5A : 0xFF36C8FF);
        c.drawRoundRect(rect, 16, 16, p);
        p.setStyle(Paint.Style.FILL);
        outlined(c, joyconMode ? "CONTROLLER: JOY-CON" : "CONTROLLER: PHONE", MODE_X + MODE_W / 2, MODE_Y + 30, 26, 0xFFFFFFFF, 1);
        outlined(c, "shoot to switch · Menu / − button", MODE_X + MODE_W / 2, MODE_Y + 55, 18, 0xCCFFFFFF, 1);
    }

    private void drawCenterTarget(Canvas c) {
        boolean waiting = false;
        for (Player pl : players) if (!pl.calibrated) waiting = true;
        float alpha = waiting ? 1 : 0.35f;
        float cx = W / 2, cy = H / 2;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setColor(Color.argb((int) (255 * alpha), 255, 255, 255));
        c.drawCircle(cx, cy, 46, p);
        p.setColor(Color.argb((int) (255 * alpha), 255, 70, 50));
        c.drawCircle(cx, cy, 26, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, 8, p);
        if (waiting) {
            outlined(c, "aim here, then RECENTER", cx, cy + 90, 28, 0xFFFFFFFF,
                    0.6f + 0.4f * (float) Math.sin(time * 5));
        }
    }

    private void drawHud(Canvas c) {
        if (phase == Phase.ROUND_INTRO || phase == Phase.WAVE || phase == Phase.WAVE_END || phase == Phase.ROUND_END) {
            // round + duck tally
            panel(c, 30, H - 110, 860, 84);
            text.setTextAlign(Paint.Align.LEFT);
            outlined(c, "R" + round, 52, H - 50, 46, 0xFFFFC23A, 1);
            text.setTextAlign(Paint.Align.CENTER);
            int need = required();
            for (int i = 0; i < DUCKS_PER_ROUND; i++) {
                float x = 175 + i * 64, y = H - 68;
                boolean current = phase == Phase.WAVE && i >= nextSlot - WAVE_SIZE && i < nextSlot && tally[i] == 0;
                int col = tally[i] == 1 ? 0xFFFF5A36 : tally[i] == 2 ? 0xFF555A66 : 0xFFF2F2F2;
                if (current && ((int) (time * 4)) % 2 == 0) col = 0xFFFFE27A;
                drawTallyDuck(c, x, y, col);
            }
            // "need" marker under the tally
            p.setColor(0xFFFFC23A);
            p.setStyle(Paint.Style.FILL);
            float nx = 175 + (need - 1) * 64;
            rect.set(175 - 24, H - 38, nx + 24, H - 32);
            c.drawRect(rect, p);
            if (phase == Phase.WAVE) {
                float left = Math.max(0, 1 - phaseTime / waveLimit());
                p.setColor(left < 0.3f ? 0xFFFF6A5A : 0xFF7CFF6A);
                float full = (DUCKS_PER_ROUND - 1) * 64 + 48;
                rect.set(175 - 24, H - 28, 175 - 24 + full * left, H - 22);
                c.drawRect(rect, p);
            }
        }

        // player cards bottom-right
        int i = 0;
        for (Player pl : players) {
            float w = 230, x = W - 30 - (players.size() - i) * (w + 12), y = H - 110;
            panel(c, x, y, w, 84);
            p.setColor(pl.color);
            rect.set(x, y, x + 10, y + 84);
            c.drawRect(rect, p);
            text.setTextAlign(Paint.Align.LEFT);
            outlined(c, "P" + (pl.index + 1), x + 22, y + 36, 30, pl.color, 1);
            outlined(c, fmt(pl.score), x + 22, y + 72, 32, 0xFFFFFFFF, 1);
            text.setTextAlign(Paint.Align.CENTER);
            if (phase == Phase.WAVE || phase == Phase.WAVE_END) {
                for (int s = 0; s < SHELLS; s++) {
                    float sx = x + w - 26 - s * 24, sy = y + 18;
                    p.setColor(s < pl.ammo ? 0xFFE8483A : 0x44FFFFFF);
                    rect.set(sx - 7, sy, sx + 7, sy + 34);
                    c.drawRoundRect(rect, 4, 4, p);
                    p.setColor(s < pl.ammo ? 0xFFD8B04A : 0x22FFFFFF);
                    rect.set(sx - 7, sy + 34, sx + 7, sy + 46);
                    c.drawRect(rect, p);
                }
            } else {
                outlined(c, pl.padId >= 0 ? (pl.padGyro ? "Joy-Con" : "stick") : pl.client == null ? "remote" : "phone", x + w - 50, y + 36, 22, 0xAAFFFFFF, 1);
            }
            i++;
        }
    }

    private void drawTallyDuck(Canvas c, float x, float y, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        rect.set(x - 20, y - 8, x + 14, y + 14);
        c.drawOval(rect, p);
        c.drawCircle(x + 14, y - 10, 10, p);
        path.reset();
        path.moveTo(x + 22, y - 13);
        path.lineTo(x + 32, y - 9);
        path.lineTo(x + 22, y - 6);
        path.close();
        c.drawPath(path, p);
    }

    private void drawCrosshair(Canvas c, Player pl) {
        float x = pl.x, y = pl.y;
        boolean idle = pl.client != null && time - pl.lastSeen > 3;
        int a = idle ? 110 : 255;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        // dark outline for contrast against any sky
        p.setColor(Color.argb(a / 2, 0, 0, 0));
        p.setStrokeWidth(10);
        c.drawCircle(x, y, 34, p);
        p.setColor((pl.color & 0x00FFFFFF) | (a << 24));
        p.setStrokeWidth(5);
        c.drawCircle(x, y, 34, p);
        p.setStrokeWidth(5);
        c.drawLine(x - 52, y, x - 16, y, p);
        c.drawLine(x + 16, y, x + 52, y, p);
        c.drawLine(x, y - 52, x, y - 16, p);
        c.drawLine(x, y + 16, x, y + 52, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, 4, p);
        if (players.size() > 1) {
            text.setTextAlign(Paint.Align.LEFT);
            outlined(c, "P" + (pl.index + 1), x + 40, y - 36, 26, pl.color, a / 255f);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void panel(Canvas c, float x, float y, float w, float h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99101418);
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, 18, 18, p);
    }

    private void outlined(Canvas c, String s, float x, float y, float size, int color, float alpha) {
        int a = (int) (255 * clamp(alpha, 0, 1));
        text.setTextSize(size);
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(Math.max(4, size / 9));
        text.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(Color.argb((int) (a * 0.85f), 10, 14, 20));
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor((color & 0x00FFFFFF) | (((Color.alpha(color) * a) / 255) << 24));
        c.drawText(s, x, y, text);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n);
    }
}
