package re.chasam.voicetastic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.model.VoiceConfigStore
import re.chasam.voicetastic.service.MeshServiceManager

/**
 * Retained owner of the whole session graph: the [MeshServiceManager] and the
 * persisted [voiceConfig]. Living in a [androidx.lifecycle.ViewModel] means the
 * framework calls [onCleared] exactly once when the activity is permanently
 * destroyed, which is the only place the manager's background work (the Rust
 * session, reconnect loop, listener thread) can be torn down deterministically.
 *
 * The feature view models ([re.chasam.voicetastic.ui.chat.MessagingViewModel],
 * [re.chasam.voicetastic.ui.settings.ConfigViewModel]) are created against this
 * one's manager + flow, so the entire graph shares a single retention scope and
 * a feature VM can never outlive the manager it depends on.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    val meshServiceManager = MeshServiceManager(app)

    private val voiceConfigStore = VoiceConfigStore(app)
    // Hydrated from disk; persisted on every change after the initial load.
    val voiceConfig = MutableStateFlow(voiceConfigStore.load())

    init {
        viewModelScope.launch {
            // drop(1): skip the value we just loaded — no point writing it back.
            voiceConfig.drop(1).collect { voiceConfigStore.save(it) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        meshServiceManager.destroy()
    }
}
