package io.github.madmax3553.staticgroot

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.madmax3553.staticgroot.channels.ChannelConfigLoader
import io.github.madmax3553.staticgroot.channels.ChannelMemberships
import io.github.madmax3553.staticgroot.channels.ChannelPools
import io.github.madmax3553.staticgroot.channels.FilterClock
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.jellyfin.AuthResult
import io.github.madmax3553.staticgroot.jellyfin.HttpUrlJellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

/*
 * Sign-in (password or Quick Connect), then the music-video channel list. Plain views, so
 * the whole screen works with a D-pad. Not the final Leanback UI.
 */
class MainActivity : Activity() {

    private val http = HttpUrlJellyfinHttp()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    @Volatile private var quickConnectCancelled = false

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("staticgroot", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(48), dp(48), dp(48))
            gravity = Gravity.TOP
        }
        setContentView(root)
        showSignIn()
    }

    override fun onDestroy() {
        quickConnectCancelled = true
        super.onDestroy()
    }

    private fun showSignIn() {
        root.removeAllViews()
        root.addView(title("Sign in to Jellyfin"))
        val server = field("Server address, e.g. http://jellyfin.example:8096")
        val user = field("Username")
        val password = field("Password", secret = true)
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(12), 0, 0) }
        root.addView(server)
        root.addView(user)
        root.addView(password)
        root.addView(Button(this).apply {
            text = "Sign in with password"
            setOnClickListener {
                status.text = "Signing in..."
                val url = server.text.toString()
                val name = user.text.toString()
                val pass = password.text.toString()
                Thread { passwordSignIn(url, name, pass) }.start()
            }
        })
        root.addView(Button(this).apply {
            text = "Sign in with Quick Connect"
            setOnClickListener {
                status.text = "Starting Quick Connect..."
                val url = server.text.toString()
                Thread { quickConnect(url) }.start()
            }
        })
        root.addView(status)
        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
            text = getString(R.string.about_credit)
        })
    }

    private fun passwordSignIn(url: String, name: String, pass: String) {
        try {
            val auth = JellyfinClient.authenticate(url, name, pass, http, deviceId)
            loadLibrary(url, auth)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Shows the code, polls until the user approves it, then loads the library. */
    private fun quickConnect(url: String) {
        try {
            quickConnectCancelled = false
            val start = JellyfinClient.quickConnectInitiate(url, http, deviceId)
            runOnUiThread {
                status.text = "Enter code ${start.code} in Jellyfin: Dashboard, then Quick Connect. Waiting..."
            }
            val deadline = System.currentTimeMillis() + QUICK_CONNECT_TIMEOUT_MS
            while (!quickConnectCancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(QUICK_CONNECT_POLL_MS)
                if (JellyfinClient.quickConnectCheck(url, http, deviceId, start.secret)) {
                    val auth = JellyfinClient.quickConnectAuthenticate(url, http, deviceId, start.secret)
                    loadLibrary(url, auth)
                    return
                }
            }
            if (!quickConnectCancelled) {
                runOnUiThread { status.text = "Quick Connect timed out. Try again." }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * Loads every library, the movies and the TV episodes, and builds the pools for all channels.
     * Logs what each library holds under the tag "StaticGroot", so an empty result can be
     * traced to the server's item types.
     */
    private fun loadLibrary(url: String, auth: AuthResult) {
        val client = JellyfinClient(url, http, deviceId, auth.accessToken, auth.userId)
        runOnUiThread { status.text = "Loading libraries..." }

        val items = ArrayList<MediaItem>()
        val summary = ArrayList<String>()
        val sections = client.loadSections()
        Log.i(TAG, "sections=${sections.size}")
        for (section in sections) {
            Log.i(TAG, "section '${section.title}' collectionType=${section.collectionType} type=${section.type}")
            if (section.type == "show") {
                val series = client.loadSeries(section.id)
                val episodes = loadEpisodesConcurrently(client, series)
                items.addAll(episodes)
                Log.i(TAG, "  series=${series.size} episodes loaded=${episodes.size}")
                summary.add("${section.title}: ${series.size} series, ${episodes.size} episodes")
                continue
            }
            val isMusic = section.type == "musicvideo"
            val movieCount = client.countItems(section.id, "Movie")
            val musicVideoCount = client.countItems(section.id, "MusicVideo")
            Log.i(TAG, "  count Movie=$movieCount MusicVideo=$musicVideoCount")

            val raw = client.loadMovies(section.id, includeItemTypes = "Movie")
            val mapped = raw.mapNotNull { JellyfinMapping.toMediaItem(it, isMusicSection = isMusic) }
            Log.i(TAG, "  loaded raw=${raw.size} mapped=${mapped.size} (no runtime dropped: ${raw.size - mapped.size})")
            items.addAll(mapped)
            summary.add("${section.title}: $movieCount movies, $musicVideoCount music videos, ${mapped.size} loaded")
        }

        val config = ChannelConfigLoader.parse(assets.open("channels.json").bufferedReader().use { it.readText() })
        val memberships = ChannelMemberships.parse(
            assets.open("channels-memberships.json").bufferedReader().use { it.readText() },
        )
        val clock = FilterClock(System.currentTimeMillis() / 1000, ZoneId.systemDefault())
        val channels = ChannelPools.buildAll(config, items, memberships = memberships, clock = clock)
        val withItems = channels.count { it.pool.isNotEmpty() }
        Log.i(TAG, "items=${items.size} channels=${channels.size} withItems=$withItems")

        AppSession.init(File(filesDir, "day_history"))
        AppSession.client = client
        AppSession.channels = channels
        runOnUiThread { showSummary(items.size, withItems, channels.size, summary) }
    }

    /** Episodes for many series at once. Six requests in flight, as a simple cap. */
    private fun loadEpisodesConcurrently(client: JellyfinClient, series: List<JellyfinItem>): List<MediaItem> {
        val pool = Executors.newFixedThreadPool(EPISODE_FETCH_THREADS)
        try {
            val futures = series.map { show ->
                pool.submit<List<MediaItem>> {
                    client.loadEpisodes(show.Id).mapNotNull { JellyfinMapping.toEpisode(it, show) }
                }
            }
            return futures.flatMap { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun showSummary(loaded: Int, withItems: Int, total: Int, summary: List<String>) {
        root.removeAllViews()
        root.addView(title("Libraries loaded"))
        root.addView(TextView(this).apply {
            textSize = 18f
            text = "$loaded items. $withItems of $total channels have programming."
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            textSize = 14f
            text = summary.joinToString("\n").ifEmpty { "The server has no libraries for this user." }
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "Open guide"
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, GuideActivity::class.java))
            }
        })
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "sign-in or load failed", e)
        runOnUiThread { status.text = e.message ?: "Sign-in failed" }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setPadding(0, 0, 0, dp(16))
    }

    private fun field(hint: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 18f
        isSingleLine = true
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "StaticGroot"
        private const val QUICK_CONNECT_POLL_MS = 2_000L
        private const val QUICK_CONNECT_TIMEOUT_MS = 5 * 60_000L
        private const val EPISODE_FETCH_THREADS = 6
    }
}
