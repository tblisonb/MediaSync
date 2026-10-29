package com.tlisonbee.mediasync.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The Library tab's web view and what it reports. It's kept while the app switches tabs, so a page
 * keeps its place. When the server sends it to the login page (the login expired, or the phone's
 * access was revoked), [onLoginNeeded] gets a fresh login for the page it was going to.
 */
@Stable
class Library(context: Context, server: String, private val onLoginNeeded: (next: String) -> Unit) {
    private val home = server.toUri()

    var canGoBack by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(100)
        private set

    /** A video playing full screen, shown over the whole app. */
    var fullScreen by mutableStateOf<View?>(null)
        private set
    private var leaveFullScreen: WebChromeClient.CustomViewCallback? = null

    /** Opens the system's file picker for an upload field; set by the tab, which owns the launcher. */
    var choose: (Intent) -> Unit = {}
    private var picked: ValueCallback<Array<Uri>>? = null

    /** The first login was made; the web view has pages of its own after that. */
    var started = false

    /** Logins that kept landing back on the login page: something's wrong, so the tab says so. */
    var stuck by mutableStateOf(false)
        private set
    private var tries = 0

    /** The last page asked for, which a login returns to. */
    private var wanted = "/"

    val view: WebView = WebView(context).apply {
        // Laid out at its full size: with the wrap-content height a view gets by default, the
        // page's 100vh is 0, and the viewer's video (at most 100vh tall) shrinks to nothing.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        @Suppress("SetJavaScriptEnabled") // the web UI's own pages, behind its login
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, userAgent, disposition, mimeType, _ -> download(url, userAgent, disposition, mimeType) }
    }

    private fun isServer(uri: Uri) = uri.scheme == home.scheme && uri.host == home.host && uri.port == home.port

    private fun relogin() {
        if (++tries > 3) {
            stuck = true
            return
        }
        onLoginNeeded(wanted)
    }

    fun retry() {
        tries = 0
        stuck = false
        onLoginNeeded(wanted)
    }

    fun reload() = view.reload()

    /** One step back: out of full screen, or to the previous page. False when there's nowhere to go. */
    fun back(): Boolean {
        when {
            fullScreen != null -> exitFullScreen()
            view.canGoBack() -> view.goBack()
            else -> return false
        }
        return true
    }

    fun exitFullScreen() {
        leaveFullScreen?.onCustomViewHidden()
        leaveFullScreen = null
        fullScreen = null
    }

    /** The tab was left: stop what's playing, as a browser does for a background tab. */
    fun pause() {
        view.evaluateJavascript("document.querySelectorAll('video, audio').forEach(m => m.pause())", null)
        view.onPause()
    }

    fun resume() = view.onResume()

    fun filesChosen(resultCode: Int, data: Intent?) {
        val uris = if (resultCode != Activity.RESULT_OK || data == null) {
            null
        } else {
            data.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
        }
        picked?.onReceiveValue(uris)
        picked = null
    }

    private fun download(url: String, userAgent: String, disposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(url.toUri()).apply {
            // The web UI's files need its login.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent)
            setMimeType(mimeType)
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        view.context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(view.context, "Downloading $name", Toast.LENGTH_SHORT).show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            if (!isServer(url)) {
                // Links to other sites open in the browser.
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
            if (url.path == "/login") {
                relogin()
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val uri = url.toUri()
            if (!isServer(uri)) return
            when (uri.path) {
                // Not every redirect passes shouldOverrideUrlLoading (one after a POST may not).
                "/login" -> {
                    view.stopLoading()
                    relogin()
                }
                "/login/device" -> {}
                else -> wanted = uri.encodedPath + (uri.encodedQuery?.let { "?$it" } ?: "")
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (isServer(url.toUri()) && url.toUri().path?.startsWith("/login") == false) tries = 0
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canGoBack = view.canGoBack()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullScreen = view
            leaveFullScreen = callback
        }

        override fun onHideCustomView() {
            fullScreen = null
            leaveFullScreen = null
        }

        override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            picked?.onReceiveValue(null)
            picked = callback
            val intent = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                choose(intent)
            } catch (_: ActivityNotFoundException) {
                filesChosen(Activity.RESULT_CANCELED, null)
            }
            return true
        }
    }
}

/** The Library tab: the web UI, once this phone is let in. */
@Composable
fun LibraryTab(library: Library, state: LibraryState, viewModel: MainViewModel, modifier: Modifier) {
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        library.filesChosen(it.resultCode, it.data)
    }
    SideEffect { library.choose = { intent -> chooser.launch(intent) } }
    LaunchedEffect(Unit) { if (!library.started) viewModel.openLibrary() }
    LaunchedEffect(state.login) {
        val login = state.login ?: return@LaunchedEffect
        library.view.postUrl(login.url, login.body)
        library.started = true
        viewModel.loginSent()
    }
    DisposableEffect(library) {
        library.resume()
        onDispose { library.pause() }
    }
    val background = MaterialTheme.colorScheme.background.toArgb()

    Box(modifier) {
        when {
            state.needsPassword -> AllowPhone(state, viewModel)
            state.error != null || library.stuck -> Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.error ?: "The web UI keeps asking this phone to log in again.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { library.retry() }, enabled = !state.busy) { Text("Try again") }
            }
            !library.started -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> {
                AndroidView(
                    factory = { library.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    update = { it.setBackgroundColor(background) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (library.progress < 100) {
                    LinearProgressIndicator(progress = { library.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The first visit: the web password lets this phone in, once. */
@Composable
private fun AllowPhone(state: LibraryState, viewModel: MainViewModel) {
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (password.isNotEmpty() && !state.busy) viewModel.allowLibrary(password) }
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Open the library here", style = MaterialTheme.typography.titleLarge)
        Text(
            "The library is the web UI, inside the app. Enter its password once to let this phone in. " +
                "After that it opens without the password, until you revoke this phone's web access on " +
                "the Devices page or change the password.",
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Web UI password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = submit, enabled = password.isNotEmpty() && !state.busy) {
            Text(if (state.busy) "Letting this phone in…" else "Let this phone in")
        }
    }
}

/** A video the web view plays full screen, over the whole app, with the system bars hidden. */
@Composable
fun FullScreen(view: View) {
    val window = (LocalContext.current as Activity).window
    DisposableEffect(view) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                (view.parent as? ViewGroup)?.removeView(view)
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
