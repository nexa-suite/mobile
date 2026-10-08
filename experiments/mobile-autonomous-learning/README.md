# SPIKE-001 bounded guidance experiment

**Observed:** 2026-10-08. This is a reproducible technical experiment for the
original Mobile Report SPIKE-001. It is not an Operations app feature, a model
execution, user research, evidence of human learning, Product acceptance, or
system acceptance.

## Question and boundaries

Can a deterministic, privacy-minimal baseline give a worker contextual
orientation during the existing manual SKU search flow while preserving the
current search, retry, selection, and server-confirmation boundaries?

The baseline accepts only the existing `ProductSearchStatus` enum. It returns
a topic token, references to existing localized Android resource keys, and a
closed list of next-step tokens. It does not accept query text, candidate
details, identifiers, Tenant/Workspace scope, session data, generated text,
free-form model output, or learning history. It stores nothing. It does not
decide catalog identity, authorization, stock, receiving, picking, price, or
any other business result. Product confirmation remains an API-backed action
in the existing `WarehouseViewModel.selectCandidate` path.

The exercise tests technical containment around a possible future advisory
boundary. “Learning” is still ambiguous: this deterministic baseline offers
state-based orientation, while an on-device model could generate contextual
text. Neither behavior establishes that a person learned anything. Whether
SPIKE-001 meant contextual guidance, per-user adaptation, or another
user-learning outcome remains an Owner question. The data and persistence
permitted for any adaptive behavior are also undecided.

## Alternatives considered

| Alternative | Utility and operability | Privacy and cost | Compatibility and accessibility | Evidence limit |
| --- | --- | --- | --- | --- |
| Deterministic mapping to existing app resources and actions | Predictable, reviewable orientation for the existing status states; no model lifecycle. The map must be maintained when the status enum or UI actions change. | Input is a synthetic status enum; no inference, network request, new storage, or per-call server cost is involved in this experiment. | The app already has English and Spanish resource files. The map itself does not validate screen-reader behavior, translation quality, or whether the guidance teaches effectively. | This experiment validates only its mapping and fail-closed fixture handling. It is not integrated into the app. |
| ML Kit GenAI Prompt API with Gemini Nano | Supports custom text prompts and text or structured output, so it could explore generated explanations. Google classifies Prompt API as beta and says it takes more integration work for prompt engineering and quality assurance than feature-specific APIs. | ML Kit documents on-device processing and no per-call server cost. The API depends on Android AICore/Gemini Nano; no app data would be sent in this experiment because no model was run. Any eventual input and persistence policy still needs Owner direction. | API level 26 is the documented minimum, so Android API level alone does not rule out this app's `minSdk 29`. The runtime also requires a supported device/AICore configuration. The published support list names selected retail devices, not the observed emulator. Model language support varies by device configuration. No accessibility behavior was exercised. | No dependency was added and no Prompt API call or generated output was tested. API availability, output quality, language, latency, battery, quota, app-size, and user impact remain unmeasured. |

