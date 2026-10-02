# Nexa mobile launcher artwork

`nexa-mobile.svg` is the preserved source artwork provided for the Operations Android launcher icon. It uses the Nexa blue `#2563EB` square background and the complete white Nexa mark and wordmark.

The Android adaptive icon keeps that blue as its full-bleed background and uses a transparent white-wordmark foreground. Foreground artwork is centered on a 108 dp canvas and limited to 62 dp, inside Android's 66 dp safe zone. Both launcher resources are adaptive drawables in `drawable-anydpi`; the `ic_launcher_round` resource receives the launcher's circular mask. The app minimum is API 29, so adaptive icons cover every supported Android version without legacy bitmap variants.

The foreground raster was rendered from the preserved SVG with `rsvg-convert` 2.62.3 and alpha-cropped/resized with Pillow 11.3.0. The source geometry is retained without cropping; only the surrounding transparent padding changes for the adaptive safe zone.
