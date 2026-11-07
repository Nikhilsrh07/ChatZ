# ChatZ

ChatZ is a local-network chat app with native Android and iOS clients, on-device SQLite history, and an optional authenticated MongoDB sync service. Both clients support one-to-one messages, mesh group chats, and locally discoverable named channels over Wi-Fi or an IP network shared through Bluetooth tethering. Android also supports direct Bluetooth Classic one-to-one chat between paired Android devices.

## iOS app

The native SwiftUI iOS app is in `ios/ChatZ.xcodeproj`. It includes SQLite conversation/message history, Bonjour peer discovery, mesh group chats and named channels, manual IP connections for tethered networks, and optional MongoDB API sync. iOS does not support Android's Bluetooth Classic RFCOMM chat; use Wi-Fi or Bluetooth tethering and connect by IP instead.

1. On a Mac with Xcode 15 or newer, open `ios/ChatZ.xcodeproj`.
2. Select the ChatZ scheme and an iOS 16+ simulator or connected iPhone, then Run. The first local-network action prompts for network permission.
3. For MongoDB sync, copy `ios/ChatZ/Local.xcconfig.example` to `ios/ChatZ/Local.xcconfig`, then set the development API URL and bearer token. This local file is git-ignored; do not use a shared development token in a production build.
4. On every device, tap **Start Wi-Fi chat**. Bonjour connects peers automatically when available. For Bluetooth tethering or when discovery is blocked, start the listener on every device, enter each peer's IP address, and tap **Connect** to form the mesh. TCP port `45454` is shared with Android.
5. Choose **New group chat** or **New channel**. Both appear on connected local peers; messages are sent to the connected mesh. Group/channel discovery is open to devices on the same local network.

GitHub Actions is configured to build the iOS simulator app on macOS. A Windows machine cannot run Xcode or install the iOS app; use Xcode on a Mac for simulator/device execution.

## Features

- **Wi-Fi:** discover peers with Android Network Service Discovery or iOS Bonjour (mDNS) and connect over TCP.
- **Conversations:** one-to-one direct chat plus locally discoverable group chats and named channels. Mesh peers forward room announcements and messages without a central host.
- **Bluetooth tethering:** use the tethered IP network. If service discovery is unavailable, enter the other device's IP address and connect to TCP port `45454`.
- **Direct Bluetooth:** use Bluetooth Classic RFCOMM for one-to-one chat between paired Android devices. Group chats and channels use Wi-Fi or Bluetooth tethering.
- **Local persistence:** every sent and received message is saved in each app's on-device SQLite database.
- **NoSQL sync:** optionally upload pending messages to MongoDB and download remote messages. Local chat/history continue to work if the backend is not configured or is unreachable.
- Messages are single-line UTF-8 text with a maximum size of 64 KiB.

## Run the Android app

1. Install Android Studio, JDK 17, Gradle 8.9, and Android SDK Platform 35.
2. Open the `android/` directory in Android Studio and configure it to use the installed Gradle distribution, or build from PowerShell:

   ```powershell
   gradle -p .\android :app:assembleDebug
   gradle -p .\android :app:installDebug
   ```

3. Connect an Android 8.0+ device (API 26 or newer) or start an emulator. For actual Bluetooth and local-network testing, use two physical devices; emulators do not reliably reproduce these network paths.

### Wi-Fi and Bluetooth tethering

On all devices, tap **Start Wi-Fi / tethered network chat**. Wi-Fi peers should be found automatically when the LAN allows multicast and device-to-device traffic. If discovery is blocked—including on some tethering implementations—start the listener on each device, find the peers' IP addresses in network settings, and enter/connect to each peer on every device using port `45454`. This creates a peer mesh; no host is required.

Use the **Chats** selector to switch between Direct, groups, and channels. Create a group or named channel on any peer; its announcement is forwarded across the mesh and appears on connected devices. Each device must be running this ChatZ version and connected to the mesh. Messages are stored per conversation in local SQLite; MongoDB sync retains the same conversation metadata.

Bluetooth tethering is controlled by Android system settings; ChatZ does not enable or configure tethering. Some devices block peer-to-peer traffic over tethered networks. Both devices must be able to reach each other's IP address and TCP port `45454`.

### Direct Bluetooth

