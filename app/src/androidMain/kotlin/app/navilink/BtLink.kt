package app.navilink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import app.navilink.live.Capture
import app.navilink.live.LiveRouter
import app.navilink.nuvi.Message
import app.navilink.nuvi.NUVI_UUID
import app.navilink.nuvi.PHONE_SERVICE_NAME
import app.navilink.nuvi.PHONE_UUID
import app.navilink.nuvi.Place
import app.navilink.nuvi.handleDeviceRequest
import app.navilink.nuvi.newRouteRequest
import app.navilink.nuvi.readMessage
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Both Bluetooth links Smartphone Link uses (all calls block; run them off the main thread):
 *  - [connect]/[send]: we are the client, on the nüvi's UUID, and POST /new-route.
 *  - [startListening]: we offer "GarminLink" so the nüvi can connect to us and send its requests.
 * Run only one of this app and Smartphone Link at a time: both offer the same GarminLink service.
 */
@SuppressLint("MissingPermission")
class BtLink(
    private val log: (String) -> Unit,
    private val onPlaces: (String, List<Place>) -> Unit,
    /** Answers requests the nüvi means for the internet (Live Services); null = refuse them as before. */
    private val router: LiveRouter? = null,
    /** The /auth-info answer with the phone identity (see live/Identity.kt). */
    private val creds: (() -> String)? = null,
    private val capture: Capture? = null,
    /** Called when a link opens or closes: the nüvi's GarminLink session, or our link 1 to it. */
    private val onSession: (Boolean) -> Unit = {},
) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var client: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    private val replies = LinkedBlockingQueue<Message>()

    /** The nüvi currently connected to GarminLink, if any. */
    @Volatile var session: String? = null
        private set

    val connectedTo: String? get() = client?.takeIf { it.isConnected }?.remoteDevice?.let { it.name ?: it.address }
    val listening get() = server != null

    fun bonded(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedByDescending { looksLikeGarmin(it) }.orEmpty()

    fun looksLikeGarmin(d: BluetoothDevice) =
        (d.name ?: "").contains(Regex("nüvi|nuvi|garmin|drive|zumo|zūmo", RegexOption.IGNORE_CASE))

    /** Open the phone -> nüvi link. Tries the normal (secure) socket first, then an insecure one. */
    fun connect(d: BluetoothDevice) {
        disconnect()
        // Needs BLUETOOTH_SCAN on Android 12+, which we do not ask for; we never scan, so nothing is lost.
        runCatching { adapter?.cancelDiscovery() }
        log("Connecting to ${d.name} (${d.address}) on $NUVI_UUID …")
        val s = try {
            d.createRfcommSocketToServiceRecord(NUVI_UUID).also { it.connect() }
        } catch (e: IOException) {
            log("Secure connect failed: ${e.message}; trying insecure")
            d.createInsecureRfcommSocketToServiceRecord(NUVI_UUID).also { it.connect() }
        }
        client = s
        log("Connected to ${d.name}.")
        onSession(true)
        thread(name = "nuvi-reader", isDaemon = true) {
            val input = s.inputStream.buffered()
            try {
                while (true) {
                    val m = readMessage(input) ?: break
                    log("nüvi → phone (link 1):\n$m")
                    if (m.isResponse) replies.offer(m)
                    else {
                        // Not seen in SPL, but answer it the same way as on the GarminLink link.
                        answer(m, s.outputStream, "link 1")
                    }
                }
                log("nüvi closed link 1.")
            } catch (e: IOException) {
                log("Link 1 closed: ${e.message}")
            } finally {
                if (client === s) client = null
                onSession(false)
            }
        }
    }

    /** POST /new-route with these stops on the open link and wait up to 15 s for the nüvi's answer. */
    fun send(stops: List<Place>): Int? {
        val s = client ?: error("Not connected to the nüvi")
        val bytes = newRouteRequest(stops)
        replies.clear()
        log("phone → nüvi:\n" + String(bytes, Charsets.UTF_8).trimEnd())
        s.outputStream.write(bytes)
        s.outputStream.flush()
        val r = replies.poll(15, TimeUnit.SECONDS)
        if (r == null) log("No answer from the nüvi within 15 s (check its screen).")
        return r?.status
    }

    fun disconnect() {
        client?.let { runCatching { it.close() } }
        client = null
        onSession(false)
    }

    /** What SPL does to wake a nüvi: connect, write "ping", close. The nüvi should then connect to GarminLink. */
    fun ping(d: BluetoothDevice) {
        log("Ping ${d.name} …")
        d.createRfcommSocketToServiceRecord(NUVI_UUID).use { s ->
            s.connect()
            s.outputStream.write("ping".toByteArray()); s.outputStream.flush()
        }
        log("Ping sent.")
    }

    /** Offer "GarminLink" and answer the nüvi's requests the way SPL does. */
    fun startListening() {
        if (server != null) return
        val ss = adapter?.listenUsingRfcommWithServiceRecord(PHONE_SERVICE_NAME, PHONE_UUID) ?: error("No Bluetooth")
        server = ss
        log("Listening as \"$PHONE_SERVICE_NAME\". Now open Smartphone Link on the nüvi.")
        thread(name = "garminlink-accept", isDaemon = true) {
            try {
                while (true) {
                    val s = ss.accept()
                    log("nüvi ${s.remoteDevice.name} connected to GarminLink.")
                    thread(name = "garminlink-conn", isDaemon = true) { serve(s) }
                }
            } catch (e: IOException) {
                log("Stopped listening (${e.message}).")
            } finally {
                if (server === ss) server = null
            }
        }
    }

    private fun serve(s: BluetoothSocket) {
        val who = s.remoteDevice.name ?: s.remoteDevice.address
        session = who
        onSession(true)
        capture?.note("GarminLink session from $who")
        try {
            s.use {
                val input = s.inputStream.buffered()
                while (true) {
                    val m = readMessage(input) ?: break
                    if (m.isResponse) { log("nüvi → phone (GarminLink), unexpected answer:\n$m"); continue }
                    answer(m, s.outputStream, "GarminLink")
                }
            }
            log("nüvi closed GarminLink.")
        } catch (e: IOException) {
            log("GarminLink connection ended: ${e.message}")
        } finally {
            if (session == who) session = null
            capture?.note("GarminLink session from $who ended")
            onSession(false)
        }
    }

    /** One request from the nüvi: Live Services go to [router], everything else is answered like SPL. */
    private fun answer(m: Message, out: java.io.OutputStream, link: String) {
        if (!m.forPhone && router != null) {
            val reply = router.handle(m)
            synchronized(out) { out.write(reply); out.flush() }
            return
        }
        log("nüvi → phone ($link):\n$m")
        val h = handleDeviceRequest(m, creds)
        synchronized(out) { out.write(h.reply); out.flush() }
        capture?.exchange(link, m, h.reply, h.event)
        log("→ ${String(h.reply, Charsets.ISO_8859_1).lineSequence().first()} (${h.event})")
        if (h.places.isNotEmpty()) onPlaces(m.route, h.places)
    }

    fun stopListening() {
        server?.let { runCatching { it.close() } }
        server = null
    }
}
