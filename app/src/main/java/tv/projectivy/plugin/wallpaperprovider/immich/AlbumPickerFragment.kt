package tv.projectivy.plugin.wallpaperprovider.immich

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Sub-step that lists the Immich albums and lets the user pick one. Albums are
 * fetched asynchronously (never on the UI thread) and the action list is replaced
 * once they arrive.
 */
class AlbumPickerFragment : GuidedStepSupportFragment() {

    private var albums: List<ImmichAlbum> = emptyList()

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.choose_album_title),
            getString(R.string.choose_album_desc),
            null,
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LOADING)
                .title(getString(R.string.loading_albums))
                .focusable(false)
                .build()
        )
        loadAlbums()
    }

    private fun loadAlbums() {
        PreferencesManager.init(requireContext())
        val url = PreferencesManager.serverUrl
        val key = PreferencesManager.apiKey
        if (url.isBlank() || key.isBlank()) {
            setActions(listOf(infoAction(getString(R.string.error_no_config))))
            return
        }
        val client = ImmichClient()
        val base = client.normalizeBaseUrl(url)
        val trust = PreferencesManager.trustSsl
        Thread {
            val fetched = try {
                client.fetchAlbums(base, key, trust)
            } catch (e: Exception) {
                emptyList()
            }
            activity?.runOnUiThread {
                if (fetched.isEmpty()) {
                    setActions(listOf(infoAction(getString(R.string.no_albums_run_test))))
                } else {
                    albums = fetched
                    setActions(buildAlbumActions(fetched))
                }
            }
        }.start()
    }

    private fun buildAlbumActions(fetched: List<ImmichAlbum>): MutableList<GuidedAction> {
        val result = mutableListOf<GuidedAction>()
        for ((i, album) in fetched.withIndex()) {
            result.add(
                GuidedAction.Builder(requireContext())
                    .id(ACTION_ALBUM_BASE + i)
                    .title(album.name)
                    .checkSetId(CHECKSET_ALBUM)
                    .checked(album.id == PreferencesManager.albumId)
                    .build()
            )
        }
        return result
    }

    private fun infoAction(message: String): GuidedAction {
        return GuidedAction.Builder(requireContext())
            .id(ACTION_INFO)
            .title(message)
            .focusable(false)
            .build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val index = (action.id - ACTION_ALBUM_BASE).toInt()
        val album = albums.getOrNull(index) ?: return
        PreferencesManager.albumId = album.id
        PreferencesManager.albumName = album.name
        requireFragmentManager().popBackStack()
    }

    companion object {
        private const val ACTION_LOADING = 1L
        private const val ACTION_INFO = 2L
        private const val ACTION_ALBUM_BASE = 1000L
        private const val CHECKSET_ALBUM = 1
    }
}
