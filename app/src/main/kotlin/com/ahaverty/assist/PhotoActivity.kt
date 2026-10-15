package com.ahaverty.assist

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible: opens the phone's photo picker (several at once) or the camera for the photo button in the text bar,
 * hands what was picked to [Conversation.addPhotos] and brings the panel back. The panel steps aside meanwhile.
 */
class PhotoActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Photos.MAX_PER_QUESTION)) { done(it) }
    private val shoot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> done(if (ok) listOfNotNull(shot) else emptyList()) }
    private var shot: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the picker is already up (recreated behind it)
        if (intent.getBooleanExtra(EXTRA_CAMERA, false)) {
            val file = Photos.cameraFile(this).also { it.delete() }
            shot = FileProvider.getUriForFile(this, "$packageName.photos", file)
            runCatching { shoot.launch(shot!!) }.onFailure { done(emptyList()) }
        } else {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shot?.let { outState.putParcelable("shot", it) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        @Suppress("DEPRECATION")
        shot = savedInstanceState.getParcelable("shot")
    }

    private fun done(uris: List<Uri>) {
        // Read the pictures before finishing: the picker's read grant can end with this activity.
        lifecycleScope.launch {
            App.convo.addPhotos(uris)
            App.convo.photosPicked()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_CAMERA = "camera"
    }
}
