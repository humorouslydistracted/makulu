package com.makulu.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// BLUETOOTH PRINTER MANAGER
// ESC/POS commands over Bluetooth Classic SPP
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class PrinterManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // ESC/POS Commands
        private val CMD_INIT = byteArrayOf(0x1B, 0x40) // ESC @
        private val CMD_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01) // ESC a 1
        private val CMD_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00) // ESC a 0
        private val CMD_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01) // ESC E 1
        private val CMD_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00) // ESC E 0
        private val CMD_DOUBLE_SIZE = byteArrayOf(0x1D, 0x21, 0x11) // GS ! 0x11
        private val CMD_NORMAL_SIZE = byteArrayOf(0x1D, 0x21, 0x00) // GS ! 0x00
        private val CMD_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00) // GS V 66 0
        private val CMD_FEED_3 = byteArrayOf(0x1B, 0x64, 0x03) // ESC d 3
        private val CMD_FEED_2 = byteArrayOf(0x1B, 0x64, 0x02) // ESC d 2
        private val CMD_LINE = "--------------------------------\n".toByteArray()

        // Format datetime in Indian format: dd-MM-yyyy hh:mm:ss.SS AM/PM
        private fun formatIndianDateTime(isoDateTime: String?): String {
            if (isoDateTime == null) return ""
            return try {
                val ldt = LocalDateTime.parse(isoDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ldt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm:ss.SS a"))
            } catch (_: Exception) { isoDateTime }
        }
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _printerName = MutableStateFlow<String?>(null)
    val printerName: StateFlow<String?> = _printerName.asStateFlow()

    // Printer log (most recent first)
    data class PrinterLog(val timestamp: String, val event: String, val success: Boolean)
    private val _logs = MutableStateFlow<List<PrinterLog>>(emptyList())
    val logs: StateFlow<List<PrinterLog>> = _logs.asStateFlow()

    private fun log(event: String, success: Boolean = true) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        Log.d("MakuluPrinter", "[$ts] $event (${if (success) "OK" else "FAIL"})")
        _logs.value = listOf(PrinterLog(ts, event, success)) + _logs.value.take(49)
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    fun isBluetoothEnabled(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun connectToPrinter(device: BluetoothDevice, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                log("Connecting to ${device.name ?: device.address}...")
                socket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream
                _isConnected.value = true
                _printerName.value = device.name

                // Save printer address
                settingsRepo.set(SettingsRepository.KEY_PRINTER_ADDRESS, device.address)
                settingsRepo.set(SettingsRepository.KEY_PRINTER_NAME, device.name ?: "Printer")

                log("Connected to ${device.name ?: device.address}")
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: IOException) {
                _isConnected.value = false
                _printerName.value = null
                log("Connection failed: ${e.message}", false)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun autoReconnect() {
        scope.launch {
            val address = settingsRepo.get(SettingsRepository.KEY_PRINTER_ADDRESS) ?: return@launch
            if (address.isBlank()) return@launch
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return@launch
            if (!adapter.isEnabled) { log("Auto-reconnect skipped: BT off", false); return@launch }

            val device = adapter.bondedDevices?.find { it.address == address } ?: run {
                log("Auto-reconnect: saved device not found", false); return@launch
            }
            try {
                log("Auto-reconnecting to ${device.name}...")
                socket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream
                _isConnected.value = true
                _printerName.value = device.name
                log("Auto-reconnected to ${device.name}")
            } catch (e: IOException) {
                _isConnected.value = false
                log("Auto-reconnect failed: ${e.message}", false)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                socket?.close()
            } catch (_: IOException) {}
            socket = null
            outputStream = null
            _isConnected.value = false
            _printerName.value = null
            log("Disconnected")
        }
    }

    fun forgetPrinter() {
        scope.launch {
            disconnect()
            settingsRepo.set(SettingsRepository.KEY_PRINTER_ADDRESS, "")
            settingsRepo.set(SettingsRepository.KEY_PRINTER_NAME, "")
            log("Printer forgotten")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINT RECEIPT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printReceipt(order: OrderWithItems, onError: (String) -> Unit = {}): Boolean {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            log("Print receipt failed: not connected", false)
            onError("Printer not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                os.write(CMD_INIT)

                // Header fields
                val headerFields = settingsRepo.getEnabledReceiptFields().filter { it.isHeader }
                if (headerFields.isNotEmpty()) {
                    os.write(CMD_ALIGN_CENTER)
                    os.write(CMD_BOLD_ON)
                    os.write(CMD_DOUBLE_SIZE)
                    // First header field (usually shop name) is double size
                    headerFields.firstOrNull()?.let {
                        os.write("${it.fieldValue}\n".toByteArray())
                    }
                    os.write(CMD_NORMAL_SIZE)
                    // Rest of header fields
                    headerFields.drop(1).forEach {
                        os.write("${it.fieldValue}\n".toByteArray())
                    }
                    os.write(CMD_BOLD_OFF)
                }

                os.write(CMD_LINE)
                os.write(CMD_ALIGN_LEFT)

                // Body fields (configurable)
                val showOrderNo = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO) != "false"
                val showDateTime = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME) != "false"
                val showTable = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TABLE) != "false"
                val showItems = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS) != "false"
                val showTotal = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL) != "false"

                if (showOrderNo) os.write("Order: ${order.order.orderNumber}\n".toByteArray())
                if (showDateTime) {
                    val dt = order.order.completedAt ?: order.order.createdAt
                    os.write("Date: $dt\n".toByteArray())
                }
                if (showTable) os.write("Table: ${order.order.tableName}\n".toByteArray())

                if (showItems) {
                    os.write(CMD_LINE)
                    os.write(CMD_BOLD_ON)
                    os.write(formatLine("Item", "Qty", "Amount").toByteArray())
                    os.write(CMD_BOLD_OFF)
                    os.write(CMD_LINE)

                    order.items.forEach { item ->
                        os.write(formatLine(
                            item.menuItemName,
                            "x${item.quantity}",
                            "₹${"%.2f".format(item.lineTotal)}"
                        ).toByteArray())
                    }
                }

                if (showTotal) {
                    os.write(CMD_LINE)
                    os.write(CMD_BOLD_ON)
                    os.write(CMD_DOUBLE_SIZE)
                    os.write("Total: ₹${"%.2f".format(order.order.totalAmount)}\n".toByteArray())
                    os.write(CMD_NORMAL_SIZE)
                    os.write(CMD_BOLD_OFF)
                }

                // Footer
                val footerEnabled = settingsRepo.get(SettingsRepository.KEY_RECEIPT_FOOTER_ENABLED) != "false"
                if (footerEnabled) {
                    val footer = settingsRepo.get(SettingsRepository.KEY_RECEIPT_FOOTER) ?: "Thank you, visit again!"
                    os.write(CMD_LINE)
                    os.write(CMD_ALIGN_CENTER)
                    os.write("$footer\n".toByteArray())
                }

                os.write(CMD_FEED_3)
                os.write(CMD_CUT)
                os.flush()
                log("Receipt printed: ${order.order.orderNumber}")
                true
            } catch (e: IOException) {
                _isConnected.value = false
                log("Receipt print failed: ${e.message}", false)
                onError("Print failed: ${e.message}")
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINT KITCHEN ORDER
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printKitchenOrder(order: OrderWithItems, onError: (String) -> Unit = {}): Boolean {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            log("Kitchen print failed: not connected", false)
            onError("Printer not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                os.write(CMD_INIT)

                // 2 blank lines at top
                os.write(CMD_FEED_2)

                os.write(CMD_ALIGN_CENTER)
                os.write(CMD_BOLD_ON)
                os.write(CMD_DOUBLE_SIZE)
                os.write("KITCHEN ORDER\n".toByteArray())
                os.write(CMD_NORMAL_SIZE)
                os.write(CMD_BOLD_OFF)
                os.write(CMD_LINE)
                os.write(CMD_ALIGN_LEFT)

                // Table bold & double-size
                os.write(CMD_BOLD_ON)
                os.write(CMD_DOUBLE_SIZE)
                os.write("Table: ${order.order.tableName}\n".toByteArray())
                os.write(CMD_NORMAL_SIZE)
                os.write(CMD_BOLD_OFF)

                os.write("Order: ${order.order.orderNumber}\n".toByteArray())
                val dt = order.order.completedAt ?: order.order.createdAt
                os.write("Date: ${formatIndianDateTime(dt)}\n".toByteArray())
                os.write(CMD_LINE)

                // Aligned items using formatLine (Item + Qty columns)
                os.write(CMD_BOLD_ON)
                os.write(formatKitchenLine("Item", "Qty").toByteArray())
                os.write(CMD_LINE)
                order.items.forEach { item ->
                    os.write(formatKitchenLine(item.menuItemName, "x${item.quantity}").toByteArray())
                }
                os.write(CMD_BOLD_OFF)

                os.write(CMD_LINE)

                // 2 blank lines at bottom + cut
                os.write(CMD_FEED_2)
                os.write(CMD_FEED_3)
                os.write(CMD_CUT)
                os.flush()
                log("Kitchen order printed: ${order.order.orderNumber}")
                true
            } catch (e: IOException) {
                _isConnected.value = false
                log("Kitchen print failed: ${e.message}", false)
                onError("Print failed: ${e.message}")
                false
            }
        }
    }

    // Format a 3-column line for 32-char width (80mm paper)
    private fun formatLine(col1: String, col2: String, col3: String): String {
        val maxWidth = 32
        val col2Width = 4
        val col3Width = 9
        val col1Width = maxWidth - col2Width - col3Width
        val c1 = col1.take(col1Width).padEnd(col1Width)
        val c2 = col2.padStart(col2Width)
        val c3 = col3.padStart(col3Width)
        return "$c1$c2$c3\n"
    }

    // Format a 2-column line for kitchen order (Item + Qty aligned)
    private fun formatKitchenLine(col1: String, col2: String): String {
        val maxWidth = 32
        val col2Width = 5
        val col1Width = maxWidth - col2Width
        val c1 = col1.take(col1Width).padEnd(col1Width)
        val c2 = col2.padStart(col2Width)
        return "$c1$c2\n"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST PRINT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printTestPage(onError: (String) -> Unit = {}) {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            onError("Printer not connected")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                os.write(CMD_INIT)
                os.write(CMD_ALIGN_CENTER)
                os.write(CMD_BOLD_ON)
                os.write(CMD_DOUBLE_SIZE)
                os.write("MAKULU\n".toByteArray())
                os.write(CMD_NORMAL_SIZE)
                os.write(CMD_BOLD_OFF)
                os.write("Printer Test Page\n".toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_ALIGN_LEFT)
                os.write("If you can read this, your\n".toByteArray())
                os.write("printer is working correctly!\n".toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_BOLD_ON)
                os.write(formatLine("Item", "Qty", "Amount").toByteArray())
                os.write(CMD_BOLD_OFF)
                os.write(CMD_LINE)
                os.write(formatLine("Test Item 1", "x2", "₹100.00").toByteArray())
                os.write(formatLine("Test Item 2", "x1", "₹50.00").toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_BOLD_ON)
                os.write("Total: ₹150.00\n".toByteArray())
                os.write(CMD_BOLD_OFF)
                os.write(CMD_LINE)
                os.write(CMD_ALIGN_CENTER)
                os.write("Connection: OK ✓\n".toByteArray())
                os.write("Paper: OK ✓\n".toByteArray())
                os.write(CMD_FEED_3)
                os.write(CMD_CUT)
                os.flush()
            } catch (e: IOException) {
                _isConnected.value = false
                onError("Test print failed: ${e.message}")
            }
        }
    }
}
