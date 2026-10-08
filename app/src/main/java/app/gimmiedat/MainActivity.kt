package app.gimmiedat

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.textclassifier.TextClassifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gimmiedat.engine.extractUrl
import app.gimmiedat.ui.ClipState
import app.gimmiedat.ui.GimmieDatRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** What's on the clipboard, judged without reading it (reading pops a system toast). */
    private val clip = MutableStateFlow(ClipState.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            GimmieDatRoot(clip = clip, readClipboard = ::readClipboard)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { clip.value = peekClipboard() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // apps may only look at the clipboard while focused
        if (hasFocus) clip.value = peekClipboard()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onPause() {
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        } ?: return
        val url = extractUrl(text)
        if (url == null) {
            Grabber.input.value = text.trim().take(500)
            Grabber.submit(text) // shows the "doesn't look like a link" hint
            return
        }
        if (!Grabber.submit(url) && Grabber.isDownloading) {
            android.widget.Toast.makeText(this, "one at a time: that link is next up", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun peekClipboard(): ClipState {
        val cm = getSystemService(ClipboardManager::class.java) ?: return ClipState.NONE
        if (!cm.hasPrimaryClip()) return ClipState.NONE
        val description = cm.primaryClipDescription ?: return ClipState.NONE
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        ) return ClipState.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE
        ) {
            return if (description.getConfidenceScore(TextClassifier.TYPE_URL) > 0.5f) ClipState.LINK else ClipState.TEXT
        }
        return ClipState.TEXT
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()
    }
}
