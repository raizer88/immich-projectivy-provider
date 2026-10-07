package tv.projectivy.plugin.wallpaperprovider.immich

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperProviderContract

class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferencesManager.init(this)

        if (!isProjectivyInstalled()) {
            Toast.makeText(this, R.string.projectivy_not_installed, Toast.LENGTH_LONG).show()
        }

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, SettingsFragment(), android.R.id.content)
        }
    }

    override fun onStop() {
        super.onStop()
        // Inform Projectivy that our wallpapers may have changed. Idempotent and cheap:
        // Projectivy re-requests and usually hits its own cache.
        requestWallpaperUpdate()
    }

    private fun requestWallpaperUpdate() {
        val intent = Intent(WallpaperProviderContract.ACTION_WALLPAPER_PROVIDER_UPDATED).apply {
            `package` = PROJECTIVY_PACKAGE_ID
            putExtra(WallpaperProviderContract.EXTRA_PROVIDER_ID, getString(R.string.plugin_uuid))
            putExtra(WallpaperProviderContract.EXTRA_UPDATE_REASON, WallpaperProviderContract.UpdateReason.PREFS_CHANGED)
        }
        sendBroadcast(intent)
    }

    private fun isProjectivyInstalled(): Boolean {
        return try {
            packageManager.getApplicationInfo(PROJECTIVY_PACKAGE_ID, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val PROJECTIVY_PACKAGE_ID = "com.spocky.projengmenu"
    }
}
