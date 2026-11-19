package app.navilink

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.navilink.core.RealNet
import app.navilink.live.Capture
import app.navilink.live.Identity
import app.navilink.live.LiveRouter
import app.navilink.live.SocketUpstream
import app.navilink.live.WeatherSource
import app.navilink.nuvi.newPlaces
import java.io.File

/**
 * The one Bluetooth link of the app, shared by the screen and [LinkService], with Live Services:
 * relay to Garmin, Open-Meteo weather, and the capture files.
 */
@SuppressLint("StaticFieldLeak") // application context only
object Live {
    private lateinit var app: Context
    lateinit var link: BtLink; private set
    lateinit var router: LiveRouter; private set
    lateinit var capture: Capture; private set
    /** Told on every change worth redrawing (session, counters), on the main thread. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val prefs get() = app.getSharedPreferences("navilink", Context.MODE_PRIVATE)

    @Synchronized fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        capture = Capture(File(app.getExternalFilesDir(null) ?: app.filesDir, "captures"))
        val cm = app.getSystemService(ConnectivityManager::class.java)
        router = LiveRouter(SocketUpstream(), RealNet(), State::log,
            online = { cm?.activeNetwork != null },
            record = { m, r, note -> capture.exchange("live", m, r.raw, note); changed() })
        router.weather = runCatching { WeatherSource.valueOf(prefs.getString("weather", "")!!) }.getOrDefault(WeatherSource.GARMIN_THEN_OPEN_METEO)
        link = BtLink(State::log, { route, ps ->
            Handler(Looper.getMainLooper()).post {
                val fresh = newPlaces(State.places, ps)
                State.places.addAll(fresh)
                State.log(when {
                    fresh.isEmpty() -> "The nüvi sent ${ps.size} place(s) already in the list ($route)."
                    fresh.size < ps.size -> "Added ${fresh.size} place(s) from the nüvi ($route); ${ps.size - fresh.size} already in the list."
                    else -> "Added ${ps.size} place(s) from the nüvi ($route)."
                })
            }
        }, router = router, creds = { identity().authInfoText(System.currentTimeMillis() / 1000, Build.VERSION.RELEASE) },
            capture = capture, onSession = { changed() })
    }

    fun changed() { Handler(Looper.getMainLooper()).post { listeners.forEach { it() } } }

    /** The phone identity for the nüvi, made once per install (like SPL's Installation id). */
    fun identity(): Identity {
        val p = prefs
        val id = p.getString("liveDeviceId", null)
        val seed = p.getString("liveTokenSeed", null)
        if (id != null && seed != null) return Identity(id, seed)
        return Identity.fresh().also { p.edit().putString("liveDeviceId", it.deviceId).putString("liveTokenSeed", it.tokenSeed).apply() }
    }

    var weather: WeatherSource
        get() = router.weather
        set(v) { router.weather = v; prefs.edit().putString("weather", v.name).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean("live", false)
        set(v) { prefs.edit().putBoolean("live", v).apply() }
}
