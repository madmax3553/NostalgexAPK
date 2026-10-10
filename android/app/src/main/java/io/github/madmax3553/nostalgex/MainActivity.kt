package io.github.madmax3553.nostalgex

import android.app.Activity
import android.os.Bundle
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
import io.github.madmax3553.nostalgex.channels.ChannelConfigLoader
import io.github.madmax3553.nostalgex.channels.ChannelMemberships
import io.github.madmax3553.nostalgex.channels.ChannelPool
import io.github.madmax3553.nostalgex.channels.FilterClock
import io.github.madmax3553.nostalgex.channels.MediaItem
import io.github.madmax3553.nostalgex.channels.MusicChannels
import io.github.madmax3553.nostalgex.jellyfin.HttpUrlJellyfinHttp
import io.github.madmax3553.nostalgex.jellyfin.JellyfinClient
import io.github.madmax3553.nostalgex.jellyfin.JellyfinMapping
import java.time.ZoneId
import java.util.UUID

/*
 * Sign-in, then the music-video channel list. Plain views, so the whole screen works with a
 * D-pad. Not the final Leanback UI.
 */
class MainActivity : Activity() {

    private val http = HttpUrlJellyfinHttp()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("nostalgex", MODE_PRIVATE)
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
            text = "Sign in"
            setOnClickListener {
                status.text = "Signing in..."
                val url = server.text.toString()
                val name = user.text.toString()
                val pass = password.text.toString()
                Thread { signIn(url, name, pass) }.start()
            }
        })
        root.addView(status)
        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
            text = getString(R.string.about_credit)
        })
    }

    /** Runs on a background thread. Loads config and libraries, then builds the channel pools. */
    private fun signIn(url: String, name: String, pass: String) {
        try {
            val auth = JellyfinClient.authenticate(url, name, pass, http, deviceId)
            val client = JellyfinClient(url, http, deviceId, auth.accessToken, auth.userId)
            runOnUiThread { status.text = "Loading libraries..." }

            val items = ArrayList<MediaItem>()
            for (section in client.loadSections()) {
                if (section.type == "show") continue
                val isMusic = section.type == "musicvideo"
                for (raw in client.loadMovies(section.id)) {
                    JellyfinMapping.toMediaItem(raw, isMusicSection = isMusic)?.let { items.add(it) }
                }
            }

            val config = ChannelConfigLoader.parse(assets.open("channels.json").bufferedReader().use { it.readText() })
            val memberships = ChannelMemberships.parse(
                assets.open("channels-memberships.json").bufferedReader().use { it.readText() },
            )
            val clock = FilterClock(System.currentTimeMillis() / 1000, ZoneId.systemDefault())
            val pools = MusicChannels.build(config, items, memberships = memberships, clock = clock)

            AppSession.client = client
            AppSession.pools = pools
            runOnUiThread { showChannels(pools, items.size) }
        } catch (e: Exception) {
            runOnUiThread { status.text = e.message ?: "Sign-in failed" }
        }
    }

    private fun showChannels(pools: List<ChannelPool>, loaded: Int) {
        root.removeAllViews()
        root.addView(title("Music channels"))
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "$loaded items loaded. Playback is NOT scheduled yet: each channel plays its first item."
            setPadding(0, 0, 0, dp(12))
        })
        if (pools.isEmpty()) {
            root.addView(TextView(this).apply {
                textSize = 18f
                text = "No music-video channels have items on this server."
            })
            return
        }
        val labels = pools.map { "CH ${it.channel.number}  ${it.channel.name}  (${it.items.size} items)" }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            isFocusable = true
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (pools[position].items.isEmpty()) {
                    status.text = "That channel has no items yet."
                } else {
                    startActivity(
                        android.content.Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_POOL_INDEX, position),
                    )
                }
            }
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status)
        list.requestFocus()
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
}
