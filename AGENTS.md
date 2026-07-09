# Repository Guidelines

## Project Structure & Module Organization

This repository is a Java 17/Spring Boot 3 multi-module RAG platform with a Vite React frontend. Backend modules are declared in `pom.xml`: `bootstrap` is the runnable application and API layer, `framework` holds shared infrastructure, `infra-ai` contains AI/provider integrations, and `mcp-server` contains MCP services. Java code follows `src/main/java`, `src/main/resources`, and `src/test/java`. Frontend code lives in `frontend/src`. Static and reference material are in `assets`, `docs`, and `resources`; database and Docker assets are under `resources/database` and `resources/docker`.

## Build, Test, and Development Commands

- `./mvnw clean compile` builds all backend modules and runs Spotless during compile.
- `./mvnw test` runs the Maven test suite.
- `./mvnw -pl bootstrap spring-boot:run` starts the backend application.
- `cd frontend && npm install` installs dependencies from `package-lock.json`.
- `cd frontend && npm run dev` starts Vite; API requests are proxied to `http://localhost:8080`.
- `cd frontend && npm run build` creates the production frontend bundle.
- `cd frontend && npm run lint` runs ESLint for TypeScript/React.
- `cd frontend && npm run format` applies Prettier formatting.

## Coding Style & Naming Conventions

Backend code uses Java 17, Spring Boot conventions, Lombok, and package names under `com.nageoffer.ai.ragent`. Keep classes focused by layer, use suffixes such as `Controller`, `Service`, `Mapper`, `DO`, `DTO`, `BO`, and `VO`, and place configuration in `src/main/resources`. Maven Spotless applies the repository copyright header during compile.

Frontend code uses TypeScript, React 18, Vite, Tailwind, Radix UI, and lucide-react. Prettier uses 2 spaces, semicolons, double quotes, no trailing commas, and 100-column width. Use PascalCase for components and camelCase for hooks, helpers, and variables.

## Testing Guidelines

Backend tests use JUnit/Spring Boot Test and are concentrated in `bootstrap/src/test/java`. Name test classes `*Test` or `*Tests` and keep integration tests explicit about external requirements. For example, `MinerUPdfUploadFlowTest` is gated by `-Dmineru.test.pdf=/path/to/file.pdf`. Add focused tests near the module whose behavior changes.

## Commit & Pull Request Guidelines

Recent commits follow Conventional Commits with scopes, for example `feat(knowledge): ...` and `refactor(rag): ...`. Keep commits small and use scopes that match the touched area. Pull requests should include a problem statement, implementation summary, verification commands, linked issues when applicable, and screenshots for frontend UI changes.

## Security & Configuration Tips

Do not commit local secrets or `.env` values. Keep service credentials, model API keys, database URLs, and Milvus/RabbitMQ settings in local configuration or deployment secrets. Use `resources/docker` for local dependency stacks and `resources/database` for schemas.
