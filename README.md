
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

## Random rotation comparison demo

With `crop.planner.strategy=random`, `POST /crop-optimization` accepts any nonblank
`currentCrop` answer, including `Pasture`, `Not sure`, `Nothing planted` and custom
names. Known crop IDs are still resolved to catalog display names. Growable crops
remain validated against `GET /crops`.

Each field includes `alternatives`: three distinct five-year sequences with titles,
summaries, hypothetical benefits, tradeoffs and per-year `rationale`. A one-crop
pool can only produce one distinct sequence; the frontend uses the full catalog
and always receives three. The existing `years` property mirrors the first option
for consumers that expect one sequence. Numeric soil and income scores remain absent.
Explanations are explicitly demo content and do not establish field suitability.

## Chat assistant

`POST /chat/stream` answers a farmer's question as server-sent events, using Spring AI 2.0 with any
OpenAI-compatible endpoint. The app sends what is on screen in `context`, so "this field" needs no
explanation:

```json
{"conversationId": null,
 "message": "What can you tell me about this field?",
 "context": {"selectedFieldId": "ON-12345",
             "fields": [{"fieldId": "ON-12345", "name": "North 40", "currentCrop": "corn", "geometry": {...}}],
             "location": {"lat": 44.35, "lon": -79.16}, "provinceCode": "ON",
             "growableCrops": ["corn", "soybeans", "wheat"]}}
```

Each SSE `event:` name matches the `type` in its JSON `data:`. `meta` comes first and carries the
`conversationId` to send next time. `model` and `fallback` say which model is answering. `token` events
are Markdown pieces to append. `tool_call` and `tool_result` report each data lookup with a label to show
while it runs. `proposal` is a rotation plan for the farmer to accept or reject in the app; nothing is
saved by the backend. The stream ends with `done` or `error`. `POST /chat` returns the same reply gathered
into one JSON object, and `DELETE /chat/{conversation_id}` clears a conversation's memory.

The model's tools call the existing services and the app's own calculations; the model only explains:

| Tools | What they do |
|---|---|
| `getFieldOverview`, `getFieldSoil`, `getFieldClimate`, `getCropHistory`, `getAgroWeather` | Field data, shortened for the model |
| `getVegetationTrend`, `getSeasonGreenness`, `getDroughtIndicators` | Slow satellite lookups (20-45 s first time) |
| `getSoilLandscape`, `getSeasonalOutlook`, `getDailyWeather`, `findFieldsNear` | Location data; default to the pin or field |
| `listCrops`, `getCropProfile`, `getCropCalendar`, `getMarketPrices` | Crop facts, soil roles, planting dates, prices |
| `assessCropSuitability` | Climate and soil fit per crop (`CropSuitabilityAssessor`, shared with the optimizer) |
| `proposeRotationPlan` | Runs the configured planner and sends a `proposal` event |
| `compareRotations` | Scores the farmer's rotations and the optimized one with the optimizer's terms |
| `findBareFieldWindows` | Off-season gaps from the crop calendar, optionally MODIS low-cover windows |
| `explainTerm`, `getDataSources`, `getCurrentDate` | Glossary (`chat/glossary.json`), sources (`chat/data-sources.json`), date |

A field id left empty means the selected field. Tools also receive the request context through Spring AI's
`ToolContext`, which is never sent to the model.

If a model fails with a timeout, connection error, HTTP 408/409/429 or 5xx before anything visible was
streamed, the next model in `chat.fallback-models` answers instead. After the first token, tool call or
proposal, the reply ends with `error` rather than switching models. Memory (the last
`chat.memory.max-messages` messages per conversation, in memory) only records completed turns.

Configuration, in the external secrets file:

```properties
# Include the API version path: Spring AI 2.0 uses the official OpenAI SDK, which does not add /v1
spring.ai.openai.base-url=https://your-endpoint/v1
spring.ai.openai.api-key=...
spring.ai.openai.chat.model=primary-model
# Optional, tried in order; base-url and api-key default to the primary's
chat.fallback-models[0].model=fallback-model
chat.fallback-models[1].model=other-model
chat.fallback-models[1].base-url=https://other-endpoint/v1
chat.fallback-models[1].api-key=...
```

`application.properties` sets a placeholder key so the application starts without one. It also sets the
temperature, fast retries, tool-call limits and a 5-minute async request timeout for long streams. Tests
use a scripted `ChatModel` through a real `ChatClient`, so no provider is called.