Official sources: [Prompt API overview and comparison](https://developers.google.com/ml-kit/genai/prompt/android), [Prompt API setup, availability states, and API floor](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [GenAI device support and on-device behavior](https://developers.google.com/ml-kit/genai), and [Google's prompt-quality evaluation guidance](https://developers.google.com/ml-kit/genai/prompt/android/evaluate-prompt). Android API resources used by the current baseline are maintained in the repository's existing [`context_strings.xml`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values/context_strings.xml) and [Spanish resource file](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values-es/context_strings.xml).

The Prompt API documentation calls for a use-case-specific input/expected-output evaluation set, recording outputs, manual or other evaluation, and repeated prompt iterations. This experiment deliberately does not fabricate that evidence: it has no model output, evaluation set for generated language, accuracy score, or prompt iteration.

## Synthetic experiment

The runner maps each current `ProductSearchStatus` value to:

- an enum-like `guidance_topic` token;
- existing resource key identifiers only; and
- zero or more existing UI next-step identifiers.

The allowed next steps are grounded in the current implementation:

| Token | Existing operation | Source evidence |
| --- | --- | --- |
| `SEARCH` | Submit the manual search from the existing query screen. | [`WarehouseViewModel.submitSearch`](../../apps/operations-android/app/src/main/kotlin/com/nexa/mobile/operations/workentry/WarehouseViewModel.kt); search button and IME action in [`WarehouseScreens.kt`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/WarehouseScreens.kt) |
| `EDIT_QUERY` | Edit the existing query field. | [`WarehouseViewModel.queryChanged`](../../apps/operations-android/app/src/main/kotlin/com/nexa/mobile/operations/workentry/WarehouseViewModel.kt) |
| `SELECT_DISPLAYED_CANDIDATE` | Select a displayed candidate, which enters `ConfirmationPending` and calls the gateway for server confirmation. | [`WarehouseViewModel.selectCandidate`](../../apps/operations-android/app/src/main/kotlin/com/nexa/mobile/operations/workentry/WarehouseViewModel.kt) and candidate row action in [`WarehouseScreens.kt`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/WarehouseScreens.kt) |
| `RETRY_LOAD_MORE` | Retry the current paginated search after `LoadMoreFailed`. | `warehouse_load_more_retry` in [`context_strings.xml`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values/context_strings.xml); `WarehouseViewModel.retrySearch` routes that state to `loadMore`. |
| `SEARCH_AGAIN` | Use the existing retry-search button for network, service, or integration errors. | `warehouse_search_retry` in [`context_strings.xml`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/res/values/context_strings.xml); retry button in [`WarehouseScreens.kt`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/WarehouseScreens.kt) |
| `CHANGE_CONTEXT` | Use the existing active-context bar when context has been invalidated. | Context bar and callback in [`WarehouseScreens.kt`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/WarehouseScreens.kt) |

Permission denial and invalid session states have no suggested local bypass.
In-progress states have no suggested action. An empty `allowed_next_steps`
array is intentional. No token instructs the client to confirm a candidate,
override access, invent stock, or mutate inventory locally.

The fixture has 17 status-only cases, one for every status in the current
Android enum, plus three fault-injection cases. The fault values
`ERRONEOUS_OUTPUT`, `INCOMPLETE_OUTPUT`, and `UNAVAILABLE` are synthetic
condition labels, not model responses. The runner has no input field for
provider text or a proposed action: it always retains the deterministic
resource-based baseline and emits the same authority constraints. Unknown
statuses, fault labels, or extra fields fail closed.

### Reproduction

From the repository root, using Python 3 and only its standard library:

```sh
python3 -m unittest discover -s experiments/mobile-autonomous-learning -p 'test_*.py' -v
python3 experiments/mobile-autonomous-learning/run_experiment.py
```

The tests compare the experiment's status set with the Kotlin enum, check that
referenced resource keys exist in both English and Spanish resource files,
validate the next-step allowlist, exercise all fault labels, and verify the
server-authority and no-local-inventory invariants. They are experiment
artifact checks; they do not run Android code or test Product behavior.

Observed on 2026-10-08 with Python 3.9.6: the runner returned
`"case_count": 20`; the test command reported 6 tests and `OK`.

## Environment observation and failure isolation

The Operations app currently declares `minSdk 29`, `compileSdk 37`, and
`targetSdk 37` in [`app/build.gradle.kts`](../../apps/operations-android/app/build.gradle.kts).
The active emulator observed on 2026-10-08 was `emulator-5554`, model
`sdk_gphone64_arm64`, API 37 (`adb devices -l`; `adb shell getprop ro.build.version.sdk`).
`adb shell pm path com.google.android.aicore` returned no package path on that
emulator. `Nexa_DDD_API29` is configured with the `android-29` Google APIs
ARM64 image. During the later API 29 promotion run it reported model
`Android SDK built for arm64`; the same AICore package-path query returned no
path. No Prompt API availability call was made on either image.

Both API levels exceed the Prompt API's documented API 26 floor. That is only
an SDK-floor comparison; it does not establish Gemini Nano/AICore support. The
API 37 and API 29 emulators had no package path for AICore. The official
supported-device list does not name these emulators. No Prompt API execution
was attempted; package observations do not establish a supported model runtime.

The experiment was run on the host with synthetic enum values. For each
injected failure class, it keeps the status-derived baseline. This demonstrates
only the runner's deterministic fallback contract. It does not prove a future
model integration's isolation, rollback, or availability behavior.

## Conclusion and backlog refinement

**Research disposition: defer runtime/model adoption.** Keep this standalone
deterministic mapping as a technical baseline artifact; do not add app
functionality, a model dependency, or client-side learning. The criteria for
learning, permitted data, persistence, and user outcome need Owner
interpretation. A future decision also needs an eligible physical device with
AICore and a reviewed, synthetic evaluation set.

Backlog refinement proposed for SPIKE-001: preserve a bounded, isolated
follow-up to (1) obtain Owner clarification of the learning outcome and data
boundary, (2) evaluate Prompt API only on an officially supported device, using
synthetic enum inputs and the existing action allowlist, (3) record actual
outputs and validate language, accessibility, failure fallback, and quality
against an approved criterion set, and (4) decide whether any runtime behavior
is warranted before proposing an app change. This is a research follow-up, not
an accepted Product story or an implementation commitment.

**Evidence status: partial technical investigation only.** The original
SPIKE-001 criterion requiring alternatives and a reproducible experiment has
bounded technical evidence here. The learning outcome and permitted data
remain open; model execution and human learning are untested; Product
acceptance and system acceptance are not evidenced. Do not close the original
spike on this artifact alone.
