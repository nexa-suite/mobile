# Bundled visual assets

Operations Android packages static instances of two typefaces from the official
[Google Fonts repository](https://github.com/google/fonts/tree/23e54b51ddffbc7713c583748e3bd86f62b1fa4a/ofl).
Both are distributed under the SIL Open Font License 1.1; their license texts
are kept beside this notice.

| Typeface | Official source at the pinned commit | Android weights | License |
| --- | --- | --- | --- |
| Plus Jakarta Sans | `ofl/plusjakartasans/PlusJakartaSans[wght].ttf` | SemiBold 600, Bold 700 | [PlusJakartaSans-OFL.txt](PlusJakartaSans-OFL.txt) |
| Inter | `ofl/inter/Inter[opsz,wght].ttf` | Regular 400, SemiBold 600; optical size 14 | [Inter-OFL.txt](Inter-OFL.txt) |

The four packaged TTFs were pinned from those official variable files with
fontTools `varLib.instancer` 4.64.0. Source Git blob IDs are
`0cb13a998ed525ba226d911b10d6c4c4f923a961` (Plus Jakarta Sans) and
`047c92f6e2212473dc436020afed689527076d44` (Inter).

The white and blue Android vector wordmarks preserve the paths and colors from
`shared/brand/logo-nexa-white.svg` and `shared/brand/logo-nexa-blue.svg` at the
signed Design Lab `v1.1.1` tag (`7f520cb483f1fa1513c514230fef69fb991504b3`).
Their SVG transforms were flattened into Android `VectorDrawable` paths.
