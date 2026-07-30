package dev.ncatt.ota.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.ncatt.ota.R
import dev.ncatt.ota.databinding.ActivityMainBinding

/**
 * OneUI 8 shell:
 *   - ToolbarLayout gives the collapsing large title + subtitle (handled by the library).
 *   - BottomTabLayout is the OneUI bottom navigation bar, populated from menu_bottom_tabs.xml.
 * Fragments are swapped directly (no ViewPager2) which is the OneUI-native pattern.
 */
class MainActivity : AppCompatActivity() {

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

        if (savedInstanceState == null) show(updateFragment, R.string.tab_check_update)
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
        // Plain FragmentTransaction API: the KTX commit{} extension lives in
        // androidx.fragment:fragment-ktx, which the SESL fork does not ship.
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
        binding.toolbarLayout.setSubtitle(getString(subtitleRes))
    }
}
