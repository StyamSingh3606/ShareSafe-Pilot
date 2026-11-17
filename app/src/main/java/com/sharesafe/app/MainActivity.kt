package com.sharesafe.app

import android.net.Uri
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.compose.*
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : FragmentActivity() {
    private var pendingInvite by mutableStateOf<String?>(null)
    private var unlocked by mutableStateOf(false)
    private var backgroundAt = 0L
    private var foreground by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureInvitation(intent)
        cacheDir.listFiles()?.filter { it.name.startsWith("preview-") }?.forEach { it.delete() }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { ShareSafeTheme { ShareSafe() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureInvitation(intent)
    }

    private fun captureInvitation(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val api = Uri.parse(BuildConfig.API_URL)
        val invitation = uri.pathSegments.getOrNull(1)
        if (uri.scheme == "https" && uri.host == api.host && uri.port == api.port &&
            uri.pathSegments.size == 2 && uri.pathSegments.first() == "i" &&
            invitation != null && invitation.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            unlocked = false
            pendingInvite = invitation
        }
    }

    override fun onStop() {
        foreground = false
        backgroundAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= 30_000) unlocked = false
    }

    private suspend fun request(path: String, body: JSONObject? = null, token: String = ""): ByteArray =
        withContext(Dispatchers.IO) {
            check(BuildConfig.API_URL.isNotEmpty()) { "Configure the backend URL for this build" }
            val connection = URL(BuildConfig.API_URL + path).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer " + token)
                body?.let {
                    val bytes = it.toString().toByteArray()
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { output -> output.write(bytes) }
                }
                if (connection.responseCode !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException(runCatching { JSONObject(error).getString("error") }.getOrDefault("Request failed"))
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun upload(uri: Uri, id: String, name: String, recipient: String, duration: Int?, access: String): Pair<String, Boolean> {
        val body = withContext(Dispatchers.IO) {
            val plaintext = contentResolver.openInputStream(uri)?.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(result.size() + count <= 25_000_000) { "File exceeds 25 MB" }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } ?: error("File unavailable")
            val rng = SecureRandom()
            val key = ByteArray(32).also(rng::nextBytes)
            val nonce = ByteArray(12).also(rng::nextBytes)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD((id + ":1").toByteArray())
                JSONObject().put("id", id).put("recipient", recipient).put("name", name)
                    .put("duration", duration ?: JSONObject.NULL).put("key", Base64.encodeToString(key, Base64.NO_WRAP))
                    .put("encrypted", Base64.encodeToString(nonce + cipher.doFinal(plaintext), Base64.NO_WRAP))
            } finally {
                key.fill(0)
                plaintext.fill(0)
            }
        }
        val published = JSONObject(request("/shares", body, access).decodeToString())
        return published.getString("invitation") to published.optBoolean("deliveryAccepted", true)
    }

    @Composable
    private fun ShareSafe() {
        val nav = rememberNavController()
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var senderSession by remember { mutableStateOf("") }
        var senderEmail by rememberSaveable { mutableStateOf("") }
        fun run(action: suspend () -> Unit) {
            if (busy) return
            status = ""
            busy = true
            scope.launch {
                try { action() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { status = error.message ?: "Something went wrong" }
                finally { busy = false }
            }
        }
        fun authenticate(success: () -> Unit) {
            val prompt = BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    status = ""
                    success()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    status = errString.toString()
                }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Receive").setNegativeButtonText("Cancel").build())
        }

        LaunchedEffect(pendingInvite) {
            if (pendingInvite != null) nav.navigate("receive") { launchSingleTop = true }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShareSafeHeader()
                    if (status.isNotBlank()) Surface(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer,
                    ) { Text(status, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                        composable("home") {
                            ShareSafeHome(
                                onSend = { status = ""; nav.navigate(if (BuildConfig.DEBUG || senderSession.isNotBlank()) "send" else "sender-login") },
                                onReceive = { authenticate { nav.navigate("receive") } },
                            )
                        }
                        composable("sender-login") {
                            var password by remember { mutableStateOf("") }
                            AccountScreen("Sign in", "Sign in to send files securely.") {
                                OutlinedTextField(senderEmail, { senderEmail = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Email address") }, singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
                                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && senderEmail.isNotBlank() && password.isNotBlank(), onClick = { run {
                                    val login = JSONObject().put("email", senderEmail.trim()).put("password", password)
                                    senderSession = JSONObject(request("/sender/login", login).decodeToString()).getString("session")
                                    password = ""
                                    nav.navigate("send") { popUpTo("sender-login") { inclusive = true } }
                                } }) { Text("Sign in") }
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                                    status = ""
                                    nav.navigate("sender-register")
                                }) { Text("Create an account") }
                                TextButton(enabled = !busy, onClick = {
                                    status = ""
                                    password = ""
                                    nav.navigate("sender-reset")
                                }) { Text("Forgot password") }
                                TextButton(enabled = !busy, onClick = { status = ""; nav.popBackStack() }) { Text("Back") }
                            }
                        }
                        composable("sender-reset") {
                            var email by rememberSaveable { mutableStateOf(senderEmail) }
                            var challenge by remember { mutableStateOf("") }
                            var code by remember { mutableStateOf("") }
                            var password by remember { mutableStateOf("") }
                            var confirmation by remember { mutableStateOf("") }
                            val awaitingCode = challenge.isNotEmpty()
                            AccountScreen("Reset your password", if (awaitingCode)
                                "If this email has an account, we’ll send a six-digit code. It expires in five minutes."
                                else "Enter the email address you use to sign in.") {
                                OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Email address") }, singleLine = true, enabled = !awaitingCode && !busy,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
                                if (awaitingCode) {
                                    OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                                        modifier = Modifier.fillMaxWidth(), label = { Text("Email verification code") }, singleLine = true,
                                        enabled = !busy,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                                        label = { Text("New password") }, supportingText = { Text("At least 12 characters") },
                                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                                    OutlinedTextField(confirmation, { confirmation = it }, modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Confirm new password") }, singleLine = true, enabled = !busy,
                                        visualTransformation = PasswordVisualTransformation(),
                                        isError = confirmation.isNotEmpty() && confirmation != password,
                                        supportingText = { if (confirmation.isNotEmpty() && confirmation != password) Text("Passwords must match") })
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && code.length == 6 &&
                                        password.length in 12..256 && password == confirmation, onClick = { run {
                                        val reset = JSONObject().put("challenge", challenge).put("code", code).put("password", password)
                                        request("/sender/reset-password", reset)
                                        senderEmail = email.trim()
                                        status = "Password reset. Sign in with your new password."
                                        nav.popBackStack()
                                    } }) { Text("Reset password") }
                                    TextButton(enabled = !busy, onClick = { challenge = ""; code = ""; status = "" }) {
                                        Text("Change email or request a new code")
                                    }
                                } else {
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank(), onClick = { run {
                                        val result = request("/sender/reset-password/code", JSONObject().put("email", email.trim()))
                                        challenge = JSONObject(result.decodeToString()).getString("challenge")
                                    } }) { Text("Send reset code") }
                                }
                                TextButton(enabled = !busy, onClick = { status = ""; nav.popBackStack() }) { Text("Back to sign in") }
                            }
                        }
                        composable("sender-register") {
                            var email by rememberSaveable { mutableStateOf(senderEmail) }
                            var password by remember { mutableStateOf("") }
                            var confirmation by remember { mutableStateOf("") }
                            var challenge by remember { mutableStateOf("") }
                            var code by remember { mutableStateOf("") }
                            val awaitingCode = challenge.isNotEmpty()
                            AccountScreen("Create a sender account", if (awaitingCode) "Enter the six-digit verification code sent to your email." else "Use an email address you can access.") {
                                OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Email address") }, singleLine = true, enabled = !awaitingCode && !busy,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
                                if (awaitingCode) {
                                    OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                                        modifier = Modifier.fillMaxWidth(), label = { Text("Email verification code") }, singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && code.length == 6, onClick = { run {
                                        val registration = JSONObject().put("challenge", challenge).put("code", code).put("password", password)
                                        request("/sender/register", registration)
                                        senderEmail = email.trim()
                                        status = "Email verified. Sign in to continue."
                                        nav.popBackStack()
                                    } }) { Text("Verify and create account") }
                                    TextButton(enabled = !busy, onClick = { challenge = ""; code = ""; status = "" }) { Text("Change email or request a new code") }
                                } else {
                                    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Password") }, supportingText = { Text("At least 12 characters") },
                                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                                    OutlinedTextField(confirmation, { confirmation = it }, modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Confirm password") }, singleLine = true, enabled = !busy,
                                        visualTransformation = PasswordVisualTransformation(),
                                        isError = confirmation.isNotEmpty() && confirmation != password,
                                        supportingText = { if (confirmation.isNotEmpty() && confirmation != password) Text("Passwords must match") })
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() &&
                                        password.length in 12..256 && password == confirmation, onClick = { run {
                                        val result = request("/sender/register/code", JSONObject().put("email", email.trim()))
                                        challenge = JSONObject(result.decodeToString()).getString("challenge")
                                    } }) { Text("Send verification code") }
                                }
                                TextButton(enabled = !busy, onClick = { status = ""; nav.popBackStack() }) { Text("Back to sign in") }
                            }
                        }
                        composable("send") {
                            LaunchedEffect(senderSession) {
                                if (!BuildConfig.DEBUG && senderSession.isBlank()) {
                                    nav.navigate("sender-login") { popUpTo("send") { inclusive = true } }
                                }
                            }
                            var access by remember { mutableStateOf("") }
                            var recipient by rememberSaveable { mutableStateOf("") }
                            var minutes by rememberSaveable { mutableStateOf("15") }
                            var timed by rememberSaveable { mutableStateOf(true) }
                            var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
                            var name by rememberSaveable { mutableStateOf("") }
                            var size by rememberSaveable { mutableLongStateOf(-1) }
                            var id by rememberSaveable { mutableStateOf("") }
                            var invitation by rememberSaveable { mutableStateOf("") }
                            val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
                                if (selected != null) {
                                    contentResolver.takePersistableUriPermission(selected, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    uri = selected
                                    name = ""; size = -1
                                    id = UUID.randomUUID().toString()
                                    invitation = ""
                                    contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                                        if (it.moveToFirst()) {
                                            name = it.getString(0)
                                            size = if (it.isNull(1)) -1 else it.getLong(1)
                                        }
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                FlowHeading("Send a file", "Choose a file, a recipient, and how long they can access it.")
                                if (BuildConfig.DEBUG) {
                                    OutlinedTextField(access, { access = it }, label = { Text("Sender access token") }, visualTransformation = PasswordVisualTransformation())
                                } else {
                                    TextButton(enabled = !busy, onClick = {
                                        senderSession = ""
                                    }) { Text("Sign out") }
                                }
                                FlowPanel {
                                    Text("File", style = MaterialTheme.typography.titleMedium)
                                    if (name.isNotBlank()) Text(if (size >= 0) name + " · " + size + " bytes" else name)
                                    else Text("PDF, DOCX, MP3 or WAV · Up to 25 MB", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                        onClick = { pick.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "audio/*")) }) { Text("Choose file") }
                                }
                                FlowPanel {
                                    Text("Recipient", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), enabled = !busy,
                                        label = { Text("Recipient email or international phone") }, singleLine = true)
                                }
                                FlowPanel {
                                    Text("Access", style = MaterialTheme.typography.titleMedium)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(timed, { timed = it }, enabled = !busy)
                                        Text("Time-limited access")
                                    }
                                    Text(if (timed) "The recipient can preview the file until access ends." else "The recipient can preview and download the original.",
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (timed) OutlinedTextField(minutes, { minutes = it.filter { digit -> digit in '0'..'9' }.take(5) },
                                        Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Minutes (1–10,080)") }, singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                }
                                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && uri != null && recipient.isNotBlank() && invitation.isEmpty() && (access.isNotBlank() || senderSession.isNotBlank()), onClick = {
                                    run {
                                        val duration = if (timed) (minutes.toIntOrNull()?.takeIf { it in 1..10080 }
                                            ?: error("Choose a valid duration")) * 60 else null
                                        val result = upload(checkNotNull(uri), id, name, recipient.trim(), duration, if (BuildConfig.DEBUG) access.trim() else senderSession)
                                        invitation = result.first
                                        if (!BuildConfig.DEBUG) status = if (result.second) "Invitation submitted for delivery" else "Delivery failed. Copy the invitation and send it to the intended recipient."

                                    }
                                }) { Text("Encrypt and publish") }
                                if (invitation.isNotEmpty()) FlowPanel {
                                    Text("Invitation", style = MaterialTheme.typography.titleMedium)
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(invitation, style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ShareSafe invitation", invitation))
                                        status = "Invitation copied"
                                    }) { Text("Copy invitation") }
                                }
                                TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                            }
                        }
                        composable("receive") {
                            var invite by rememberSaveable { mutableStateOf("") }
                            var receiver by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
                            var destination by remember { mutableStateOf("") }
                            var code by remember { mutableStateOf("") }
                            var session by remember { mutableStateOf("") }
                            var timed by remember { mutableStateOf(true) }
                            var format by remember { mutableStateOf("") }
                            var previewGeneration by remember { mutableIntStateOf(0) }
                            var deadline by remember { mutableLongStateOf(0L) }
                            var remaining by remember { mutableLongStateOf(0L) }
                            var file by remember { mutableStateOf<File?>(null) }
                            val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                                val target = result.data?.data?.takeIf { result.resultCode == RESULT_OK }
                                if (target != null && unlocked && !timed && session.isNotEmpty()) run {
                                    val bytes = request("/receive/" + invite + "/content/download", token = session)
                                    withContext(Dispatchers.IO) { contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("Save failed") }
                                    status = "File saved"
                                }
                            }
                            LaunchedEffect(pendingInvite, unlocked) {
                                if (!unlocked) {
                                    session = ""
                                    file = null
                                    destination = ""
                                    code = ""
                                    deadline = 0
                                    previewGeneration++
                                } else pendingInvite?.let {
                                    invite = it
                                    pendingInvite = null
                                }
                            }
                            LaunchedEffect(deadline, unlocked) {
                                if (unlocked && deadline == 0L) return@LaunchedEffect
                                while (unlocked && deadline > 0) {
                                    remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                                    if (remaining == 0L) break
                                    delay(250)
                                }
                                session = ""
                                file = null
                            }
                            if (!unlocked) {
                                AccountScreen("Receive a file", "Unlock with your phone’s biometric prompt to continue.") {
                                    Button(modifier = Modifier.fillMaxWidth(), onClick = { authenticate {} }) { Text("Unlock Receive") }
                                    TextButton(onClick = { pendingInvite = null; status = ""; nav.popBackStack() }) { Text("Back") }
                                }
                            } else Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                FlowHeading("Receive a file", "Open the invitation and verify your access.")
                                FlowPanel {
                                    OutlinedTextField(invite, { entered ->
                                        val value = entered.trim()
                                        val pastedLink = Uri.parse(value)
                                        val api = Uri.parse(BuildConfig.API_URL)
                                        invite = if (pastedLink.scheme == "https" && pastedLink.host == api.host &&
                                            pastedLink.port == api.port && pastedLink.pathSegments.size == 2 &&
                                            pastedLink.pathSegments.first() == "i") pastedLink.lastPathSegment.orEmpty()
                                            else value
                                        destination = ""; session = ""; file = null; previewGeneration++
                                    }, modifier = Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Invitation code") },
                                        supportingText = { Text("Paste the invitation link or its code.") })
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && invite.isNotBlank(), onClick = {
                                        run {
                                            check(invite.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Enter a valid invitation" }
                                            val info = JSONObject(request("/receive/" + invite).decodeToString())
                                            destination = info.getString("destination")
                                            timed = info.getBoolean("timed")
                                            format = info.optString("format")
                                        }
                                    }) { Text("Open invitation") }
                                }
                                if (destination.isNotEmpty() && session.isEmpty()) FlowPanel {
                                    Text("Verification destination " + destination, style = MaterialTheme.typography.titleMedium)
                                    Button(enabled = !busy, onClick = { run {
                                        request("/receive/" + invite + "/code", JSONObject().put("receiver", receiver))
                                        status = "Verification code requested"
                                    } }) { Text("Request code") }
                                    OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, Modifier.fillMaxWidth(), label = { Text("Verification code") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && code.length == 6, onClick = { run {
                                        val start = SystemClock.elapsedRealtime()
                                        val result = JSONObject(request("/receive/" + invite + "/verify",
                                            JSONObject().put("receiver", receiver).put("code", code)).decodeToString())
                                        format = result.optString("format", format)
                                        deadline = start + result.getLong("remainingMs")
                                        session = result.getString("session")
                                        code = ""
                                    } }) { Text("Verify") }
                                }
                                if (session.isNotEmpty()) {
                                    FlowPanel {
                                        Text("File access verified", style = MaterialTheme.typography.titleMedium)
                                        if (timed) Text("Access remaining " + (remaining / 1000) + " seconds")
                                        Button(enabled = !busy && file == null, onClick = { run {
                                            val generation = previewGeneration
                                            val originalSession = session
                                            val bytes = request("/receive/" + invite + "/content/preview", token = originalSession)
                                            if (unlocked && generation == previewGeneration && session == originalSession && SystemClock.elapsedRealtime() < deadline) {
                                                val staged = withContext(Dispatchers.IO) { File.createTempFile("preview-", "." + format, cacheDir).apply { writeBytes(bytes) } }
                                                if (unlocked && generation == previewGeneration && session == originalSession && SystemClock.elapsedRealtime() < deadline) file = staged
                                                else staged.delete()
                                            }
                                        } }) { Text("Open preview") }
                                        if (!timed) Button(enabled = !busy, onClick = {
                                            val mimeType = when (format) {
                                                "pdf" -> "application/pdf"
                                                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                                "mp3" -> "audio/mpeg"
                                                "wav" -> "audio/wav"
                                                else -> null
                                            }
                                            if (mimeType == null) {
                                                status = "This file type cannot be saved"
                                            } else {
                                                save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                    type = mimeType
                                                    putExtra(Intent.EXTRA_TITLE, "ShareSafe." + format)
                                                })
                                            }
                                        }) { Text("Download original") }
                                    }
                                    file?.let { Preview(it, format) }
                                }
                                TextButton(onClick = { pendingInvite = null; unlocked = false; nav.popBackStack() }) { Text("Back") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountScreen(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowHeading(title, description)
            FlowPanel(content)
        }
    }

    @Composable
    private fun Preview(file: File, format: String) {
        DisposableEffect(file) { onDispose { file.delete() } }
        when (format) {
            "mp3", "wav" -> {
                val player = remember(file) { ExoPlayer.Builder(this).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                } }
                LaunchedEffect(foreground, player) { if (!foreground) player.pause() }
                DisposableEffect(player) { onDispose { player.release() } }
                AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxWidth().height(140.dp))
            }
            "pdf" -> {
                var page by remember(file) { mutableIntStateOf(0) }
                var pages by remember(file) { mutableIntStateOf(0) }
                var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
                var failed by remember(file) { mutableStateOf(false) }
                LaunchedEffect(file, page) {
                    try {
                        bitmap = withContext(Dispatchers.IO) {
                            val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                            renderer.use {
                                pages = it.pageCount
                                it.openPage(page).use { pdf ->
                                    val width = minOf(1200, pdf.width)
                                    val height = (pdf.height.toLong() * width / pdf.width).coerceIn(1, 1800).toInt()
                                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                                        eraseColor(android.graphics.Color.WHITE)
                                        pdf.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                }
                if (failed) Text("This PDF could not be rendered")
                bitmap?.let { Image(it.asImageBitmap(), "PDF page " + (page + 1), Modifier.fillMaxWidth()) }
                Row {
                    TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
                    TextButton(enabled = page + 1 < pages, onClick = { page++ }) { Text("Next") }
                }
            }
            "docx" -> {
                var paragraphs by remember(file) { mutableStateOf<List<String>>(emptyList()) }
                var renderFailed by remember(file) { mutableStateOf(false) }
                LaunchedEffect(file) {
                    try {
                        paragraphs = withContext(Dispatchers.IO) {
                            java.util.zip.ZipFile(file).use { zip ->
                                val document = zip.getEntry("word/document.xml") ?: error("Missing document")
                                check(document.size in 1..4_000_000)
                                zip.getInputStream(document).use { input ->
                                    val parser = android.util.Xml.newPullParser()
                                    parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                                    parser.setInput(input, null)
                                    val result = mutableListOf<String>()
                                    val paragraph = StringBuilder()
                                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                                            paragraph.append(parser.nextText())
                                        }
                                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "p") {
                                            result.add(paragraph.toString())
                                            paragraph.clear()
                                        }
                                        parser.next()
                                    }
                                    result
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { renderFailed = true }
                }
                if (renderFailed) Text("This document could not be rendered")
                else if (paragraphs.isEmpty()) Text("No document text available")
                else Text(paragraphs.joinToString("\n\n"))
            }
        }
    }
}
