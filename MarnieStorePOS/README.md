# Marnie Store POS — Kotlin Android + Neon Postgres

A full rewrite of the original React/Firebase debt-tracking POS as a native
**Kotlin Android app** (Jetpack Compose) backed by **Neon Postgres**, with a
small **Ktor backend** in between (required for security — see below),
offline-first sync, realtime updates, and a proper security layer.

## Why a backend, if the data lives in Neon?

Neon is just Postgres. A mobile app should never embed raw database
credentials and connect directly to a Postgres server:
- No per-user authentication/authorization — anyone who decompiles the APK
  gets your DB password.
- No connection pooling — hundreds of phones opening raw Postgres
  connections will exhaust Neon's connection limit immediately.
- No server-side validation, rate limiting, or audit trail.
- No way to push realtime events to other devices.

So this project ships **two things**:

1. **`app/`** — the Kotlin Android app you asked for.
2. **`backend/`** — a small Ktor service that authenticates the app's one
   admin account (JWT) and is the only thing that ever holds Neon
   credentials. Deploy it once to any host that runs a JVM (Fly.io, Railway,
   Render, a small VPS, etc.) — Codemagic builds the Android app, not this
   backend, so provision it separately.

If you'd genuinely rather have zero backend of your own, the alternative is
swapping this Ktor layer for something like PostgREST + Neon's built-in
connection pooler + a JWT-issuing edge function — the Android app's
`SyncApi`/`AuthApi` interfaces would stay the same, only the base URL and a
couple of route names would change.

## Project layout

```
app/         Kotlin Android app (Jetpack Compose, Room, WorkManager, Hilt)
backend/     Ktor server (Exposed ORM over Neon Postgres, JWT auth, WebSocket)
sql/schema.sql   Run this against your Neon database first
codemagic.yaml   CI config to build & sign the Android app
docs/DEPLOY.md   Step-by-step setup (Neon, backend hosting, Codemagic, keystore)
```

## Features carried over from the original app

- Dashboard with today's sales, outstanding debt, low-stock alerts
- New Sale / POS screen with **live camera barcode scanning** (CameraX + ML Kit)
- Products CRUD with cost/retail/selling price, stock, low-stock threshold
- Customers CRUD with an optional access PIN (hashed server-side)
- Customer debt ("utang") tracking tied to each sale

## Essential features added

- **Partial payments** — the old app only had a paid/unpaid boolean; this
  version records every payment against a sale and tracks a running balance
  (`pending` → `partial` → `paid`).
- **Full offline mode** — every write goes to an encrypted local database
  first (the app is fully usable with zero signal) and is queued in an
  outbox table that drains automatically the moment connectivity returns.
- **Realtime sync** — a WebSocket connection means a sale made on one
  cashier's phone shows up on another device within a second or two.
- **Conflict handling** — last-write-wins by timestamp, with conflicts
  logged so you can see when it happens instead of silently losing data.
- **Audit log** — every login and mutation is recorded server-side with
  device ID and IP for traceability.
- **Adaptive UI** — one Compose codebase that shows a bottom nav bar on
  phones and a navigation rail on tablets/foldables, using
  `WindowSizeClass` rather than separate phone/tablet layouts.
- **Account lockout & rate limiting** — 5 failed logins locks the account
  for 15 minutes; the backend rate-limits all endpoints.
- Optional TOTP (authenticator app) MFA — set the `ADMIN_MFA_SECRET`
  environment variable on the backend to require a 6-digit code at login.

## Security layer summary

**On the device:**
- Local Room database is encrypted at rest with **SQLCipher**, using a
  256-bit passphrase generated once and stored in `EncryptedSharedPreferences`
  behind the Android Keystore (hardware-backed on most devices).
- Auth tokens live only in `EncryptedSharedPreferences`, never in plain
  SharedPreferences, logs, or backups (`dataExtractionRules.xml` excludes
  the DB and prefs from Android's auto-backup/device-transfer).
- Access tokens are short-lived (15 min); refresh tokens are rotated
  (single-use) and stored server-side only as a SHA-256 hash.
- Certificate pinning hook is wired into OkHttp (`NetworkModule`) — set
  `CERT_PIN_SHA256` once you know your backend's certificate.
- Cleartext HTTP is blocked everywhere except the Android emulator's
  loopback address (`network_security_config.xml`).

**On the backend:**
- Passwords hashed with bcrypt (cost 12); refresh tokens hashed with SHA-256.
- Single-admin by design: there is no `users` table and no sign-up flow —
  the one operator's credentials come from environment variables (see
  `docs/DEPLOY.md`), so there's no account-enumeration or privilege-escalation
  surface to defend.
- Every mutation goes through a JWT-authenticated route — the JWT subject is
  the fixed admin id, verified per request, never trusted from the client.
- Security headers (HSTS, X-Frame-Options, nosniff), CORS allow-list, and
  per-IP rate limiting are enabled by default.

## Quick start

1. Create a Neon project, then run `sql/schema.sql` against it.
2. Deploy `backend/` (see `docs/DEPLOY.md`) with the env vars it needs.
3. Point `codemagic.yaml`'s `API_BASE_URL` / `API_HOST` at that backend.
4. Push this repo to GitHub/GitLab/Bitbucket and connect it in Codemagic.
5. Add your keystore under Codemagic's "Android signing" and a secrets
   group named `marnie_pos_secrets` (see comments in `codemagic.yaml`).
6. Trigger a build — Codemagic produces a signed `.aab` (Play Store) and
   `.apk` (direct install) as build artifacts.

Full details, including how to set up your login (there's no public sign-up
screen by design — this is a single-admin app configured via environment
variables), are in `docs/DEPLOY.md`.
