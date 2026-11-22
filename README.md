# Claude TV

A personal Android TV app for [claude.ai](https://claude.ai): fullscreen, launches from
the TV home screen, and is driven entirely by the remote.

## What it does

- **Native-feeling launcher tile** – shows up on the Android TV / Google TV home row,
  opens straight into a new chat, no address bar.
- **D-pad navigation** – arrow keys jump between buttons, chats and the prompt box with a
  big orange focus ring. When there's nothing to jump to, Up/Down scroll the conversation
  so you can read long answers.
- **Voice input** – press the remote's Search/mic key (or *Hold OK → Voice input*). The
  system speech recognizer fills the prompt box and focus jumps to Send. Turn on
  *Send voice prompts automatically* in the menu to skip that step.
- **Stays signed in** – cookies persist across launches.
- **Text size** – bigger/smaller text from the menu, remembered between launches.
- **Pointer mode** – an on-screen mouse pointer for things the D-pad can't reach. Arrows
  move it (hold to speed up; push against the top/bottom edge to scroll), OK clicks for
  real, Back turns it off. It switches on by itself on Cloudflare's "Verify you are human"
  page and parks the pointer on the checkbox, so you just press OK.

## Remote controls

| Button | Action |
| --- | --- |
| D-pad | Move focus / scroll the chat |
| OK | Click, or open the keyboard in the prompt box |
| **Hold OK** or Menu | App menu (voice, new chat, all chats, account settings, pointer mode, text size, reload, exit) |
| Search / mic, red key | Voice input |
| Ch+ / Ch-, Rew / FF | Page up / page down |
| Green key | New chat |
| Blue key (or menu) | Pointer mode on/off |
| Back | Leave prompt box → previous page → press twice to exit |

Note: on many Google TV remotes the mic/Assistant button is reserved by the system and
never reaches apps. If yours does that, use *Hold OK → Voice input*.

## Installing

Every push builds an APK with GitHub Actions and publishes it as the **latest** release.

1. On the TV, enable *Developer options → Install unknown apps* for your downloader app
   (e.g. "Downloader" or "Send Files to TV").
2. Download `claude-tv.apk` from the repo's
   [latest release](../../releases/latest) and install it.
   - If the repo is private, download it on your phone/PC and send it to the TV
     (Send Files to TV, a USB stick, or `adb install claude-tv.apk`).
3. Updates install over the old version because every build is signed with the same
   key (`keystore/`).

## Signing in

Typing on a TV is painful, so sign in once:

- **Email login**: enter your email with the on-screen keyboard (or voice), then open the
  link/code from the email. If the link opens on your phone instead of the TV, use the
  code option if offered.
- **Google login**: the app uses a desktop Chrome user agent, which usually lets Google
  sign-in through. If Google refuses ("This browser or app may not be secure"), use email
  login instead.

Your session is stored in the app's cookie jar, so you only do this once.

## "Verify you are human" loops

If the check keeps coming back after you tick it, open the menu and turn
**Desktop layout** off. That uses the TV's stock browser identity instead, which some
checks trust more. You can turn it back on once you're signed in.

## Building locally

Needs JDK 17 and the Android SDK (platform 35).

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## How it works

- `app/src/main/java/app/claudetv/MainActivity.java` – the WebView, remote key handling,
  menu, voice recognition, mic permission for claude.ai's own voice mode, file picker.
- `app/src/main/assets/tvnav.js` – injected into claude.ai: spatial D-pad navigation,
  focus ring, scrolling, and helpers to click things and insert dictated text into the
  prompt editor.

Since this wraps the claude.ai website, a redesign of the site can break some of the
remote shortcuts (e.g. finding the Send button). Plain D-pad navigation keeps working.

The signing key in `keystore/` is only for this personal sideloaded app; don't reuse it
for anything you publish.

---

# Duck Blast

A duck-shooting game in the same repo (`duckblast/`). It plays on the TV, and your
Android phone is the gun: point the phone at the screen and pull the trigger.

**One APK for both devices.** Install `duck-blast.apk` (from the same
[latest release](../../releases/latest)) on the TV *and* on your phone:

- On the TV it shows up as **Duck Blast**, which is the game.
- On the phone it shows up as **Duck Blast Remote**, which is the gun.

## Playing

1. Start Duck Blast on the TV.
2. Open Duck Blast Remote on the phone. On the same Wi-Fi it finds the TV by itself.
   If it doesn't, type the address shown on the TV's title screen.
3. Hold the phone like a TV remote (top edge toward the screen), point at the
   target in the middle of the TV, and tap **RECENTER**.
4. Shoot the **START** sign.

- **Fire:** tap anywhere on the big button, or press a volume key.
- **RECENTER:** point at the middle of the TV and tap it whenever the aim drifts.
- **⚙ settings:** aim sensitivity, touchpad aiming (drag to aim, for phones without a
  gyroscope), and switching TVs.
- Up to 4 phones can play at once. Each one gets its own crosshair colour and score.
- No phone handy? The TV remote works too: arrows aim, OK fires, Play/Pause starts.
  Back goes to the title screen.

## Rules

Each round has 10 ducks, released two at a time, and you get 3 shells per pair.
Ducks fly away when time runs out or everyone is out of shells. Hit enough of them
to clear the round (6 at first, up to 9 later). Each round they fly faster. Brown ducks
are worth more, and golden ducks are rare and worth the most. Hit all 10 for a perfect
round bonus.

## How it works

- `GameActivity` / `GameView`: the game, drawn entirely in code (no image assets) on a
  1920×1080 virtual canvas.
- `GameServer`: a TCP server on port 47823, advertised over mDNS (`_duckblast._tcp`).
- `RemoteActivity` / `PadView` / `RemoteLink`: the phone side. Uses the gyro-fused
  *game rotation vector* sensor to work out where the phone's top edge points,
  relative to the RECENTER direction, and streams that aim point to the TV.
- `Sfx`: all sounds are synthesised at startup.
- Protocol: plain text lines, documented in `Net.java`.
