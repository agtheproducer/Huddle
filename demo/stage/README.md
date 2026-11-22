# Rehearsal stage

One local page to watch the demo calculator while the agents build it.

- **Files**: everything under `demo/calculator` (no build output), with when it last changed. A file an agent just touched lights up orange.
- **Code**: the file you click, refreshed when it changes.
- **Live app**: the calculator frontend served from disk, reloaded when `frontend/` changes, plus whether the Kotlin backend on `:8090` is up.

## Run it

In IntelliJ, pick **Rehearsal** in the run configurations and press Run. It starts the calculator backend (`:8090`) and this stage (`:8095`). Then open <http://localhost:8095>.

From a terminal:

```bash
./gradlew :calc-backend:run     # http://localhost:8090
./gradlew :demo-stage:run       # http://localhost:8095
```

The page and the app reach the backend through the stage (`/backend/...` → `CALC_URL`, default `http://localhost:8090`), so no browser blocks the call; the files on disk are never changed. The stage only listens on localhost and only serves files inside `demo/calculator`. Port: `STAGE_PORT`.
