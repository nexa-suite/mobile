# Nexa mobile launcher artwork

`nexa-mobile.svg` is the preserved source artwork provided for the Operations Android launcher icon. It uses the Nexa blue `#2563EB` square background and the complete white Nexa mark and wordmark.

The Android adaptive icon keeps that blue as its full-bleed background and uses a transparent white-wordmark foreground. Foreground artwork is centered on a 108 dp canvas and limited to 62 dp, inside Android's 66 dp safe zone. The `ic_launcher_round` adaptive resource receives the launcher's circular mask; its legacy bitmap fallback uses a blue circle and a proportionally scaled wordmark. Square and round legacy bitmaps are supplied at Android density buckets for compatibility.

Raster derivatives were rendered from the preserved SVG with `rsvg-convert` 2.62.3 and alpha-cropped/resized with Pillow 11.3.0. The source geometry is retained without cropping; only the surrounding transparent padding changes for the adaptive safe zone and circular legacy fallback.
