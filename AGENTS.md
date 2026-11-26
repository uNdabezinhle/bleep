# Repository Guidelines

## Project Structure & Module Organization

- `client/` contains the React/TypeScript client: source in `src/`, static files in `public/`, and colocated `*.test.ts` tests.
- `relay/` is the FastAPI service, with runtime modules in `app/` and pytest suites in `tests/`.
- `android/` contains the Kotlin/Compose client. Production and JVM test code follow Gradle's `app/src/main/` and `app/src/test/` layout.
- `docs/PROTOCOL.md` defines the wire protocol. `deploy/`, `scripts/`, and `Bleep-SDLC-pack/` hold deployment assets, operational helpers, and governing documents.

Keep protocol-compatible changes aligned across web, relay, and Android implementations where applicable.

## Build, Test, and Development Commands

- `docker compose up --build`: build and run Postgres, relay, web client, and TURN; open `http://localhost:8090`.
- `cd relay; python -m pip install -r requirements.txt; python -m pytest -q`: install dependencies and test with SQLite.
- `cd relay; python -m uvicorn app.main:app --reload --port 8000`: run the relay locally.
- `cd client; npm install; npm test`: install dependencies and run Vitest.
- `cd client; npm run dev` / `npm run build`: start Vite or type-check and create a production bundle.
- `cd android; .\gradlew.bat test assembleDebug`: run JVM tests and build the debug APK. Use JDK 17 or newer.

## Coding Style & Naming Conventions

Use four spaces for Python and Kotlin and two for TypeScript; retain semicolons and double quotes in TypeScript. Use `snake_case` for Python functions/modules, `camelCase` for TypeScript and Kotlin members, and `PascalCase` for components, classes, and exported types. No repository-wide formatter or linter is configured; preserve local style and make `npm run build` pass.

## Testing Guidelines

Use pytest/pytest-asyncio for the relay, Vitest for web logic, and JUnit 4 for Android. Name tests `test_*.py`, `*.test.ts`, or `*Test.kt`. Add regression tests in the affected domain and exercise protocol changes in every impacted client. No numeric coverage threshold is configured; prioritize authentication, encryption, TTL, acknowledgement, and compatibility.

## Commit & Pull Request Guidelines

Commits use short, imperative subjects such as `Add ...`, `Keep ...`, and `Route ...`. Keep each commit to one coherent behavior. Pull requests should explain user and protocol effects, list verification commands, link the issue or SDLC requirement, and include screenshots for UI changes. Call out migrations, configuration changes, and compatibility risks.

## Security & Configuration

Copy `.env.example` to `.env`; never commit secrets, databases, signing material, or production credentials. Do not call the product "secure" or "audited"; preserve the relay's no-plaintext/no-social-graph design.
