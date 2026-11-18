package com.zarkawy.tvhome

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    data class AppEntry(
        val label: String,
        val component: ComponentName,
        val icon: android.graphics.drawable.Drawable,
    )

    private lateinit var jelly: JellyfinClient
    private lateinit var tray: LinearLayout
    private lateinit var trayContainer: LinearLayout
    private lateinit var dockLabel: TextView
    private lateinit var heroA: ImageView
    private lateinit var heroB: ImageView

    private lateinit var apps: List<AppEntry>
    private val tiles = mutableListOf<View>()
    private var focusedPos = 0
    private var dockScale = 1.3f   // magnification, capped so neighbors are never touched

    // Hero state
    private var heroes: List<JellyfinClient.HeroItem> = emptyList()
    private var heroIndex = -1
    private var heroTop: ImageView? = null   // the currently visible one
    private var fullscreen = false
    private var lastPoolFetch = 0L
    private var poolRetries = 0
    private val heroHandler = Handler(Looper.getMainLooper())
    private val rotateRunnable = object : Runnable {
        override fun run() {
            showNextHero()
            heroHandler.postDelayed(this, ROTATE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        jelly = JellyfinClient(this)

        heroA = findViewById(R.id.hero_a)
        heroB = findViewById(R.id.hero_b)
        tray = findViewById(R.id.tray)
        trayContainer = findViewById(R.id.tray_container)
        dockLabel = findViewById(R.id.dock_label)

        apps = loadApps()
        buildDock()
        clipAvatarsCircular()

        if (!jelly.isConfigured) maybeShowSetup()
        refreshPool()
    }

    private fun clipAvatarsCircular() {
        val circleOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        for (id in intArrayOf(
            R.id.avatar_hassan, R.id.avatar_israa, R.id.avatar_youssef, R.id.avatar_layla
        )) {
            findViewById<ImageView>(id).apply {
                clipToOutline = true
                outlineProvider = circleOutline
            }
        }
    }

    override fun onResume() {
        super.onResume()
        heroHandler.postDelayed(rotateRunnable, ROTATE_MS)
        // Zero-background policy: shortly after landing home, kill the processes
        // of every dock app so the stick always runs with a clean slate.
        heroHandler.postDelayed(killDockAppsRunnable, KILL_DELAY_MS)
    }

    override fun onPause() {
        super.onPause()
        heroHandler.removeCallbacks(rotateRunnable)
        // The user just opened an app — never kill it while they're using it.
        heroHandler.removeCallbacks(killDockAppsRunnable)
    }

    private val killDockAppsRunnable = Runnable {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (app in apps) {
            if (app.component.packageName == packageName) continue
            am.killBackgroundProcesses(app.component.packageName)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Restore D-pad focus to the last tile the user was on. The dock is a
            // plain LinearLayout, but the window can gain focus before the first
            // layout, so retry briefly.
            focusTile(0)
        }
    }

    private fun focusTile(attempt: Int) {
        val delay = 80L * (attempt + 1)
        trayContainer.postDelayed({
            if (tray.findFocus() == null && !fullscreen) {
                tiles.getOrNull(focusedPos)?.requestFocus()
            }
            if (tray.findFocus() == null && attempt < 3) focusTile(attempt + 1)
        }, delay)
    }

    // ---------------------------------------------------------------- dock

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val list = pm.queryIntentActivities(query, 0)
            .map { ri ->
                val ai = ri.activityInfo
                if (ai == null || ai.packageName == packageName) {
                    null
                } else {
                    val cn = ComponentName(ai.packageName, ai.name)
                    val icon = try {
                        pm.getActivityIcon(cn)
                    } catch (e: Exception) {
                        pm.defaultActivityIcon
                    }
                    AppEntry(ri.loadLabel(pm).toString(), cn, icon)
                }
            }
            .filterNotNull()
            .toMutableList()

        // The firmware's settings app doesn't advertise itself as a leanback launcher,
        // so add it manually.
        val settings = ComponentName(SETTINGS_PKG, SETTINGS_ACTIVITY)
        if (pm.resolveActivity(Intent().setComponent(settings), 0) != null) {
            val icon = try {
                pm.getActivityIcon(settings)
            } catch (e: Exception) {
                pm.defaultActivityIcon
            }
            list.add(AppEntry("Settings", settings, icon))
        }
        return list.sortedBy { it.label.lowercase() }
    }

    private fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** macOS Dock: every app gets an equal slot; icons shrink if the tray fills up. */
    private fun buildDock() {
        tray.removeAllViews()
        tiles.clear()

        val d = resources.displayMetrics.density
        val screenWdp = resources.displayMetrics.widthPixels / d
        val trayWdp = screenWdp * TRAY_WIDTH_FRACTION - 56f  // minus tray padding
        val slotW = trayWdp / apps.size
        val iconDp = (slotW - 6f).coerceIn(30f, 48f).toInt()
        // Magnification must never make an icon touch its neighbors. Unscaled
        // icons are inset ~6dp within their slot, so scaling up to the slot
        // width still leaves a visible gap to the neighbor icon edges.
        dockScale = (slotW / iconDp).coerceAtMost(1.35f)

        apps.forEachIndexed { pos, app ->
            val tile = layoutInflater.inflate(R.layout.item_dock_app, tray, false)
            tile.layoutParams = (tile.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f }

            val wrap = tile.findViewById<FrameLayout>(R.id.icon_wrap)
            wrap.layoutParams = FrameLayout.LayoutParams(
                (iconDp * d).toInt(), (iconDp * d).toInt(), android.view.Gravity.CENTER
            )
            val icon = tile.findViewById<ImageView>(R.id.icon)
            icon.clipToOutline = true
            icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, iconDp * 0.24f * d)
                }
            }
            icon.setImageDrawable(app.icon)

            tile.setOnClickListener {
                // Cancel the pending kill the moment the user commits to a launch:
                // a late kill would SIGKILL the app mid-cold-start (black screens,
                // corrupted WebView cache dirs).
                heroHandler.removeCallbacks(killDockAppsRunnable)
                focusedPos = pos
                launch(app)
            }
            tile.setOnLongClickListener {
                // Hidden affordance: long-press Settings to edit the Jellyfin config.
                if (app.component.packageName == SETTINGS_PKG) {
                    maybeShowSetup(force = true)
                    true
                } else false
            }
            tile.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusedPos = pos
                    showDockLabel(app.label, tile)
                }
                animateDockFocus(wrap, icon, hasFocus)
            }
            // tvOS/macOS: up from the dock shows the hero full-screen
            tile.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    setFullscreen(true)
                    true
                } else {
                    false
                }
            }

            tray.addView(tile)
            tiles.add(tile)
        }
    }

    private fun animateDockFocus(wrap: View, icon: ImageView, focused: Boolean) {
        val d = resources.displayMetrics.density
        wrap.animate()
            .scaleX(if (focused) dockScale else 1f)
            .scaleY(if (focused) dockScale else 1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.7f))
            .start()
        wrap.elevation = if (focused) 24f * d else 4f * d
        wrap.foreground = if (focused) getDrawable(R.drawable.tile_focus_ring) else null
        icon.alpha = if (focused) 1f else 0.9f
    }

    /** Tooltip above the focused tile, clamped to the tray edges. */
    private fun showDockLabel(text: String, tile: View) {
        dockLabel.text = text
        dockLabel.visibility = View.VISIBLE
        dockLabel.post {
            val tileCenter = tile.x + tile.width / 2f     // relative to the tray
            val containerCenter = trayContainer.width / 2f
            val half = dockLabel.width / 2f
            val maxShift = (containerCenter - half - 8f).coerceAtLeast(0f)
            dockLabel.translationX = (tileCenter - containerCenter).coerceIn(-maxShift, maxShift)
        }
    }

    // ---------------------------------------------------------------- hero

    private fun refreshPool() {
        val now = System.currentTimeMillis()
        if (now - lastPoolFetch < POOL_REFRESH_MS) return
        lastPoolFetch = now
        jelly.fetchPool { pool ->
            if (pool.isEmpty()) {
                // Network (or server) may not be up yet, e.g. right after boot.
                if (poolRetries < 5) {
                    poolRetries++
                    heroHandler.postDelayed({
                        lastPoolFetch = 0
                        refreshPool()
                    }, 15_000L)
                }
                return@fetchPool
            }
            poolRetries = 0
            heroes = pool
            heroIndex = -1
            showNextHero()
        }
    }

    private fun showNextHero() {
        if (heroes.isEmpty()) return
        val item = heroes[(heroIndex + 1).mod(heroes.size)]
        jelly.image(item.heroUrl) { bmp ->
            if (bmp != null) {
                heroIndex = heroes.indexOf(item) // may have refreshed meanwhile
                swapHero(bmp, item)
            }
        }
    }

    private fun swapHero(bmp: Bitmap, item: JellyfinClient.HeroItem) {
        val next = if (heroTop === heroA) heroB else heroA
        next.setImageBitmap(bmp)
        next.animate().alpha(1f).setDuration(700).start()
        (if (next === heroA) heroB else heroA).animate().alpha(0f).setDuration(700).start()
        heroTop = next

        // Warm the cache for the next slide.
        if (heroes.size > 1) {
            val nxt = heroes[(heroIndex + 1).mod(heroes.size)]
            jelly.image(nxt.heroUrl) { }
        }
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        if (on) {
            // Slide the dock off-screen instead of GONE: dropping it from the
            // hierarchy clears tile focus and the system's auto-focus then
            // re-assigns to the first tile, racing any restore we attempt.
            trayContainer.translationY = trayContainer.height + 40f * resources.displayMetrics.density
            dockLabel.visibility = View.GONE
        } else {
            trayContainer.translationY = 0f
            // Focus never dropped during fullscreen, so re-show the tooltip manually.
            apps.getOrNull(focusedPos)?.let { app ->
                tiles.getOrNull(focusedPos)?.let { showDockLabel(app.label, it) }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (fullscreen && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            setFullscreen(false)
            return true
        }
        // This is the home screen: never let BACK finish the activity (black screen).
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- setup

    private fun maybeShowSetup(force: Boolean = false) {
        val skipped = getSharedPreferences("tvhome", MODE_PRIVATE).getBoolean("setup_skipped", false)
        if (!force && (jelly.isConfigured || skipped)) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val d = resources.displayMetrics.density
            setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), 0)
        }
        val urlInput = EditText(this).apply {
            hint = "Server URL e.g. http://192.168.1.110:8096"
            setText("http://")
        }
        val keyInput = EditText(this).apply { hint = "API key (Dashboard -> API Keys)" }
        container.addView(urlInput)
        container.addView(keyInput)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Jellyfin hero art")
            .setMessage("Show your Continue Watching as the background. Configure the server, or skip for a plain background.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val url = urlInput.text.toString().trim().trimEnd('/')
                val key = keyInput.text.toString().trim()
                if (url.startsWith("http") && key.isNotEmpty()) {
                    jelly.saveConfig(url, key)
                    refreshPool()
                }
            }
            .setNeutralButton("Skip") { _, _ ->
                getSharedPreferences("tvhome", MODE_PRIVATE).edit().putBoolean("setup_skipped", true).apply()
            }
            .show()
    }

    companion object {
        private const val SETTINGS_PKG = "com.android.tv.settings"
        private const val SETTINGS_ACTIVITY = "com.android.tv.settings.MainSettings"
        private const val ROTATE_MS = 30_000L
        private const val POOL_REFRESH_MS = 2 * 60_000L
        private const val KILL_DELAY_MS = 10_000L
        private const val TRAY_WIDTH_FRACTION = 0.8f
    }
}
