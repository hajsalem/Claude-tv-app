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

## Remote controls

| Button | Action |
| --- | --- |
| D-pad | Move focus / scroll the chat |
| OK | Click, or open the keyboard in the prompt box |
| **Hold OK** or Menu | App menu (voice, new chat, all chats, text size, reload, exit) |
| Search / mic, red key | Voice input |
| Ch+ / Ch-, Rew / FF | Page up / page down |
| Green key | New chat |
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
