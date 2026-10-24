package com.nasser.huaweimanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class SignalMetrics(
    val rsrp: String = "0 dBm",
    val rsrq: String = "0 dB",
    val sinr: String = "0 dB",
    val rssi: String = "0 dBm",
    val cellId: String = "Unknown",
    val enodebId: String = "0",
    val mode: String = "No Service",
    val band: String = "Unknown",
    val dlFreq: String = "0",
    val ulFreq: String = "0"
)

class HilinkClient(private var gatewayIp: String = "192.168.8.1") {
    private val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
        private val cookieStore = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore
    }).build()

    private var sessionToken: String? = null

    fun setGatewayIp(ip: String) {
        gatewayIp = ip
    }

    suspend fun fetchSessionInfo(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://$gatewayIp/api/webserver/SesTokInfo")
                .build()
            client.newCall(req).execute().use { response ->
                val body = response.body?.string() ?: return@withContext false
                val doc = parseXml(body)
                sessionToken = doc.getElementsByTagName("TokInfo").item(0)?.textContent
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getDeviceSignal(): SignalMetrics = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("http://$gatewayIp/api/device/signal")
                .header("__RequestVerificationToken", sessionToken ?: "")
                .build()

            client.newCall(req).execute().use { res ->
                val xml = res.body?.string() ?: return@withContext SignalMetrics()
                val doc = parseXml(xml)

                fun tag(name: String) = doc.getElementsByTagName(name).item(0)?.textContent ?: "-"

                val cellIdStr = tag("cell_id")
                val enodeb = if (cellIdStr.matches(Regex("\\d+")) && cellIdStr.toLong() > 256) {
                    (cellIdStr.toLong() / 256).toString()
                } else "0"

                SignalMetrics(
                    rsrp = "${tag("rsrp")}dBm",
                    rsrq = "${tag("rsrq")}dB",
                    sinr = "${tag("sinr")}dB",
                    rssi = "${tag("rssi")}dBm",
                    cellId = cellIdStr,
                    enodebId = enodeb,
                    mode = tag("mode"),
                    band = tag("band"),
                    dlFreq = tag("dlfrequency"),
                    ulFreq = tag("ulfrequency")
                )
            }
        } catch (e: Exception) {
            SignalMetrics()
        }
    }

    suspend fun lockLteBands(bands: List<Int>): Boolean = withContext(Dispatchers.IO) {
        try {
            var mask = 0L
            for (b in bands) {
                mask = mask or (1L shl (b - 1))
            }
            val hexMask = java.lang.Long.toHexString(mask).uppercase()

            val xmlPayload = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>03</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>$hexMask</LTEBand></request>"""
            val body = xmlPayload.toRequestBody("application/xml".toMediaTypeOrNull())

            val req = Request.Builder()
                .url("http://$gatewayIp/api/net/net-mode")
                .header("__RequestVerificationToken", sessionToken ?: "")
                .post(body)
                .build()

            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseXml(content: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        return doc.documentElement
    }
}
