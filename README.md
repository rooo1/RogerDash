# RogerDash

**Turn arrows, calls and messages on your Suzuki's dashboard, and a ride log and fuel tracker that work on any bike or scooter.**

RogerDash is a replacement for Suzuki Ride Connect. It talks to Suzuki's Bluetooth (Ride Connect) dashboards, records your rides and keeps track of fuel. No account, no server: everything stays on your phone.

---

## What it does

| On the dash (Suzuki) | On the phone (any bike or scooter) |
|---|---|
| Turn-by-turn arrows and distance to the next turn | Rides recorded automatically, with a map coloured by speed |
| Incoming and missed calls, with the caller's name | Fuel log, real mileage and range estimate |
| Who an SMS or WhatsApp is from (never the text) | Navigation with two-wheeler routes and a weekend-loop planner |
| Short pop-ups: range, street names, speed warning | Spoken turns in your helmet or earphones |

---

## Which bikes it works with

| Vehicle | Status |
|---|---|
| Suzuki **V-Strom 250 SX** | ✅ Tested on a real bike |
| Other Suzukis with a Bluetooth (Ride Connect) dash | ⚠️ **Not tested yet.** RogerDash uses the same messages as Suzuki's own app, so it should work |
| Older Access / Burgman Street dashes (Bluetooth name starts with `SAS`) | ❌ Not supported yet |
| Any other bike or scooter | ✅ Ride log and fuel tracker (no dash features) |

**Ride an untested Suzuki?** The setup wizard tests the dash on your bike and writes the results to the app's log (More → Dash test tools → Log). Please share them, with your model and year, so this list can grow.

---

## Get started

1. Download `RogerDash-<version>-release.apk` from [Releases](../../releases) and open it on your phone (Android 12 or newer).
2. The **setup wizard** starts by itself. It asks what you ride and for the permissions your setup needs, then tests everything on your phone and bike:
   - the connection to your dash, plus a test turn arrow and pop-up
   - a test call and message on the dash
   - spoken turns in your helmet
   - your tank size and mileage
3. At the end you get a report of what works and what to fix.

You can run the wizard again any time from **More → How it works**.

> **Using Suzuki Ride Connect too?** Only one app can talk to the dash at a time, so close Ride Connect while you use RogerDash.

> **"Restricted setting" when allowing notification access?** Android does this for apps installed from a file. Open App info → ⋮ → *Allow restricted settings*, then try again.

---

## Better search with Google (optional)

Out of the box, search and routes use OpenStreetMap, which is free but misses some newer places. With **your own** Google key you get Google search, two-wheeler routes with live traffic, and petrol pumps along your route.

**More → Google Maps** walks you through it, with Copy buttons for everything:

1. In [Google Cloud Console](https://console.cloud.google.com/google/maps-apis/credentials), create a project (Google asks for a billing account).
2. Enable **Places API (New)** and **Routes API**.
3. Create an API key restricted to **Android apps**: package `app.roger.dash` and the SHA-1 fingerprint shown in the app. For the official release it's `D5:A2:11:B0:B7:EA:5F:4D:94:14:CC:57:63:FA:4D:D1:23:E7:40:7D`.
4. Paste the key in the app and tap **Save key**. The app checks it straight away.

Your key stays on your phone, and Google bills your own account. If Google refuses the key, the app says why and falls back to OpenStreetMap.

---

## Good to know

- **Pop-ups are short.** The V-Strom dash shows 12 capital letters, so street names are shortened ("Mahatma Gandhi Road" → "M GANDHI RD").
- **No message text on the dash.** The dash only takes who a message is from.
- **Range is an estimate.** Fuel bars × your mileage. Log full-tank fill-ups and it gets more accurate.
- **The bike doesn't send its trip average** (V-Strom), so type in the AVG from your dash, or log fill-ups.
- **Moving to a new phone?** More → Backup saves rides, fill-ups and settings to one file. The file includes your Google key if you entered one, so keep it private.

## Privacy

No account and no server. Rides, fill-ups and settings stay on your phone. Searches and routes go to OpenStreetMap services, or to Google if you add your own key. Caller and sender names only go to your dash; they're never stored.

---

## For developers

### Build

Needs Android SDK 35 and JDK 17.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb install -r app\build\outputs\apk\debug\RogerDash-1.0.0-debug.apk
```

### Release build

Releases are signed with the RogerDash key, which is kept outside the repo. Put a git-ignored `keystore.properties` in the project root:

```properties
storeFile=C:/path/to/rogerdash-release.jks
storePassword=...
keyAlias=rogerdash
keyPassword=...
```

```powershell
.\gradlew.bat :app:assembleRelease   # app\build\outputs\apk\release\RogerDash-<version>-release.apk
.\gradlew.bat :app:bundleRelease     # .aab, for the Play Store if needed later
```

Before each release, raise `versionCode` by 1 and update `versionName` in `app/build.gradle.kts`. Always sign with the same key, or phones won't install the update.

### Google's map in your own build

The public release always shows the free OpenStreetMap map. Google's map (Maps SDK for Android) needs a key **inside the APK**, so it's only for builds you make for yourself. Put your key in `local.properties` (git-ignored):

```
MAPS_API_KEY=your-key
```

- **Debug builds** use it automatically.
- **`.\gradlew.bat :app:assembleRelease -Ppersonal`** makes a signed `RogerDash-<version>-personal-release.apk` with Google's map, for your own phone. **Never publish it**: the key can be pulled out of it.

### Project layout

| File | What it does |
|---|---|
| `DashHub.kt` | The app's core: bike connection, rides, navigation, settings |
| `Models.kt` | Supported vehicles, and everything that differs between them |
| `DashLink.kt`, `Frames.kt` | Bluetooth link and the dash's 30-byte messages |
| `PhoneAlerts.kt`, `NavListener.kt` | Calls and messages to the dash |
| `SetupActivity.kt`, `HelpView.kt` | Setup wizard and "How it works" |
| `Router.kt`, `GoogleRoutes.kt`, `Places.kt` | Routes and search (OpenStreetMap or Google) |
| `RouteMap.kt` | The map (OpenStreetMap, or Google in your own builds) |
| `RideRecorder.kt`, `Trips.kt`, `Range.kt` | Rides, storage, fuel range |
| `Backup.kt` | Backup and restore |
| `*Tab.kt` | The screens: Ride, Navigate, Trips, Fuel, More |

All under `app/src/main/java/app/roger/dash/`.

---

## Disclaimer

Not affiliated with or endorsed by Suzuki. The dashboard protocol was worked out from the official app's own messages, for interoperability. Use at your own risk, and don't use your phone while riding.
