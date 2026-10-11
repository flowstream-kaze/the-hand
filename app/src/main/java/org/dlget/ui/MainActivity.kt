package org.dlget.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.iterator
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView
import org.dlget.databinding.ActivityMainBinding
import org.dlget.R
import org.dlget.vm.VidInfoViewModel
import org.dlget.utils.URLUtils.cleanUrl

class MainActivity : AppCompatActivity(), NavActivity {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    // Only present in the landscape layout variant (not in the ViewBinding class)
    private var drawerLayout: DrawerLayout? = null
    private var navView: NavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerLayout = binding.root.findViewById<View>(R.id.root) as? DrawerLayout
        navView = binding.root.findViewById(R.id.nav_view)
        setSupportActionBar(binding.toolbar)

        // Both system bars sit on colorSurface, so their icons must be dark in light
        // mode. Setting one bit through WindowInsetsControllerCompat takes over from the
        // theme's windowLightStatusBar, so both have to be set here.
        val lightBars = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        // FragmentContainerView adds the NavHostFragment via FragmentTransaction,
        // so findNavController(id) is not reliable this early; fetch it directly.
        navController =
            (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                .navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.home_fragment,
                R.id.downloads_fragment,
                R.id.youtube_dl_fragment
            ), drawerLayout
        )
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        supportActionBar?.title = navController.currentDestination?.label
        binding.bottomView?.setupWithNavController(navController)
        navView?.setupWithNavController(navController)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_toolbar, menu)
        // Must reach FragmentActivity: this is what lets fragments (e.g. the
        // Downloads screen's "Clear download history") add their own items.
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val navigated = NavigationUI.onNavDestinationSelected(item, navController)
        return navigated || super.onOptionsItemSelected(item)
    }

    override fun hideNav() {
        drawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.bottomView?.visibility = View.GONE
    }

    override fun showNav() {
        drawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        binding.bottomView?.visibility = View.VISIBLE
    }

    override fun showOptions() {
        binding.toolbar.menu.iterator().forEach { it.isVisible = true }
    }

    override fun hideOptions() {
        binding.toolbar.menu.iterator().forEach { it.isVisible = false }
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val cleanUrl = cleanUrl(text)
            if (!URLUtil.isValidUrl(cleanUrl)) {
                Toast.makeText(applicationContext, R.string.invalid_url, Toast.LENGTH_SHORT)
                    .show()
                return
            }
            navigateHome()
            val vidFormatsVm =
                ViewModelProvider(this).get(VidInfoViewModel::class.java)
            vidFormatsVm.fetchInfo(cleanUrl)
        }
    }

    private fun navigateHome() {
        val navOptions = NavOptions.Builder().setLaunchSingleTop(true).build()
        navController.navigate(R.id.home_fragment, null, navOptions)
    }

}

interface NavActivity {
    fun hideNav()
    fun showNav()
    fun showOptions()
    fun hideOptions()
}