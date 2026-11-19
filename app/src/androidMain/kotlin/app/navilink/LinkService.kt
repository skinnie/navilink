package app.navilink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the nüvi link up while the screen is off: offers GarminLink all the time, wakes the nüvi with a ping
 * when it comes into Bluetooth range (what SPL does), retries, and shows the state in a notification.
 * Smartphone Link must not run at the same time (both offer GarminLink).
 */
@SuppressLint("MissingPermission")
class LinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { update() }
    private var pings = 0
    private var aclUp = false

    private val bt = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON) main.postDelayed({ ensureListening() }, 1500)
                    if (st == BluetoothAdapter.STATE_OFF) { aclUp = false; update() }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION") val d: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!isOurNuvi(d)) return
                    aclUp = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    State.log(if (aclUp) "${d.name} is in Bluetooth range." else "${d.name} left Bluetooth range.")
                    if (aclUp) { pings = 0; main.postDelayed(wake, 3000) }
                    update()
                }
            }
        }
    }

    /** Ping the nüvi so it opens GarminLink; again every 30 s, 5 times, while it is near and not connected. */
    private val wake = object : Runnable {
        override fun run() {
            if (!aclUp || Live.link.session != null || pings >= 5) return
            val d = nuvi() ?: return
            pings++
            Thread { runCatching { Live.link.ping(d) }.onFailure { State.log("Ping failed: ${it.message}") } }.start()
            main.postDelayed(this, 30_000)
        }
    }

    /** Re-offer GarminLink if the listening socket died, and refresh the notification. */
    private val watchdog = object : Runnable {
        override fun run() { ensureListening(); update(); main.postDelayed(this, 30_000) }
    }

    override fun onCreate() {
        super.onCreate()
        Live.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "nüvi link", NotificationManager.IMPORTANCE_LOW))
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bt, f, RECEIVER_EXPORTED) else registerReceiver(bt, f)
        Live.listeners += listener
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { Live.enabled = false; stopSelf(); return START_NOT_STICKY }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
        main.removeCallbacks(watchdog)
        main.post(watchdog)
        // The nüvi may already be connected at ACL level (paired, in range): try to wake it once.
        pings = 4; aclUp = true; main.postDelayed(wake, 2000)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(bt) }
        runCatching { Live.link.stopListening() }
        Live.listeners -= listener
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureListening() {
        if (Live.link.listening) return
        runCatching { Live.link.startListening() }.onFailure { State.log("Can't offer GarminLink: ${it.message}") }
    }

    private fun nuvi(): BluetoothDevice? = runCatching { Live.link.bonded() }.getOrDefault(emptyList()).let { ds ->
        ds.firstOrNull { it.address == State.device } ?: ds.firstOrNull { Live.link.looksLikeGarmin(it) }
    }

    private fun isOurNuvi(d: BluetoothDevice): Boolean = nuvi()?.address == d.address

    private fun statusText(): String {
        val s = Live.link.session
        val st = Live.router.stats
        return when {
            s != null -> "Connected to $s · ${st.summary()}"
            Live.link.listening -> "Waiting for the nüvi" + if (st.relayed + st.local > 0) " · ${st.summary()}" else ""
            else -> "Bluetooth is off or not allowed"
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LinkService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NaviLink · nüvi Live Services")
            .setContentText(statusText())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun update() {
        State.liveStatus = statusText()
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    companion object {
        private const val CHANNEL = "link"
        private const val ID = 7
        private const val STOP = "app.navilink.STOP"

        fun start(c: Context) {
            Live.enabled = true
            c.startForegroundService(Intent(c, LinkService::class.java))
        }
        fun stop(c: Context) {
            Live.enabled = false
            c.stopService(Intent(c, LinkService::class.java))
            State.liveStatus = "Off"
        }
    }
}
