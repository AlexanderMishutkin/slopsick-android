# Changelog

Versions are what was actually installed on a phone. Every release from 1.1.1 onwards
exists because something on that phone was wrong and the 👎 button said so, which is why
each entry names the failure rather than the feature.

## 1.1.6 — 2026-09-05

- **The "You've seen all new posts" bar is kept.** It is the line where the feed you chose
  ends, and covering it removed the one thing on screen that distinguished a working
  filter from a blank feed. It now breaks the post grouping the way a header does; the
  recommendations below it are still covered.
- **A half-drawn frame no longer paints the screen white.** Instagram rebuilds the feed
  under the reader, and for a frame or two the posts have no headers. The guard used to
  fire only when a scan recognised *nothing*; it now fires when a scan on an unmoved
  screen keeps less than a quarter of what the previous one kept.
- **Settings carry a schema version.** A default that changes in a release never reaches
  anyone who has opened the settings screen even once, because a saved preference wins
  over a new default. Hiding what your network merely liked or commented on became the
  default in 1.1.3 and stayed off on a phone through three releases, which looked exactly
  like an analyzer that could not read the post. One migration step corrects it. It is not
  a lock — the switch still works afterwards.

## 1.1.5 — 2026-09-04

- **The web feed's top bar ends where it ends.** Growing the bar to "the lowest edge of
  anything short near the top of the page" swallowed the first post's avatar once the
  stories row had scrolled under the sticky header, and the covered region then began
  below the post it was meant to judge. It is grown by overlap from a flush start instead,
  and capped.

## 1.1.4 — 2026-09-04

- **Russian, on both surfaces that need it.** The native apps ship English view ids
  whatever the interface language is; Chrome and LinkedIn expose only translated
  screen-reader labels. A phone with Instagram and LinkedIn in Russian matched not one
  landmark — the browser feed was covered end to end, and LinkedIn kept everything.
- **Chrome reads by shape first.** Bars, the stories tray and post headers are found
  geometrically, with words as a second opinion, so a language the analyzer has never seen
  still parses. A Spanish fixture holds it to that.
- **LinkedIn no longer keeps a post on the strength of a timestamp.** The loose author
  guess read "13 ч." as a person, and a post with an author and no follow control is taken
  to be someone you know, so an unreadable feed came back entirely KEEP. Keeping a post now
  needs an author read from a "view profile" label.
- **No more hairline covers.** A band under 40px is not painted at all — those were the
  stray polygons, and they were never anything but noise.

## 1.1.3 — 2026-09-03

- **Blocked buttons are windows, not paint.** The main overlay passes touches through so
  scrolling still works, which meant a painted-over Reels tab still opened Reels when
  tapped blind. The button gets its own window, exactly its size, that swallows the touch.
- **A frame that recognises nothing is not believed.** It is retried rather than covered.

## 1.1.2 — 2026-09-03

- **The overlay window does not start where it asked to.** Every band was landing 138
  pixels low — a strip of live feed above each cover — because the window's own origin is
  not the origin it requested. The plan is projected into the window's real coordinates.

## 1.1.1 — 2026-09-03

- **The navigation bar is never covered.** From eight bug reports off a real phone whose
  LinkedIn reported its own bar as zero-height and pinned to the bottom of the screen. The
  bar is now found three ways — by id, by shape, and by refusing to use the space when an
  app still claims to have one.

## 1.1.0 — 2026-09-03

First release installed on a real phone. Instagram, LinkedIn and YouTube apps plus
instagram.com in Chrome; Reels, Shorts and Explore; the 👎 report button; three switches
instead of eleven; signed release builds.

## 1.0.0 — 2026-09-02

Renamed to SLOPSICK. Settings screen, the overlay, and the accessibility service.

## 0.1.0 — 2026-09-01

The classification core, tested against captured device trees. No Android imports in it,
which is why it is the part that is tested.
