# FocusTube for Android

Covers the posts you did not ask to see — in the Instagram, LinkedIn and YouTube apps,
and on instagram.com in Chrome.

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

**It runs on its own.** An accessibility service is bound by the system, not by the app's
UI: once switched on it runs whenever the phone is on, from boot, whether or not the
settings screen has ever been opened, and it comes straight back if its process is killed.
There is no notification and no wake lock — it only wakes when one of the four apps it is
allowed to see sends an event. Turning it off is the accessibility toggle, nothing else.

## Tests

85 JVM tests, no device or emulator needed. The analyzers work against a `UiNode`
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

After `adb install -r`, the accessibility framework keeps the old, now-dead service bound
and silently delivers it nothing. Force-stop the app and toggle the service off and on,
or you will spend a while debugging code that is not running:

```
adb shell am force-stop dev.amishutkin.focustube
adb shell settings put secure enabled_accessibility_services ''
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure enabled_accessibility_services dev.amishutkin.focustube/dev.amishutkin.focustube.platform.FocusAccessibilityService
adb shell settings put secure accessibility_enabled 1
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
- **The YouTube Shorts shelf rule is word-based.** YouTube's feed rows carry no view ids
  at all, so a shelf is a row whose heading is exactly "Shorts", plus the rows under it
  whose items describe themselves as "... - play Short". Narrow enough not to catch a
  video titled "I wore Shorts for 30 days", but "play Short" is translated, so in another
  language only the heading would be covered. Verified against a real shelf on device.
- **The web feed leaves a thin strip below the site header uncovered** — the region
  starts at the header's reported bottom and the sticky header overlaps a little further.
- **A strip under the status bar can leak.** When an app draws its content edge to edge,
  the top ~60px sits under the transparent status bar, and the cover stops there because
  painting over the clock is worse.
- **Chrome covers instagram.com only.** LinkedIn and YouTube on the mobile web are not
  read.
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
