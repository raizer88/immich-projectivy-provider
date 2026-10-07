package tv.projectivy.plugin.wallpaperprovider.immich

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

class SettingsFragment : GuidedStepSupportFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.plugin_name),
            getString(R.string.settings_description) +
                "\n\n" + getString(R.string.setting_seconds_per_image_desc),
            getString(R.string.settings_title),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        PreferencesManager.init(requireContext())

        // Server URL
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SERVER_URL)
                .title(getString(R.string.setting_server_url))
                .description(PreferencesManager.serverUrl.ifBlank { getString(R.string.setting_server_url_desc) })
                .editDescription(PreferencesManager.serverUrl)
                .descriptionEditable(true)
                .build()
        )

        // API key (masked description, real value editable)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_API_KEY)
                .title(getString(R.string.setting_api_key))
                .description(PreferencesManager.maskedApiKey().ifBlank { getString(R.string.setting_api_key_desc) })
                .editDescription(PreferencesManager.apiKey)
                .descriptionEditable(true)
                .build()
        )

        // Test connection
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TEST)
                .title(getString(R.string.setting_test_connection))
                .description(getString(R.string.setting_test_connection))
                .build()
        )

        // Photo source (radio group)
        actions.add(sourceAction(ACTION_SRC_RECENT, R.string.setting_source_recent, PreferencesManager.SOURCE_RECENT))
        actions.add(sourceAction(ACTION_SRC_FAVORITES, R.string.setting_source_favorites, PreferencesManager.SOURCE_FAVORITES))
        actions.add(sourceAction(ACTION_SRC_RANDOM, R.string.setting_source_random, PreferencesManager.SOURCE_RANDOM))
        actions.add(sourceAction(ACTION_SRC_ALBUM, R.string.setting_source_album, PreferencesManager.SOURCE_ALBUM))

        // Choose album (meaningful when source == album)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_CHOOSE_ALBUM)
                .title(getString(R.string.setting_choose_album))
                .description(currentAlbumDescription())
                .build()
        )

        // Images to cache
        actions.add(numberAction(ACTION_IMAGE_COUNT, R.string.setting_image_count, PreferencesManager.imageCount))

        // Seconds per image (advisory)
        actions.add(numberAction(ACTION_SECONDS, R.string.setting_seconds_per_image, PreferencesManager.secondsPerImage))

        // Cache size limit
        actions.add(numberAction(ACTION_CACHE_LIMIT, R.string.setting_cache_limit, PreferencesManager.cacheLimitMb))

        // Trust self-signed certificates (checkbox)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TRUST_SSL)
                .title(getString(R.string.setting_trust_ssl))
                .description(getString(R.string.setting_trust_ssl_desc))
                .checkSetId(CHECKSET_SSL)
                .checked(PreferencesManager.trustSsl)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_SERVER_URL -> {
                val value = action.editDescription?.toString()?.trim() ?: ""
                PreferencesManager.serverUrl = value
                action.description = value.ifBlank { getString(R.string.setting_server_url_desc) }
                refresh(ACTION_SERVER_URL)
            }
            ACTION_API_KEY -> {
                val value = action.editDescription?.toString()?.trim() ?: ""
                PreferencesManager.apiKey = value
                action.description = PreferencesManager.maskedApiKey().ifBlank { getString(R.string.setting_api_key_desc) }
                refresh(ACTION_API_KEY)
            }
            ACTION_TEST -> runTestConnection()
            ACTION_SRC_RECENT, ACTION_SRC_FAVORITES, ACTION_SRC_RANDOM, ACTION_SRC_ALBUM -> {
                PreferencesManager.source = when {
                    isChecked(ACTION_SRC_FAVORITES) -> PreferencesManager.SOURCE_FAVORITES
                    isChecked(ACTION_SRC_RANDOM) -> PreferencesManager.SOURCE_RANDOM
                    isChecked(ACTION_SRC_ALBUM) -> PreferencesManager.SOURCE_ALBUM
                    else -> PreferencesManager.SOURCE_RECENT
                }
            }
            ACTION_CHOOSE_ALBUM -> openAlbumPicker()
            ACTION_IMAGE_COUNT -> {
                parseAndStore(action) { PreferencesManager.imageCount = it }
                action.description = PreferencesManager.imageCount.toString()
                refresh(ACTION_IMAGE_COUNT)
            }
            ACTION_SECONDS -> {
                parseAndStore(action) { PreferencesManager.secondsPerImage = it }
                action.description = PreferencesManager.secondsPerImage.toString()
                refresh(ACTION_SECONDS)
            }
            ACTION_CACHE_LIMIT -> {
                parseAndStore(action) { PreferencesManager.cacheLimitMb = it }
                action.description = PreferencesManager.cacheLimitMb.toString()
                refresh(ACTION_CACHE_LIMIT)
            }
            ACTION_TRUST_SSL -> PreferencesManager.trustSsl = action.isChecked
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect an album picked in the sub-step.
        findActionById(ACTION_CHOOSE_ALBUM)?.description = currentAlbumDescription()
        refresh(ACTION_CHOOSE_ALBUM)
    }

    // --- helpers -------------------------------------------------------------

    private fun sourceAction(id: Long, titleRes: Int, sourceValue: String): GuidedAction {
        return GuidedAction.Builder(requireContext())
            .id(id)
            .title(getString(titleRes))
            .checkSetId(CHECKSET_SOURCE)
            .checked(PreferencesManager.source == sourceValue)
            .build()
    }

    private fun numberAction(id: Long, titleRes: Int, value: Int): GuidedAction {
        return GuidedAction.Builder(requireContext())
            .id(id)
            .title(getString(titleRes))
            .description(value.toString())
            .editDescription(value.toString())
            .descriptionEditable(true)
            .build()
    }

    private fun currentAlbumDescription(): String =
        PreferencesManager.albumName.ifBlank { "(none)" }

    private fun isChecked(id: Long): Boolean =
        findActionById(id)?.isChecked == true

    private fun refresh(id: Long) {
        val pos = findActionPositionById(id)
        if (pos >= 0) notifyActionChanged(pos)
    }

    private fun parseAndStore(action: GuidedAction, apply: (Int) -> Unit) {
        val value = action.editDescription?.toString()?.trim()?.toIntOrNull()
        if (value != null) apply(value)
    }

    private fun runTestConnection() {
        val url = PreferencesManager.serverUrl
        val key = PreferencesManager.apiKey
        if (url.isBlank() || key.isBlank()) {
            Toast.makeText(requireContext(), R.string.error_no_config, Toast.LENGTH_LONG).show()
            return
        }
        findActionById(ACTION_TEST)?.description = "\u2026"
        refresh(ACTION_TEST)

        val client = ImmichClient()
        val base = client.normalizeBaseUrl(url)
        val trust = PreferencesManager.trustSsl
        Thread {
            val result = client.testConnection(base, key, trust)
            activity?.runOnUiThread {
                findActionById(ACTION_TEST)?.description = result.message
                refresh(ACTION_TEST)
            }
        }.start()
    }

    private fun openAlbumPicker() {
        GuidedStepSupportFragment.add(requireFragmentManager(), AlbumPickerFragment())
    }

    companion object {
        private const val ACTION_SERVER_URL = 1L
        private const val ACTION_API_KEY = 2L
        private const val ACTION_TEST = 3L
        private const val ACTION_SRC_RECENT = 10L
        private const val ACTION_SRC_FAVORITES = 11L
        private const val ACTION_SRC_RANDOM = 12L
        private const val ACTION_SRC_ALBUM = 13L
        private const val ACTION_CHOOSE_ALBUM = 14L
        private const val ACTION_IMAGE_COUNT = 20L
        private const val ACTION_SECONDS = 21L
        private const val ACTION_CACHE_LIMIT = 22L
        private const val ACTION_TRUST_SSL = 30L

        private const val CHECKSET_SOURCE = 1
        private const val CHECKSET_SSL = 2
    }
}
