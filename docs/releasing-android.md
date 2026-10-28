# Releasing the Android app

The Android app is signed with an upload key and sent to Google Play with [fastlane](https://docs.fastlane.tools). The version numbers come from [semantic-release](https://github.com/nprail/kairos/blob/main/AGENTS.md) (see "Commit messages and releases"); nothing here sets a version by hand.

## How a release flows

1. A push to `next` (stable) or `beta` (pre-release) runs the **Release** workflow. If the commits call for an app release, semantic-release tags `app-vX.Y.Z` and writes `versionName` and `versionCode` into `apps/android/app/build.gradle.kts`.
2. The workflow's **Android app** job checks out that tag, runs the unit tests, and builds a **signed** release APK and bundle (AAB) with `fastlane android build`. It checks the APK's signature, and keeps both as the `android-release` workflow artifact.
3. If the Play secrets are set, it uploads the AAB to the **internal** testing track (`fastlane android deploy`).
4. Moving the build to a wider track (closed testing, production, a staged rollout) is a person's decision, made in Play Console: open the release on the internal track and promote it there.

There is no GitHub Release for the app, on purpose: the apps look for firmware at `releases/latest/download/manifest.json`, and an app release would become "latest".

Without `ANDROID_KEYSTORE_BASE64` the Android job says so and does nothing, so the firmware release is never held up by it.

## One-time setup

### 1. Create the app in Play Console

The Play API can only update an app that exists, and the **first upload of a new app has to be done by hand**.

1. In [Play Console](https://play.google.com/console), create the app. Name: Kairos. Package name: `com.noahprail.kairos`.
2. Fill in what Play requires that fastlane cannot: the **privacy policy URL** (the app collects nothing, but Play still wants a page), the **Data safety** form, the **content rating**, the **target audience**, and the app category.
   - Data safety, for reference: no data collected or shared. The app looks for firmware on GitHub when it connects, which sends the phone's IP address to GitHub and nothing else; the pairing key is stored on the device and is not backed up.
   - Permissions to explain: Nearby devices (`BLUETOOTH_SCAN` with `neverForLocation`, and `BLUETOOTH_CONNECT`) to find and talk to the device; Internet to look for firmware.
3. Leave **Play App Signing** on (the default). Google keeps the key that signs what users install. The key below is only the *upload* key.
4. Build the first release (below), and upload `app-release.aab` by hand to the internal testing track. After that, everything else is automatic.

### 2. Make the upload key

```sh
apps/android/scripts/generate-keystore.sh      # writes ~/.kairos-signing/android-upload.jks and android-keystore.properties
```

The files stay outside the repository. **Back up the directory somewhere safe and offline.** If the upload key is lost, Google can reset it, but it takes a support request.

Then store it as repository secrets (the script prints these commands):

| Secret | What |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64 |
| `ANDROID_KEYSTORE_PASSWORD` | its password |
| `ANDROID_KEY_ALIAS` | `kairos-upload` |
| `ANDROID_KEY_PASSWORD` | the key's password (the same as the store's) |

```sh
base64 -i ~/.kairos-signing/android-upload.jks | gh secret set ANDROID_KEYSTORE_BASE64
```

### 3. Let CI upload to Play

1. In Play Console, **Setup, API access**: link a Google Cloud project, create a **service account**, and download its JSON key.
2. Back in Play Console, give that account access to the app, with permission to release to testing tracks.
3. Store the JSON as the secret `PLAY_SERVICE_ACCOUNT_JSON`:

```sh
gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json
```

Check it from your machine:

```sh
cd apps/android
SUPPLY_JSON_KEY=play-service-account.json bundle exec fastlane android validate
```

## Working with fastlane

Everything is in `apps/android/fastlane/`. Install it once with `cd apps/android && bundle install` (Ruby 3.1 or newer).

| Lane | What it does |
| --- | --- |
| `fastlane android test` | the unit tests |
| `fastlane android build` | a signed release APK and AAB (`app/build/outputs/`). Needs the upload key: `ANDROID_KEYSTORE_FILE` and the other `ANDROID_KEY*` variables, or `apps/android/keystore.properties`, a git-ignored copy of `~/.kairos-signing/android-keystore.properties` |
| `fastlane android deploy` | builds, writes the "what's new" text from `apps/CHANGELOG.md`, and uploads the AAB. `track:internal` by default |
| `fastlane android metadata` | uploads the listing: title, descriptions, icon, feature graphic and screenshots |
| `fastlane android screenshots serial:emulator-5554` | takes the phone screenshots (below) |
| `fastlane android graphics` | draws the icon and the feature graphic from `brand/` (needs `brew install librsvg imagemagick`) |
| `fastlane android validate` | checks the Play service account key |

A release without a keystore is not an error elsewhere: `./gradlew :app:assembleRelease` builds an unsigned APK, so the tests, `assembleDebug` and forks that do not publish keep working.

### Listing text

The texts are in `fastlane/metadata/android/en-US/`: `title.txt` (30 characters at most), `short_description.txt` (80), `full_description.txt` (4000), and `changelogs/default.txt` (500). Edit them and run `fastlane android metadata`. A release's own "what's new" text is generated from the changelog at deploy time and is not committed.

### Screenshots

Play wants two to eight phone screenshots, with no side longer than twice the other. `fastlane android screenshots` takes six from the app's **debug demo scenes** (the same fixtures as in AGENTS.md, "Debug demo mode"), so they need no Bluetooth hardware. It installs the debug build on a running device or emulator, sets the screen to 1080 by 2160 in the light theme, shows a clean status bar (9:41, full battery), photographs each scene, and puts everything back. The scenes and file names are the `SCREENSHOTS` list at the top of the Fastfile.

```sh
emulator -avd Pixel_8_Pro_API_35 &            # or plug in a phone
cd apps/android && bundle exec fastlane android screenshots serial:emulator-5554
git add fastlane/metadata && git commit -m "docs(android): refresh the Play screenshots"
```

Retake them when a screen changes, then upload with `fastlane android metadata`.

### Other languages

Add a folder next to `en-US` (for example `de-DE`) with the same files. The screenshots lane and `LOCALE` in the Fastfile only produce `en-US`.
