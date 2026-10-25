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

        // Trust self-signed certificates (checkbox)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_TRUST_SSL)
                .title(getString(R.string.setting_trust_ssl))
                .description(getString(R.string.setting_trust_ssl_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(PreferencesManager.trustSsl)
                .build()
        )

        // Landscape photos only (checkbox)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_LANDSCAPE_ONLY)
                .title(getString(R.string.setting_landscape_only))
                .description(getString(R.string.setting_landscape_only_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(PreferencesManager.landscapeOnly)
                .build()
        )

        // Photo quality (radio group)
        actions.add(qualityAction(ACTION_QUALITY_PREVIEW, R.string.quality_preview, PreferencesManager.QUALITY_PREVIEW))
        actions.add(qualityAction(ACTION_QUALITY_FULL_JPEG_PNG, R.string.quality_full_jpeg_png, PreferencesManager.QUALITY_FULL_JPEG_PNG))
        actions.add(qualityAction(ACTION_QUALITY_FULL_ALL, R.string.quality_full_all, PreferencesManager.QUALITY_FULL_ALL))

        // Include archived & hidden photos (checkbox)
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_INCLUDE_ARCHIVED_HIDDEN)
                .title(getString(R.string.setting_include_archived_hidden))
                .description(getString(R.string.setting_include_archived_hidden_desc))
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(PreferencesManager.includeArchivedHidden)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_SERVER_URL -> {
                val value = action.editDescription?.toString()?.trim() ?: ""
                if (value.startsWith("http://", ignoreCase = true)) {
                    HttpWarningFragment.show(requireFragmentManager(), value)
                } else {
                    saveServerUrl(value)
                }
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
            ACTION_TRUST_SSL -> PreferencesManager.trustSsl = action.isChecked
            ACTION_LANDSCAPE_ONLY -> PreferencesManager.landscapeOnly = action.isChecked
            ACTION_INCLUDE_ARCHIVED_HIDDEN -> PreferencesManager.includeArchivedHidden = action.isChecked
            ACTION_QUALITY_PREVIEW, ACTION_QUALITY_FULL_JPEG_PNG, ACTION_QUALITY_FULL_ALL -> {
                PreferencesManager.quality = when {
                    isChecked(ACTION_QUALITY_FULL_JPEG_PNG) -> PreferencesManager.QUALITY_FULL_JPEG_PNG
                    isChecked(ACTION_QUALITY_FULL_ALL) -> PreferencesManager.QUALITY_FULL_ALL
                    else -> PreferencesManager.QUALITY_PREVIEW
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect an album picked in the sub-step.
        findActionById(ACTION_CHOOSE_ALBUM)?.description = currentAlbumDescription()
        refresh(ACTION_CHOOSE_ALBUM)

        // Reflect a server URL accepted (or cleared) in the HTTP warning sub-step.
        val saved = PreferencesManager.serverUrl
        findActionById(ACTION_SERVER_URL)?.let {
            it.editDescription = saved
            it.description = saved.ifBlank { getString(R.string.setting_server_url_desc) }
            refresh(ACTION_SERVER_URL)
        }
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

    private fun qualityAction(id: Long, titleRes: Int, value: String): GuidedAction {
        return GuidedAction.Builder(requireContext())
            .id(id)
            .title(getString(titleRes))
            .checkSetId(CHECKSET_QUALITY)
            .checked(PreferencesManager.quality == value)
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

    private fun saveServerUrl(value: String) {
        PreferencesManager.serverUrl = value
        findActionById(ACTION_SERVER_URL)?.let {
            it.description = value.ifBlank { getString(R.string.setting_server_url_desc) }
            refresh(ACTION_SERVER_URL)
        }
    }

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
        private const val ACTION_TRUST_SSL = 30L
        private const val ACTION_LANDSCAPE_ONLY = 31L
        private const val ACTION_QUALITY_PREVIEW = 40L
        private const val ACTION_QUALITY_FULL_JPEG_PNG = 41L
        private const val ACTION_QUALITY_FULL_ALL = 42L
        private const val ACTION_INCLUDE_ARCHIVED_HIDDEN = 43L

        private const val CHECKSET_SOURCE = 1
        private const val CHECKSET_QUALITY = 4
    }
}
