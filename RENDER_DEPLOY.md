# Deploying MessMate Backend to Render

This guide outlines how to deploy the MessMate Spring Boot (Kotlin + Java 21) backend to [Render](https://render.com).

---

## Why Docker on Render?

Render does not offer a native Java runtime environment. Render natively supports Node, Python, Go, Rust, and Ruby; all JVM/Java/Kotlin applications on Render run in Docker containers.

MessMate includes:
- A production-grade multi-stage [`Dockerfile`](file:///Users/sazibhossainsazib1008/projects/MessMate/messmate_backend/Dockerfile) using **Eclipse Temurin JDK 21** and **JRE 21**.
- Automatic JVM memory optimization (`-XX:MaxRAMPercentage=75.0`) to avoid OOM kills on Render's 512MB RAM free/starter instances.
- Seamless compatibility with dynamic `$PORT` binding.
- Built-in health check endpoints: `/api/health`, `/healthz`, and `/`.
- Automatic translation of Render's standard `DATABASE_URL` (`postgres://...`) to JDBC format (`jdbc:postgresql://...`) via [`DatabaseConfig.kt`](file:///Users/sazibhossainsazib1008/projects/MessMate/messmate_backend/src/main/kotlin/com/example/messmate_backend/config/DatabaseConfig.kt).

---

## Deployment Option 1: Blueprint Deployment (Recommended — 1 Click)

1. Push your changes to GitHub.
2. In the Render Dashboard, click **New +** -> **Blueprint**.
3. Select your repository (`messmate_backend` or `MessMate`).
4. Render will detect [`render.yaml`](file:///Users/sazibhossainsazib1008/projects/MessMate/messmate_backend/render.yaml) and automatically configure the Web Service with the correct Dockerfile and environment variables.
5. Provide your database credentials (either Render PostgreSQL or existing Neon DB):
   - Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` OR link a Render PostgreSQL database (which provides `DATABASE_URL`).
6. Click **Apply**. Render will build the Docker image and deploy the service.

---

## Deployment Option 2: Manual Web Service Setup

If you prefer setting up the web service manually:

1. In Render Dashboard, click **New +** -> **Web Service**.
2. Connect your GitHub repository:
   - If connecting the standalone `messmate_backend` repository: leave **Root Directory** empty.
   - If connecting the root `MessMate` monorepo: set **Root Directory** to `messmate_backend`.
3. Choose **Docker** as the Runtime.
4. Set the **Region** (e.g., `Singapore` or closest to your users/database).
5. Set **Health Check Path** to `/api/health`.
6. Add the following **Environment Variables**:

| Variable | Recommended Value | Notes |
| :--- | :--- | :--- |
| `PORT` | `10000` | Render injects this automatically |
| `JWT_SECRET` | *Generate random 64-char hex* | Authentication signing secret |
| `JPA_DDL_AUTO` | `update` | Automatically creates/updates DB schema |
| `SHOW_SQL` | `false` | Keeps logs clean |

### Connecting to the Database:
- **Using Render PostgreSQL:**
  - Create a PostgreSQL database on Render.
  - In the Web Service settings, add the database or set `DATABASE_URL` from the database's "Internal Database URL".
  - [`DatabaseConfig`](file:///Users/sazibhossainsazib1008/projects/MessMate/messmate_backend/src/main/kotlin/com/example/messmate_backend/config/DatabaseConfig.kt) automatically adapts Render's `postgres://` format into JDBC format.
- **Using Neon or External PostgreSQL:**
  - Add `DB_URL` (e.g., `jdbc:postgresql://ep-xyz.aws.neon.tech/neondb?sslmode=require&prepareThreshold=0`).
  - Add `DB_USERNAME` and `DB_PASSWORD`.

---

## Verifying Deployment

Once deployed, Render provides a public URL (e.g. `https://messmate-backend.onrender.com`).

Verify that the service is running:
```bash
curl https://messmate-backend.onrender.com/api/health
```
Expected response:
```json
{
  "status": "UP",
  "service": "messmate-backend",
  "timestamp": "2026-09-29T..."
}
```
