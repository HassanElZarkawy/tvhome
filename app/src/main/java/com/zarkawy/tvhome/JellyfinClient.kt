package com.zarkawy.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal Jellyfin REST client for the home hero art.
 *
 * Auth uses an API key (Dashboard -> API Keys); the pool shown on the hero is
 * the user's Continue Watching, filled up with Recently Added.
 */
class JellyfinClient(context: Context) {

    data class HeroItem(
        val id: String,
        val title: String,
        val heroUrl: String,
    )

    private val prefs = context.getSharedPreferences("tvhome", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "jellyfin").apply { mkdirs() }
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val isConfigured: Boolean
        get() = prefs.getString(KEY_URL, null) != null && prefs.getString(KEY_APIKEY, null) != null

    fun saveConfig(url: String, apiKey: String, userId: String? = null) {
        prefs.edit()
            .putString(KEY_URL, url.trimEnd('/'))
            .putString(KEY_APIKEY, apiKey.trim())
            .apply()
        if (userId != null) prefs.edit().putString(KEY_USER, userId).apply()
    }

    fun clearConfig() = prefs.edit().clear().apply()

    /** Resolves (and stores) the first server user id. Returns null on failure. */
    fun discoverUser(): String? {
        val arr = getJson("/Users") as? JSONArray ?: return null
        if (arr.length() == 0) return null
        val id = arr.getJSONObject(0).getString("Id")
        prefs.edit().putString(KEY_USER, id).apply()
        return id
    }

    fun fetchPool(cb: (List<HeroItem>) -> Unit) {
        io.execute {
            val result = try {
                fetchPoolBlocking()
            } catch (e: Exception) {
                emptyList()
            }
            main.post { cb(result) }
        }
    }

    private fun fetchPoolBlocking(): List<HeroItem> {
        val user = prefs.getString(KEY_USER, null) ?: discoverUser() ?: return emptyList()
        val seen = HashSet<String>()
        val pool = ArrayList<HeroItem>()

        // Whole library, shuffled server-side — not limited to Continue Watching.
        val res = getJson(
            "/Users/$user/Items?Recursive=true&IncludeItemTypes=Movie,Episode,Series" +
                "&SortBy=Random&Limit=15&Fields=SeriesName,SeriesId" +
                "&EnableImageTypes=Backdrop,Logo,Primary,Thumb"
        )
        if (res is JSONObject) appendItems(res.optJSONArray("Items"), seen, pool)

        return pool
    }

    private fun appendItems(arr: JSONArray?, seen: MutableSet<String>, out: MutableList<HeroItem>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val id = it.optString("Id") ?: continue
            if (!seen.add(id)) continue
            val tags = it.optJSONObject("ImageTags") ?: JSONObject()
            // Skip items with no usable hero art entirely.
            if (!tags.has("Backdrop") && !tags.has("Thumb") && !tags.has("Primary")) continue
            val kind = when {
                tags.has("Thumb") -> "Thumb"
                tags.has("Backdrop") -> "Backdrop"
                else -> "Primary"
            }
            val title = it.optString("SeriesName", "").ifEmpty { it.optString("Name") }
            // Match the screen (1280px) 1:1: Jellyfin's scaler does the downscale
            // properly and we decode without sampling, so nothing gets pixelated.
            out.add(HeroItem(id, title, imgUrl("/Items/$id/Images/$kind", 1280, 90)))
        }
    }

    private fun imgUrl(path: String, maxW: Int, quality: Int): String {
        val base = prefs.getString(KEY_URL, null) ?: return ""
        return "$base$path?maxWidth=$maxW&quality=$quality"
    }

    /** Loads a bitmap through the disk cache; callback on the main thread. */
    fun image(url: String, cb: (Bitmap?) -> Unit) {
        io.execute {
            val bmp = try {
                val f = cacheFile(url)
                if (!f.exists()) downloadTo(url, f)
                if (f.exists()) decode(f) else null
            } catch (e: Exception) {
                null
            }
            main.post { cb(bmp) }
        }
    }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return File(cacheDir, digest.joinToString("") { "%02x".format(it) } + ".img")
    }

    private fun downloadTo(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 15000
        prefs.getString(KEY_APIKEY, null)?.let { conn.setRequestProperty("X-Emby-Token", it) }
        conn.inputStream.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        trimCache()
    }

    private fun decode(f: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        // Only downsample when the source is at least 2x the screen — halving a
        // 1440px image to 720px and stretching it to a 1280px screen is what
        // made the heroes look pixelated.
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1280) sample *= 2
        val opts2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts2)
    }

    /** Keeps the image cache under ~40 MB, oldest files first. */
    private fun trimCache() {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= 40L * 1024 * 1024) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= 40L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private fun getJson(path: String): Any? {
        val base = prefs.getString(KEY_URL, null) ?: return null
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        prefs.getString(KEY_APIKEY, null)?.let { conn.setRequestProperty("X-Emby-Token", it) }
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        return try { JSONObject(body) } catch (e: Exception) { try { JSONArray(body) } catch (e: Exception) { null } }
    }

    companion object {
        private const val KEY_URL = "jelly_url"
        private const val KEY_APIKEY = "jelly_key"
        private const val KEY_USER = "jelly_user"
    }
}
