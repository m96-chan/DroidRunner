# Icon artwork

The Android robot running with a box under its arm — a git graph and a green
check on the side. What the project does, in one picture.

| File | What it is |
| --- | --- |
| `icon-fg.svg` | The figure alone, transparent. **This is the source.** |
| `icon.svg` | The same figure on the launcher background, for anything wanting one self-contained file. |
| `github-app-avatar.png` | 1024×1024 avatar for the [GitHub App](https://github.com/settings/apps/droidgithubrunner). |
| `build-icons.py` | Renders every icon the project ships, from `icon-fg.svg`. |

Edit the SVG and re-run the script. Never touch a generated PNG by hand:

```bash
python3 art/build-icons.py        # needs rsvg-convert and Pillow
```

It writes the launcher icons for all five densities, the GitHub App avatar and
the site favicons. The GitHub App logo itself has no REST endpoint — upload
`github-app-avatar.png` through the app's settings page by hand.

## Two things the script encodes

**An adaptive icon's foreground is masked to the centre 72 of its 108dp
canvas**, so the artwork cannot fill the frame: it is scaled to 0.57 of the
canvas, which is what the icon shipped with before this script existed
(measured off the old assets, not guessed). Every vendor masks to a slightly
different shape, so check a real device after changing it.

**The background is `#0B0E14`** — the dashboard's background, so the icon and
the app agree. It is duplicated in `app/src/main/res/values/colors.xml` as
`ic_launcher_background`; change both.

## Provenance

The figure was traced from reference artwork with `potrace`, one pass per flat
colour, then reassembled. The trace deliberately drops the reference's
hairline outlines: they were invisible against white and read as dirt against a
dark background. Colours are flat — robot `#91BA26`, strap `#3F4145`, details
white — which is why it stays crisp down to 48px.

## Also in this directory

`qnn-pins.sh` has nothing to do with the artwork; it lives here because it is
the other thing kept beside the assets rather than beside the code it feeds.
It regenerates the pinned SHA-256 digests in
`app/src/main/java/io/github/m96chan/droidrunner/npu/QnnArtifacts.kt`, because
Maven Central publishes only SHA-1 and MD5 beside Qualcomm's AARs and there is
nothing worth verifying a download against until we compute a SHA-256 ourselves
and commit it. Run it when bumping the QNN release, then paste the rows it
prints into `QnnArtifacts.kt` and update `VERSION`:

```bash
art/qnn-pins.sh 2.49.0
```
