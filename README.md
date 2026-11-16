# SpaceApps 2026 Application Backend

The application backend serves every request from the React frontend. It is the
only path between the frontend and the Python data service, and it holds all of
the application's logic: interpreting field data, judging crop suitability,
planning crop rotations, pulling in Canadian regional and market data, and
running the chat assistant.

## What it is responsible for

- **The single entry point for the frontend.** The frontend calls only this
  backend. The data service is never exposed directly.
- **Typed copies of every data-service route.** Each data-service response is
  read into Java models and written out again, not forwarded as-is, so logic can
  be added in the service layer.
- **Interpretation.** Turning raw observations into farmer-facing meaning, for
  example crop groups and rotation patterns from crop maps, or display quality,
  gaps and summaries for season charts.
- **Planning and decision support.** Crop suitability, rotation planning and
  comparison of strategies.
- **Other non-NASA data the application needs**, fetched directly.
- **The AI chat assistant** and its tools.

The frontend only displays results; any calculation happens here.

## Field data

| Route | Description |
|---|---|
| `GET /fields`, `GET /fields/{id}` | Field outlines for the map, and everything known about one field gathered into one response. A source that fails is reported as unavailable rather than failing the request. |
| `/field-summary`, `/agro-weather`, `/daily-point`, `/soil-moisture`, `/drought-indicators` | Climate, water and drought data, from the data service |
| `/soil`, `/fields/soil` | Soil properties, from the data service |
| `/crop-history`, `/fields/crop-history` | What was grown each year, with crop groups and rotation patterns worked out here |
| `/crop-calendar`, `/greenness`, `/vegetation-history` | Planting dates and vegetation trends, from the data service |
| `GET /fields/{id}/season` | One season's satellite greenness, prepared for charting: quality grading, one best observation per day, gaps and summaries. It does not guess planting or harvest dates. |
| `/imagery`, `GET /imagery/image` | Satellite images of a field. Images are fetched through this backend, and only from the expected Planetary Computer address. |
| `/field-coverage`, `/health`, `/ready` | Data coverage and service status |

## Data from other sources

These come from Canadian public services. The data service is not involved:

| Route | Source | Provides |
|---|---|---|
| `GET /soil-landscape` | Agriculture and Agri-Food Canada (CanSIS, agri-environmental indicators) | Land capability class and limitations, drainage, erosion risk, soil cover days, organic carbon change, residual nitrogen |
| `GET /seasonal-outlook` | Environment and Climate Change Canada, CanSIPS via MSC GeoMet | Chances of above- or below-normal temperature and precipitation over the coming months |
| `GET /crops/market` | Statistics Canada | Recent provincial yields and farm prices per crop, and gross revenue estimates |

All three cover Canada only.

## Crops and rotation planning

- `GET /crops` and `GET /crops/reference` return the crop catalog. Each crop's
  climate and soil needs come from FAO ECOCROP. Nitrogen, residue, disease and
  margin values are estimates still to be reviewed.
- `POST /crop-optimization` takes one or more fields, the crop in each now, the
  crops the farmer can grow and the farmer's priorities, and returns a five-year
  plan per field with alternatives to compare.

The planner is chosen with `crop.planner.strategy`:

| Strategy | Behaviour |
|---|---|
| `random` (default) | Placeholder rotations with demo explanations |
| `mock` | Varied, repeatable demo plans. Not calculated from field data. |
| `dynamic` | Finds the best-scoring five-year sequence. Each year's score blends **soil health** (disease pressure from repeating a crop family, nitrogen balance, residue and soil cover), **reliability** (how well the field's climate and soil suit the crop) and **income**, weighted by the farmer's priorities. |

Crop suitability compares the field's growing-season temperature, rainfall,
frost-free days, soil texture, drainage and land capability with each crop's
needs. The weakest condition sets the result. Missing data never rules a crop
out. The planner and the chat assistant share this check, so they give the same
answer.

## Chat assistant

`POST /chat/stream` (streamed) and `POST /chat` answer a farmer's questions in
plain language, using Spring AI 2.0 with an OpenAI-compatible model.
`DELETE /chat/{id}` clears a conversation. The app sends what is on screen
(selected field, location, growable crops), so "this field" works without
explanation.

The model doesn't calculate anything itself. It calls tools that use the same
services as the rest of the API:

- **Field data:** overview, soil, climate, crop history, weather, vegetation,
  season greenness, drought
- **Location data:** soil landscape, seasonal outlook, daily weather, nearby fields
- **Crops:** catalog, profiles, planting calendars, market prices, suitability
- **Planning:** propose a rotation, compare rotations, find bare-field periods
- **Explanations:** glossary of terms (NDVI, evapotranspiration, frost risk and so
  on), data sources, today's date

A proposed rotation comes back as a `proposal` event for the farmer to accept or
reject in the app; the backend saves nothing. If a model fails before answering,
the next configured fallback model takes over.

## Supporting, not replacing, local judgement

Results combine regional satellite data, models and estimates. Every plan and
answer includes its assumptions and gaps. Field-level decisions still need the
farmer's knowledge, soil tests and local agronomic advice.

OpenAPI docs are generated from the controllers and served by Swagger UI.

## AI Usage

AI was used during this project for:

- Brainstorming and ideas
- Research
- Data interpretation
- Code generation
- Writing and editing