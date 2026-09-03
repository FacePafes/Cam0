# Contributing to Cam0

Short version: bug reports are welcome right now, code PRs less so --
Cam0 is still MVP and the codebase is moving fast enough that a PR
today might just get overtaken by the next round of changes. That's
not a permanent door-closing, just where things are at for now. Once
it's past MVP this'll open up properly.

## Bug reports

Open an issue. That's the default, and public issues are genuinely
useful -- if you hit something, someone else probably will too.

If it's something you'd rather not post publicly (security-shaped
stuff, anything that feels like it shouldn't be laid out for anyone to
exploit), email me instead -- my email's on my GitHub profile.

## Device compatibility

If you build it and it works (or doesn't) on a device that's not
already listed in the README, saying so is genuinely helpful, even
informally -- a comment on an issue like "built and ran fine on a
[device]" counts. Nothing formal here at MVP stage, no official
device-support list being tracked yet, just a running sense of what's
been tried. A proper testing process is a post-MVP problem.

## If you're poking at the code anyway

Not asking for PRs yet, but if you're reading the source and something
seems off, an issue describing it is still useful even without a fix
attached. A few things that'll save both of us time if you do end up
proposing a change later:

- No new dependencies without discussing it first. GuiLite for the UI
  chrome, CameraX for the camera, otherwise plain Android framework --
  that's deliberate, not an oversight.
- If it touches `core/camera_overlay.cpp`, it should still compile
  clean against the real `GuiLite.h` (`g++ -std=c++17 -Wall -c
  camera_overlay.cpp -I core -I third_party/GuiLite`) -- catches a lot
  before it ever needs an Android build.
- `third_party/GuiLite/GuiLite.h` has one intentional local patch:
  `ASSERT()` is disabled by default (search for "Cam0 local patch" in
  that file). Without it, GuiLite's own internal asserts bake the
  compile-time absolute path to `GuiLite.h` -- i.e. wherever it lives
  on *your* machine -- into a real string constant in the compiled
  binary, survives `strip`, ships in release builds. Found this the
  hard way. If GuiLite ever gets updated to a newer upstream version,
  reapply that patch, don't just drop in a fresh copy of the file.

## License

By contributing (code, or anything that ends up in the repo), you're
agreeing it's under the project's GPLv3 license.
