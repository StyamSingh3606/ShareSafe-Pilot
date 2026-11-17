# Hosted ShareSafe pilot

The hosted build supports the operator-provisioned sender account and accounts created by testers who verify their email. The receiver opens a verified Android App Link and enters a short-lived code sent to the saved recipient. Email delivery verifies mailbox control. SMS delivery depends on the configured Twilio sender and destination.

1. Provision PostgreSQL and deploy the Node backend with a TLS reverse proxy that forwards to the backend on loopback. Set the body limit to at least 36 MB, preserve the request path, and keep invitation paths and authorization headers out of access logs. Use a dedicated database for the pilot.
2. Set the variables in `backend/.env.hosted.example` in the service's secret configuration. Generate separate random 32-byte hexadecimal values for `LOCAL_WRAP_KEY` and `OTP_SECRET`. Retain the wrapping key across restarts. This pilot stores ciphertext and wrapped keys in PostgreSQL and uses an operator-managed wrapping key.
3. Provision the sender email and scrypt password hash. With Node installed, run the following from PowerShell. Store the output as `SENDER_PASSWORD_HASH` in the deployment secret configuration.

       $credential = Get-Credential -UserName sender@example.com -Message 'Choose a long unique ShareSafe password'
       $credential.GetNetworkCredential().Password | node -e "const c=require('node:crypto');let p='';process.stdin.on('data',b=>p+=b);process.stdin.on('end',()=>{const s=c.randomBytes(16);console.log(s.toString('hex')+':'+c.scryptSync(p.trimEnd(),s,64).toString('hex'))})"

4. Verify your sending domain with Resend and configure the API key and from address. Configure the three Twilio variables if enabling SMS. Invitation and code messages go only to the destination on the share.
5. Build with `gradlew.bat assembleRelease -PshareSafeBaseUrl=https://share.example.com` and sign the release using your Android signing key. Set `ANDROID_CERT_SHA256` to the colon-delimited fingerprint of the certificate actually used on the installed APK, and point `ANDROID_INSTALL_URL` to your HTTPS install page.
6. From `backend`, run `npm ci`, then `npm run dev` with the hosted environment variables. Start the service under a process supervisor. The app serves `/.well-known/assetlinks.json` for the configured certificate. Serve that path directly through HTTPS and check Android domain verification before testing the link.
7. From the signed APK, sign in and share a sample file to a test recipient. Use a second phone to open the link, authenticate locally, request the code, and verify preview and download rules. Test expired and forwarded invitations. Repeat over SMS if enabled.

The debug build continues using the loopback API and test inbox. The hosted service disables the test inbox and development sender token. Hosted delivery responses indicate provider acceptance. If an invitation submission fails, the sender can copy the link and send it privately to the intended recipient.

This pilot still requires a production deployment decision for managed file storage and key wrapping,.

Provider documentation

- [Resend email API](https://resend.com/docs/api-reference/emails/send-email)
- [Twilio Messages API](https://www.twilio.com/docs/messaging/api/message-resource)
- [Android App Links verification](https://developer.android.com/training/app-links/verify-applinks)

## Render free pilot

The supplied Blueprint deploys the API and PostgreSQL in Singapore and uses
Render's HTTPS service URL. On Windows, run
`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/new-pilot-password-hash.ps1`
to choose a sender password and copy its scrypt hash to the clipboard. Paste the
hash directly into Render as SENDER_PASSWORD_HASH. Use the original password to
sign into ShareSafe.

Use the SHA-256 fingerprint from apksigner for ANDROID_CERT_SHA256. Preserve
the pilot signing key and password in private storage for future APK updates.
For the installation fallback, point ANDROID_INSTALL_URL to the HTTPS page
where your authorized testers can obtain the signed APK. For a private GitHub
release, testers must sign in with access to the repository. You can also give
both pilot phones the APK directly before testing the invitation flow.

## Sender registration

Deploy migration 004 and the updated server before installing the email signup
APK. Registration sends a separate six-digit code to the submitted email.
Codes expire after five minutes, allow five guesses, and can be used once.
The server rate-limits requests by source and email. A saved
SENDER_REGISTRATION_CODE environment variable can be removed.

With MAIL_FROM=onboarding@resend.dev, Resend delivers only to the email
associated with your Resend account. If that address already has a ShareSafe
account, sign in with it. To test a fresh signup while retaining the existing
sender account, use a verified sending domain or a separate free Resend account
with a different test email and configure its sending API key in Render.

## Optional Brevo transactional provider

Deploy the updated backend/src/hosted.ts. In Render Environment, set
EMAIL_PROVIDER=brevo, BREVO_API_KEY to the Brevo API key, and
BREVO_SENDER_EMAIL to the verified sender address in Brevo. Activate
transactional sending if prompted. The provider switch applies to signup,
invitations and file access codes. Resend remains the default provider.

Brevo can rewrite a free-mail sender address for a pilot. Confirm acceptance
and delivery in the transactional logs using a different recipient mailbox.
A verified sending domain is preferable for ongoing deliverability.

- [Verify a Brevo sender](https://help.brevo.com/hc/en-us/articles/208836149-Create-a-new-sender-From-name-and-From-email)
- [Brevo sender rewriting](https://help.brevo.com/hc/en-us/articles/14925263522578-Comply-with-Gmail-Yahoo-and-Microsoft-s-requirements-for-email-senders)
