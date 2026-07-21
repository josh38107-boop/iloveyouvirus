package com.kape.coffeepos.printer

import com.kape.coffeepos.FACEBOOK_PAGE_URL

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PrinterDevice(
    val name: String,
    val address: String,
    val paired: Boolean = true
)

data class PrinterResult(
    val success: Boolean,
    val message: String,
    val device: PrinterDevice? = null
)

data class PrinterProfile(
    val name: String = "POS-58",
    val model: String = "POS-58",
    val interfaceType: String = PRINTER_INTERFACE_BLUETOOTH,
    val bluetoothAddress: String? = null,
    val bridgeUrl: String = DEFAULT_WINDOWS_BRIDGE_PRINT_URL,
    val paperWidthMm: Int = 58,
    val printReceipts: Boolean = true,
    val autoPrintReceipts: Boolean = false,
    val kickCashDrawer: Boolean = true,
    val pesoSignStyle: String = "p",
    val lineCharacters: Int = 32
)

const val PRINTER_INTERFACE_BLUETOOTH = "Bluetooth"
const val PRINTER_INTERFACE_WINDOWS_BRIDGE = "Windows Bridge"
const val DEFAULT_WINDOWS_BRIDGE_PRINT_URL = "http://127.0.0.1:9123/print"

class BluetoothPrinterManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("printer_settings", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var connectedDevice: PrinterDevice? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private val discoveredDevices = linkedMapOf<String, PrinterDevice>()

    val savedPrinterAddress: String?
        get() = preferences.getString(KEY_PRINTER_ADDRESS, null)

    val printerProfile: PrinterProfile
        get() = PrinterProfile(
            name = preferences.getString(KEY_PROFILE_NAME, null)
                ?: preferences.getString(KEY_PROFILE_MODEL, null)
                ?: "POS-58",
            model = preferences.getString(KEY_PROFILE_MODEL, null) ?: "POS-58",
            interfaceType = preferences.getString(KEY_PROFILE_INTERFACE, null) ?: PRINTER_INTERFACE_BLUETOOTH,
            bluetoothAddress = preferences.getString(KEY_PRINTER_ADDRESS, null),
            bridgeUrl = preferences.getString(KEY_PROFILE_BRIDGE_URL, null) ?: DEFAULT_WINDOWS_BRIDGE_PRINT_URL,
            paperWidthMm = preferences.getInt(KEY_PROFILE_PAPER_WIDTH, 58),
            printReceipts = preferences.getBoolean(KEY_PROFILE_PRINT_RECEIPTS, true),
            autoPrintReceipts = preferences.getBoolean(KEY_PROFILE_AUTO_PRINT, false),
            kickCashDrawer = preferences.getBoolean(KEY_PROFILE_KICK_DRAWER, true),
            pesoSignStyle = preferences.getString(KEY_PROFILE_PESO_STYLE, "p") ?: "p",
            lineCharacters = preferences.getInt(KEY_PROFILE_LINE_CHARS, 32)
        )

    fun savePrinterProfile(profile: PrinterProfile) {
        preferences.edit()
            .putString(KEY_PROFILE_NAME, profile.name.ifBlank { "POS-58" })
            .putString(KEY_PROFILE_MODEL, profile.model.ifBlank { "POS-58" })
            .putString(KEY_PROFILE_INTERFACE, profile.interfaceType)
            .putString(KEY_PROFILE_BRIDGE_URL, profile.bridgeUrl.ifBlank { DEFAULT_WINDOWS_BRIDGE_PRINT_URL })
            .putInt(KEY_PROFILE_PAPER_WIDTH, profile.paperWidthMm)
            .putBoolean(KEY_PROFILE_PRINT_RECEIPTS, profile.printReceipts)
            .putBoolean(KEY_PROFILE_AUTO_PRINT, profile.autoPrintReceipts)
            .putBoolean(KEY_PROFILE_KICK_DRAWER, profile.kickCashDrawer)
            .putString(KEY_PROFILE_PESO_STYLE, profile.pesoSignStyle)
            .putInt(KEY_PROFILE_LINE_CHARS, profile.lineCharacters)
            .apply()
        profile.bluetoothAddress?.takeIf { it.isNotBlank() }?.let { address ->
            preferences.edit().putString(KEY_PRINTER_ADDRESS, address).apply()
        }
    }

    fun deletePrinterProfile() {
        closeSocket()
        preferences.edit()
            .remove(KEY_PROFILE_NAME)
            .remove(KEY_PROFILE_MODEL)
            .remove(KEY_PROFILE_INTERFACE)
            .remove(KEY_PRINTER_ADDRESS)
            .remove(KEY_PROFILE_BRIDGE_URL)
            .remove(KEY_PROFILE_PAPER_WIDTH)
            .remove(KEY_PROFILE_PRINT_RECEIPTS)
            .remove(KEY_PROFILE_AUTO_PRINT)
            .remove(KEY_PROFILE_KICK_DRAWER)
            .remove(KEY_PROFILE_PESO_STYLE)
            .remove(KEY_PROFILE_LINE_CHARS)
            .apply()
    }

    fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun allDevices(): List<PrinterDevice> {
        val merged = linkedMapOf<String, PrinterDevice>()
        pairedDevices().forEach { merged[it.address] = it }
        discoveredDevices.values.forEach { discovered ->
            merged.putIfAbsent(discovered.address, discovered)
        }
        savedPrinterAddress?.takeIf { it == MXW01_ADDRESS }?.let {
            merged.putIfAbsent(it, knownMxw01())
        }
        return merged.values.sortedWith(compareBy<PrinterDevice> { !it.paired }.thenBy { it.name.lowercase() }.thenBy { it.address })
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PrinterDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val bluetoothAdapter = adapter ?: return emptyList()
        return bluetoothAdapter.bondedDevices
            .map { device ->
                PrinterDevice(
                    name = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Printer",
                    address = device.address,
                    paired = true
                )
            }
            .sortedWith(compareBy<PrinterDevice> { it.name.lowercase() }.thenBy { it.address })
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onDevicesChanged: (List<PrinterDevice>) -> Unit,
        onFinished: (String) -> Unit
    ): Boolean {
        if (!hasScanPermission()) {
            onFinished("Bluetooth scan permission is needed to find nearby printers.")
            return false
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            onFinished("Bluetooth is not available on this device.")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            onFinished("Turn on Bluetooth, then refresh the device list.")
            return false
        }

        stopDiscovery()
        discoveredDevices.clear()
        onDevicesChanged(allDevices())

        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val address = device.address ?: return
                        val printer = PrinterDevice(
                            name = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Device",
                            address = address,
                            paired = device.bondState == BluetoothDevice.BOND_BONDED
                        )
                        discoveredDevices[address] = printer
                        onDevicesChanged(allDevices())
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        if (discoveredDevices.isEmpty() && bondedDevice(MXW01_ADDRESS) == null) {
                            discoveredDevices[MXW01_ADDRESS] = knownMxw01()
                            onDevicesChanged(allDevices())
                        }
                        stopDiscovery()
                        val count = discoveredDevices.size
                        onFinished(if (count == 0) "No nearby printers found. Make sure MXW01 is on and not connected to another phone." else "Found $count nearby Bluetooth device${if (count == 1) "" else "s"}.")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(discoveryReceiver, filter)
        }
        val started = bluetoothAdapter.startDiscovery()
        if (!started) {
            discoveredDevices[MXW01_ADDRESS] = knownMxw01()
            onDevicesChanged(allDevices())
            stopDiscovery()
            onFinished("Bluetooth scan could not start, so MXW01 was added from its saved address. Tap MXW01 to pair or connect.")
        }
        return started
    }

    fun connectedPrinter(): PrinterDevice? = connectedDevice

    suspend fun connect(address: String): PrinterResult = withContext(Dispatchers.IO) {
        if (!hasBluetoothPermission()) {
            return@withContext PrinterResult(false, "Bluetooth permission is needed to connect printers.")
        }
        val bluetoothAdapter = adapter
            ?: return@withContext PrinterResult(false, "Bluetooth is not available on this device.")
        if (!bluetoothAdapter.isEnabled) {
            return@withContext PrinterResult(false, "Turn on Bluetooth, then refresh the device list.")
        }
        val device = knownDevice(address)
            ?: return@withContext PrinterResult(false, "Pair the printer in Android Bluetooth settings first.")

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return@withContext try {
                device.createBond()
                PrinterResult(false, "Pairing requested for ${device.name ?: "printer"}. Tap the Android pairing prompt, then refresh.")
            } catch (_: SecurityException) {
                PrinterResult(false, "Pair MXW01 in Android Bluetooth settings, then refresh.")
            }
        }

        closeSocket()
        try {
            bluetoothAdapter.cancelDiscovery()
            val nextSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            nextSocket.connect()
            socket = nextSocket
            connectedDevice = PrinterDevice(
                name = device.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Printer",
                address = device.address,
                paired = true
            )
            preferences.edit().putString(KEY_PRINTER_ADDRESS, device.address).apply()
            PrinterResult(true, "Connected to ${connectedDevice?.name}.", connectedDevice)
        } catch (error: IOException) {
            closeSocket()
            PrinterResult(false, "Printer unavailable. Check Bluetooth and reconnect.")
        }
    }

    suspend fun print(text: String, allowCashDrawerKick: Boolean = true): PrinterResult = withContext(Dispatchers.IO) {
        printInternal(
            text = text,
            promotionQrPayload = null,
            includeSocialQr = true,
            allowCashDrawerKick = allowCashDrawerKick
        )
    }

    /** Prints one continuous receipt with the promotion QR before the normal social QR/footer. */
    suspend fun printReceiptWithPromotion(
        receiptText: String,
        promotionText: String,
        promotionQrPayload: String,
        allowCashDrawerKick: Boolean = true
    ): PrinterResult = withContext(Dispatchers.IO) {
        if (promotionQrPayload.isBlank() || promotionQrPayload.toByteArray(Charsets.UTF_8).size > 7089) {
            return@withContext PrinterResult(false, "Promotion QR payload is empty or too long.")
        }
        val combinedText = receiptText.trimEnd() + "\n\n" + promotionText.trim()
        printInternal(
            text = combinedText,
            promotionQrPayload = promotionQrPayload,
            includeSocialQr = true,
            allowCashDrawerKick = allowCashDrawerKick
        )
    }

    private suspend fun printInternal(
        text: String,
        promotionQrPayload: String?,
        includeSocialQr: Boolean,
        allowCashDrawerKick: Boolean
    ): PrinterResult {
        val profile = printerProfile
        val lineChars = if (profile.lineCharacters > 0) profile.lineCharacters else (if (profile.paperWidthMm >= 80) 48 else 32)
        val formattedText = formatForPaper(text, lineChars, profile.paperWidthMm, profile.pesoSignStyle)
        if (profile.interfaceType == PRINTER_INTERFACE_WINDOWS_BRIDGE) {
            printViaWindowsBridge(formattedText, profile.bridgeUrl, promotionQrPayload)?.let { return it }
            return PrinterResult(false, "Windows print bridge is not reachable. Start the bridge, then try again.")
        }
        if (!hasBluetoothPermission()) {
            printViaWindowsBridge(formattedText, profile.bridgeUrl, promotionQrPayload)?.let { return it }
            return PrinterResult(false, "Bluetooth permission is needed to print receipts.")
        }
        val target = connectedDevice
            ?: (profile.bluetoothAddress ?: savedPrinterAddress)?.let { address ->
                val reconnect = connect(address)
                if (!reconnect.success) return reconnect
                reconnect.device
            }
            ?: run {
                printViaWindowsBridge(formattedText, profile.bridgeUrl, promotionQrPayload)?.let { return it }
                return PrinterResult(false, "Connect a printer before printing.")
            }

        val activeSocket = socket
            ?: run {
                printViaWindowsBridge(formattedText, profile.bridgeUrl, promotionQrPayload)?.let { return it }
                return PrinterResult(false, "Printer unavailable. Check Bluetooth and reconnect.")
            }
        return try {
            activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x40.toByte()))
            if (includeSocialQr && allowCashDrawerKick && profile.kickCashDrawer) {
                // ESC p m t1 t2 - Kick cash drawer (Pin 2, ON 100ms, OFF 500ms)
                activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x70.toByte(), 0x00.toByte(), 50.toByte(), 250.toByte()))
            }
            val lines = formattedText.split("\n")
            for (line in lines) {
                val processedLine = when (profile.pesoSignStyle.lowercase(java.util.Locale.US)) {
                    "p" -> line.replace("₱", "P")
                    "php" -> line.replace("₱", "Php")
                    "utf8" -> line
                    "legacy" -> line.replace('₱', '\u00B1')
                    else -> line.replace("₱", "P")
                }
                val bytes = if (profile.pesoSignStyle.lowercase(java.util.Locale.US) == "utf8") {
                    processedLine.toByteArray(Charsets.UTF_8)
                } else {
                    processedLine.toByteArray(Charsets.ISO_8859_1)
                }
                val trimmedLine = processedLine.trim()
                val isOrderType = trimmedLine == "DINE-IN" || trimmedLine == "TAKE-OUT"
                if (isOrderType) {
                    val orderTypeBytes = if (profile.pesoSignStyle.lowercase(java.util.Locale.US) == "utf8") {
                        trimmedLine.toByteArray(Charsets.UTF_8)
                    } else {
                        trimmedLine.toByteArray(Charsets.ISO_8859_1)
                    }
                    // Center and emphasize the order type with double-height text.
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x61.toByte(), 0x01.toByte()))
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x45.toByte(), 0x01.toByte()))
                    activeSocket.outputStream.write(byteArrayOf(0x1D.toByte(), 0x21.toByte(), 0x01.toByte()))
                    activeSocket.outputStream.write(orderTypeBytes)
                    activeSocket.outputStream.write(byteArrayOf(0x1D.toByte(), 0x21.toByte(), 0x00.toByte()))
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x45.toByte(), 0x00.toByte()))
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x61.toByte(), 0x00.toByte()))
                } else if (line.startsWith("TOTAL")) {
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x45.toByte(), 0x01.toByte()))
                    activeSocket.outputStream.write(bytes)
                    activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x45.toByte(), 0x00.toByte()))
                } else {
                    activeSocket.outputStream.write(bytes)
                }
                activeSocket.outputStream.write("\n".toByteArray(Charsets.ISO_8859_1))
            }
            
            if (promotionQrPayload != null) {
                activeSocket.outputStream.write(byteArrayOf(0x1B, 0x61, 0x01))
                activeSocket.outputStream.write("\nScan to claim your free drink\n".toByteArray(Charsets.UTF_8))
                writeQrCode(activeSocket.outputStream, promotionQrPayload)
                activeSocket.outputStream.write(byteArrayOf(0x1B, 0x61, 0x00))
            }

            if (includeSocialQr) try {
                // 1. Center alignment
                activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x61.toByte(), 0x01.toByte()))
                
                // Add spacing and optional label text
                activeSocket.outputStream.write("\nScan and Follow Us!\n".toByteArray(Charsets.UTF_8))
                
                writeQrCode(activeSocket.outputStream, FACEBOOK_PAGE_URL)
                
                // 6.5. Print "Not Official Receipt!" below QR code
                activeSocket.outputStream.write("\nNot Official Receipt!\n".toByteArray(Charsets.UTF_8))
                
                // 7. Restore Left alignment
                activeSocket.outputStream.write(byteArrayOf(0x1B.toByte(), 0x61.toByte(), 0x00.toByte()))
            } catch (_: Exception) {
                // Ignore any QR code writing errors so printing the main receipt doesn't fail
            }

            activeSocket.outputStream.write("\n\n\n\n".toByteArray(Charsets.UTF_8))
            activeSocket.outputStream.flush()
            PrinterResult(true, "Receipt sent to ${target.name}.", target)
        } catch (error: IOException) {
            closeSocket()
            printViaWindowsBridge(formattedText, profile.bridgeUrl, promotionQrPayload) ?: PrinterResult(false, "Printer unavailable. Check Bluetooth and reconnect.")
        }
    }

    private fun writeQrCode(output: java.io.OutputStream, payload: String) {
        val dataBytes = payload.toByteArray(Charsets.UTF_8)
        require(dataBytes.size <= 7089) { "QR payload is too long." }
        output.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        output.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06))
        output.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30))
        val length = dataBytes.size + 3
        output.write(byteArrayOf(0x1D, 0x28, 0x6B, (length % 256).toByte(), (length / 256).toByte(), 0x31, 0x50, 0x30))
        output.write(dataBytes)
        output.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
    }

    private fun printViaWindowsBridge(
        text: String,
        preferredBridgeUrl: String = DEFAULT_WINDOWS_BRIDGE_PRINT_URL,
        promotionQrPayload: String? = null
    ): PrinterResult? {
        val bridgeUrls = (listOf(preferredBridgeUrl) + WINDOWS_BRIDGE_PRINT_URLS).distinct()
        bridgeUrls.forEach { bridgeUrl ->
            try {
                val connection = (URL(bridgeUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    if (promotionQrPayload != null) {
                        setRequestProperty("X-Kape-Print-Type", "receipt-with-promotion")
                        setRequestProperty(
                            "X-Kape-QR-Payload",
                            android.util.Base64.encodeToString(promotionQrPayload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        )
                    }
                }
                connection.outputStream.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                }
                val responseCode = connection.responseCode
                connection.disconnect()
                if (responseCode in 200..299) {
                    return PrinterResult(
                        success = true,
                        message = "Receipt sent to Windows printer.",
                        device = PrinterDevice("Windows Print Bridge", bridgeUrl, paired = true)
                    )
                }
            } catch (_: IOException) {
            }
        }
        return null
    }

    private fun formatForPaper(text: String, lineCharacters: Int, paperWidthMm: Int, pesoSignStyle: String): String {
        val W = if (lineCharacters > 0) lineCharacters else (if (paperWidthMm >= 80) 48 else 32)
        val pesoReplacement = when (pesoSignStyle.lowercase(java.util.Locale.US)) {
            "p" -> "P"
            "php" -> "Php"
            "utf8" -> "₱"
            "legacy" -> "\u00B1"
            else -> "P"
        }
        
        val textWithCurrency = text.replace("₱", pesoReplacement)
        val lines = textWithCurrency.lines()
        val resultLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                resultLines.add("")
                continue
            }
            
            if (trimmed.all { it == '-' }) {
                resultLines.add("-".repeat(W))
                continue
            }
            
            if (trimmed.startsWith("+")) {
                val indent = "  + "
                val content = trimmed.substring(1).trim()
                val maxContentLen = W - indent.length
                if (content.length <= maxContentLen) {
                    resultLines.add(indent + content)
                } else {
                    resultLines.addAll(content.chunked(maxContentLen).mapIndexed { idx, chunk ->
                        if (idx == 0) indent + chunk else "    " + chunk
                    })
                }
                continue
            }
            
            if (line.startsWith(" ")) {
                val pad = ((W - trimmed.length) / 2).coerceAtLeast(0)
                resultLines.add(" ".repeat(pad) + trimmed)
                continue
            }
            
            val gapRegex = Regex("\\s{2,}")
            val match = gapRegex.find(line)
            if (match != null && match.range.first > 0 && match.range.last < line.length - 1) {
                val left = line.substring(0, match.range.first).trim()
                val right = line.substring(match.range.last + 1).trim()
                
                val space = (W - left.length - right.length).coerceAtLeast(1)
                resultLines.add(left + " ".repeat(space) + right)
                continue
            }
            
            if (trimmed.length <= W) {
                resultLines.add(trimmed)
            } else {
                resultLines.addAll(trimmed.chunked(W))
            }
        }
        return resultLines.joinToString("\n")
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevice(address: String): BluetoothDevice? {
        if (!hasBluetoothPermission()) return null
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    @SuppressLint("MissingPermission")
    private fun knownDevice(address: String): BluetoothDevice? {
        if (!hasBluetoothPermission()) return null
        return bondedDevice(address) ?: adapter?.getRemoteDevice(address)
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
        }
        discoveryReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
        discoveryReceiver = null
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
            connectedDevice = null
        }
    }

    private fun knownMxw01(): PrinterDevice = PrinterDevice(
        name = "MXW01",
        address = MXW01_ADDRESS,
        paired = false
    )

    private companion object {
        const val KEY_PRINTER_ADDRESS = "selected_printer_address"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_PROFILE_MODEL = "profile_model"
        const val KEY_PROFILE_INTERFACE = "profile_interface"
        const val KEY_PROFILE_BRIDGE_URL = "profile_bridge_url"
        const val KEY_PROFILE_PAPER_WIDTH = "profile_paper_width"
        const val KEY_PROFILE_PRINT_RECEIPTS = "profile_print_receipts"
        const val KEY_PROFILE_AUTO_PRINT = "profile_auto_print"
        const val KEY_PROFILE_KICK_DRAWER = "profile_kick_drawer"
        const val KEY_PROFILE_PESO_STYLE = "peso_sign_style"
        const val KEY_PROFILE_LINE_CHARS = "line_characters"
        const val MXW01_ADDRESS = "48:0F:57:2C:D8:1B"
        val WINDOWS_BRIDGE_PRINT_URLS = listOf(
            "http://10.0.2.2:9123/print",
            DEFAULT_WINDOWS_BRIDGE_PRINT_URL
        )
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
