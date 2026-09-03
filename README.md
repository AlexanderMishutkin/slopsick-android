# SLOPSICK for Android

Covers the posts you did not ask to see — in the Instagram, LinkedIn and YouTube apps,
and on instagram.com in Chrome.

It is the same idea as the [browser extension](https://github.com/apmishutkin/SLOPSICK)
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
Reels and Shorts do not, because they are dark on a light phone too. Both apps also use a
lighter black for the navigation bar than for the video behind it — Instagram's bar is
`#0C1014`, YouTube's `#0F0F0F` — so a blocked button takes the bar's colour and not the
surface's. Otherwise there is a visible rectangle exactly where the thing you are trying
to forget used to be.

Once the screen stops moving, each covered thing gets an outline and a label — "Suggested
post hidden", "Ad hidden", "Explore hidden". During a scroll the cover is a flat sheet:
the bounds are already a frame or two out of date, and an outline that lags is more
distracting than none. It also **grows by a quarter while the page moves**, because that
same lag otherwise shows as a strip of the very thing being covered — a Short playing
along the edge of its own cover. It snaps back the moment scrolling stops.

### Explore

Instagram's Explore tab is a grid of things the algorithm picked, so all of it goes. The
search bar stays — searching for something is a thing you chose to do — and so does the
tab bar.

Once you type, Instagram drops the `explore_action_bar`, and that absence is what tells
the two apart. Keying off the grid alone would cover the results you went looking for,
which is the opposite of helping.

### YouTube

Only Shorts. The home feed is left alone; its ads were explicitly not worth the false
positives.

- **The Shorts tab is removed**, the same way as Instagram's Reels tab.
- **The Shorts player is covered** if you reach it another way.
- **Shorts shelves in the home feed are covered**, videos included. A shelf is not one
  row: YouTube puts the "Shorts" heading in one child of the feed and the videos in the
  next, so matching the heading alone covers a caption and leaves the Shorts playing
  underneath it. The run is stitched back together into one region.

  A shelf is recognised **by its shape, not by its words**. The first version matched the
  heading and the `- play Short` suffix in each thumbnail's description; on a phone whose
  YouTube build writes no such suffix that produced the worst possible result — the word
  "Shorts" neatly covered, the videos underneath it playing on. The shape is the thing no
  build changes: *YouTube's feed is one video per row, and Shorts come two or three
  abreast, in portrait.* A row of tall tiles side by side is a shelf in any locale. The
  string matches are kept as a second opinion, because the heading row has no tiles in
  it, but nothing depends on them.

### Never covering the way out

The worst thing this app can do is not cover too much of a feed — it is cover the tab
bar, because then you cannot leave the app to turn it off. That happened, on a phone
whose Instagram no longer published the view id the code looked for; the fallback was
"cover down to the bottom of the scroll container", which on all three apps runs behind
the navigation bar.

`ScreenChrome.kt` now finds the bar three ways and takes the most cautious answer:

1. **By id**, when the app still publishes one.
2. **By shape** — a strip flush with the bottom of the screen, full width, a few percent
   of its height, holding three to six *equally sized* controls side by side. That is
   what a navigation bar is, and no app update changes it.
3. **By giving up carefully.** Found neither way, the bar may still be *declared* — its id
   present in the tree, reporting bounds that cannot be true. A phone report showed
   LinkedIn's own bar coming back as `[0,2712][1220,2712]`: zero height, pinned to the
   bottom of a 2712-pixel screen, while the bar was on screen and being tapped.
   `bottom_nav_container` did worse and reported a top *below* its own bottom. An app that
   still says it has a bar is not offering us the space, so the bottom 9% is left alone.
   Only when nothing declares a bar and nothing down there is even shaped like one does
   the feed run to the bottom of the display — the YouTube case, where the bar really has
   gone. Without that last distinction a Shorts shelf is covered to within an inch of the
   bottom and its channel names show under the cover.

### The overlay window does not always start where it asked to

The analyzers reason in screen coordinates, because that is what an accessibility tree
reports. The cover window asks to be laid out from the top-left of the display, and on the
emulator it gets it — so view coordinates and screen coordinates coincide and nobody
notices the assumption.

On a Xiaomi running Android 16 they do not coincide. The window begins below the status
bar, so every band was painted 138 pixels lower than asked: a strip of live feed above the
cover, and the bottom of the cover pushed down over the app's own tab bar. One offset,
three separate-looking complaints.

`CoverView` now measures where it actually landed with `getLocationOnScreen` and translates
the canvas by it, so it draws in screen coordinates whatever the window manager did; the
blocker and report windows are moved by the same offset. Debug builds print it:

```
draw 1 bands, view 1080x2400 at 0,0, insets top=63 bottom=63
```

`at 0,0` is the emulator. `at 0,138` was the phone.

**And none of it is trusted on its own.** The scan carries the bar out to the service,
which remembers where each app's was and passes it to the overlay as a floor the painter
will not cross. That last part is not belt and braces for its own sake: the first reports
off a real phone had the bar in exactly the right place in the scan and a cover painted
over it anyway, because what gets painted *between* scans is not always what the last scan
decided. The invariant belongs to the thing holding the brush.

The equal-width test is not decoration. Instagram's like/comment/share row also sits
flush above the tab bar, is also full width, is also 76px tall and also holds a row of
controls — but its buttons are 64 to 183 pixels wide, where a tab bar divides its width
evenly. That capture is a test.

The same treatment was tried on the *top* bar and withdrawn: Instagram hides its toolbar
on scroll, and the shape test then matches whatever is pinned to the top of the screen
instead — in the captures, a post's own like row — which leaves a strip of the post
showing. The top bar is found by id or not at all.

### Chrome

instagram.com in the mobile browser — the feed, Explore and Reels. Chrome puts the whole
page into the accessibility tree and its address bar alongside, so both "which page is
this" and "what is on it" are answerable. The page itself arrives as a deep pile of
anonymous `View`s with no ids at all, so a post is not a node: it is the stretch of page
between one author's avatar and the next.

Three things worth knowing:

- **The covered region sits between the site's own two navigation bars.** Those are page
  content rather than app chrome, and covering them would leave you unable to go
  anywhere. An earlier version anchored the region to the first avatar it could see, and
  so uncovered the top of the screen as soon as a post scrolled far enough for its avatar
  to leave — the feed "opened up" exactly while you were scrolling past it.
- **Which page you are on comes from the address bar, and is remembered.** Chrome hides
  its toolbar the moment you scroll, taking the URL with it; re-checking every frame
  switches the filter off as soon as you start reading. `/explore` and `/reels` are
  covered wall to wall; a profile or a single post is something you navigated to on
  purpose and is left alone.
- **Chrome is read less often than the native apps** — every 350ms rather than 120ms, and
  the foreground check every 900ms rather than 400ms. Each read makes Chrome build an
  accessibility tree for a whole web page, and asking eight times a second visibly hurts
  it.

Only instagram.com. LinkedIn and YouTube on the mobile web are not read.

Every signal here is a word — Chrome exposes the page's accessible names, the same
strings a screen reader announces, and those are translated with the site.

### LinkedIn

Only the feed tab. Every LinkedIn tab is the same lazy column from the tree's point of
view, so keying off that alone covered job listings and search results too — neither of
which is the feed choosing things for you. Which tab is current (`tab_feed`, selected) is
the only thing that tells them apart, and if the tab bar is not on screen the answer is
"not the feed": being unable to tell which screen this is, is not a licence to paint over
it.

LinkedIn is otherwise the bad case. Its feed is Jetpack Compose driven by server-defined UI and
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

Reading an accessibility tree is a round trip into another process and costs tens to
hundreds of milliseconds on a loaded feed — far too much to do per frame. The first
version handled that by covering the whole feed the moment anything moved and waiting for
the next scan to cut the holes back, which is what made scrolling feel the way it did:
the post you were reading went black, and stayed black for the best part of a second
after you stopped. Three things replaced it, none of them "scan more often":

- **Scans run on a worker thread.** The main thread only paints, so a scroll event is
  never queued behind a tree read.
- **Between scans the cover is projected, not guessed.** A scroll event carries the exact
  number of pixels the list moved, so the last scan's holes move with it — no tree needed.
  When the distance is unknown the fallback is the *feed*, never the whole screen. An
  earlier version covered the whole drawable region there, and on a frame that had the
  navigation bar wrong that was a white sheet over the entire app — reported, accurately,
  as "random white polygons".
  The holes are *shrunk* as they travel, by a tenth of the distance covered: an
  extrapolation that is a few pixels out should err into the cover, never into a strip of
  an uncovered suggestion. Past two screens' worth of scrolling since the last scan the
  projection stops being evidence and the whole feed is covered again.
- **Stopping is what triggers a scan**, rather than a fixed debounce: the scan is armed
  for 70ms after the last scroll and re-armed by each new one, so it lands as the feed
  settles. On the emulator the precise cover appears 20–280ms after the last scroll event,
  labels included. A long slow drag never goes quiet, so a scan is forced anyway every
  400ms; and everything that is *not* a scroll is throttled to one scan every 250ms,
  because a video playing in the feed changes its window several times a second and moves
  nothing.

## Privacy

- **No permissions.** Not internet, not storage, nothing. The manifest has no
  `<uses-permission>` element at all. The app cannot open a socket, so it cannot send
  what it reads anywhere, whatever the code says. Check it yourself:

  ```
  adb shell dumpsys package dev.amishutkin.slopsick | grep -A5 "requested permissions"
  ```

  (there is no such section, because there are none)

- **It only sees four apps.** `android:packageNames` in
  `app/src/main/res/xml/accessibility_service_config.xml` names Instagram, LinkedIn,
  YouTube and Chrome. The system enforces that; it is not a promise made in code.

- **One capability beyond reading those windows**: `android:canTakeScreenshot`, added for
  the bug-report button. It is used in exactly one file, `BugReporter.kt`, only when you
  tap the button, and what it writes goes into the app's own folder on the phone. With no
  INTERNET permission it cannot go anywhere else.

- **The manifest has a `<queries>` element**, naming the same four packages. That is not a
  permission — it declares which packages this app is allowed to *see the existence of*,
  which Android 11 hides by default — and it is there so a bug report can record which
  version of the app it came from. `QUERY_ALL_PACKAGES` is deliberately not used.

- **Nothing leaves the device and nothing is stored** except the four switches and any
  bug reports you write yourself.

- **No dependencies** beyond JUnit for the tests. Every library is one more thing a
  reader has to trust, and the point of this app is that it can be read in an afternoon.

- Release builds are **unminified**, so the shipped APK stays readable.

If you are reviewing a change to this app, the single thing to refuse is a new
permission.

## Bug reports

Everything this tool gets wrong, it gets wrong about a particular screen, on a particular
build of a particular app, in a particular language. None of that survives being described
from memory, and none of it reproduces on another device. So the cover carries a button.

Tap the 👎 in the corner of a cover when it gets something wrong, and SLOPSICK writes
three files:

| file | what it is |
| --- | --- |
| `tree.xml` | the accessibility tree, in `uiautomator dump` format — the same format the test fixtures are in, so it drops straight into the corpus and becomes a test |
| `report.json` | what the analyzer made of that tree: every item, its verdict and reason, the regions painted, the app's version, the device |
| `screen.png` | taken with the covers **up** — where the rectangles landed is what the reports are about, and it means the folder does not fill with pictures of the feed |

Collect them over USB:

```
adb pull /sdcard/Android/data/dev.amishutkin.slopsick/files/reports
```

To turn one into a test, anonymise it and drop it in:

```
python3 tools/anonymize.py <dir-with-tree.xml> app/src/test/resources/fixtures
```

**`tree.xml` contains the posts that were on screen** — names, handles, text — because
that is what the analyzer reads, and a report without it cannot reproduce anything. The
screenshot does not: it is a picture of the covers. All of it sits in this app's own
directory and goes nowhere else; there is no INTERNET permission.
Nothing is written unless you tap the button, and the button only exists while the
reporting switch is on. Delete them when you are done:

```
adb shell rm -rf /sdcard/Android/data/dev.amishutkin.slopsick/files/reports
```

## Building

See [INSTALL.md](INSTALL.md) for the full story, including signing and the emulator.

Needs JDK 17+ and an Android SDK with platform 36.

```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest    # the tests below
```

### Signing

`assembleRelease` looks for `keystore.properties` in the project root, pointing at a
keystore that lives outside the repository. Both are gitignored. Without them the release
build still succeeds — just unsigned — so a checkout by anyone else still compiles.

**The keystore is not recoverable.** Android refuses an upgrade signed by a different
key, so losing it means everyone who installed a build has to uninstall before they can
take another one. Back up the keystore and `keystore.properties` together.

Release builds are deliberately **unminified**. The point of this app is that the shipped
APK can be read; obfuscating it would undercut the only claim it makes.

Then install it, and turn it on under Settings → Accessibility → Installed apps →
SLOPSICK feed filter.

## Settings

Three switches and a lock, and that is the whole screen.

One switch per app — Instagram (which also covers instagram.com in Chrome), LinkedIn,
YouTube. Off means that app is not touched at all.

What each app's switch *means* is decided in `Settings`' defaults rather than by the
reader. An earlier version put all eleven flags on screen; a screen of eleven switches is
a screen nobody reads, and every one of them is another thing to get wrong. They remain
as fields, because the analyzers are tested through them and because exposing one later
is a one-line change.

### The lock

Locks the switches for a chosen time, an hour by default. While it holds, **a switch that
is on cannot be turned off**; switching more *on* is still allowed, and so is extending
the lock. Only the moment of weakness is prevented.

It is a promise the app makes to you, not a security measure. Android's own accessibility
toggle is always there and nothing here tries to make that harder — a lock that fought
the user for control of their own phone would be a worse thing than the feed.

**Installing it: see [INSTALL.md](INSTALL.md).** One thing there is worth repeating
here, because it fails silently: install with `adb install -r -i com.android.vending`.
Without an installer attributed, Android 13+ treats the app as sideloaded and revokes its
accessibility access at the next reboot, with no error anywhere.

**It runs on its own.** An accessibility service is bound by the system, not by the app's
UI: once switched on it runs whenever the phone is on, from boot, whether or not the
settings screen has ever been opened, and it comes straight back if its process is killed.
There is no notification and no wake lock — it only wakes when one of the four apps it is
allowed to see sends an event. Turning it off is the accessibility toggle, nothing else.

## Tests

125 JVM tests, no device or emulator needed. The analyzers work against a `UiNode`
interface rather than `AccessibilityNodeInfo`, so they can be run against 60 UI trees
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
adb shell setprop log.tag.SlopsickTree VERBOSE
adb logcat -s SlopsickTree
```

After `adb install -r`, the accessibility framework keeps the old, now-dead service bound
and silently delivers it nothing. Force-stop the app and toggle the service off and on,
or you will spend a while debugging code that is not running:

```
adb shell am force-stop dev.amishutkin.slopsick
adb shell settings put secure enabled_accessibility_services ''
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services dev.amishutkin.slopsick/dev.amishutkin.slopsick.platform.SlopsickAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The most useful tests are the corpus invariants, which hold over all 60 screens rather
than expectations about one of them — in particular that **everything not explicitly
kept ends up covered**, and that **no capture ever has its navigation bar painted over**.

`ytshelf-unlabelled.xml` is worth singling out: a real YouTube capture with the "Shorts"
heading and every `play Short` suffix deleted, which is the phone that reported the bug,
reproduced. The shape rule covers the shelf in it; the old word rule covered nothing.

## What is not done

Being specific, because the gaps matter more than the features:

- **Ad detection is unverified.** No sponsored post appeared in 34 Instagram screens or
  8 LinkedIn ones — the recon account was new and had no ad profile. The Instagram ad
  path is a string match written from the docs and has never matched anything real. It
  is the one word-match in `InstagramAnalyzer.kt`, and it is marked as such.
- **The YouTube Shorts shelf rule is a shape rule, and shapes are not proofs.** Two or
  three tall tiles side by side is a Shorts shelf everywhere it has been seen, but any
  other multi-column portrait row in the feed would be covered too. Nothing in the
  captures is, and the alternative — matching words — is what failed on a real phone.
- **Instagram's stories row is still left uncovered**, along with Instagram's own toolbar,
  which together are the top 23% of the screen. That is by choice, not by accident; it is
  one flag (`hideStoriesTray`) if it should go.
- **LinkedIn's post grouping is wrong on LinkedIn 4.1.1196.** The analyzer assumes one
  child of the lazy column is one feed item. Phone reports show a post split across
  several children: one carries the "X liked this" header and another the post itself, so
  the header is covered as unreadable while the post beside it stays. Most children come
  back with no labels at all — `NO_SIGNAL`, covered by default — which is why a post you
  subscribe to gets covered too. This needs the Instagram treatment (group a run of
  children into one post) and is the largest thing still outstanding.
- **LinkedIn cannot be re-verified on the emulator.** That account was restricted after
  being driven with scripted input; see the note in this file's history. It is covered by
  12 captured screens plus the phone reports.
- **The stories row is kept**, in the app and on the web alike: those are people you
  followed on purpose.
- **The web feed leaves a thin strip below the site header uncovered** — the region
  starts at the header's reported bottom and the sticky header overlaps a little further.
- **A strip under the status bar can leak.** When an app draws its content edge to edge,
  the top ~60px sits under the transparent status bar, and the cover stops there because
  painting over the clock is worse.
- **Chrome covers instagram.com only.** LinkedIn and YouTube on the mobile web are not
  read.
- **Mostly tested on an emulator** (Pixel-shaped AVD, Android 16) with spot checks on one
  real phone. The emulator's Instagram and YouTube are a different build from the phone's,
  which is how the Shorts bug got in.
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
app/src/main/java/dev/amishutkin/slopsick/
  core/       no Android imports; pure functions over a UiNode tree — this is the part
              that is tested
  platform/   the accessibility service, the overlay window, the settings store
  ui/         one settings screen
app/src/test/ the tests, and 60 anonymised device captures
tools/        the anonymiser
```

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE) — use, modify and share it for anything that is
not primarily for commercial advantage, with no warranty and no liability.

That is deliberately **not** an OSI-approved open source licence; restricting the field of
use is exactly what the Open Source Definition forbids, so hosts will label it "Other".

The code here is original. It shares no code with the MIT-licensed browser extension it
takes its idea from — only the idea, which is not copyrightable. The extension fork is a
separate repository and stays MIT.
