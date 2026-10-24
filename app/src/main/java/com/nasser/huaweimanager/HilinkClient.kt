package com.nasser.huaweimanager

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

data class TelemetryData(
    val status: String = "No Connect",
    val model: String = "Unknown",
    val service: String = "Unknown",
    val pci: String = "0",
    val cellId: String = "0",
    val tac: String = "0",
    val enbid: String = "0",
    val rssi: String = "0",
    val rscp: String = "0",
    val ecio: String = "0",
    val rsrp: String = "0",
    val rsrq: String = "0",
    val sinr: String = "0",
    val dlRate: String = "0K/s",
    val ulRate: String = "0K/s",
    val totalRate: String = "0K/s",
    val uptime: String = "00:00:00",
    val dlBytes: String = "0B/s",
    val ulBytes: String = "0B/s",
    val usersCount: String = "0",
    val bandWork: String = "-",
    val mode: String = "-",
    val netMode: String = "-"
)

class HilinkClient(var gatewayIp: String = "http://192.168.8.1") {

    private val cookieStore = mutableMapOf<String, Cookie>()

    private val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) cookieStore[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.values.toList()
    }).build()

    var sessionToken: String? = null

    suspend fun fetchSessionInfo(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$gatewayIp/api/webserver/SesTokInfo")
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@withContext false
                val doc = parseXml(body)
                sessionToken = doc.getElementsByTagName("TokInfo").item(0)?.textContent

                val sesInfo = doc.getElementsByTagName("SesInfo").item(0)?.textContent
                if (!sesInfo.isNullOrEmpty()) {
                    val parts = sesInfo.split("=")
                    if (parts.size >= 2) {
                        // Extract hostname cleanly without OkHttp extension issues
                        val cleanHost = gatewayIp
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .substringBefore("/")
                            .substringBefore(":")

                        val c = Cookie.Builder()
                            .domain(cleanHost)
                            .name(parts[0].trim())
                            .value(parts[1].trim())
                            .build()
                        cookieStore[c.name] = c
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun login(user: String, pass: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo()
            val token = sessionToken ?: ""

            fun sha256(input: String): String {
                val md = MessageDigest.getInstance("SHA-256")
                return md.digest(input.toByteArray()).fold("") { str, it -> str + "%02x".format(it) }
            }

            val passHash = sha256(pass)
            val passB64 = Base64.encodeToString(passHash.toByteArray(), Base64.NO_WRAP)
            val finalHash = sha256(user + passB64 + token)
            val authPassword = Base64.encodeToString(finalHash.toByteArray(), Base64.NO_WRAP)

            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Username>$user</Username><Password>$authPassword</Password><password_type>4</password_type></request>"""
            val body = xml.toRequestBody("application/xml".toMediaTypeOrNull())

            val req = Request.Builder()
                .url("$gatewayIp/api/user/login")
                .header("__RequestVerificationToken", token)
                .post(body)
                .build()

            client.newCall(req).execute().use { res ->
                val resp = res.body?.string() ?: ""
                res.header("__RequestVerificationToken")?.let { sessionToken = it }
                if (resp.contains("<response>OK</response>")) {
                    Pair(true, "Login Successful")
                } else {
                    val code = if (resp.contains("<code>")) resp.substringAfter("<code>").substringBefore("</code>") else "Unknown"
                    Pair(false, "Login Error: $code")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Connection Error: ${e.message}")
        }
    }

    suspend fun lockBands(bands: List<Int>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo() // Always refresh token before band lock
            val token = sessionToken ?: ""

            val hexMask = if (bands.isEmpty()) {
                "7FFFFFFFFFFFFFFF" // All bands
            } else {
                var mask = 0L
                for (b in bands) mask = mask or (1L shl (b - 1))
                java.lang.Long.toHexString(mask).uppercase()
            }

            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>03</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>$hexMask</LTEBand></request>"""
            val body = xml.toRequestBody("application/xml; charset=UTF-8".toMediaTypeOrNull())

            val req = Request.Builder()
                .url("$gatewayIp/api/net/net-mode")
                .header("__RequestVerificationToken", token)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build()

            client.newCall(req).execute().use { res ->
                val resp = res.body?.string() ?: ""
                res.header("__RequestVerificationToken")?.let { sessionToken = it }
                if (resp.contains("<response>OK</response>")) {
                    Pair(true, "Bands Locked to: $hexMask")
                } else {
                    val code = if (resp.contains("<code>")) resp.substringAfter("<code>").substringBefore("</code>") else "Denied"
                    Pair(false, "Router Error Code: $code (Please Login)")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Request Error: ${e.message}")
        }
    }

    suspend fun setNetworkMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo()
            val token = sessionToken ?: ""
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>$mode</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>7FFFFFFFFFFFFFFF</LTEBand></request>"""
            val body = xml.toRequestBody("application/xml; charset=UTF-8".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("$gatewayIp/api/net/net-mode")
                .header("__RequestVerificationToken", token)
                .post(body)
                .build()
            client.newCall(req).execute().use { it.body?.string()?.contains("<response>OK</response>") == true }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getTelemetry(): TelemetryData = withContext(Dispatchers.IO) {
        try {
            fun getXml(endpoint: String): Element? {
                val req = Request.Builder()
                    .url("$gatewayIp$endpoint")
                    .header("__RequestVerificationToken", sessionToken ?: "")
                    .build()
                return client.newCall(req).execute().use {
                    it.header("__RequestVerificationToken")?.let { t -> sessionToken = t }
                    it.body?.string()?.let { s -> parseXml(s) }
                }
            }

            val sig = getXml("/api/device/signal")
            val statusDoc = getXml("/api/monitoring/status")
            val traffic = getXml("/api/monitoring/traffic-statistics")

            fun tag(el: Element?, name: String): String {
                val txt = el?.getElementsByTagName(name)?.item(0)?.textContent ?: ""
                return if (txt.isBlank()) "0" else txt
            }

            fun clean(raw: String, unit: String): String {
                val v = raw.replace(unit, "", ignoreCase = true).trim()
                return if (v.isEmpty() || v == "0") "0" else "$v$unit"
            }

            val cellIdStr = tag(sig, "cell_id")
            val enb = if (cellIdStr.matches(Regex("\\d+")) && cellIdStr.toLong() > 256) (cellIdStr.toLong() / 256).toString() else "0"

            val curDl = tag(traffic, "CurrentDownloadRate").toLongOrNull() ?: 0L
            val curUl = tag(traffic, "CurrentUploadRate").toLongOrNull() ?: 0L

            TelemetryData(
                status = if (tag(statusDoc, "ConnectionStatus") == "901") "Connected" else "No Connect",
                model = tag(sig, "modemmodel").ifEmpty { "Huawei Router" },
                service = tag(sig, "service_provider").ifEmpty { "Cellular" },
                pci = tag(sig, "pci"),
                cellId = cellIdStr,
                tac = tag(sig, "tac"),
                enbid = enb,
                rssi = clean(tag(sig, "rssi"), "dBm"),
                rscp = clean(tag(sig, "rscp"), "dBm"),
                ecio = clean(tag(sig, "ecio"), "dB"),
                rsrp = clean(tag(sig, "rsrp"), "dBm"),
                rsrq = clean(tag(sig, "rsrq"), "dB"),
                sinr = clean(tag(sig, "sinr"), "dB"),
                dlRate = "${curDl / 1024}K/s",
                ulRate = "${curUl / 1024}K/s",
                totalRate = "${(curDl + curUl) / 1024}K/s",
                uptime = formatTime(tag(traffic, "CurrentConnectTime").toLongOrNull() ?: 0L),
                dlBytes = "${curDl}B/s",
                ulBytes = "${curUl}B/s",
                usersCount = tag(statusDoc, "CurrentWifiUser"),
                bandWork = tag(sig, "band"),
                mode = tag(sig, "mode"),
                netMode = tag(sig, "netmode")
            )
        } catch (e: Exception) {
            TelemetryData()
        }
    }

    suspend fun controlDevice(command: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo()
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Control>$command</Control></request>"""
            val body = xml.toRequestBody("application/xml".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("$gatewayIp/api/device/control")
                .header("__RequestVerificationToken", sessionToken ?: "")
                .post(body)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun parseXml(content: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        return doc.documentElement
    }
}
