package com.example.sonymultilive

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SonyPtpLiveViewController(
    private val client: SonyPtpIpClient,
    private val log: (String) -> Unit
) : AutoCloseable {
    companion object {
        private const val OP_GET_DEVICE_INFO = 0x1001
        private const val OP_GET_STORAGE_IDS = 0x1004
        private const val OP_GET_STORAGE_INFO = 0x1005
        private const val OP_GET_DEVICE_PROP_VALUE = 0x1015
        private const val OP_SONY_GET_DEVICE_PROPERTY_VALUE = 0x9204
        private const val OP_GET_OBJECT_INFO = 0x1008
        private const val OP_GET_OBJECT = 0x1009
        private const val OP_SDIO_CONNECT = 0x9201
        private const val OP_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        private const val OP_SDIO_SET_EXT_DEVICE_PROP_VALUE = 0x9205
        private const val OP_SDIO_CONTROL_DEVICE = 0x9207
        private const val OP_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209

        private const val EXT_PTP2 = 0x00C8
        private const val EXT_PTP3 = 0x012C

        // Sony Camera Remote property: Low = 640 px wide, High = 1024 px wide.
        private const val PROP_LIVEVIEW_IMAGE_QUALITY = 0xD26A
        // Sony Camera Remote status properties published in the 0x9209 table.
        // BatteryRemain is an actual percentage. BatteryLevel (D20E) is only an
        // indicator enum and must not be displayed as a percent.
        const val PROP_BATTERY_REMAIN = 0xD218
        const val PROP_BATTERY_LEVEL_INDICATOR = 0xD20E
        const val PROP_TOTAL_BATTERY_REMAIN = 0xD204
        private const val PROP_STANDARD_BATTERY_LEVEL = 0x5001
        const val PROP_MEDIA_SLOT1_REMAINING_TIME = 0xD24A
        const val PROP_MEDIA_SLOT2_REMAINING_TIME = 0xD258
        const val PROP_MOVIE_RECORDING_STATE = 0xD21D
        const val PROP_METERED_MANUAL_LEVEL = 0xD1B5
        const val PROP_EXPOSURE_PROGRAM_MODE = 0x500E
        const val PROP_ISO = 0xD21E
        const val PROP_ISO_ABSOLUTE = 0xD226
        const val CONTROL_S1_AF = 0xD2C1
        const val CONTROL_S2 = 0xD2C2
        const val CONTROL_MOVIE_REC = 0xD2C8
        const val CONTROL_REMOTE_TOUCH_OPERATION = 0xD2E4
        const val CONTROL_CANCEL_REMOTE_TOUCH_OPERATION = 0xD2E5
        // Sony Camera Control PTP 3: Camera Button Function can emulate the
        // physical Playback key. CameraOperatingMode D0BC is read-only, so mode
        // changes must be driven through the button control rather than 0x9205.
        const val PROP_CAMERA_BUTTON_FUNCTION_CAPABILITY = 0xD208
        const val PROP_CAMERA_OPERATING_MODE = 0xD0BC
        const val CONTROL_CAMERA_BUTTON_FUNCTION = 0xD309
        private const val CAMERA_BUTTON_LEFT = 0x0003
        private const val CAMERA_BUTTON_RIGHT = 0x0004
        private const val CAMERA_BUTTON_ENTER = 0x0005
        private const val CAMERA_BUTTON_MULTI_SELECTOR_LEFT = 0x0009
        private const val CAMERA_BUTTON_MULTI_SELECTOR_RIGHT = 0x000A
        private const val CAMERA_BUTTON_MULTI_SELECTOR_ENTER = 0x000B
        private const val CAMERA_BUTTON_PLAYBACK = 0x0011
        private const val CAMERA_OPERATING_MODE_PLAYBACK = 0x02
        // Sony PTP3 playback actions. E021/E022 can be advertised by 0x9209 as
        // Sony controls (getSet high bit set), in which case they MUST be sent via
        // 0x9207 ControlDevice rather than 0x9205 SetExtDevicePropValue.
        const val PROP_MOVIE_PLAY_BUTTON = 0xE021
        const val PROP_MOVIE_PLAY_PAUSE_BUTTON = 0xE022
        private const val LIVEVIEW_OBJECT_HANDLE = -16382 // 0xFFFFC002
        private const val RC_ACCESS_DENIED = 0x200F
        private const val RC_DEVICE_BUSY = 0x2019

        private const val DTC_INT8 = 0x0001
        private const val DTC_UINT8 = 0x0002
        private const val DTC_INT16 = 0x0003
        private const val DTC_UINT16 = 0x0004
        private const val DTC_INT32 = 0x0005
        private const val DTC_UINT32 = 0x0006
        private const val DTC_INT64 = 0x0007
        private const val DTC_UINT64 = 0x0008
        private const val DTC_STRING = 0xFFFF
        private const val FORM_NONE = 0
        private const val FORM_RANGE = 1
        private const val FORM_ENUM = 2
        private const val BUTTON_UP = 1
        private const val BUTTON_DOWN = 2
        private const val OPTION_FLAG = 1
    }

    enum class Protocol { PTP2_SDIO, PTP3_SDIO }

    data class DeviceInfoSnapshot(
        val standardVersion: Int,
        val vendorExtensionId: Long,
        val vendorExtensionVersion: Int,
        val manufacturer: String,
        val model: String,
        val deviceVersion: String,
        val serialNumber: String,
        val operationsSupported: Set<Int>,
        val eventsSupported: Set<Int>,
        val devicePropertiesSupported: Set<Int>
    )


    data class StorageInfoSnapshot(
        val storageId: Long,
        val maxCapacityBytes: Long,
        val freeSpaceBytes: Long,
        val freeSpaceImages: Long,
        val description: String,
        val volumeLabel: String
    )

    data class CameraStatusSnapshot(
        val batteryPercent: Int? = null,
        val remainingRecordSeconds: Long? = null,
        val storage: List<StorageInfoSnapshot> = emptyList()
    )

    /** Parsed Sony 0x9209 property descriptor used by the Sony Multiple Monitor setting UI. */
    data class ExtPropSnapshot(
        val code: Int,
        val dataType: Int,
        val getSet: Int = 0,
        val enabled: Int = 0,
        val currentValue: Long? = null,
        val currentString: String? = null,
        val enumValues: List<Long> = emptyList(),
        val rangeMin: Long? = null,
        val rangeMax: Long? = null,
        val rangeStep: Long? = null
    ) {
        val writable: Boolean get() = enabled == 1
    }

    private data class SonyPropertyDesc(
        val code: Int,
        val dataType: Int,
        val getSet: Int,
        val enabled: Int,
        val current: Long,
        val values: List<Long>
    )

    var protocol: Protocol? = null; private set
    var extInfoVersion: Int = 0; private set
    var supportedProperties: Set<Int> = emptySet(); private set
    var supportedControls: Set<Int> = emptySet(); private set
    @Volatile var deviceInfo: DeviceInfoSnapshot? = null; private set
    @Volatile private var storageIds: List<Long> = emptyList()
    private var liveViewObjectPrimed = false
    private var lastSonyProperties: Map<Int, SonyPropertyDesc> = emptyMap()
    @Volatile private var lastExtProperties: Map<Int, ExtPropSnapshot> = emptyMap()

    /**
     * ZV-1 / Imaging Edge compatible setup.
     *
     * ZV-1 advertises PTP 3.00. Ask for protocol 3 first using Sony's two-parameter
     * GetExtDeviceInfo request (0x012C, 1), then fall back to the proven PTP2/C8 path.
     * The rest of the connection sequence is unchanged from the stable v0.4.3 build.
     */
    fun initialize(): Protocol {
        val open = client.openSession(1)
        checkOk("OpenSession", open)
        log("OpenSession -> OK (tx=0)")

        val info = client.transaction(OP_GET_DEVICE_INFO)
        log("GetDeviceInfo -> PTP 0x${info.responseCode.toString(16)} data=${info.data.size}B")
        checkOk("GetDeviceInfo", info)
        deviceInfo = runCatching { parseDeviceInfo(info.data) }.getOrNull()

        val storage = client.transaction(OP_GET_STORAGE_IDS)
        if (storage.ok) {
            storageIds = parseStorageIds(storage.data)
            log("GetStorageIDs -> OK ids=${storageIds.size} data=${storage.data.size}B")
        } else {
            storageIds = emptyList()
            log("GetStorageIDs -> PTP 0x${storage.responseCode.toString(16)} (continuing)")
        }

        sdioPhase(1)
        sdioPhase(2)

        // 2020+ Sony bodies expose more directly-settable properties in protocol 3.00.
        // If ZV-1 declines it, immediately fall back to the already proven C8 flow.
        val ext = waitExt(EXT_PTP3, modernRequest = true)
            ?: waitExt(EXT_PTP2, modernRequest = false)
            ?: error("Sony SDIO auth: no ExtDeviceInfo response for 0x012C/0x00C8")

        extInfoVersion = ext.first
        protocol = if (ext.first == EXT_PTP3) Protocol.PTP3_SDIO else Protocol.PTP2_SDIO
        applyCapabilities(ext.second)
        log("ExtDeviceInfo version=0x${ext.first.toString(16)} props=${supportedProperties.size} controls=${supportedControls.size}")

        sdioPhase(3)

        refreshCapabilities(ext.first)
        log("Sony SDIO remote session ready protocol=$protocol")

        repeat(10) { index ->
            val ready = runCatching { client.transaction(OP_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO) }.getOrNull()
            if (ready == null) {
                log("0x9209 readiness #${index + 1}: transport error (continuing)")
            } else {
                log("0x9209 readiness #${index + 1}: PTP 0x${ready.responseCode.toString(16)} data=${ready.data.size}B")
                if (ready.ok && ready.data.size >= 8) {
                    val parsed = parseAllSonyProperties(ready.data)
                    if (parsed.isNotEmpty()) lastSonyProperties = lastSonyProperties + parsed.associateBy { it.code }
                    // Reuse the exact same warm-up 0x9209 response for telemetry/settings.
                    // No extra command is inserted between warm-up and FFFFC002 prime.
                    val propDescriptors = parseExtPropDataset(ready.data)
                    if (propDescriptors.isNotEmpty()) {
                        lastExtProperties = lastExtProperties + propDescriptors
                    }
                }
            }
            Thread.sleep(80)
        }

        return protocol!!
    }

    /**
     * Apply Sony camera-side Live View source quality BEFORE FFFFC002 is primed.
     *
     * Sony Monitor & Control mapping used by the supplied project:
     *   D26A = 1 -> Low  (640-class)
     *   D26A = 2 -> High (1024-class)
     *   D26A = 3 -> VeryLow
     *
     * Uses the descriptor already captured by the existing 10x 0x9209 warm-up.
     * No additional property read is inserted, and this must never be called while
     * GetObject(FFFFC002) is streaming.
     */
    fun applyLiveViewSourceQuality(high: Boolean): Boolean {
        val desc = lastExtProperties[PROP_LIVEVIEW_IMAGE_QUALITY]
        val label = if (high) "HIGH / 1024-class" else "LOW / 640-class"
        val target = if (high) 2L else 1L

        if (desc == null) {
            log("D26A LiveViewImageQuality is not present in cached 0x9209; keeping camera native source")
            return false
        }
        if ((desc.getSet and 0x80) != 0) {
            log("D26A is a Sony control, not a stored property; refusing 0x9205 write")
            return false
        }
        if (!desc.writable) {
            log("D26A is disabled/read-only (enabled=${desc.enabled}); keeping camera native source")
            return false
        }
        if (desc.enumValues.isNotEmpty() && target !in desc.enumValues) {
            log("D26A target $target ($label) is not advertised by camera: ${desc.enumValues}")
            return false
        }
        if (desc.currentValue == target) {
            log("D26A already $label; no write needed")
            return true
        }

        val ok = setExtDevicePropValue(desc.code, desc.dataType, target)
        log("LiveViewImageQuality D26A -> $label value=$target result=${if (ok) "OK" else "REJECTED"}")
        if (!ok) return false

        liveViewObjectPrimed = false
        Thread.sleep(180)
        return true
    }

    /** Compatibility helper retained for older call sites. */
    fun requestLiveViewWidth1024(): Boolean = applyLiveViewSourceQuality(high = true)

    private fun setSonyProperty(desc: SonyPropertyDesc, value: Long, label: String): Boolean {
        val payload = packScalar(desc.dataType, value) ?: run {
            log("$label skipped: unsupported datatype=0x${desc.dataType.toString(16)}")
            return false
        }
        return try {
            val r = client.transaction(OP_SDIO_SET_EXT_DEVICE_PROP_VALUE, intArrayOf(desc.code), payload)
            log("$label -> PTP 0x${r.responseCode.toString(16)} value=${formatValue(value)}")
            r.ok
        } catch (t: Throwable) {
            // A transport timeout means the command channel may be desynchronised. Do not send any
            // more resolution probes. Let the caller's normal START error handling close the session.
            log("$label transport error: ${t.javaClass.simpleName}: ${t.message}")
            throw t
        }
    }

    /** Poll the bulk Sony property snapshot (0x9209), the safe/primary read path on PTP3 bodies. */
    private fun refreshSonyPropertySnapshot(): Map<Int, SonyPropertyDesc> {
        val r = client.transaction(OP_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO)
        if (!r.ok || r.data.size < 8) {
            log("0x9209 property snapshot -> PTP 0x${r.responseCode.toString(16)} data=${r.data.size}B")
            return lastSonyProperties
        }
        val parsed = parseAllSonyProperties(r.data)
        if (parsed.isNotEmpty()) {
            lastSonyProperties = parsed.associateBy { it.code }
            log("0x9209 property snapshot parsed=${parsed.size} data=${r.data.size}B")
        } else {
            log("0x9209 property snapshot parse produced 0 entries (${r.data.size}B)")
        }
        return lastSonyProperties
    }

    /**
     * Sony 0x9209 layout:
     *   u32 entryCount, u32 zero, then repeated vendor descriptors:
     *   u16 code, u16 datatype, u8 getSet, u8 enabled, default, current, u8 form, form-data.
     */
    private fun parseAllSonyProperties(data: ByteArray): List<SonyPropertyDesc> {
        if (data.size < 8) return emptyList()
        val count = (u32(data, 0).toLong() and 0xffffffffL).coerceIn(0, 4096).toInt()
        var off = 8
        val out = ArrayList<SonyPropertyDesc>(count.coerceAtMost(512))

        fun readPtpString(): Boolean {
            if (off >= data.size) return false
            val chars = data[off++].toInt() and 0xff
            val bytes = chars * 2
            if (off + bytes > data.size) return false
            off += bytes
            return true
        }

        fun readScalar(type: Int): Long? {
            val size = scalarSize(type) ?: return null
            if (off + size > data.size) return null
            val value = when (type) {
                DTC_INT8 -> data[off].toLong()
                DTC_UINT8 -> (data[off].toInt() and 0xff).toLong()
                DTC_INT16 -> ByteBuffer.wrap(data, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toLong()
                DTC_UINT16 -> u16(data, off).toLong()
                DTC_INT32 -> ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                DTC_UINT32 -> u32(data, off).toLong() and 0xffffffffL
                DTC_INT64, DTC_UINT64 -> ByteBuffer.wrap(data, off, 8).order(ByteOrder.LITTLE_ENDIAN).long
                else -> return null
            }
            off += size
            return value
        }

        repeat(count) { index ->
            if (off + 6 > data.size) {
                log("0x9209 parse stopped at entry=$index offset=$off/${data.size}")
                return out
            }
            val code = u16(data, off); off += 2
            val type = u16(data, off); off += 2
            val getSet = data[off++].toInt() and 0xff
            val enabled = data[off++].toInt() and 0xff

            var current = 0L
            if (type == DTC_STRING) {
                if (!readPtpString()) return out // default string
                if (!readPtpString()) return out // current string
            } else {
                if (readScalar(type) == null) {
                    log("0x9209 unsupported datatype=0x${type.toString(16)} at property=0x${code.toString(16)}")
                    return out
                }
                current = readScalar(type) ?: return out
            }

            if (off >= data.size) return out
            val form = data[off++].toInt() and 0xff
            val values = mutableListOf<Long>()
            when (form) {
                FORM_NONE -> Unit
                FORM_RANGE -> {
                    if (type == DTC_STRING) return out
                    repeat(3) { readScalar(type)?.let(values::add) ?: return out }
                }
                FORM_ENUM -> {
                    if (off + 2 > data.size) return out
                    val firstCount = u16(data, off).coerceIn(0, 4096); off += 2
                    repeat(firstCount) {
                        if (type == DTC_STRING) {
                            if (!readPtpString()) return out
                        } else {
                            readScalar(type)?.let(values::add) ?: return out
                        }
                    }

                    // Sony has two layouts in the field. Older ZV-1-era bodies end the
                    // descriptor after the first enum list. Newer (roughly 2024+) bodies
                    // may append a second support list. Peek instead of assuming it exists:
                    // the next property code is 0x5xxx/0xDxxx, while a list count is small.
                    if (off + 2 <= data.size) {
                        val maybeSecondCount = u16(data, off)
                        if (maybeSecondCount < 0x0200) {
                            val savedOff = off
                            val second = mutableListOf<Long>()
                            off += 2
                            var valid = true
                            repeat(maybeSecondCount) secondLoop@ {
                                if (!valid) return@secondLoop
                                if (type == DTC_STRING) {
                                    if (!readPtpString()) valid = false
                                } else {
                                    val v = readScalar(type)
                                    if (v == null) valid = false else second += v
                                }
                            }
                            if (valid) {
                                values.clear()
                                values.addAll(second)
                            } else {
                                // False-positive peek; restore so the next descriptor remains aligned.
                                off = savedOff
                            }
                        }
                    }
                }
                else -> {
                    log("0x9209 unknown form=$form property=0x${code.toString(16)}")
                    return out
                }
            }
            out += SonyPropertyDesc(code, type, getSet, enabled, current, values)
        }
        return out
    }


    private fun parseDeviceInfo(data: ByteArray): DeviceInfoSnapshot {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun u16v(): Int = b.short.toInt() and 0xffff
        fun u32v(): Long = b.int.toLong() and 0xffffffffL
        fun u16Array(): Set<Int> {
            val n = b.int.toLong() and 0xffffffffL
            require(n <= 65536L && n * 2L <= b.remaining())
            val out = linkedSetOf<Int>()
            repeat(n.toInt()) { out += u16v() }
            return out
        }
        val standardVersion = u16v()
        val vendorExtensionId = u32v()
        val vendorExtensionVersion = u16v()
        readPtpString(b) // VendorExtensionDesc
        u16v() // FunctionalMode
        val operations = u16Array()
        val events = u16Array()
        val properties = u16Array()
        u16Array() // CaptureFormats
        u16Array() // ImageFormats
        val manufacturer = readPtpString(b)
        val model = readPtpString(b)
        val deviceVersion = readPtpString(b)
        val serial = readPtpString(b)
        return DeviceInfoSnapshot(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serial,
            operationsSupported = operations,
            eventsSupported = events,
            devicePropertiesSupported = properties
        )
    }

    /**
     * One-shot camera status read. This is never part of the FFFFC002 startup
     * handshake; SonyCameraSession calls it only after Live View has produced
     * several frames or on an explicit manual refresh.
     */
    fun readCameraStatus(): CameraStatusSnapshot {
        // Cache-only by design. Never issue 0x9209/0x1015/0x1005 while FFFFC002
        // Live View is active: those commands share the same PTP command channel and
        // can stall the virtual-object stream. initialize() has already collected ten
        // Sony 0x9209 snapshots before primeLiveViewObject().
        val props = lastExtProperties

        fun percent(code: Int): Int? = props[code]?.currentValue
            ?.takeIf { it in 0L..100L }?.toInt()
        val battery = percent(PROP_BATTERY_REMAIN)
            ?: percent(PROP_TOTAL_BATTERY_REMAIN)

        fun remaining(code: Int): Long? = props[code]?.currentValue
            ?.takeIf { it in 0L..0xffff_fffeL }
        val remainingSeconds = remaining(PROP_MEDIA_SLOT1_REMAINING_TIME)
            ?: remaining(PROP_MEDIA_SLOT2_REMAINING_TIME)

        return CameraStatusSnapshot(
            batteryPercent = battery,
            remainingRecordSeconds = remainingSeconds,
            storage = emptyList()
        )
    }

    private fun parseStorageIds(data: ByteArray): List<Long> {
        if (data.size < 4) return emptyList()
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = (b.int.toLong() and 0xffffffffL).coerceAtMost(64L).toInt()
        val out = ArrayList<Long>(count)
        repeat(count) {
            if (b.remaining() < 4) return@repeat
            out += b.int.toLong() and 0xffffffffL
        }
        return out
    }

    private fun parseStorageInfo(storageId: Long, data: ByteArray): StorageInfoSnapshot {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 26) { "StorageInfo too short: ${data.size}" }
        b.short // StorageType
        b.short // FilesystemType
        b.short // AccessCapability
        val maxCapacity = b.long
        val freeBytes = b.long
        val freeImages = b.int.toLong() and 0xffffffffL
        val description = if (b.hasRemaining()) readPtpString(b) else ""
        val volume = if (b.hasRemaining()) readPtpString(b) else ""
        return StorageInfoSnapshot(
            storageId = storageId,
            maxCapacityBytes = maxCapacity.coerceAtLeast(0L),
            freeSpaceBytes = freeBytes.coerceAtLeast(0L),
            freeSpaceImages = freeImages,
            description = description,
            volumeLabel = volume
        )
    }

    /** Return the last property table captured from the existing Sony PTP warm-up. */
    fun cachedExtDevicePropInfo(): Map<Int, ExtPropSnapshot> = lastExtProperties.toMap()

    /** Manual/on-demand refresh. Never called concurrently with GetObject. */
    fun getAllExtDevicePropInfo(): Map<Int, ExtPropSnapshot> {
        refreshExtDevicePropInfo()
        return lastExtProperties
    }

    /**
     * Fetch one fresh Sony 0x9209 dataset and return only entries parsed from this
     * transaction. Null means the camera rejected/busied the read or parsing failed.
     * The successful result is also merged into the controller cache.
     */
    fun refreshExtDevicePropInfo(): Map<Int, ExtPropSnapshot>? {
        val result = client.transaction(OP_SDIO_GET_ALL_EXT_DEVICE_PROP_INFO)
        if (!result.ok || result.data.size < 8) return null
        // The generic Sony DPD parser is intentionally conservative and stops when
        // it encounters an unknown datatype/form. Real ZV-1 / ZV-E10M2 0x9209
        // datasets can contain model-specific descriptors before D21D/D1B5/5010,
        // so a perfectly valid dynamic status entry later in the blob would never
        // reach the cache. Always recover the three monitor-critical scalar values
        // with an independent raw descriptor scan and let them override the generic
        // result. This is still the same single 0x9209 transaction; no extra poll or
        // reconnect is introduced.
        val parsed = parseExtPropDataset(result.data).toMutableMap()
        scanCriticalDynamicScalars(result.data).forEach { (code, snapshot) ->
            // Preserve enum/range/display metadata from a successfully parsed or
            // previously cached descriptor. The raw scan is only authoritative for
            // the live scalar value.
            val base = parsed[code] ?: lastExtProperties[code]
            parsed[code] = if (base != null && base.dataType == snapshot.dataType) {
                base.copy(currentValue = snapshot.currentValue)
            } else {
                snapshot
            }
        }
        // Playback controls can appear only after the body has switched out of
        // Record/Remote mode. They may also sit after a model-specific descriptor
        // that the conservative generic parser cannot decode. Recover E021/E022
        // independently and, unlike the dynamic scalar scan above, keep getSet and
        // enabled because those fields decide whether the action uses 0x9207 or
        // 0x9205.
        scanPlaybackActionDescriptors(result.data).forEach { (code, snapshot) ->
            val base = parsed[code] ?: lastExtProperties[code]
            parsed[code] = if (base != null && base.dataType == snapshot.dataType) {
                base.copy(
                    getSet = snapshot.getSet,
                    enabled = snapshot.enabled,
                    currentValue = snapshot.currentValue
                )
            } else {
                snapshot
            }
        }
        if (parsed.isEmpty()) return null
        lastExtProperties = lastExtProperties + parsed
        return parsed
    }

    /**
     * Recover scalar status values from a Sony 0x9209 blob without depending on
     * every preceding proprietary descriptor being understood.
     *
     * Sony DPD scalar layout (libgphoto2 ptp_unpack_Sony_DPD):
     *   code:u16, type:u16, getSet:u8, enabled:u8, default:T, current:T, form:u8...
     *
     * We only scan properties whose wire type and value domain are known here.
     * The checks on datatype/getSet/enabled/value make accidental matches inside an
     * enum payload extremely unlikely. The normal parser remains authoritative for
     * every other property.
     */
    private fun scanCriticalDynamicScalars(data: ByteArray): Map<Int, ExtPropSnapshot> {
        if (data.size < 10) return emptyMap()
        val out = linkedMapOf<Int, ExtPropSnapshot>()

        fun candidate(code: Int, expectedType: Int, valueOk: (Long) -> Boolean): ExtPropSnapshot? {
            val valueSize = scalarSize(expectedType) ?: return null
            val lastStart = data.size - (6 + valueSize * 2)
            if (lastStart < 8) return null
            var i = 8 // Sony 0x9209 header: entry count u32 + reserved u32.
            while (i <= lastStart) {
                if (u16(data, i) != code || u16(data, i + 2) != expectedType) {
                    i++
                    continue
                }
                val getSet = data[i + 4].toInt() and 0xff
                val enabled = data[i + 5].toInt() and 0xff
                val plausibleGetSet = getSet == 0 || getSet == 1 || (getSet and 0x80) != 0
                if (!plausibleGetSet || enabled !in 0..2) {
                    i++
                    continue
                }
                val currentOffset = i + 6 + valueSize
                val formOffset = currentOffset + valueSize
                if (formOffset >= data.size || (data[formOffset].toInt() and 0xff) !in 0..2) {
                    i++
                    continue
                }
                val current = runCatching {
                    readTypedValue(
                        ByteBuffer.wrap(data, currentOffset, valueSize).order(ByteOrder.LITTLE_ENDIAN),
                        expectedType
                    )
                }.getOrNull()
                if (current != null && valueOk(current)) {
                    return ExtPropSnapshot(
                        code = code,
                        dataType = expectedType,
                        getSet = getSet,
                        enabled = enabled,
                        currentValue = current
                    )
                }
                i++
            }
            return null
        }

        candidate(PROP_MOVIE_RECORDING_STATE, DTC_UINT8) { it in 0L..2L }
            ?.let { out[PROP_MOVIE_RECORDING_STATE] = it }
        candidate(PROP_METERED_MANUAL_LEVEL, DTC_INT16) { true }
            ?.let { out[PROP_METERED_MANUAL_LEVEL] = it }
        candidate(0x5010, DTC_INT16) { true }
            ?.let { out[0x5010] = it }
        return out
    }

    /**
     * Recover E021/E022 descriptors from a Sony 0x9209 blob even when an earlier
     * proprietary descriptor makes the generic parser stop. The getSet byte is
     * essential: 0x8x marks a Sony ControlDevice action (0x9207), while a normal
     * 0/1 entry is a stored property (0x9205).
     */
    private fun scanPlaybackActionDescriptors(data: ByteArray): Map<Int, ExtPropSnapshot> {
        if (data.size < 12) return emptyMap()
        val wanted = intArrayOf(PROP_MOVIE_PLAY_BUTTON, PROP_MOVIE_PLAY_PAUSE_BUTTON)
        val out = linkedMapOf<Int, ExtPropSnapshot>()

        for (code in wanted) {
            var best: ExtPropSnapshot? = null
            var i = 8
            while (i + 9 < data.size) {
                if (u16(data, i) != code) {
                    i++
                    continue
                }
                val type = u16(data, i + 2)
                val valueSize = scalarSize(type)
                if (valueSize == null) {
                    i++
                    continue
                }
                val getSet = data[i + 4].toInt() and 0xff
                val enabled = data[i + 5].toInt() and 0xff
                val plausibleGetSet = getSet == 0 || getSet == 1 || (getSet and 0x80) != 0
                val currentOffset = i + 6 + valueSize
                val formOffset = currentOffset + valueSize
                if (!plausibleGetSet || enabled !in 0..2 || formOffset >= data.size) {
                    i++
                    continue
                }
                val form = data[formOffset].toInt() and 0xff
                if (form !in 0..2) {
                    i++
                    continue
                }
                val current = runCatching {
                    readTypedValue(
                        ByteBuffer.wrap(data, currentOffset, valueSize).order(ByteOrder.LITTLE_ENDIAN),
                        type
                    )
                }.getOrNull()
                if (current != null) {
                    val candidate = ExtPropSnapshot(
                        code = code,
                        dataType = type,
                        getSet = getSet,
                        enabled = enabled,
                        currentValue = current
                    )
                    // A control marker is much stronger evidence than an accidental
                    // byte match inside another descriptor's enum payload.
                    if ((getSet and 0x80) != 0) {
                        best = candidate
                        break
                    }
                    if (best == null) best = candidate
                }
                i++
            }
            if (best != null) out[code] = best
        }
        return out
    }

    /**
     * Read exactly one property after a PropertyChanged event. No bulk 0x9209 and
     * no vendor/standard fallback retry are used here: one event -> at most one
     * command transaction. This keeps Live View latency bounded.
     */
    fun readChangedNumericProperty(code: Int): Long? {
        val snapshot = lastExtProperties[code] ?: return null
        val dataType = snapshot.dataType
        if (dataType == DTC_STRING || dataType >= 0x4000) return null
        val opcode = if ((code and 0xF000) == 0xD000) {
            OP_SONY_GET_DEVICE_PROPERTY_VALUE
        } else {
            OP_GET_DEVICE_PROP_VALUE
        }
        val r = client.transaction(opcode, intArrayOf(code))
        if (!r.ok || r.data.isEmpty()) return null
        return runCatching {
            val b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
            readTypedValue(b, dataType)
        }.getOrNull()
    }

    /**
     * Lightweight single-property read used for dynamic camera telemetry.
     *
     * Sony vendor properties (0xDxxx) must use Sony GetDevicePropertyValue
     * opcode 0x9204. 0x1015 is the standard PTP opcode and ZV-1 can accept the
     * transaction while returning no useful vendor value, which left metered EV
     * stuck at the warm-up cache value. Standard 0x5xxx properties still use
     * 0x1015. No bulk 0x9209 refresh is performed here.
     */
    fun readCachedNumericProperty(code: Int): Long? {
        val snapshot = lastExtProperties[code]
        // Dynamic status properties are valid even when a particular body omits
        // their descriptor from the 0x9209 warm-up table. Keep protocol-known
        // scalar types here so a C203 event can still fetch the live value.
        val dataType = snapshot?.dataType ?: when (code) {
            PROP_MOVIE_RECORDING_STATE -> DTC_UINT8
            PROP_METERED_MANUAL_LEVEL -> DTC_INT16
            0x5010 -> DTC_INT16 // standard Exposure Bias Compensation
            else -> return null
        }
        if (dataType == DTC_STRING || dataType >= 0x4000) return null

        val primaryOpcode = if ((code and 0xF000) == 0xD000) {
            OP_SONY_GET_DEVICE_PROPERTY_VALUE
        } else {
            OP_GET_DEVICE_PROP_VALUE
        }
        var r = client.transaction(primaryOpcode, intArrayOf(code))
        // Some bodies expose a Sony property through the standard path as well.
        // Keep this as a one-shot fallback only; never use the bulk 0x9209 here.
        if ((!r.ok || r.data.isEmpty()) && primaryOpcode != OP_GET_DEVICE_PROP_VALUE) {
            r = client.transaction(OP_GET_DEVICE_PROP_VALUE, intArrayOf(code))
        }
        if (!r.ok || r.data.isEmpty()) return null
        return runCatching {
            val b = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
            readTypedValue(b, dataType)
        }.getOrNull()
    }

    /** Metered Manual Level (D1B5), signed Int16 in 1/1000 EV. */
    fun readMeteredManualLevel(): Long? = readCachedNumericProperty(PROP_METERED_MANUAL_LEVEL)

    fun setExtDevicePropValue(snapshot: ExtPropSnapshot, value: Long): Boolean =
        setExtDevicePropValue(snapshot.code, snapshot.dataType, value)

    fun setExtDevicePropValue(code: Int, dataType: Int, value: Long): Boolean {
        val payload = encodeTypedValue(dataType, value) ?: return false
        return vendorPropertyTransaction(OP_SDIO_SET_EXT_DEVICE_PROP_VALUE, code, payload).ok
    }

    /** Sony CameraRemoteCommand exposure +/- control (0x9207). */
    fun stepExtDeviceProperty(code: Int, up: Boolean): Boolean {
        val direction = if (up) 0x00000001 else 0x000000ff
        return vendorPropertyTransaction(OP_SDIO_CONTROL_DEVICE, code, le32(direction)).ok
    }

    fun pulseAutofocus(holdMs: Long = 300L) {
        controlButton(CONTROL_S1_AF, BUTTON_DOWN)
        try { Thread.sleep(holdMs) } finally { runCatching { controlButton(CONTROL_S1_AF, BUTTON_UP) } }
    }

    /**
     * Set Sony movie recording without a blind DOWN->UP pulse.
     *
     * D2C8 is a two-state Sony ControlDevice property, but its resting polarity
     * differs across camera generations. ZV-1-class bodies can advertise rest=1
     * while newer PTP3 bodies can advertise rest=2. Replaying the generic button
     * pulse therefore becomes START->STOP on one body and STOP->START on another.
     *
     * Use the D2C8 current value captured by the existing 0x9209 warm-up as the
     * resting/inactive value. REC sends the opposite value; STOP restores rest.
     * No extra read, reconnect, or bulk refresh is required.
     */
    fun setMovieRecording(recording: Boolean): Boolean {
        val desc = lastExtProperties[CONTROL_MOVIE_REC]
        val rest = desc?.currentValue?.toInt()?.takeIf { it == BUTTON_UP || it == BUTTON_DOWN }
            ?: BUTTON_UP
        val active = if (rest == BUTTON_UP) BUTTON_DOWN else BUTTON_UP
        val state = if (recording) active else rest
        val payload = desc?.let { encodeTypedValue(it.dataType, state.toLong()) } ?: le16(state)
        return vendorPropertyTransaction(OP_SDIO_CONTROL_DEVICE, CONTROL_MOVIE_REC, payload).ok
    }

    /**
     * Enter the camera's physical Playback mode.
     *
     * D0BC CameraOperatingMode is GET-only (1=Record, 2=Playback). Sony's
     * documented transition path is Camera Button Function (D309): encode the
     * target button ID in the upper 16 bits and ON/OFF in the lower 16 bits.
     * Playback is button ID 0x0011.
     */
    fun enterPlaybackMode(): Boolean {
        val operatingMode = lastExtProperties[PROP_CAMERA_OPERATING_MODE]?.currentValue
        if (operatingMode?.toInt() == CAMERA_OPERATING_MODE_PLAYBACK) {
            log("Camera already in Playback mode (D0BC=2)")
            return true
        }
        return pressPlaybackModeButton()
    }

    /** Press the physical Playback key. The same key is used to enter/leave Playback. */
    fun pressPlaybackModeButton(): Boolean = pressCameraButton(CAMERA_BUTTON_PLAYBACK)

    /**
     * Start movie playback in camera Playback mode. Sony PTP3 exposes E021 as
     * Movie Play Button. On bodies that do not publish/accept E021, fall back to
     * the physical center/ENTER key, which is the camera-side play/pause action
     * in Playback.
     */
    fun moviePlaybackPlay(): Boolean {
        // ZV-E10M2 accepts some playback property transactions without actually
        // changing the camera UI. Do not treat a transport-level ACK as proof that
        // E021 executed on this body: use the same physical CENTER/ENTER key the
        // camera itself uses in Playback mode.
        if (isZve10m2()) {
            refreshPlaybackButtonCapabilities()
            return pressPlaybackCenterButton()
        }
        if (pulsePlaybackPropertyButton(PROP_MOVIE_PLAY_BUTTON, "Movie Play E021")) return true
        return pressPlaybackCenterButton()
    }

    /** Pause movie playback. ZV-E10M2 uses the physical CENTER/ENTER toggle. */
    fun moviePlaybackPause(): Boolean {
        if (isZve10m2()) {
            refreshPlaybackButtonCapabilities()
            return pressPlaybackCenterButton()
        }
        if (pulsePlaybackPropertyButton(PROP_MOVIE_PLAY_PAUSE_BUTTON, "Movie Pause E022")) return true
        return pressPlaybackCenterButton()
    }

    /** Select the previous file in camera Playback using the physical LEFT key. */
    fun moviePlaybackPrevious(): Boolean {
        refreshPlaybackButtonCapabilities()
        return pressPlaybackHorizontalButton(previous = true)
    }

    /** Select the next file in camera Playback using the physical RIGHT key. */
    fun moviePlaybackNext(): Boolean {
        refreshPlaybackButtonCapabilities()
        return pressPlaybackHorizontalButton(previous = false)
    }

    private fun isZve10m2(): Boolean {
        val normalized = deviceInfo?.model
            ?.uppercase()
            ?.filter { it.isLetterOrDigit() }
            .orEmpty()
        return normalized.contains("ZVE10M2")
    }

    private fun refreshPlaybackButtonCapabilities() {
        runCatching { refreshExtDevicePropInfo() }
            .onFailure { log("Playback capability refresh failed: ${it.message}") }
    }

    /**
     * ZV bodies use the control-wheel ENTER key (D208 ID 0x0005) for movie
     * play/pause. An earlier implementation fell back only to Multi-selector Enter
     * (0x000B), which is the joystick-center key found on larger Alpha bodies.
     * Respect D208 when available and prefer the ordinary ENTER key otherwise.
     */
    private data class CameraButtonCapabilities(
        val rawValues: List<Long>,
        val buttonIds: Set<Int>
    )

    private fun cameraButtonCapabilities(): CameraButtonCapabilities {
        val rawValues = lastExtProperties[PROP_CAMERA_BUTTON_FUNCTION_CAPABILITY]?.enumValues.orEmpty()
        val buttonIds = rawValues.mapNotNull(::normalizeCameraButtonId).toSet()
        return CameraButtonCapabilities(rawValues, buttonIds)
    }

    private fun normalizeCameraButtonId(raw: Long): Int? {
        // D208 is a UInt32Array. Sony normally places the selector in the upper
        // 16 bits (ENTER=0x00050000, Playback=0x00110000). Older captures can
        // already contain the normalized 16-bit selector in the lower half.
        val upper = ((raw ushr 16) and 0xffffL).toInt()
        val lower = (raw and 0xffffL).toInt()
        return when {
            upper != 0 -> upper
            lower != 0 -> lower
            else -> null
        }
    }

    private fun pressPlaybackCenterButton(): Boolean {
        val capabilities = cameraButtonCapabilities()
        val buttonId = when {
            CAMERA_BUTTON_ENTER in capabilities.buttonIds -> CAMERA_BUTTON_ENTER
            CAMERA_BUTTON_MULTI_SELECTOR_ENTER in capabilities.buttonIds -> CAMERA_BUTTON_MULTI_SELECTOR_ENTER
            else -> CAMERA_BUTTON_ENTER
        }
        logCameraButtonChoice("center", capabilities, buttonId)
        return pressCameraButton(buttonId, requireAdvertised = capabilities.buttonIds.isNotEmpty())
    }

    private fun pressPlaybackHorizontalButton(previous: Boolean): Boolean {
        val capabilities = cameraButtonCapabilities()
        val primary = if (previous) CAMERA_BUTTON_LEFT else CAMERA_BUTTON_RIGHT
        val alternate = if (previous) CAMERA_BUTTON_MULTI_SELECTOR_LEFT else CAMERA_BUTTON_MULTI_SELECTOR_RIGHT
        val buttonId = when {
            primary in capabilities.buttonIds -> primary
            alternate in capabilities.buttonIds -> alternate
            else -> primary
        }
        logCameraButtonChoice(if (previous) "PREVIOUS" else "NEXT", capabilities, buttonId)
        return pressCameraButton(buttonId, requireAdvertised = capabilities.buttonIds.isNotEmpty())
    }


    private fun logCameraButtonChoice(
        label: String,
        capabilities: CameraButtonCapabilities,
        buttonId: Int
    ) {
        val raw = capabilities.rawValues.joinToString { "0x${it.toString(16)}" }
        val ids = capabilities.buttonIds.joinToString { "0x${it.toString(16)}" }
        log("Playback $label: D208 raw=$raw ids=$ids -> 0x${buttonId.toString(16)}")
    }

    private fun pulsePlaybackPropertyButton(code: Int, label: String): Boolean {
        // E021/E022 can be absent/disabled in the Record-mode 0x9209 table and
        // become actionable only after entering Playback. Refresh now instead of
        // trusting the warm-up cache from before the mode switch.
        val fresh = runCatching { refreshExtDevicePropInfo() }.getOrNull()
        val freshDesc = fresh?.get(code)
        val cachedDesc = lastExtProperties[code]
        val desc = freshDesc ?: cachedDesc
        val dataType = desc?.dataType ?: DTC_UINT16
        val down = encodeTypedValue(dataType, BUTTON_DOWN.toLong()) ?: le16(BUTTON_DOWN)
        val up = encodeTypedValue(dataType, BUTTON_UP.toLong()) ?: le16(BUTTON_UP)

        fun pulse(op: Int, route: String): Boolean {
            val press = vendorPropertyTransaction(op, code, down)
            if (!press.ok) {
                log("$label $route DOWN rejected: PTP 0x${press.responseCode.toString(16)}")
                return false
            }
            Thread.sleep(60L)
            val release = runCatching {
                vendorPropertyTransaction(op, code, up)
            }.getOrNull()
            if (release?.ok != true) {
                log("$label $route UP was not acknowledged")
                return false
            }
            log("$label via $route getSet=0x${(desc?.getSet ?: -1).toString(16)} type=0x${dataType.toString(16)}")
            return true
        }

        if (freshDesc != null) {
            // Camera Control PTP3 normally publishes E021/E022 as Get/Set
            // properties (getSet=1), so those go through 0x9205. Keep support for
            // a vendor/control descriptor too, because older Sony generations can
            // expose button-like entries with the 0x80 control bit.
            if ((freshDesc.getSet and 0x80) != 0) {
                return pulse(OP_SDIO_CONTROL_DEVICE, "0x9207")
            }
            if (freshDesc.enabled == 1) {
                return pulse(OP_SDIO_SET_EXT_DEVICE_PROP_VALUE, "0x9205")
            }
            log("$label unavailable in current Playback state: getSet=0x${freshDesc.getSet.toString(16)} enabled=${freshDesc.enabled}")
            return false
        }

        // Never blind-probe E021/E022. Several Sony bodies acknowledge a vendor
        // property/control write even when the action is unavailable in the current
        // mode, so PTP OK is not evidence that PLAY/PAUSE happened. Only use a
        // cached descriptor when the capability table explicitly advertises the
        // code; otherwise fall through to the physical D309 CENTER key.
        val explicitlyAdvertised = code in supportedProperties || code in supportedControls
        if (!explicitlyAdvertised) {
            log("$label not advertised; use physical Playback CENTER")
            return false
        }
        if (cachedDesc != null && cachedDesc.enabled == 1) {
            return if ((cachedDesc.getSet and 0x80) != 0) {
                pulse(OP_SDIO_CONTROL_DEVICE, "0x9207 cached")
            } else {
                pulse(OP_SDIO_SET_EXT_DEVICE_PROP_VALUE, "0x9205 cached")
            }
        }
        log("$label advertised but has no enabled Playback descriptor; use physical Playback CENTER")
        return false
    }

    private fun pressCameraButton(buttonId: Int, requireAdvertised: Boolean = true): Boolean {
        // If the camera published capabilities, obey them for the Playback mode key.
        // Fallback playback transport keys are allowed to probe once when the list
        // is incomplete, because older bodies often omit some D208 values.
        if (requireAdvertised && supportedControls.isNotEmpty() && CONTROL_CAMERA_BUTTON_FUNCTION !in supportedControls) {
            log("Camera button unsupported: D309 not advertised")
            return false
        }
        val advertisedButtonIds = cameraButtonCapabilities().buttonIds
        if (requireAdvertised && advertisedButtonIds.isNotEmpty() && buttonId !in advertisedButtonIds) {
            log("Camera button unsupported: D208 has no 0x${buttonId.toString(16)}; advertised=$advertisedButtonIds")
            return false
        }

        val down = (buttonId shl 16) or BUTTON_DOWN
        val up = (buttonId shl 16) or BUTTON_UP
        val press = vendorPropertyTransaction(
            OP_SDIO_CONTROL_DEVICE,
            CONTROL_CAMERA_BUTTON_FUNCTION,
            le32(down)
        )
        if (!press.ok) {
            log("D309 button 0x${buttonId.toString(16)} DOWN rejected: PTP 0x${press.responseCode.toString(16)}")
            return false
        }

        // A short 45 ms pulse was enough for the Playback key, but ZV-E10M2 can
        // miss the control-wheel CENTER pulse in Playback mode. Hold it long enough
        // to be recognized and make absolutely sure the key is released; D309 is
        // only valid again after CameraButtonFunctionStatus returns to Idle.
        Thread.sleep(110L)
        var released = false
        for (attempt in 0 until 3) {
            val ok = runCatching {
                vendorPropertyTransaction(
                    OP_SDIO_CONTROL_DEVICE,
                    CONTROL_CAMERA_BUTTON_FUNCTION,
                    le32(up)
                ).ok
            }.getOrDefault(false)
            if (ok) {
                released = true
                break
            }
            log("D309 button 0x${buttonId.toString(16)} UP retry ${attempt + 1}/3")
            Thread.sleep(70L)
        }
        if (!released) {
            log("D309 button 0x${buttonId.toString(16)} UP was not acknowledged; action not confirmed")
            return false
        }
        Thread.sleep(100L)
        return true
    }

    fun setTouchFocus(normalizedX: Double, normalizedY: Double): Boolean {
        val x = (normalizedX.coerceIn(0.0, 1.0) * 639.0).toInt()
        val y = (normalizedY.coerceIn(0.0, 1.0) * 479.0).toInt()
        val packed = (x shl 16) or (y and 0xffff)
        return vendorPropertyTransaction(OP_SDIO_CONTROL_DEVICE, CONTROL_REMOTE_TOUCH_OPERATION, le32(packed)).ok
    }

    /** Cancel Sony Remote Touch Operation (D2E5): Down then Up. */
    fun cancelTouchFocus(): Boolean {
        val down = vendorPropertyTransaction(
            OP_SDIO_CONTROL_DEVICE,
            CONTROL_CANCEL_REMOTE_TOUCH_OPERATION,
            le16(BUTTON_DOWN)
        )
        if (!down.ok) return false
        try {
            Thread.sleep(35L)
        } finally {
            val up = vendorPropertyTransaction(
                OP_SDIO_CONTROL_DEVICE,
                CONTROL_CANCEL_REMOTE_TOUCH_OPERATION,
                le16(BUTTON_UP)
            )
            if (!up.ok) return false
        }
        return true
    }

    private fun controlButton(property: Int, state: Int) {
        val result = vendorPropertyTransaction(OP_SDIO_CONTROL_DEVICE, property, le16(state))
        checkOk("Control 0x${property.toString(16)}=$state", result)
    }

    /** Public Sony command shape first; retry PTP3 option-flag shape only if needed. */
    private fun vendorPropertyTransaction(op: Int, property: Int, data: ByteArray): SonyPtpIpClient.PtpResult {
        val official = client.transaction(op, intArrayOf(property), data)
        if (official.ok || protocol != Protocol.PTP3_SDIO) return official
        return client.transaction(op, intArrayOf(property, OPTION_FLAG), data)
    }

    /**
     * Full Sony CameraRemoteCommand 0x9209 parser. Enum form contains two lists;
     * the second list is the actual settable support list. Arrays/strings are
     * skipped safely so later scalar settings stay aligned.
     */
    private fun parseExtPropDataset(data: ByteArray): Map<Int, ExtPropSnapshot> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val out = linkedMapOf<Int, ExtPropSnapshot>()
        if (b.remaining() < 8) return out
        val count = b.long.coerceIn(0L, 16384L).toInt()
        repeat(count) {
            if (b.remaining() < 7) return@repeat
            val code = b.short.toInt() and 0xffff
            val type = b.short.toInt() and 0xffff
            val getSet = b.get().toInt() and 0xff
            val enabled = b.get().toInt() and 0xff
            try {
                when {
                    type == DTC_STRING -> {
                        readPtpString(b) // default
                        val current = readPtpString(b)
                        val form = b.get().toInt() and 0xff
                        if (form != FORM_NONE) throw IllegalArgumentException("STR form=$form unsupported")
                        out[code] = ExtPropSnapshot(code, type, getSet, enabled, currentString = current)
                    }
                    type in 0x4001..0x400a -> {
                        readTypedArrayValues(b, type) // default array
                        val currentValues = readTypedArrayValues(b, type)
                        val form = b.get().toInt() and 0xff
                        if (form != FORM_NONE) throw IllegalArgumentException("array form=$form unsupported")
                        // D208 CameraButtonFunction is a UInt32Array capability list,
                        // not a scalar. Keep the complete current array so Playback can
                        // select the actual ENTER key advertised by each body. For other
                        // Sony array properties enumValues is also harmless and preserves
                        // useful capability data while currentValue remains first element.
                        out[code] = ExtPropSnapshot(
                            code = code,
                            dataType = type,
                            getSet = getSet,
                            enabled = enabled,
                            currentValue = currentValues.firstOrNull(),
                            enumValues = currentValues
                        )
                    }
                    else -> {
                        readTypedValue(b, type) // default
                        val current = readTypedValue(b, type)
                        val form = b.get().toInt() and 0xff
                        var enums = emptyList<Long>()
                        var min: Long? = null
                        var max: Long? = null
                        var step: Long? = null
                        when (form) {
                            FORM_NONE -> Unit
                            FORM_RANGE -> {
                                min = readTypedValue(b, type)
                                max = readTypedValue(b, type)
                                step = readTypedValue(b, type)
                            }
                            FORM_ENUM -> {
                                val firstCount = b.short.toInt() and 0xffff
                                require(firstCount <= 4096)
                                val firstValues = ArrayList<Long>(firstCount)
                                repeat(firstCount) { firstValues.add(readTypedValue(b, type)) }
                                enums = firstValues

                                // Do not unconditionally consume a second enum list. ZV-1 uses
                                // the classic single-list Sony DPD layout. Newer Sony bodies can
                                // append a second list; libgphoto2 detects it by peeking for a
                                // small uint16 count (<0x200). A following property code starts
                                // at 0x5xxx or 0xDxxx and must be left untouched.
                                if (b.remaining() >= 2) {
                                    b.mark()
                                    val maybeSecondCount = b.short.toInt() and 0xffff
                                    if (maybeSecondCount < 0x0200) {
                                        val secondValues = ArrayList<Long>(maybeSecondCount)
                                        var valid = true
                                        try {
                                            repeat(maybeSecondCount) { secondValues.add(readTypedValue(b, type)) }
                                        } catch (_: Throwable) {
                                            valid = false
                                        }
                                        if (valid) enums = secondValues else b.reset()
                                    } else {
                                        b.reset()
                                    }
                                }
                            }
                            else -> throw IllegalArgumentException("unsupported Sony ext form 0x${form.toString(16)}")
                        }
                        out[code] = ExtPropSnapshot(
                            code = code, dataType = type, getSet = getSet, enabled = enabled,
                            currentValue = current, enumValues = enums,
                            rangeMin = min, rangeMax = max, rangeStep = step
                        )
                    }
                }
            } catch (_: Throwable) {
                return out
            }
        }
        return out
    }

    private fun readPtpString(b: ByteBuffer): String {
        val chars = b.get().toInt() and 0xff
        if (chars == 0) return ""
        val byteCount = chars * 2
        require(byteCount <= b.remaining())
        val bytes = ByteArray(byteCount)
        b.get(bytes)
        val usable = (chars - 1).coerceAtLeast(0) * 2
        return String(bytes, 0, usable, Charsets.UTF_16LE)
    }

    private fun skipTypedArray(b: ByteBuffer, type: Int) {
        readTypedArrayValues(b, type)
    }

    private fun readTypedArrayFirst(b: ByteBuffer, type: Int): Long? =
        readTypedArrayValues(b, type).firstOrNull()

    private fun readTypedArrayValues(b: ByteBuffer, type: Int): List<Long> {
        val scalar = type - 0x4000
        val n = b.int.toLong() and 0xffffffffL
        require(n <= 1_000_000L)
        val values = ArrayList<Long>(n.coerceAtMost(4096L).toInt())
        repeat(n.toInt()) { index ->
            val value = readTypedValue(b, scalar)
            if (index < 4096) values += value
        }
        return values
    }

    private fun readTypedValue(b: ByteBuffer, type: Int): Long = when (type) {
        DTC_INT8 -> b.get().toLong()
        DTC_UINT8 -> (b.get().toInt() and 0xff).toLong()
        DTC_INT16 -> b.short.toLong()
        DTC_UINT16 -> (b.short.toInt() and 0xffff).toLong()
        DTC_INT32 -> b.int.toLong()
        DTC_UINT32 -> b.int.toLong() and 0xffffffffL
        DTC_INT64, DTC_UINT64 -> b.long
        0x0009, 0x000a -> { val low = b.long; b.long; low }
        else -> throw IllegalArgumentException("unsupported Sony datatype 0x${type.toString(16)}")
    }

    private fun encodeTypedValue(type: Int, value: Long): ByteArray? = when (type) {
        DTC_INT8, DTC_UINT8 -> byteArrayOf(value.toByte())
        DTC_INT16, DTC_UINT16 -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        DTC_INT32, DTC_UINT32 -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        DTC_INT64, DTC_UINT64 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        0x0009, 0x000a -> ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(value).putLong(0L).array()
        else -> null
    }

    private fun scalarSize(type: Int): Int? = when (type) {
        DTC_INT8, DTC_UINT8 -> 1
        DTC_INT16, DTC_UINT16 -> 2
        DTC_INT32, DTC_UINT32 -> 4
        DTC_INT64, DTC_UINT64 -> 8
        else -> null
    }

    private fun packScalar(type: Int, value: Long): ByteArray? = when (type) {
        DTC_INT8, DTC_UINT8 -> byteArrayOf((value and 0xff).toByte())
        DTC_INT16, DTC_UINT16 -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
        DTC_INT32, DTC_UINT32 -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        DTC_INT64, DTC_UINT64 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        else -> null
    }

    private fun logProperty(label: String, desc: SonyPropertyDesc) {
        val options = if (desc.values.isEmpty()) "<none>" else desc.values.joinToString { formatValue(it) }
        log("$label type=0x${desc.dataType.toString(16)} getSet=${desc.getSet} enabled=${desc.enabled} current=${formatValue(desc.current)} options=[$options]")
    }

    private fun formatValue(v: Long) = "0x${java.lang.Long.toHexString(v)}($v)"

    private fun sdioPhase(phase: Int) {
        log("SDIO_Connect phase $phase -> send 0x9201($phase,0,0)")
        val r = client.transaction(OP_SDIO_CONNECT, intArrayOf(phase, 0, 0))
        checkOk("SDIO_Connect phase $phase", r)
        log("SDIO_Connect phase $phase -> OK")
    }

    private fun waitExt(version: Int, modernRequest: Boolean): Pair<Int, ByteArray>? {
        repeat(12) { attempt ->
            val params = if (modernRequest) intArrayOf(version, 1) else intArrayOf(version)
            log("SDIO_GetExtDeviceInfo 0x${version.toString(16)}${if (modernRequest) ",1" else ""} attempt ${attempt + 1}")
            val r = client.transaction(OP_SDIO_GET_EXT_DEVICE_INFO, params)
            if (r.ok && r.data.size >= 2) {
                val returned = u16(r.data, 0)
                log("0x9202 -> returned=0x${returned.toString(16)} data=${r.data.size}B")
                if (returned == EXT_PTP2 || returned == EXT_PTP3) return returned to r.data
            } else {
                log("0x9202 -> PTP 0x${r.responseCode.toString(16)} data=${r.data.size}B")
            }
            Thread.sleep(120)
        }
        return null
    }

    private fun refreshCapabilities(preferred: Int) {
        for (version in linkedSetOf(preferred, EXT_PTP3, EXT_PTP2)) {
            val params = if (version == EXT_PTP3) intArrayOf(version, 1) else intArrayOf(version)
            val r = client.transaction(OP_SDIO_GET_EXT_DEVICE_INFO, params)
            if (!r.ok || r.data.size < 2) continue
            val returned = u16(r.data, 0)
            if (returned != EXT_PTP2 && returned != EXT_PTP3) continue
            extInfoVersion = returned
            applyCapabilities(r.data)
            log("ExtDeviceInfo refresh -> 0x${returned.toString(16)} data=${r.data.size}B")
            return
        }
        log("ExtDeviceInfo refresh -> no usable dataset (continuing)")
    }

    private fun applyCapabilities(data: ByteArray) {
        var off = if (data.size >= 2 && (u16(data, 0) == EXT_PTP2 || u16(data, 0) == EXT_PTP3)) 2 else 0
        fun readList(): Set<Int> {
            if (off + 4 > data.size) return emptySet()
            val count = u32(data, off).coerceIn(0, 4096); off += 4
            val out = linkedSetOf<Int>()
            repeat(count) {
                if (off + 2 <= data.size) { out += u16(data, off); off += 2 }
            }
            return out
        }
        supportedProperties = readList()
        supportedControls = readList()
    }

    fun primeLiveViewObject() {
        if (liveViewObjectPrimed) return
        var lastCode = 0
        repeat(20) { attempt ->
            val info = client.transaction(OP_GET_OBJECT_INFO, intArrayOf(LIVEVIEW_OBJECT_HANDLE))
            lastCode = info.responseCode
            if (info.ok) {
                liveViewObjectPrimed = true
                log("GetObjectInfo(FFFFC002) prime -> OK data=${info.data.size}B")
                return
            }
            if (info.responseCode != RC_ACCESS_DENIED && info.responseCode != RC_DEVICE_BUSY) {
                error("GetObjectInfo(0xFFFFC002) failed PTP 0x${info.responseCode.toString(16)}")
            }
            Thread.sleep(25L + attempt * 5L)
        }
        error("GetObjectInfo(0xFFFFC002) not ready, last PTP=0x${lastCode.toString(16)}")
    }

    fun getLiveViewJpeg(): ByteArray {
        if (!liveViewObjectPrimed) primeLiveViewObject()
        var lastCode = 0
        repeat(20) { attempt ->
            val r = client.transaction(OP_GET_OBJECT, intArrayOf(LIVEVIEW_OBJECT_HANDLE))
            lastCode = r.responseCode
            if (r.ok && r.data.isNotEmpty()) {
                return extractJpeg(r.data)
            }
            if (r.responseCode != RC_ACCESS_DENIED && r.responseCode != RC_DEVICE_BUSY) {
                liveViewObjectPrimed = false
                error("GetObject(0xFFFFC002) failed PTP 0x${r.responseCode.toString(16)}")
            }
            if (attempt == 4) {
                liveViewObjectPrimed = false
                runCatching { primeLiveViewObject() }
                    .onFailure { log("LiveView re-prime failed: ${it.javaClass.simpleName}: ${it.message}") }
            }
            Thread.sleep(15L + attempt * 3L)
        }
        error("GetObject(0xFFFFC002) not ready, last PTP=0x${lastCode.toString(16)}")
    }

    /**
     * Steady-state low-latency pull used by Sony Multiple Monitor after the proven Sony PTP/IP transport
     * handshake and FFFFC002 prime have completed.
     *
     * The original stable method above deliberately waits 15..72 ms after a
     * DeviceBusy/AccessDenied response. That is conservative, but it can miss
     * the next virtual live-view object when the camera produces frames faster
     * than the retry cadence. This variant keeps the exact same PTP operation
     * and object handle, but polls readiness at 2..6 ms intervals. No D26A,
     * resolution, bitrate or other camera property is changed.
     */
    fun getLiveViewJpegFast(): ByteArray {
        if (!liveViewObjectPrimed) primeLiveViewObject()
        var lastCode = 0
        repeat(64) { attempt ->
            val r = client.transaction(OP_GET_OBJECT, intArrayOf(LIVEVIEW_OBJECT_HANDLE))
            lastCode = r.responseCode
            if (r.ok && r.data.isNotEmpty()) return extractJpeg(r.data)

            if (r.responseCode != RC_ACCESS_DENIED && r.responseCode != RC_DEVICE_BUSY) {
                liveViewObjectPrimed = false
                error("GetObject(0xFFFFC002) failed PTP 0x${r.responseCode.toString(16)}")
            }

            // Do not inject GetObjectInfo into a normal busy window. A re-prime can
            // cost an extra source frame and is one of the largest avoidable latency
            // spikes. Poll the virtual object tightly; recover only after the whole
            // busy window has expired.
            val waitMs = when {
                attempt < 16 -> 1L
                attempt < 32 -> 2L
                attempt < 48 -> 3L
                else -> 4L
            }
            Thread.sleep(waitMs)
        }

        // Sustained miss only: rebuild the virtual-object prime once, then let the
        // session-level error recovery take over if the camera still rejects it.
        liveViewObjectPrimed = false
        runCatching { primeLiveViewObject() }
            .onFailure { log("Fast LiveView recovery prime failed: ${it.javaClass.simpleName}: ${it.message}") }
        error("GetObject(0xFFFFC002) fast pull not ready, last PTP=0x${lastCode.toString(16)}")
    }

    private fun extractJpeg(data: ByteArray): ByteArray {
        val start = findMarker(data, 0xFF, 0xD8, 0)
        require(start >= 0) { "LiveView payload has no JPEG SOI (${data.size}B)" }
        val end = findMarker(data, 0xFF, 0xD9, start + 2)
        require(end >= start) { "LiveView payload has no JPEG EOI (${data.size}B)" }
        // Most ZV-1 GetObject payloads are already exactly one JPEG. Avoid a
        // second full-frame allocation/copy on the hot path when no wrapper bytes
        // are present.
        if (start == 0 && end + 2 == data.size) return data
        return data.copyOfRange(start, end + 2)
    }

    private fun findMarker(data: ByteArray, a: Int, b: Int, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xff) == a && (data[i + 1].toInt() and 0xff) == b) return i
            i++
        }
        return -1
    }

    private fun checkOk(label: String, r: SonyPtpIpClient.PtpResult) {
        if (!r.ok) error("$label failed PTP 0x${r.responseCode.toString(16)}")
    }

    private fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun u16(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
    override fun close() = client.close()
}
