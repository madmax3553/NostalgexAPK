package io.github.madmax3553.staticgroot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.madmax3553.staticgroot.channels.ChannelConfigLoader
import io.github.madmax3553.staticgroot.channels.ChannelMemberships
import io.github.madmax3553.staticgroot.channels.ChannelPools
import io.github.madmax3553.staticgroot.channels.FilterClock
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.jellyfin.AuthResult
import io.github.madmax3553.staticgroot.jellyfin.HttpUrlJellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/*
 * Launch screen. With a saved session it loads the library and opens the guide, with no sign-in.
 * Without one, or when the server rejects the saved token, it shows sign-in (password or Quick
 * Connect). Plain views, not the final Leanback UI.
 */
class MainActivity : Activity() {

    private val http = HttpUrlJellyfinHttp()
    private lateinit var store: SessionStore
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
        store = SessionStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(48), dp(48), dp(48))
            gravity = Gravity.TOP
        }
        setContentView(root)
        status = TextView(this).apply { textSize = 18f }

        val saved = store.load()
        if (saved != null) resume(saved) else showSignIn(null)
    }

    override fun onDestroy() {
        quickConnectCancelled = true
        super.onDestroy()
    }

    // MARK: - Resume with the saved session

    private fun resume(saved: SessionStore.Saved) {
        root.removeAllViews()
        root.addView(title("StaticGroot"))
        status = TextView(this).apply { textSize = 18f; text = "Loading your library..." }
        root.addView(status)
        Thread {
            try {
                val client = JellyfinClient(saved.serverUrl, http, deviceId, saved.accessToken, saved.userId)
                loadLibrary(client)
            } catch (e: JellyfinException) {
                if (e.status == 401 || e.status == 403) {
                    store.clearSession()
                    runOnUiThread { showSignIn("Your session has ended. Please sign in again.") }
                } else {
                    fail(e)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }.start()
    }

    // MARK: - Sign-in screen

    private fun showSignIn(message: String?) {
        root.removeAllViews()
        root.addView(title("Sign in to Jellyfin"))
        val server = field("Server address, e.g. http://jellyfin.example:8096").apply {
            setText(store.lastServerUrl())
        }
        val user = field("Username")
        val password = field("Password", secret = true)
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(12), 0, 0); text = message.orEmpty() }
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
            completeSignIn(url, auth)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Shows the code, polls until the user approves it, then signs in. */
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
                    completeSignIn(url, auth)
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

    /** Saves the session (encrypted), then loads the library and opens the guide. */
    private fun completeSignIn(url: String, auth: AuthResult) {
        val client = JellyfinClient(url, http, deviceId, auth.accessToken, auth.userId)
        store.save(client.serverUrl, auth.accessToken, auth.userId)
        loadLibrary(client)
    }

    // MARK: - Library

    /**
     * Loads every library, the movies and the TV episodes, and builds the pools for all channels,
     * then opens the guide. Logs what each library holds under the tag "StaticGroot", so an empty
     * result can be traced to the server's item types.
     */
    private fun loadLibrary(client: JellyfinClient) {
        runOnUiThread { status.text = "Loading libraries..." }

        val items = ArrayList<MediaItem>()
        val sections = client.loadSections()
        Log.i(TAG, "sections=${sections.size}")
        for (section in sections) {
            Log.i(TAG, "section '${section.title}' collectionType=${section.collectionType} type=${section.type}")
            if (section.type == "show") {
                val series = client.loadSeries(section.id)
                val episodes = loadEpisodesConcurrently(client, series)
                items.addAll(episodes)
                Log.i(TAG, "  series=${series.size} episodes loaded=${episodes.size}")
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
        }

        val config = ChannelConfigLoader.parse(assets.open("channels.json").bufferedReader().use { it.readText() })
        val memberships = ChannelMemberships.parse(
            assets.open("channels-memberships.json").bufferedReader().use { it.readText() },
        )
        val clock = FilterClock(System.currentTimeMillis() / 1000, ZoneId.systemDefault())
        val channels = ChannelPools.buildAll(config, items, memberships = memberships, clock = clock)
        Log.i(TAG, "items=${items.size} channels=${channels.size} withItems=${channels.count { it.pool.isNotEmpty() }}")

        AppSession.init(File(filesDir, "day_history"))
        AppSession.client = client
        AppSession.channels = channels
        runOnUiThread {
            startActivity(Intent(this, GuideActivity::class.java))
            finish()
        }
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
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
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
