package app.navilink.live

import app.navilink.core.Json
import app.navilink.core.Net
import app.navilink.nuvi.fromSemi
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Weather answered on the phone from Open-Meteo (free, no key), in Garmin's WeatherResponse layout
 * (WeatherProto in SPL 2.11.12):
 *   WeatherResponse 1 status, 11 ConditionsResponse, 16 HourlyForecastResponse
 *   ConditionsRequest 1 position(ScPoint: 1 lat, 2 lon, sint32 semicircles), 2 language, 3 days, 6 speed unit, 7 temp unit
 *   ConditionsResponse 1 CurrentWeather, 2 ForecastWeather*, 3 location name, 4 DataProvider*
 *   CurrentWeather 1 text, 2 temp(sint32), 3 wind dir text, 4 wind speed, 5 feels like, 8 icon, 10 humidity,
 *                  11 day of week, 13 provider, 16 wind bearing
 *   ForecastWeather 1 day of week, 2 high(sint32), 3 low(sint32), 4 summary, 5 icon, 6 rain chance
 *   HourlyForecastResponse 1 Forecast*, 2 DataProvider*; Forecast 1 time, 3 temp(sint32), 4 rain chance, 5 icon, 7 wind
 * Untested on the nüvi: which fields it shows, and whether the hour field is seconds since 1970.
 */
object Weather {
    // WeatherIcon enum numbers
    const val CLEAR = 0; const val PARTLY_CLOUDY = 1; const val MOSTLY_CLOUDY = 2; const val RAIN = 3; const val SNOW = 4
    const val THUNDERSTORMS = 6; const val WINTRY_MIX = 7; const val FOG = 8; const val HAIL = 12
    const val SCATTERED_SHOWERS = 13; const val LIGHT_RAIN = 16; const val HEAVY_RAIN = 17; const val LIGHT_SNOW = 18
    const val HEAVY_SNOW = 19; const val CLOUDY = 22

    /** WMO weather code (Open-Meteo) to Garmin icon. */
    fun icon(wmo: Int): Int = when (wmo) {
        0 -> CLEAR; 1, 2 -> PARTLY_CLOUDY; 3 -> CLOUDY; 45, 48 -> FOG
        51, 53, 55, 61 -> LIGHT_RAIN; 63 -> RAIN; 65, 82 -> HEAVY_RAIN; 56, 57, 66, 67 -> WINTRY_MIX
        71, 85 -> LIGHT_SNOW; 73, 77 -> SNOW; 75, 86 -> HEAVY_SNOW; 80, 81 -> SCATTERED_SHOWERS
        95 -> THUNDERSTORMS; 96, 99 -> HAIL; else -> MOSTLY_CLOUDY
    }

    private val PT = mapOf(0 to "Céu limpo", 1 to "Pouco nublado", 2 to "Parcialmente nublado", 3 to "Nublado", 45 to "Nevoeiro",
        48 to "Nevoeiro", 51 to "Chuvisco", 53 to "Chuvisco", 55 to "Chuvisco forte", 61 to "Chuva fraca", 63 to "Chuva",
        65 to "Chuva forte", 56 to "Chuvisco gelado", 57 to "Chuvisco gelado", 66 to "Chuva gelada", 67 to "Chuva gelada",
        71 to "Neve fraca", 73 to "Neve", 75 to "Neve forte", 77 to "Neve", 80 to "Aguaceiros", 81 to "Aguaceiros",
        82 to "Aguaceiros fortes", 85 to "Aguaceiros de neve", 86 to "Aguaceiros de neve", 95 to "Trovoada",
        96 to "Trovoada com granizo", 99 to "Trovoada com granizo")
    private val EN = mapOf(0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Cloudy", 45 to "Fog", 48 to "Fog",
        51 to "Drizzle", 53 to "Drizzle", 55 to "Heavy drizzle", 61 to "Light rain", 63 to "Rain", 65 to "Heavy rain",
        56 to "Freezing drizzle", 57 to "Freezing drizzle", 66 to "Freezing rain", 67 to "Freezing rain", 71 to "Light snow",
        73 to "Snow", 75 to "Heavy snow", 77 to "Snow", 80 to "Showers", 81 to "Showers", 82 to "Heavy showers",
        85 to "Snow showers", 86 to "Snow showers", 95 to "Thunderstorm", 96 to "Thunderstorm, hail", 99 to "Thunderstorm, hail")

    fun text(wmo: Int, lang: String) = (if (lang.startsWith("pt")) PT else EN)[wmo] ?: if (lang.startsWith("pt")) "Nublado" else "Cloudy"

    fun compass(deg: Int, lang: String): String {
        val en = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val pt = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return (if (lang.startsWith("pt")) pt else en)[(((deg % 360) + 360 + 22) % 360) / 45]
    }

    /** Garmin DayOfWeek: Sunday = 1 ... Saturday = 7. */
    fun garminDay(d: java.time.DayOfWeek) = d.value % 7 + 1

    class Ask(val lat: Double, val lon: Double, val lang: String, val days: Int, val hours: Int,
              val fahrenheit: Boolean, val speedUnit: Int)

