# Nexa mobile launcher artwork

Launcher artwork comes from the preserved Design Lab sources outside this
repository:

- Operations Android: `design-lab/shared/brand/nexa-mobile-inverted.svg`, with
  the white background and blue mark.
- Buyer Android and iOS: `design-lab/shared/brand/nexa-mobile.svg`, with the
  Nexa blue `#2563EB` background and white mark.

The source files remain unchanged. PNGs are rendered directly from those SVGs
with `rsvg-convert` 2.62.4. Android adaptive foregrounds remove only the
source background rectangle and place the original vector artwork on a
transparent 108 dp canvas. The source tile is scaled to 72 dp; its horizontal
mark spans about 50 by 27 dp and stays within Android's centered 66 dp safe
circle, including circular masks. The supplied horizontal artwork falls below
Android's [documented 48 dp minimum](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) on its short axis; no alternate compact mark
was supplied, so the complete source shape is preserved. Adaptive backgrounds
match the corresponding source colors. Density-specific legacy icons render
the complete source artwork.

Buyer iOS app-icon sizes render directly from `nexa-mobile.svg` into the Xcode
asset catalog as square, unmasked PNGs. iOS applies its platform mask.
