# Nexa Mobile v3.0.3

Buyer can reopen unfinished purchase requests using the version returned by the
API. The list shown when resuming a request uses plain-language progress labels.

Both apps add an interface language setting for English (US) and Latin American
Spanish. The selection is saved on the device. Some screens still contain
untranslated text.

| Android app | Version | Minimum Android | APK |
| --- | --- | --- | --- |
| Buyer (Flutter) | 2.0.2 (build 7) | Android 7.0 (API 24) | `nexa-buyer-v2.0.2.apk` |
| Operations (Kotlin) | 3.0.3 (build 17) | Android 10 (API 29) | `nexa-operations-v3.0.3.apk` |

## Validate with ICISA

1. Install the APK for your role and sign in.
2. In Buyer, open Account, then Preferences, then App language. In Operations,
   open the profile menu and select Display language.
3. Change the language, reopen the app, and confirm the selection is retained.
4. In Buyer, open Orders, then Requests, then Drafts. Open an existing draft
   and check that the products and delivery details are available.
5. In Operations, open the worklist for your role and check that it loads.
