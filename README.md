# ShareSafe Android pilot

ShareSafe is a native Kotlin Android app with a TypeScript, Fastify, and PostgreSQL backend. The app targets API 35 and supports Android 11 and later. The local backend binds to the Windows loopback interface and stores encrypted file records and wrapped file keys in PostgreSQL. The local service can decrypt files after granting access.

## Use the Android app

Read the [ShareSafe User Guide](ShareSafe%20User%20Guide.docx) for installation, sender signup, secure sharing, and receiver verification. The signed classroom APK is available from [Releases](https://github.com/StyamSingh3606/ShareSafe-Pilot/releases).

## Start on Windows

1. Install Node.js with npm, PostgreSQL 17, JDK 17, and Android Studio. Install Android SDK Platform 35, build tools 35.0.0, and Platform Tools. The local backend script expects the standard PostgreSQL and Node.js installation paths.
2. Run `npm.cmd --prefix backend ci` in the repository.
3. Run `powershell -ExecutionPolicy Bypass -File scripts/local-backend.ps1`. Leave the terminal open. The script creates a dedicated PostgreSQL cluster on port 55432 and starts the API at `http://127.0.0.1:8787`. Local credentials are generated in `.local/backend.env`. Keep this file private.
4. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to your Android SDK. Run `.\gradlew.bat assembleDebug` if the wrapper is present, or use Gradle 8.11.1 with `gradle assembleDebug`. The debug output is `app/build/outputs/apk/debug/app-debug.apk`.
5. Start an emulator or connect a physical phone with USB debugging. Run `adb devices`, `adb reverse tcp:8787 tcp:8787`, and `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The same reverse tunnel gives both phone and emulator access to the loopback API.

## Try a share

1. Choose Send and enter the `DEV_SENDER_TOKEN` value from `.local/backend.env`. Choose a synthetic PDF or audio file, enter a test destination, and choose the desired access mode. Publish and copy the invitation code.
2. Enroll a fingerprint or supported face biometric in Android Settings. Open Receive, authenticate, paste the invitation code, and request a verification code.
3. In a private PowerShell terminal, replace the invitation placeholder and retrieve the development OTP.

       $invitation = '<invitation>'
       $line = Get-Content .local/backend.env | Where-Object { $_ -like 'DEV_INBOX_TOKEN=*' }
       $inboxToken = $line.Split('=', 2)[1]
       Invoke-RestMethod "http://127.0.0.1:8787/dev/inbox/$invitation" -Headers @{ Authorization = "Bearer $inboxToken" }

4. Enter the code and open the preview. Try a timed share and a no-deadline share. With no deadline, use Download original and compare the saved bytes with the source.

The local inbox simulates delivery. DOCX preview currently extracts paragraphs and omits layout, tables, and images. Media playback seeks within the authenticated local preview file. Server expiration prevents further delivery and app expiration removes its managed preview. A saved copy can persist.

## Checks and release status

Run `npm.cmd --prefix backend run check` for backend type checking. With the backend running, execute `powershell -ExecutionPolicy Bypass -File scripts/local-backend.ps1 -Test` for the API integration tests. Run `.\gradlew.bat lintDebug testDebugUnitTest assembleDebug` for Android checks. Device validation requires starting the emulator or connecting a phone.

See [Hosted pilot setup](HOSTING.md) for sender sign-in, invitation links, and provider configuration. The hosted APK requires an HTTPS endpoint and release signing.
