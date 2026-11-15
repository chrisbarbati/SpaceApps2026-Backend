
## Field-season satellite evidence

`GET /fields/{field_id}/season?year=2026` starts or polls HLS acquisition through
Python. Omit `year` for the current UTC calendar year. Java chooses the inclusive
period, assigns display quality, chooses the best-supported observation per day,
and prepares `points`, gap-aware `segments`, `gaps`, axis ticks and observed
summaries. The browser should render this contract without rebuilding these rules.

Clear daily observations require at least 70% valid sample pixels, 90% field
coverage and three measured native pixels. Partial observations need 20% valid
sample pixels and are displayed separately; they do not enter peak/latest
summaries. Snow, water and unsupported passes have null plotted NDVI. Clear line
segments stop across gaps longer than 20 days. Daily selection follows support,
not maximum greenness. All numeric thresholds are presentation policies in
`FieldSeasonService`, independent of Python's raster measurements.

Statuses are `queued`, `running`, `done`, `partial` and `failed`; poll the first
two. `retry=true` retries a terminal acquisition, reusing Python's raster cache.
No request waits for the entire season. No crop, harvest or planting date is
inferred from this endpoint. Quality notes and provenance accompany every result.
Existing crop-history inference remains separate.

Python must provide `/fields/{field_id}/season` on the existing `data.backend.url`.
No new application property is required. Service checks are in
`FieldSeasonServiceUnitTest`.
