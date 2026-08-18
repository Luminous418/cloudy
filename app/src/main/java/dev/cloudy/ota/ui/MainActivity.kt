package dev.cloudy.ota.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import dev.cloudy.ota.R
import dev.cloudy.ota.databinding.ActivityMainBinding
import dev.cloudy.ota.ota.UpdateAlarm
import dev.cloudy.ota.ota.UpdateChecker
import dev.cloudy.ota.ota.UpdateNotifier
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * OneUI 8 shell:
 *   - ToolbarLayout gives the collapsing large title + subtitle (handled by the library).
 *   - BottomTabLayout is the OneUI bottom navigation bar, populated from menu_bottom_tabs.xml.
 * Fragments are swapped directly (no ViewPager2) which is the OneUI-native pattern.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Which tab a tapped update notification should open. */
        const val EXTRA_TAB = "dev.cloudy.ota.extra.TAB"
        private const val REQUEST_NOTIF_PERMISSION = 9001
    }

    private lateinit var binding: ActivityMainBinding

    private val updateFragment by lazy { CheckUpdateFragment() }
    private val maintainerFragment by lazy { MaintainerFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate(): AppCompat installs its own Factory2 there, and
        // LayoutInflaterCompat.setFactory2 throws once a factory is already attached. This is
        // what pushes the bundled One UI Sans into the library's own CardItemView/Separator
        // TextViews, which ignore the theme-level fontFamily.
        OneUiFont.install(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomTab.inflateMenu(R.menu.menu_bottom_tabs) { item ->
            when (item.itemId) {
                R.id.tab_update -> show(updateFragment, R.string.tab_check_update)
                R.id.tab_maintainer -> show(maintainerFragment, R.string.tab_maintainer)
                R.id.tab_settings -> show(settingsFragment, R.string.tab_settings)
            }
            true
        }

        removeOverflowTab()

        if (savedInstanceState == null) {
            // A tapped notification may ask for a specific tab; otherwise land on Update.
            val requestedTab = intent?.getStringExtra(EXTRA_TAB)
            if (requestedTab == UpdateChecker.TAB_SETTINGS) {
                show(settingsFragment, R.string.tab_settings)
                binding.bottomTab.getTabAt(2)?.select()
            } else {
                show(updateFragment, R.string.tab_check_update)
                binding.bottomTab.getTabAt(0)?.select()
            }
        }

        // Start with the collapsing header already collapsed (title at the top-left),
        // matching the state it reaches after scrolling down.
        binding.toolbarLayout.setExpanded(false, false)

        startUpdateNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // BottomTabLayout maps a selected tab to the menu item callback in show().
        when (intent.getStringExtra(EXTRA_TAB)) {
            UpdateChecker.TAB_SETTINGS -> binding.bottomTab.getTabAt(2)?.select()
            UpdateChecker.TAB_UPDATE -> binding.bottomTab.getTabAt(0)?.select()
        }
    }

    /**
     * Background update notifications: ensure the channel exists, ask for the runtime
     * permission if needed (Android 13+), arm the daily alarm, and do one silent check
     * right now so the user gets pinged as soon as something is released.
     */
    private fun startUpdateNotifications() {
        val ctx = applicationContext
        UpdateNotifier.ensureChannel(ctx)
        if (!UpdateChecker.notificationsEnabled(ctx)) return
        if (!UpdateNotifier.canPost(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIF_PERMISSION
            )
        }
        UpdateAlarm.schedule(ctx)
        thread {
            val result = runBlocking { UpdateChecker.check(ctx) }
            UpdateNotifier.notifyIfNeeded(ctx, result)
        }
    }

    /**
     * BottomTabLayout appends a "More" overflow tab (hamburger, opening a grid dialog)
     * whenever it decides some menu items don't fit. With only three tabs we never want it,
     * so drop it if the library added one.
     */
    private fun removeOverflowTab() {
        val overflowId = dev.oneuiproject.oneui.design.R.id.bottom_tab_menu_show_grid_dialog
        for (i in binding.bottomTab.tabCount - 1 downTo 0) {
            if (binding.bottomTab.getTabAt(i)?.id == overflowId) {
                binding.bottomTab.removeTabAt(i)
            }
        }
    }

    /** Swap the main_content fragment and update the collapsing header subtitle. */
    private fun show(fragment: Fragment, subtitleRes: Int) {
        // Dropping any pushed sub-screen (e.g. Advanced settings) when switching tabs so the
        // tab always lands on its base fragment and the back stack can't leak between tabs.
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        // Plain FragmentTransaction API: the KTX commit{} extension lives in
        // androidx.fragment:fragment-ktx, which the SESL fork does not ship.
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.tab_enter, R.anim.tab_exit)
            .setReorderingAllowed(true)
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
        binding.toolbarLayout.setSubtitle(getString(subtitleRes))
    }

    /** Push a sub-screen on top of the current tab (system back returns to the tab). */
    fun pushFragment(fragment: Fragment, subtitleRes: Int) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.advanced_enter, R.anim.advanced_exit,
                R.anim.advanced_pop_enter, R.anim.advanced_pop_exit
            )
            .setReorderingAllowed(true)
            .replace(binding.fragmentContainer.id, fragment)
            .addToBackStack(null)
            .commit()
        binding.toolbarLayout.setSubtitle(getString(subtitleRes))
    }
}
