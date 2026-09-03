# Icon artwork

The mascot is the Android robot mid-sprint, wearing a `GH` sash: the thing this
project does, in one picture.

| File | What it is |
| --- | --- |
| `icon.svg` | Full artwork on the `#1B4B7C` background. 1024×1024 viewBox. |
| `icon-fg.svg` | The same figure with no background — the adaptive-icon foreground layer. |
| `github-app-avatar.png` | 1024×1024 avatar for the [GitHub App](https://github.com/settings/apps/droidgithubrunner). |

These SVGs are the design source. Everything else is generated from them, so
edit the SVG and re-render — never touch a PNG by hand.

## Re-rendering

```bash
# GitHub App avatar: centre the artwork and let it fill ~80% of the frame, so it
# survives being masked into a circle (GitHub does that in some places).
rsvg-convert -w 2048 -h 2048 art/icon-fg.svg -o /tmp/fg.png
# then crop to the alpha bounding box, scale to 0.80 of 1024, centre on #1B4B7C
```

The launcher icons under `app/src/main/res/mipmap-*/` are this same artwork, but
they are **not** a plain render of `icon-fg.svg`: an adaptive icon's foreground
is zoomed by the launcher's mask, so the figure sits inset inside the 108dp
canvas (measured at roughly 0.83 of a straight render). Regenerate those through
Android Studio's Image Asset tool, or by rendering `icon-fg.svg` scaled down and
centred — and check the result on a device, since every vendor masks differently.

Colours match the app: background `#1B4B7C`, robot `#7ED388`, sash `#5CBF6E`,
sash text and eyes in the background blue.