    /** What a WeatherRequest asks for, or null when it asks for something we can't answer (radar, alerts ...). */
    fun parseRequest(weather: List<PbField>): Ask? {
        val parts = weather.map { it.num }.toSet()
        if (parts.isEmpty() || !(parts - setOf(11, 16)).isEmpty()) return null
        val cond = weather.first(11)?.msg()
        val hourly = weather.first(16)?.msg()
        val q = cond ?: hourly ?: return null
        val pos = q.first(1)?.msg() ?: return null
        val lat = fromSemi(pos.first(1)?.sint?.toLong() ?: return null)
        val lon = fromSemi(pos.first(2)?.sint?.toLong() ?: return null)
        val lang = q.first(2)?.msg()?.first(1)?.text?.lowercase(Locale.ROOT) ?: "en"
        return Ask(lat, lon, lang,
            days = if (cond != null) (cond.first(3)?.int ?: 3).coerceIn(1, 10) else 0,
            hours = if (hourly != null) (hourly.first(3)?.int ?: 12).coerceIn(1, 48) else 0,
            fahrenheit = cond?.first(7)?.int == 2,
            speedUnit = cond?.first(6)?.int ?: 2)
    }

    fun url(a: Ask): String {
        val wind = when (a.speedUnit) { 1 -> "mph"; 3 -> "ms"; 4 -> "kn"; else -> "kmh" }
        return "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.ROOT, a.lat)}" +
            "&longitude=${"%.4f".format(Locale.ROOT, a.lon)}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m,wind_direction_10m" +
            "&forecast_days=${maxOf(a.days, 3).coerceAtMost(10)}&timezone=auto&timeformat=unixtime" +
            "&wind_speed_unit=$wind&temperature_unit=${if (a.fahrenheit) "fahrenheit" else "celsius"}"
    }

    private fun provider() = PbWriter().string(1, "Open-Meteo")

    /** Build the WeatherResponse body from an Open-Meteo answer. */
    @Suppress("UNCHECKED_CAST")
    fun buildResponse(a: Ask, json: String, now: Instant = Instant.now()): ByteArray {
        val j = Json.parse(json) as Map<String, Any?>
        val zone = runCatching { ZoneId.of(j["timezone"] as String) }.getOrDefault(ZoneId.systemDefault())
        fun num(v: Any?) = (v as? Number)?.toDouble()
        val w = PbWriter().varint(1, 0)
        if (a.days > 0) {
            val c = j["current"] as Map<String, Any?>
            val code = num(c["weather_code"])?.toInt() ?: 3
            val bearing = num(c["wind_direction_10m"])?.roundToInt() ?: 0
            val cur = PbWriter()
                .string(1, text(code, a.lang))
                .sint32(2, num(c["temperature_2m"])?.roundToInt() ?: 0)
                .string(3, compass(bearing, a.lang))
                .int32(4, num(c["wind_speed_10m"])?.roundToInt() ?: 0)
                .int32(5, num(c["apparent_temperature"])?.roundToInt() ?: 0)
                .varint(8, icon(code).toLong())
                .int32(10, num(c["relative_humidity_2m"])?.roundToInt() ?: 0)
                .varint(11, garminDay(now.atZone(zone).dayOfWeek).toLong())
                .msg(13, provider())
                .varint(16, bearing.toLong())
            val cond = PbWriter().msg(1, cur)
            val d = j["daily"] as Map<String, Any?>
            val times = d["time"] as List<Any?>
            for (i in 0 until minOf(a.days, times.size)) {
                fun at(k: String) = num((d[k] as? List<Any?>)?.getOrNull(i))
                val day = Instant.ofEpochSecond((times[i] as Number).toLong()).atZone(zone).toLocalDate()
                val dc = at("weather_code")?.toInt() ?: 3
                cond.msg(2, PbWriter()
                    .varint(1, garminDay(day.dayOfWeek).toLong())
                    .sint32(2, at("temperature_2m_max")?.roundToInt() ?: 0)
                    .sint32(3, at("temperature_2m_min")?.roundToInt() ?: 0)
                    .string(4, text(dc, a.lang))
                    .varint(5, icon(dc).toLong())
                    .int32(6, at("precipitation_probability_max")?.roundToInt() ?: 0))
            }
            cond.msg(4, provider())
            w.msg(11, cond)
        }
        if (a.hours > 0) {
            val h = j["hourly"] as Map<String, Any?>
            val times = (h["time"] as List<Any?>).map { (it as Number).toLong() }
            val first = times.indexOfFirst { it + 3600 > now.epochSecond }.coerceAtLeast(0)
            val hr = PbWriter()
            for (i in first until minOf(first + a.hours, times.size)) {
                fun at(k: String) = num((h[k] as? List<Any?>)?.getOrNull(i))
                val hc = at("weather_code")?.toInt() ?: 3
                val b = at("wind_direction_10m")?.roundToInt() ?: 0
                hr.msg(1, PbWriter()
                    .varint(1, times[i])
                    .sint32(3, at("temperature_2m")?.roundToInt() ?: 0)
                    .varint(4, (at("precipitation_probability")?.roundToInt() ?: 0).toLong())
                    .varint(5, icon(hc).toLong())
                    .msg(7, PbWriter().int32(1, at("wind_speed_10m")?.roundToInt() ?: 0).string(2, compass(b, a.lang)).varint(3, b.toLong())))
            }
            hr.msg(2, provider())
            w.msg(16, hr)
        }
        return w.toByteArray()
    }

    /** Fetch and build; throws on network or parse errors. */
    fun answer(net: Net, a: Ask): ByteArray {
        val r = net.get(url(a), mapOf("User-Agent" to "NaviLink"), timeoutMs = 15_000)
        require(r.code == 200) { "Open-Meteo answered ${r.code}" }
        return buildResponse(a, r.text)
    }

}