Pair the two Android devices in system Bluetooth settings. On both devices, tap **Start Bluetooth server**. On the initiating device, tap **Load paired Bluetooth devices** or **Discover Bluetooth devices**, choose the peer, then tap **Connect Bluetooth**. Grant the requested Bluetooth permissions. Direct Bluetooth uses RFCOMM and does not require Wi-Fi or Internet. Bluetooth Classic remains a one-to-one transport; use Bluetooth tethering for group rooms.

## Run MongoDB sync locally

Docker Desktop with Docker Compose is required.

1. In PowerShell, from the `backend/` directory, copy `.env.example` to `.env`:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Replace both sample passwords/tokens with long random URL-safe values (for example, generate each with `python -c "import secrets; print(secrets.token_urlsafe(32))"`). Do not commit `.env`.
3. Start MongoDB and the API:

   ```powershell
   docker compose up --build -d
   Invoke-RestMethod http://localhost:8000/healthz
   ```

4. Find the development computer's LAN IP address. In `android/local.properties`, preserve any existing `sdk.dir` entry and add:

   ```properties
   chatz.syncUrl=http://YOUR_COMPUTER_LAN_IP:8000
   chatz.syncToken=THE_CHATZ_SYNC_TOKEN_FROM_backend/.env
   ```

5. In iOS, optionally configure the same URL and token in `ios/ChatZ/Local.xcconfig`.
6. Tap **Sync** in either app. ChatZ uploads locally stored unsynced messages, marks them synced only after API confirmation, then imports recent messages from MongoDB into local SQLite.

`backend/.env.example` contains placeholders only. MongoDB data is stored in the Compose `mongo-data` volume. Use `docker compose down` to stop the services; do not use `down -v` unless you intend to delete that database volume.

### API

- `GET /healthz` — readiness check; verifies MongoDB connectivity.
- `POST /api/messages` — authenticated, idempotent message upsert.
- `GET /api/messages?limit=50` — authenticated message retrieval (limit 1–100).

The API requires `MONGODB_URI` and a `CHATZ_SYNC_TOKEN` with at least 32 characters. The Android debug build uses the configured bearer token. This shared-token setup is for private development/testing only: the token is embedded in the APK and HTTP is enabled only in the Android debug manifest. A production deployment must use HTTPS and user/device-scoped authentication instead of shipping a shared secret.

## Security notes

- The Wi-Fi/tethered TCP mesh protocol is plaintext. Local group chats and channels are discoverable to other ChatZ peers on that network and are not private or access-controlled; use only trusted networks and do not expose TCP port `45454` to the public Internet.
- Direct Bluetooth chat uses paired-device RFCOMM.
- The development MongoDB API token is shared across configured devices and the API returns messages for that token. Use private networking during development; production needs TLS, per-user/device authorization, and a managed secrets solution.
- Android message history is local to the app and Android backups are disabled.

## Backend tests

From the repository root in PowerShell:

```powershell
python -m venv backend\.venv
backend\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt
Push-Location backend
.venv\Scripts\python.exe -m unittest -v
Pop-Location
```

## Deploy the sync API with Helm

Build and publish the image from the `backend/` directory, then create a Kubernetes Secret named `chatz-backend-secrets` (or configure another name in `devops/helm/chatz/values.yaml`) with keys `MONGODB_URI` and `CHATZ_SYNC_TOKEN`. Supply the MongoDB URI and token through a managed secret store; never put their values in Helm files or CI logs.

```powershell
docker build -t YOUR_REGISTRY/chatz-backend:YOUR_TAG .\backend
docker push YOUR_REGISTRY/chatz-backend:YOUR_TAG
```

The chart deploys the stateless API on port `8080` by default, with health checks at `/healthz`. Set `image.repository` and `image.tag` to the published API image, and set `containerPort` and `service.port` to `8000` to match the supplied backend:

```powershell
helm lint .\devops\helm\chatz
helm template chatz .\devops\helm\chatz --namespace chatz
helm upgrade --install chatz .\devops\helm\chatz --namespace chatz --create-namespace --set image.repository=YOUR_REGISTRY/chatz-backend --set image.tag=YOUR_TAG --set containerPort=8000 --set service.port=8000
```

The chart does not provision MongoDB. Configure `MONGODB_URI` to point to a managed MongoDB-compatible service or an independently operated MongoDB deployment.

## CI

GitHub Actions runs the MongoDB API unit tests, builds the Android debug APK, and validates the Helm chart. The GitLab CI, Azure Pipelines, and Jenkins examples currently validate the Helm chart. iOS build/test automation requires macOS and an Xcode project.
