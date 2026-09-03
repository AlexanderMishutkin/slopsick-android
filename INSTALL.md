# Installing SLOPSICK

There is no Play Store listing and there will not be one: Google restricts
`AccessibilityService` to genuine accessibility tools, and this is not one. Everything
below is a hand-installed APK.

---

## The one thing that will bite you

**Install with an installer attributed.** Not this:

```
adb install SLOPSICK-1.0.0.apk          # don't
```

This:

```
adb install -r -i com.android.vending SLOPSICK-1.0.0.apk
```

A plain `adb install` leaves `installerPackageName=null`. Android 13 and later treat an
app with no installer as sideloaded and put it behind **restricted settings** — the gate
that governs accessibility access specifically.

It does not fail in any way you would notice. The service switches on, works perfectly,
and is then **silently revoked at the next reboot**: `accessibility_enabled` back to `0`,
the service listed under `Crashed services`, and no exception in any log buffer. It looks
exactly like the phone's battery manager killing it, which is the wrong thing to go and
fix.

If you install from a file manager instead, you hit the same gate earlier — the install
itself is refused. The manual equivalent of the flag is:

> Settings → Apps → SLOPSICK → ⋮ (top right) → **Allow restricted settings**

---

## Installing a release build

1. Get the APK onto the phone — USB (*File transfer* mode), cloud storage, or adb.
2. Install it, ideally with adb and `-i` as above. From the file manager: tap the APK,
   let Android take you to **Allow from this source**, come back, **Install**.
   Play Protect may warn that it cannot scan an app from an unknown developer. That is
   what it says about every unsigned-by-Google app.
3. Turn it on: **Settings → Accessibility → Installed apps → SLOPSICK feed filter → On**.

The permission dialog says SLOPSICK can "view and control screen". That is the real
capability and it is worth reading rather than tapping past. It is also why the app asks
for no permissions at all — with no `INTERNET` permission it cannot open a socket, so
nothing it reads can leave the phone. Check for yourself:

```
adb shell dumpsys package dev.amishutkin.slopsick | grep -A3 "requested permissions"
```

There is no such section, because there are none.

### Confirming it took

```
adb shell dumpsys accessibility | grep -E "Bound services|Crashed services"
```

You want SLOPSICK under **Bound**, and **Crashed** empty.

---

## Phones that kill background apps

Xiaomi, Samsung, Huawei, Oppo and OnePlus all ship power management that kills services
the rest of Android would leave alone. This is a **separate** mechanism from restricted
settings above — it kills the process under memory pressure rather than at boot — and it
needs its own settings:

- **Autostart** — on Xiaomi: Settings → Apps → Permissions → Autostart → SLOPSICK on.
  It cannot be granted over adb; `AUTO_START` is not a standard app op.
- **Battery** — Settings → Apps → SLOPSICK → Battery saver → **No restrictions**.
- **Lock in recents** — long-press the app in the task switcher and tap the padlock.

An accessibility service cannot ask for any of this on its own behalf, and the app will
not grow a foreground service and a permanent notification to defend itself — that would
mean adding permissions, which is the one thing it is built not to do.

---

## Building it yourself

Needs JDK 17+ and an Android SDK with platform 36. `local.properties` must point at your
SDK (`sdk.dir=/path/to/android-sdk`).

```
./gradlew :app:testDebugUnitTest    # 97 tests, no device needed
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/
```

### Signing

`assembleRelease` looks for `keystore.properties` in the project root:

```properties
storeFile=/path/to/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and the keystore are gitignored, and the keystore lives outside the
repository. Without them the release build still succeeds — just unsigned — so someone
else's checkout still compiles.

Generate one with:

```
keytool -genkeypair -v -keystore release.jks -alias slopsick \
        -keyalg RSA -keysize 4096 -validity 10950
```

**Keep it.** Android refuses an upgrade signed by a different key, so losing the keystore
means everyone who installed a build has to uninstall before they can take another one.

Release builds are deliberately **unminified**. The claim this app makes is that its APK
can be read; obfuscating it would undercut the only thing it is offering.

---

## Debug builds and development

```
adb install -r -i com.android.vending app/build/outputs/apk/debug/app-debug.apk
```

Debug and release are signed by different keys, so you cannot install one over the other —
uninstall first when switching.

**After every `install -r`, restart the service.** Android keeps the old, now-dead service
bound and silently delivers it nothing, which looks exactly like your change not working:

```
adb shell am force-stop dev.amishutkin.slopsick
adb shell settings put secure enabled_accessibility_services ''
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services \
  dev.amishutkin.slopsick/dev.amishutkin.slopsick.platform.SlopsickAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Debug builds log what they are covering — geometry only, never text taken from a feed:

