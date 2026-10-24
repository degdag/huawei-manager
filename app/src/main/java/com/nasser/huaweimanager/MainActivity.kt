package com.nasser.huaweimanager

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val hilink = HilinkClient("192.168.8.1")
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private lateinit var tvRsrp: TextView
    private lateinit var tvRsrq: TextView
    private lateinit var tvSinr: TextView
    private lateinit var tvRssi: TextView
    private lateinit var tvCellId: TextView
    private lateinit var tvEnodeb: TextView
    private lateinit var pbRsrp: ProgressBar
    private lateinit var pbRsrq: ProgressBar
    private lateinit var pbSinr: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createNativeLayout())

        lifecycleScope.launch {
            hilink.fetchSessionInfo()
            startTelemetryLoop()
        }
    }

    private fun startTelemetryLoop() {
        isPolling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!isPolling) return
                lifecycleScope.launch {
                    val metrics = hilink.getDeviceSignal()
                    updateUi(metrics)
                }
                handler.postDelayed(this, 1500)
            }
        })
    }

    private fun updateUi(m: SignalMetrics) {
        tvRsrp.text = "RSRP: ${m.rsrp}"
        tvRsrq.text = "RSRQ: ${m.rsrq}"
        tvSinr.text = "SINR: ${m.sinr}"
        tvRssi.text = "RSSI: ${m.rssi}"
        tvCellId.text = "Cell ID: ${m.cellId}"
        tvEnodeb.text = "eNodeB: ${m.enodebId}"

        val rawRsrp = m.rsrp.replace("dBm", "").trim().toIntOrNull() ?: -140
        pbRsrp.progress = (rawRsrp + 140).coerceIn(0, 100)

        val rawRsrq = m.rsrq.replace("dB", "").trim().toIntOrNull() ?: -20
        pbRsrq.progress = ((rawRsrq + 20) * 5).coerceIn(0, 100)

        val rawSinr = m.sinr.replace("dB", "").trim().toIntOrNull() ?: -10
        pbSinr.progress = ((rawSinr + 10) * 2.5).toInt().coerceIn(0, 100)
    }

    private fun showBandSelectionDialog() {
        val bands = arrayOf("B1 (2100MHz)", "B3 (1800MHz)", "B7 (2600MHz)", "B8 (900MHz)", "B20 (800MHz)", "B28 (700MHz)", "B38 (2600 TDD)", "B40 (2300 TDD)")
        val bandNumbers = intArrayOf(1, 3, 7, 8, 20, 28, 38, 40)
        val selected = BooleanArray(bands.size)

        AlertDialog.Builder(this)
            .setTitle("Select LTE Bands to Lock")
            .setMultiChoiceItems(bands, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                val chosen = mutableListOf<Int>()
                for (i in selected.indices) {
                    if (selected[i]) chosen.add(bandNumbers[i])
                }
                lifecycleScope.launch {
                    val ok = hilink.lockLteBands(chosen)
                    Toast.makeText(this@MainActivity, if (ok) "Bands Applied!" else "Failed to lock bands", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNativeLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#191919"))
            setPadding(40, 60, 40, 40)

            val title = TextView(this@MainActivity).apply {
                text = "Huawei Manager (API 34+)"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(0, 0, 0, 40)
            }
            addView(title)

            fun addMetricBlock(label: String): Pair<TextView, ProgressBar> {
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(0, 15, 0, 5)
                }
                val pb = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 24).apply {
                        setMargins(0, 0, 0, 15)
                    }
                    max = 100
                }
                addView(tv)
                addView(pb)
                return Pair(tv, pb)
            }

            val rsrpBlock = addMetricBlock("RSRP: --")
            tvRsrp = rsrpBlock.first
            pbRsrp = rsrpBlock.second

            val rsrqBlock = addMetricBlock("RSRQ: --")
            tvRsrq = rsrqBlock.first
            pbRsrq = rsrqBlock.second

            val sinrBlock = addMetricBlock("SINR: --")
            tvSinr = sinrBlock.first
            pbSinr = sinrBlock.second

            fun addSimpleText(defaultText: String): TextView {
                return TextView(this@MainActivity).apply {
                    text = defaultText
                    setTextColor(Color.LTGRAY)
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                }.also { addView(it) }
            }

            tvRssi = addSimpleText("RSSI: --")
            tvCellId = addSimpleText("Cell ID: --")
            tvEnodeb = addSimpleText("eNodeB: --")

            val btnLock = Button(this@MainActivity).apply {
                text = "Frequency / Band Locking"
                setBackgroundColor(Color.parseColor("#991111"))
                setTextColor(Color.WHITE)
                setOnClickListener { showBandSelectionDialog() }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 40, 0, 0)
            }
            addView(btnLock, lp)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }
}
