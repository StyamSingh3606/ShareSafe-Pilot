# Verification report

| Check | Result |
|---|---|
| Backend TypeScript check | Passed with `npm.cmd --prefix backend run check`. |
| PostgreSQL-backed API integration | Two tests passed using `scripts/local-backend.ps1 -Test`. They cover a tampered tag, idempotent publication, pre-verification denial, OTP cooldown, receiver binding, concurrent redemption, preview bytes, download policy, and rejection of preview and OTP requests after database-enforced expiry. Fixtures exercise the API with synthetic PDF bytes. |
| Debug APK | Packaged under `app/build/outputs/apk/debug/app-debug.apk`. Android SDK apksigner verified the debug signature. |
| Release APK | Gradle completed `assembleRelease`. The release is unsigned and needs a hosted HTTPS API before use. |
| Android lint | `lintDebug` and release vital lint passed. |
| Android unit and instrumentation | The unit task found no test sources. Automated instrumentation and physical-device verification remain pending. |
| Emulator | Debug APK installed and cold-launched successfully on the Android 15 emulator after it finished booting. Tested the receiver UI with an enrolled emulator fingerprint and a development OTP. A timed PDF published through the API rendered page 1 after verification. A synthetic PCM WAV selected and published from the Android sender entered the Media3 player after a fresh receiver biometric and OTP check. Android audio logs showed output activity after pressing play. The emulator ran with host audio disabled. |

DOCX preview renders paragraphs. Rich DOCX fidelity, full file validation and size testing, live delivery, hosted object storage and KMS, and signed release distribution are pending.




Active playback at the expiry boundary, Android download through the system save dialog, audio seeking, and MP3 still need device verification.


Hosted pilot verification

- The local test command now runs three PostgreSQL-backed integration tests. All passed.
- The hosted test starts the production-mode API on a separate loopback port and simulates Resend. It tests sender password authentication, rejection of the development sender token, Android association JSON, private file metadata, idempotent invitation submission, OTP delivery and verification, absence of the development inbox, and byte-for-byte download.
- Public HTTPS deployment, live SMS and email, Android domain verification, and a live two-phone test require deployment configuration.
- The updated debug APK built. A clean hosted release build with a test HTTPS hostname passed Android debug lint and release vital lint. The merged release manifest included the test host, App Links verification, and cleartext traffic disabled.

Pilot download filename fix

- The user's saved file contained a PDF header and end marker, but its name ended in ".pdf (1)". A copy with a conventional ".pdf" extension matched the original SHA-256 hash.
- The Android save intent now uses the verified file format's MIME type and reports success after the output stream closes. Device retest should cover the same filename twice to exercise the provider's duplicate-name handling.

## Live email signup verification

The public Render backend accepted a signup-code request for a mailbox outside
the original Resend test account. Brevo recorded the signup email as Delivered.
Both the Brevo and Resend integrations passed the local PostgreSQL integration
suite with simulated delivery. Live SMS, rich DOCX layout fidelity, and managed
object storage and key management are outside the verified classroom pilot.

## Password reset verification

The PostgreSQL integration suite passed with simulated Brevo and Resend
delivery. Recovery was verified for registered senders and the configured
pilot sender. Tests covered purpose-bound codes, wrong-code rejection,
single redemption under concurrent requests, rejection of the old password,
acceptance of the new password, and revocation of existing sender sessions.
