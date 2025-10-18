# Dropbox Link Cleaner (Spring Boot, console)

A text-based Spring Boot program that:
- Reads your **Dropbox access token** from environment variable `DROPBOX_ACCESS_TOKEN`
- Lists **shared links 10 at a time**
- Lets you choose numbers, **`all`**, or **`all except 2,5`** to revoke on the current page
- Asks you to type **`yes`** to confirm, otherwise skips
- Allows **`exit`** any time to quit

## Requirements
- Java 17+
- Maven 3.9+
- A Dropbox API OAuth 2 access token with scopes: **`sharing.read`** and **`sharing.write`**
- Environment variable `DROPBOX_ACCESS_TOKEN` set to your token

## Get a Dropbox access token
Follow these steps to create an app and generate a user access token with the required scopes:

1. Open the Dropbox App Console: https://www.dropbox.com/developers/apps
2. Click "Create app".
   - Choose Scoped access.
   - Choose "Full Dropbox" (recommended) or "App folder" if you only need a specific folder.
   - Name your app and create it.
3. In your app's settings, go to the "Permissions" tab and enable the following scopes:
   - `sharing.read`
   - `sharing.write`
   Save your changes.
4. Go to the "Settings" tab and, under "OAuth 2", generate a token:
   - For personal testing, you can use the "Generated access token" (short‑lived) button.
   - If you need a longer‑lived setup, implement OAuth to obtain a refresh token; for this CLI, a standard user access token is sufficient.
5. Copy the generated access token and set it as the environment variable `DROPBOX_ACCESS_TOKEN` before running the app (see Run section below).

Helpful docs:
- Dropbox OAuth guide: https://developers.dropbox.com/oauth-guide
- App console help: https://help.dropbox.com/developers/documentation

Notes:
- If you get `400 Bad Request` responses, verify your token has the `sharing.read` and `sharing.write` scopes and that the token hasn't expired.
- Team/Business accounts may require creating a Team app and selecting the appropriate access type.

## Build
```bash
mvn -q -DskipTests package
```

## Run
Set your Dropbox access token in the environment variable `DROPBOX_ACCESS_TOKEN`, then run the jar.

macOS/Linux (bash/zsh):
```bash
export DROPBOX_ACCESS_TOKEN="<your token here>"
java -jar target/dropbox-link-cleaner-1.0.0.jar
```

Windows (PowerShell):
```powershell
$env:DROPBOX_ACCESS_TOKEN="<your token here>"
java -jar target/dropbox-link-cleaner-1.0.0.jar
```

## Configuration
- The app reads the token from Spring property `dropbox.access-token`.
  - By default, `dropbox.access-token` is wired to the environment variable `DROPBOX_ACCESS_TOKEN` (see `src/main/resources/application.properties`).
  - You can override it by passing a Spring property, for example:
    - Command line: `java -Ddropbox.access-token=YOUR_TOKEN -jar target/dropbox-link-cleaner-1.0.0.jar`
    - Env var (default): `export DROPBOX_ACCESS_TOKEN=YOUR_TOKEN`
- The Dropbox API base URL is configurable via Spring property `dropbox.api-base-url`.
  - Default: `https://api.dropboxapi.com/2`
  - Environment variable override: `DROPBOX_API_BASE_URL`
  - Command line override example:
    - `java -Ddropbox.api-base-url=https://api.dropboxapi.com/2 -jar target/dropbox-link-cleaner-1.0.0.jar`

## Notes
- Uses Dropbox API endpoints:
  - `POST /2/sharing/list_shared_links`
  - `POST /2/sharing/list_shared_links/continue`
  - `POST /2/sharing/revoke_shared_link`

See Dropbox official docs:
- List links: https://www.dropbox.com/developers/documentation/http/documentation#sharing-list_shared_links
- Continue listing: https://www.dropbox.com/developers/documentation/http/documentation#sharing-list_shared_links-continue
- Revoke link: https://www.dropbox.com/developers/documentation/http/documentation#sharing-revoke_shared_link

## Troubleshooting
- If you see a Netty warning on macOS about `MacOSDnsServerAddressStreamProvider`, it's from the HTTP client and can usually be ignored if networking works. If you prefer to silence it or need native resolution, you can add `io.netty:netty-resolver-dns-native-macos` (classifier `osx-aarch_64` or `osx-x86_64`) to your own build.
- If you get `400 Bad Request` from Dropbox, the app now prints the full response body to help diagnose (e.g., missing scopes, malformed request, etc.). Ensure your token has both `sharing.read` and `sharing.write` scopes.
- To increase log verbosity, you can run with: `java -Dlogging.level.org.springframework.web.reactive.function.client=DEBUG -jar target/dropbox-link-cleaner-1.0.0.jar`

## Safety
- You can only revoke links you have permission to manage.
- Revoking a file link does **not** revoke other links to parent folders.


## License

This project is licensed under the Apache License, Version 2.0. See the LICENSE file for details.
