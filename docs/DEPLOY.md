# Deployment guide

## 1. Neon Postgres

1. Create a project at https://neon.tech.
2. Grab the connection string from the Neon dashboard — you'll need it in
   two forms:
   - A **JDBC URL** for the backend: `jdbc:postgresql://<host>/<db>?sslmode=require`
   - The plain username/password Neon gives you.
3. Run the schema:
   ```bash
   psql "postgresql://<user>:<password>@<host>/<db>?sslmode=require" -f sql/schema.sql
   ```

## 2. Backend

The backend in `backend/` is a standard Ktor app — build a fat jar and run
it anywhere that runs a JVM.

```bash
cd backend
./gradlew build   # (or `gradle build` if you don't have a wrapper yet locally)
```

Required environment variables:

| Variable            | Example                                              |
|---------------------|-------------------------------------------------------|
| `NEON_JDBC_URL`      | `jdbc:postgresql://ep-xxx.neon.tech/neondb?sslmode=require` |
| `NEON_DB_USER`       | from Neon dashboard                                   |
| `NEON_DB_PASSWORD`   | from Neon dashboard                                   |
| `JWT_SECRET`         | 32+ random bytes, e.g. `openssl rand -base64 48`      |
| `ADMIN_EMAIL`        | the one login email for this app, e.g. `owner@yourstore.com` |
| `ADMIN_PASSWORD_HASH`| a bcrypt hash of your password — see step 3 below     |
| `ADMIN_MFA_SECRET`   | optional TOTP secret; omit to skip MFA at login       |
| `ADMIN_DISPLAY_NAME` | optional, defaults to `Admin`                         |
| `ALLOWED_ORIGINS`    | comma-separated, only matters for browser clients     |
| `PORT`               | optional, defaults to 8080                            |

This app has exactly one operator — there's no `app_users` table and no
sign-up flow. The account is entirely defined by these environment
variables, not by a database row.

Any host that can run a Docker container or a JVM works: Fly.io, Railway,
Render, a small VPS with `systemd`, etc. A minimal `Dockerfile` (not
included, since your host's conventions vary) would be:

```dockerfile
FROM eclipse-temurin:17-jre
COPY backend/build/libs/marnie-pos-backend-all.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Once deployed, confirm it's alive:
```bash
curl https://your-backend-host/health
# {"status":"ok"}
```

## 3. Create your login

There's intentionally no sign-up screen and no users table — a POS app
shouldn't let strangers create accounts, and this one only ever has a single
operator. Generate a bcrypt hash (cost 12) for your password using any
bcrypt tool that supports a cost factor, for example Python:

```bash
pip install bcrypt
python3 -c "import bcrypt; print(bcrypt.hashpw(b'yourpassword', bcrypt.gensalt(12)).decode())"
```

Then set on the backend host:

```
ADMIN_EMAIL=owner@yourstore.com
ADMIN_PASSWORD_HASH=<the bcrypt hash you generated>
ADMIN_DISPLAY_NAME=Store Owner   # optional
```

Restart the backend, then log in from the app with that email/password.
To change the password later, generate a new hash and update
`ADMIN_PASSWORD_HASH` — there's nothing to update in the database.

## 4. Point the Android app at your backend

Either edit the defaults in `codemagic.yaml` / `app/build.gradle.kts`, or
pass them as Gradle properties at build time:

```bash
./gradlew assembleRelease -PAPI_BASE_URL="https://your-backend-host/" -PAPI_HOST="your-backend-host"
```

`API_BASE_URL` must end with a trailing `/` (Retrofit requirement).

## 5. Codemagic

1. Connect this repository in Codemagic.
2. **Android signing**: Codemagic → App settings → Android code signing →
   upload your `.jks` keystore, note the reference name, and put that name
   under `android_signing:` in `codemagic.yaml` (already named
   `marnie_pos_keystore` — rename both places if you use something else).
   If you don't have a keystore yet:
   ```bash
   keytool -genkey -v -keystore marnie-release.jks -keyalg RSA -keysize 2048 \
     -validity 10000 -alias marnie
   ```
3. **Environment group**: create a group called `marnie_pos_secrets` in
   Codemagic's Environment variables settings and add `CERT_PIN_SHA256` once
   you've pinned your backend's certificate (optional but recommended for
   production — see below).
4. Push to `main` (or whatever branch you configured) — Codemagic runs
   `codemagic.yaml`'s `marnie-pos-android` workflow and produces a signed
   `.aab`/`.apk` as build artifacts, emailed to whoever you list under
   `publishing.email.recipients`.

### Getting the certificate pin (optional, recommended)

```bash
openssl s_client -connect your-backend-host:443 -servername your-backend-host < /dev/null 2>/dev/null | \
  openssl x509 -pubkey -noout | \
  openssl pkey -pubin -outform der | \
  openssl dgst -sha256 -binary | \
  openssl enc -base64
```
Put the resulting base64 string in the `CERT_PIN_SHA256` Codemagic variable.
Leave it blank while developing against a certificate that might rotate —
an empty value disables pinning rather than breaking the build.

## Local development against the backend

Run the backend locally (`./gradlew run` inside `backend/`) and build a
debug APK with:
```bash
./gradlew assembleDebug -PAPI_BASE_URL="http://10.0.2.2:8080/"
```
`10.0.2.2` is the Android emulator's alias for your host machine's
`localhost`; it's already allow-listed for cleartext HTTP in
`network_security_config.xml` for exactly this reason. On a physical device
over the same Wi-Fi, use your machine's LAN IP instead and add it to that
same XML file's cleartext domain list temporarily.
