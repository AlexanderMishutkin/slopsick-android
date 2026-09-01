# FocusTube for Android

Covers the posts you did not ask to see, in the Instagram and LinkedIn apps.

It is the same idea as the [browser extension](https://github.com/apmishutkin/FocusTube)
— keep what you follow, lose what the feed picked for you — carried onto a phone,
where the rules are different and worse.

## What it actually does

A browser extension can delete a post from the page. An Android app cannot: an
accessibility service may read another app's screen and draw on top of it, and that
is all. So this does not hide posts. It **covers the whole feed and cuts holes** where
a post earned one:

```
cover = the feed region − the posts judged KEEP
```

Everything else goes under the cover: ads, suggestions, posts still loading, regions
no post claimed, and any layout this build has never seen. A feed filter that fails
open stops filtering without telling you. This one fails shut.

The cover does not intercept touches, so scrolling and flinging feel exactly as they
did — Instagram's scroll physics stay Instagram's problem.

### Instagram

Instagram is the good case. Despite Litho, the feed exposes stable view ids, so the
decision is an id lookup rather than a word match:

> a post carries `inline_follow_button` in its header ⇔ you do not follow the author

That works in any interface language. The visible label is *not* usable: `secondary_label`
holds the audio track, "Edited · 7d", "Translate with AI" **or** "Suggested for you",
depending on the post. Seven of the test fixtures are suggested posts whose label says
something else; reading the label would pass every other test and miss all seven.

### Reels

Reels is not a feed of things you chose, so there is nothing in it to judge post by
post. It is dealt with in two places:

- **The tab button is removed.** Painting over it is not enough — the main overlay lets
  touches through so that scrolling still works, so a button you cannot see would still
  open Reels when tapped blind. The button gets a small window of its own, exactly its
  size, that takes the touch and swallows it.
- **The player is covered whole**, if you reach it another way. Instagram opens straight
  into Reels by itself often enough that handling only the button would miss the case
  that matters. The tab bar is left uncovered so you can leave.

Covers are painted the colour the app uses behind the thing being covered, so they read
as "nothing here" rather than as a hole. The feeds follow the system light/dark setting;
Reels does not, because Reels is dark on a light phone too. Instagram's tab bar is
`#0C1014` where its Reels player is pure black, so a blocked button is painted with the
bar's colour and not the surface's — otherwise there is a visible rectangle exactly
where the thing you are trying to forget used to be.

### LinkedIn

LinkedIn is the bad case. Its feed is Jetpack Compose driven by server-defined UI and
exposes **no view ids on post content** — only the scroll container, `sdui:lazyColumn`.
Grouping is easier than Instagram (one child of the lazy column is one post) but every
signal is a string: the connection degree (`• 1st`, `• 3rd+`), `Follow <name>`,
`<name> commented`, `Because you recently followed <name>`.

**So the LinkedIn side is English-only.** That is a real regression against the browser
extension, which classifies LinkedIn structurally and works in any language. The strings
are collected at the top of `LinkedInAnalyzer.kt` so translating is one object, but until
LinkedIn exposes ids there is no honest way around it.

### Scrolling

Half a post scrolled off the top takes its header with it, and with the header goes the
follow button and the degree marker. Judged frame by frame, every scroll would black out
the post you were reading. Verdicts therefore carry across frames, by identity where a
post has one, and otherwise by measuring how far the feed moved and asking who owned
that space a moment ago. When the frames do not line up, nothing is inherited and the
region stays covered.

## Privacy

- **No permissions.** Not internet, not storage, nothing. The manifest has no
  `<uses-permission>` element at all. The app cannot open a socket, so it cannot send
  what it reads anywhere, whatever the code says. Check it yourself:

  ```
  adb shell dumpsys package dev.amishutkin.focustube | grep -A5 "requested permissions"
  ```

  (there is no such section, because there are none)

- **It only sees two apps.** `android:packageNames` in
  `app/src/main/res/xml/accessibility_service_config.xml` names Instagram and LinkedIn.
  The system enforces that; it is not a promise made in code.

- **Nothing leaves the device and nothing is stored** except the five switches.

- **No dependencies** beyond JUnit for the tests. Every library is one more thing a
  reader has to trust, and the point of this app is that it can be read in an afternoon.

- Release builds are **unminified**, so the shipped APK stays readable.

If you are reviewing a change to this app, the single thing to refuse is a new
permission.

## Building

Needs JDK 17+ and an Android SDK with platform 36.

```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # the tests below
```

Then install it, and turn it on under Settings → Accessibility → Installed apps →
FocusTube feed filter.

## Tests

55 JVM tests, no device or emulator needed. The analyzers work against a `UiNode`
interface rather than `AccessibilityNodeInfo`, so they can be run against 46 UI trees
captured from real devices with `uiautomator dump` — the same trick the browser
extension uses with jsdom.

The fixtures came from real feeds, so `tools/anonymize.py` rewrites every handle,
name and post body to synthetic values before they are committed. Re-run it if you
capture more:

```
python3 tools/anonymize.py <dir-of-dumps> app/src/test/resources/fixtures
```

Some rules cannot be expressed by a captured screen — `uiautomator dump` cannot read
Reels at all, because it waits for a window that stops changing and an autoplaying video
never does. Those screens are built by hand in `FakeNode.kt` from ids and bounds logged
off a real device.

If you need to see the ids on a screen `uiautomator` cannot read, a debug build can print
them (**view ids and geometry only, never text**):

```
adb shell setprop log.tag.FocusTubeTree VERBOSE
adb logcat -s FocusTubeTree
```

The most useful tests are the corpus invariants, which hold over all 46 screens rather
than expectations about one of them — in particular that **everything not explicitly
kept ends up covered**.

## What is not done

Being specific, because the gaps matter more than the features:

- **Ad detection is unverified.** No sponsored post appeared in 34 Instagram screens or
  8 LinkedIn ones — the recon account was new and had no ad profile. The Instagram ad
  path is a string match written from the docs and has never matched anything real. It
  is the one word-match in `InstagramAnalyzer.kt`, and it is marked as such.
- **Chrome is not covered.** Chrome exposes its URL bar (`url_bar`) and the whole page
  to the accessibility tree, so the mobile web feeds are reachable — but that needs
  captures of the logged-in mobile web feed, which the recon account does not have.
  Chrome is deliberately absent from `packageNames` until there is code that uses it:
  listing a package is what grants the service sight of it.
- **Only tested on an emulator** (Pixel-shaped AVD, Android 16). Fling behaviour on real
  hardware is different enough that acceptance belongs on a real phone.
- **Only tested on one account's feed**, which follows about ten public accounts.
- **Not on Google Play.** Play restricts `AccessibilityService` to genuine accessibility
  tools, and this is not one. Distribution is a signed APK, installed by hand.
- The watchdog and overlay are not unit tested — they need a device. Both were found
  broken on one and fixed there.
- The Reels cover is a plain sheet of colour with nothing written on it. It is not
  obvious to a first-time user that the app is doing this rather than Instagram breaking.
- Cover colours follow the *system* theme, not the app's own. Both apps follow the
  system by default, so this is right until someone sets a per-app theme.

## Layout

```
app/src/main/java/dev/amishutkin/focustube/
  core/       no Android imports; pure functions over a UiNode tree — this is the part
              that is tested
  platform/   the accessibility service, the overlay window, the settings store
  ui/         one settings screen
app/src/test/ the tests, and 46 anonymised device captures
tools/        the anonymiser
```