```
adb logcat -s Slopsick
```

There is also a tree dumper for working out how to recognise a new surface. `uiautomator
dump` cannot read a screen that never stops animating — Reels, Shorts, any autoplaying
video — because it waits for an idle window that never comes. The service is already
holding the tree, so it can print what uiautomator cannot reach. **View ids and geometry
only, never text**, and debug builds only:

```
adb shell setprop log.tag.SlopsickTree VERBOSE
adb logcat -s SlopsickTree
```

Turn it off again with `adb shell setprop log.tag.SlopsickTree INFO` — it builds a large
string on every pass and will cost you frames.

### Collecting bug reports off the phone

The tree dumper needs a cable and a running logcat. The report button does not: tap the
👎 on a cover when it gets something wrong, and the phone writes the tree, the verdicts
and a screenshot to its own folder. Pick them up whenever the phone is next plugged in:

```
adb pull /sdcard/Android/data/dev.amishutkin.slopsick/files/reports
```

Each report is a folder named `<timestamp>-<app>` holding:

- `tree.xml` — the accessibility tree in `uiautomator dump` format
- `report.json` — the analyzer's verdicts, the regions painted, the app version, the device
- `screen.png` — the screen with the covers up, which is what a report about alignment or a border is about

`tree.xml` is in the same format as the test fixtures, so a report becomes a regression
test in one step:

```
mkdir /tmp/incoming && cp reports/20260903-115930-instagram/tree.xml /tmp/incoming/ytshelf-01.xml
python3 tools/anonymize.py /tmp/incoming app/src/test/resources/fixtures
```

**`tree.xml` contains the posts that were on screen**; the screenshot does not. They stay
on the phone until you pull them — the app has no INTERNET permission — but do run the
anonymiser before committing anything, and clear them when you are done:

```
adb shell rm -rf /sdcard/Android/data/dev.amishutkin.slopsick/files/reports
```

The screenshot is the only reason this app declares `android:canTakeScreenshot` in
`accessibility_service_config.xml`. If you would rather it did not, remove that attribute:
the reports still carry the tree and the verdicts, which is the part the tests need.

---

## Emulator

Instagram and LinkedIn need a Play Store image (`google_apis_playstore`) so you can sign
in. Animations must be off or `uiautomator dump` fails with "could not get idle state":

```
adb shell settings put global window_animation_scale 0.0
adb shell settings put global transition_animation_scale 0.0
adb shell settings put global animator_duration_scale 0.0
```

Give the AVD no more RAM than the host can spare — 4 GB on an 8 GB machine will take the
emulator down mid-session, and a Play Store update on top of that will freeze it.

---

## Updating

Same command; app data and settings survive as long as the signing key is the same.

```
adb install -r -i com.android.vending SLOPSICK-x.y.z.apk
```

Then re-check accessibility is still on.

## Uninstalling

Settings → Apps → SLOPSICK → Uninstall, or:

```
adb uninstall dev.amishutkin.slopsick
```

Nothing is left behind: the app stores five switches and a lock expiry in its own
SharedPreferences and writes nowhere else.

---

## When it stops working

| What you see | Why | Fix |
|---|---|---|
| Stopped after a reboot | Restricted settings; no installer attributed | Reinstall with `-i com.android.vending`, or **Allow restricted settings** |
| Stopped after a day of use | The phone's power management killed it | Autostart + unrestricted battery + lock in recents |
| Stopped right after reinstalling | Old dead service still bound | The force-stop and toggle sequence above |
| Nothing covered, service bound | Not on a screen it reads | It only reads the feed — profiles, chats and search are left alone |
| Covers the wrong thing | The app changed its layout | Tap the 👎 on the cover, then `adb pull` the report |
| No 👎 on the covers | Reporting is switched off, or the band is too short to hold one | Turn on **Report button on every cover** in SLOPSICK |
| The tab bar is covered | Should not happen any more — the bar is found by shape as well as by id | Report it; that is the one failure worth a bug report on its own |
