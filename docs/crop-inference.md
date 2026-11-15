# Crop-history inference

Python acquires national crop-map observations: provider class names, class shares,
source metadata and missing years. Crop group normalization, rotation detection and
current-season suggestions belong to `CropHistoryInferenceService` in Java. No HLS
interpretation or planting-window model is introduced by this change.

The field, outline and batch crop-history routes all apply the same interpreter.
Java recomputes summaries from observations, even if an older Python deployment
still supplies its own `summary` or group labels. Batch order and per-field errors
are preserved.

## Response contract

`summary.reviewYear` is the application calendar year (UTC).
`summary.latestMapped` is the latest source observation at or before that year.
`summary.currentCrop` always states one of:

- `mapped`: the evaluated year has a sufficiently clear crop classification.
- `estimated`: a rotation supports a crop, with a reason and a high/medium pattern-support label.
- `unavailable`: the evidence is insufficient; crop, group and confidence are null.

Mapped classifications and rotation estimates are not farmer confirmations.
Confidence labels are not calibrated probabilities. `expectedThisYear` remains
available for consumers of the older estimate shape, but is derived in Java.

`summary.recentYears` and `summary.timeline` carry calendar slots with a null
observation for missing years. React displays these fields and collects user input.
It neither calculates the review year nor supplies an alternative suggested crop.

## Pattern support

The interpreter uses a contiguous, sufficiently clear suffix of at most nine
calendar years. Missing, duplicate or low-share latest observations break support;
source rows are not compressed across gaps. Stable crops and established two- or
three-year patterns retain high/medium support labels. A five-year exact recent
three-year sequence can produce a tentative medium-support estimate, including
wheat, corn, soybeans, wheat, corn pointing to soybeans next. Older irregular years
need not suppress that recent candidate.

A changed final season, mixed/non-crop observations, stale maps or insufficient
support produce an unavailable result. Last year's crop is never a substitute.
The algorithm is a transparent rotation heuristic and has not been validated as a
current-season satellite crop classifier.

## Verification and rollout

The inference tests use a fixed calendar. MVC tests cover indexed-field, outline
and batch endpoints, including JSON serialization and preservation of errors.
The normal socket-based integration tests remain part of the suite.

Deploy Java first, then the frontend and Python acquisition changes. No new secret
configuration is required. The frontend API proxy must point to Java. Services need
rebuilding/restarting to use the new contract; editing source does not update a
remote deployment.
