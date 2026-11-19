package app.navilink.live

/*
 * Garmin Live Services ("OBN", gold.garmin.com/OBN/OBNServlet) message layout, from the protobuf classes in
 * Smartphone Link 2.11.12 (com/garmin/proto/generated, rebuilt by garmin3790/obn/protoschema.py):
 *   TransactionRequest  1 txn key, 2 client type, 3 app version, 7 ServiceRequest (repeated)
 *   TransactionResponse 1 status, 2 server time (ms), 3 ServiceResponse (repeated)
 *   ServiceRequest      1 sequence number, 4 position, then one service field (30 weather, 36 safety cameras ...)
 *   ServiceResponse     1 sequence number, 2 status, 3 error text, then the same service field number
 * The nüvi may also use Garmin's older text protocol (header "protocol: cld", tab-separated key=value lines);
 * that is relayed untouched.
 */
object Obn {
    val SERVICES = mapOf(
        30 to "weather", 35 to "parking", 36 to "safety cameras", 47 to "auth", 48 to "traffic cameras",
        51 to "image", 62 to "local search", 69 to "Garmin messages", 72 to "tracker", 74 to "forwarding",
        76 to "nav core", 80 to "app auth", 81 to "app store", 82 to "fitness",
    )
    val WEATHER_PARTS = mapOf(
        11 to "conditions", 12 to "alerts", 13 to "radar", 14 to "temperature", 15 to "zone", 16 to "hourly",
        17 to "interesting alerts", 18 to "interesting radar", 19 to "radar tile", 20 to "road conditions", 21 to "partial day",
    )
    val SERVICE_STATUS = listOf(
        "OK", "provider unavailable", "missing parameters", "out of range", "unknown service", "not authorized here",
        "data unavailable", "unhandled exception", "invalid input", "internal error", "bad provider", "time out",
    )
    val TXN_STATUS = listOf("OK", "authentication failure", "message format failure", "too many requests")

    const val WEATHER = 30

    class ServiceRequest(val seq: Int, val service: Int, val body: List<PbField>, val all: List<PbField>) {
        val name get() = SERVICES[service] ?: "service #$service"
        val parts: List<Int> get() = if (service == WEATHER) body.map { it.num } else emptyList()
        val describe get() = name + if (service == WEATHER) parts.joinToString(",", " (", ")") { WEATHER_PARTS[it] ?: "#$it" } else ""
    }

    /** One ServiceRequest: the first field after the header fields 1-4 is the service. */
    fun serviceRequest(f: List<PbField>): ServiceRequest {
        val svc = f.firstOrNull { it.num > 4 && it.wire == 2 }
        return ServiceRequest(f.first(1)?.int ?: 0, svc?.num ?: 0, svc?.msg() ?: emptyList(), f)
    }

    fun requests(txn: ByteArray): List<ServiceRequest> = Pb.parse(txn).all(7).map { serviceRequest(it.msg()) }

    /** A short line for the log, such as "weather (conditions) #3". */
    fun describeRequest(txn: ByteArray): String = runCatching {
        requests(txn).joinToString(", ") { "${it.describe} #${it.seq}" }.ifEmpty { "empty transaction" }
    }.getOrElse { "not protobuf" }

    fun describeResponse(txn: ByteArray): String = runCatching {
        val t = Pb.parse(txn)
        val st = t.first(1)?.int ?: 0
        val parts = t.all(3).map { sr ->
            val f = sr.msg()
            val svc = f.firstOrNull { it.num > 4 && it.wire == 2 }?.num
            val s = f.first(2)?.int ?: 0
            "${SERVICES[svc] ?: if (svc == null) "no data" else "service #$svc"} #${f.first(1)?.int ?: 0}: ${SERVICE_STATUS.getOrNull(s) ?: "status $s"}" +
                (f.first(3)?.text?.let { " ($it)" } ?: "")
        }
        (if (st != 0) "transaction ${TXN_STATUS.getOrNull(st) ?: "status $st"}; " else "") + parts.joinToString(", ").ifEmpty { "no services" }
    }.getOrElse { "not protobuf" }

    /** TransactionResponse with these ServiceResponses (raw bytes each), status OK, server time now. */
    fun transactionResponse(services: List<ByteArray>, nowMs: Long): ByteArray {
        val w = PbWriter().varint(1, 0).varint(2, nowMs)
        services.forEach { w.bytes(3, it) }
        return w.toByteArray()
    }

    fun serviceResponse(seq: Int, service: Int, body: ByteArray, status: Int = 0): ByteArray =
        PbWriter().varint(1, seq.toLong()).varint(2, status.toLong()).bytes(service, body).toByteArray()

    /** Status of each ServiceResponse in a TransactionResponse, by sequence number. */
    fun responseStatus(txn: ByteArray): Map<Int, Int> = runCatching {
        Pb.parse(txn).all(3).associate { sr -> val f = sr.msg(); (f.first(1)?.int ?: 0) to (f.first(2)?.int ?: 0) }
    }.getOrDefault(emptyMap())

    /** Replace the ServiceResponses with these sequence numbers, keeping every other field byte for byte. */
    fun spliceResponse(txn: ByteArray, replace: Map<Int, ByteArray>): ByteArray {
        val w = PbWriter()
        val done = HashSet<Int>()
        for (f in Pb.parse(txn)) {
            if (f.num == 3) {
                val seq = f.msg().first(1)?.int ?: 0
                val r = replace[seq]
                if (r != null) { w.bytes(3, r); done += seq; continue }
            }
            w.field(f)
        }
        replace.filterKeys { it !in done }.values.forEach { w.bytes(3, it) }
        return w.toByteArray()
    }
}
