package com.kape.coffeepos.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.mutableStateOf
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

private val PUBLIC_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

data class PromotionConfig(
    val available: Boolean = true,
    val enabled: Boolean = false,
    val ordersPerReward: Int = 300,
    val cycleProgress: Int = 0,
    val lifetimeOrderCount: Long = 0,
    val googleFormUrlTemplate: String = "",
    val eligibleItemIds: List<String> = emptyList(),
    val message: String? = null
)

internal data class OperationalResetState(
    val generation: Long = 0,
    val protocolVersion: Int = 1
)

internal fun shouldApplyOperationalReset(localGeneration: Long, remoteGeneration: Long): Boolean =
    remoteGeneration > localGeneration

internal fun isLegacyResetStateResponse(responseCode: Int): Boolean = responseCode == 404

internal fun posOrderUploadPayload(
    order: PosOrder,
    shiftSource: Pair<String, Long>
): Map<String, Any?> = mapOf(
    "id" to order.id,
    "status" to order.status,
    "employee_id" to order.employeeId,
    "shift_id" to shiftSource.second,
    "shift_device_id" to shiftSource.first,
    "subtotal_cents" to order.subtotalCents,
    "discount_cents" to order.discountCents,
    "discount_rule_id" to order.discountRuleId,
    "discount_category" to order.discountCategory,
    "discount_percent" to order.discountPercent,
    "discount_scope" to order.discountScope,
    "discount_reference" to order.discountReference,
    "tax_cents" to order.taxCents,
    "tip_cents" to order.tipCents,
    "total_cents" to order.totalCents,
    "created_at" to order.createdAt,
    "paid_at" to order.paidAt,
    "void_reason" to order.voidReason,
    "customer_name" to order.customerName,
    "table_number" to order.tableNumber,
    "order_type" to order.orderType
)

internal fun shouldApplyRemoteOrderTypeCorrection(
    isDownloadedRemoteOrder: Boolean,
    localOrderType: String,
    remoteOrderType: String
): Boolean = isDownloadedRemoteOrder && localOrderType != remoteOrderType

internal fun remoteLong(value: Any?, field: String): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
} ?: throw IllegalArgumentException("Render returned a non-numeric value for '$field': $value")

internal fun remoteLongOrNull(value: Any?, field: String): Long? =
    if (value == null) null else remoteLong(value, field)

internal fun remoteInt(value: Any?, field: String): Int = remoteLong(value, field).toInt()

internal fun remoteIntOrNull(value: Any?, field: String): Int? =
    remoteLongOrNull(value, field)?.toInt()

internal fun remoteDoubleOrNull(value: Any?, field: String): Double? {
    if (value == null) return null
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    } ?: throw IllegalArgumentException("Render returned a non-numeric value for '$field': $value")
}

internal fun versionedMutationId(deviceId: String, entity: String, canonicalRow: String): String {
    require(deviceId.isNotBlank()) { "Device ID is required for synchronized mutations." }
    require(entity.isNotBlank()) { "Entity is required for synchronized mutations." }
    return MessageDigest.getInstance("SHA-256")
        .digest("$deviceId:$entity:$canonicalRow".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun inventoryEventMutationId(eventId: Any?, generatedId: String): Any =
    eventId ?: generatedId

internal fun orderInventoryAddOnPayload(row: OrderInventoryAddOn): Map<String, Any> =
    mutableMapOf<String, Any>(
        "id" to row.id,
        "order_id" to row.orderId,
        "ingredient_id" to row.ingredientId,
        "quantity" to row.quantity,
        "created_at" to row.createdAt,
        "updated_at" to row.updatedAt
    ).apply {
        row.restoredAt?.let { put("restored_at", it) }
    }

data class PromotionResult(
    val isWinner: Boolean = false,
    val awardId: String? = null,
    val claimCode: String? = null,
    val qrUrl: String? = null,
    val ordersPerReward: Int = 300,
    val sequenceNumber: Long = 0,
    val expiresAt: Long? = null,
    val printed: Boolean = false,
    val message: String? = null
)

data class PromotionClaim(
    val valid: Boolean = false,
    val awardId: String? = null,
    val claimCode: String? = null,
    val status: String? = null,
    val expiresAt: Long? = null,
    val formSubmitted: Boolean = false,
    val eligibleItemIds: List<String> = emptyList(),
    val reservationToken: String? = null,
    val message: String? = null
)

data class BranchDeviceAuthority(
    val branchId: String,
    val managerDeviceId: String,
    val managerDeviceName: String,
    val revision: Long = 1
)

private data class EnrolledRenderDevice(
    val id: String,
    val branchId: String,
    val hardwareId: String,
    val name: String,
    val role: String
)

private data class RenderEnrollmentResponse(
    val token: String?,
    val device: EnrolledRenderDevice?,
    val serverVersion: String?
)

internal data class IngredientCatalogMetadata(
    val id: String,
    val name: String,
    val unit: String,
    val lowStockThreshold: Double,
    val takeoutOnly: Boolean
)

internal fun ingredientCatalogMetadata(ingredient: Ingredient) = IngredientCatalogMetadata(
    id = ingredient.id,
    name = ingredient.name,
    unit = ingredient.unit,
    lowStockThreshold = ingredient.lowStockThreshold,
    takeoutOnly = ingredient.takeoutOnly
)

internal fun mergeRemoteIngredientMetadata(local: Ingredient?, remote: Ingredient): Ingredient =
    if (local == null) remote else remote.copy(quantityOnHand = local.quantityOnHand)

internal fun resolveRemoteIngredient(
    local: Ingredient?,
    remote: Ingredient,
    preserveLocalMetadata: Boolean
): Ingredient = when {
    local == null -> remote
    preserveLocalMetadata -> local
    else -> mergeRemoteIngredientMetadata(local, remote)
}

internal fun resolveRemoteStoreSettings(
    local: StoreSettings?,
    remote: StoreSettings,
    preserveLocalSettings: Boolean
): StoreSettings = if (local != null && preserveLocalSettings) {
    local.copy(
        seniorDiscountPercent = remote.seniorDiscountPercent,
        pwdDiscountPercent = remote.pwdDiscountPercent,
        discountSettingsUpdatedAt = remote.discountSettingsUpdatedAt,
        voidRefundPin = remote.voidRefundPin,
        paymentVoidSettingsUpdatedAt = remote.paymentVoidSettingsUpdatedAt,
        businessDayCutoffMinutes = remote.businessDayCutoffMinutes,
        businessDaySettingsUpdatedAt = remote.businessDaySettingsUpdatedAt
    )
} else remote

class SupabaseSyncManager(
    private val context: Context,
    private val db: AppDatabase
) {
    private val TAG = "RenderCloudSync"
    private val syncMutex = Mutex()
    private val promotionSyncMutex = Mutex()
    
    private val prefs: SharedPreferences = context.getSharedPreferences("supabase_sync_prefs", Context.MODE_PRIVATE)
    private val mappingPrefs: SharedPreferences = context.getSharedPreferences("supabase_shift_mappings", Context.MODE_PRIVATE)
    private val syncPrefs: SharedPreferences = context.getSharedPreferences("supabase_sync_state", Context.MODE_PRIVATE)
    private val promotionPrefs: SharedPreferences = context.getSharedPreferences("promotion_pending_state", Context.MODE_PRIVATE)
    private val tokenStore = SecureTokenStore(context)
    private val resetProtocolVersion = 1

    // Configuration properties
    var supabaseUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(value) = prefs.edit().putString("url", value.trim().trimEnd('/')).apply()

    var renderCloudUrl: String
        get() = supabaseUrl
        set(value) { supabaseUrl = value }

    var supabaseKey: String
        get() = prefs.getString("key", "") ?: ""
        set(value) = prefs.edit().putString("key", value.trim()).apply()

    private var deviceToken: String
        get() = tokenStore.read()
        set(value) = tokenStore.write(value)

    private var enrolledRole: String
        get() = prefs.getString("render_device_role", "") ?: ""
        set(value) = prefs.edit().putString("render_device_role", value).apply()

    var deviceName: String
        get() = prefs.getString("device_name", "") ?: ""
        set(value) = prefs.edit().putString("device_name", value.trim()).apply()

    private val _managerDeviceId = mutableStateOf("")
    var managerDeviceId: String
        get() = _managerDeviceId.value
        private set(value) { _managerDeviceId.value = value }

    private val _managerDeviceName = mutableStateOf("")
    var managerDeviceName: String
        get() = _managerDeviceName.value
        private set(value) { _managerDeviceName.value = value }

    private val _authorityRevision = mutableStateOf(0L)
    var authorityRevision: Long
        get() = _authorityRevision.value
        private set(value) { _authorityRevision.value = value }

    val isManagerTablet: Boolean get() = enrolledRole == "manager" || (managerDeviceId.isNotBlank() && managerDeviceId == deviceId)
    val deviceRoleLabel: String get() = if (isManagerTablet) "Manager Tablet" else "Counter"

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", "") ?: ""
            if (id.isEmpty()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    private val _isSyncing = mutableStateOf(false)
    var isSyncing: Boolean
        get() = _isSyncing.value
        private set(value) { _isSyncing.value = value }

    private val _lastSyncStatus = mutableStateOf("Not configured")
    var lastSyncStatus: String
        get() = _lastSyncStatus.value
        private set(value) { _lastSyncStatus.value = value }

    private val _lastSyncTime = mutableStateOf(0L)
    var lastSyncTime: Long
        get() = _lastSyncTime.value
        private set(value) {
            _lastSyncTime.value = value
            prefs.edit().putLong("last_sync_time", value).apply()
        }

    init {
        // Initialize from preferences
        _lastSyncTime.value = prefs.getLong("last_sync_time", 0L)
        _lastSyncStatus.value = if (isConfigured()) "Ready to sync" else "Not configured"
    }

    private val client = OkHttpClient.Builder()
        .dns(ResilientDns)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .create()

    private fun ingredientMetadataKey(id: String) = "ingredient_metadata:$id"

    private fun storeSettingsFingerprintKey(id: String) = "store_settings_fingerprint:$id"

    private fun ingredientMetadataJson(ingredient: Ingredient): String =
        gson.toJson(ingredientCatalogMetadata(ingredient))

    private fun lastSyncedIngredientMetadataJson(id: String): String? {
        syncPrefs.getString(ingredientMetadataKey(id), null)?.let { return it }
        val legacyJson = syncPrefs.getString("ingredient:$id", null) ?: return null
        return runCatching {
            ingredientMetadataJson(gson.fromJson(legacyJson, Ingredient::class.java))
        }.getOrNull()
    }

    private fun storeSettingsFingerprint(settings: StoreSettings): String = gson.toJson(settings)

    private var appliedResetGeneration: Long
        get() = prefs.getLong("operational_reset_generation", 0L)
        set(value) = prefs.edit().putLong("operational_reset_generation", value).apply()

    private fun withResetHeaders(builder: Request.Builder): Request.Builder =
        builder
            .header("X-Reset-Protocol-Version", resetProtocolVersion.toString())
            .header("X-Operational-Reset-Generation", appliedResetGeneration.toString())

    suspend fun <T> runManagerConfigurationMutation(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                syncMutex.withLock {
                    refreshAuthority()
                    check(isManagerTablet) { "Only the designated Manager Tablet can edit shared configuration." }
                    block()
                }
            }
        }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean {
        return renderCloudUrl.isNotEmpty() && deviceToken.isNotEmpty()
    }

    val isEnrolled: Boolean get() = deviceToken.isNotEmpty()

    suspend fun enroll(renderUrl: String, enrollmentCode: String, requestedName: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(renderUrl.isNotBlank()) { "Enter the Render Cloud URL." }
            require(!renderUrl.contains("YOUR-SERVICE", ignoreCase = true)) {
                "Replace YOUR-SERVICE with the actual Render website address shown in your browser."
            }
            require(renderUrl.trim().startsWith("https://")) { "The Render Cloud URL must start with https://" }
            require(enrollmentCode.isNotBlank()) { "Enter an enrollment code from the admin website." }
            renderCloudUrl = renderUrl
            deviceName = requestedName
            val payload = gson.toJson(mapOf(
                "code" to enrollmentCode.trim(),
                "hardwareId" to deviceId,
                "deviceName" to requestedName.trim()
            ))
            val request = Request.Builder()
                .url("$renderCloudUrl/sync/v1/enroll")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val responseJson = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { gson.fromJson(body, Map::class.java)["error"]?.toString() }.getOrNull()
                    throw Exception(message ?: "Enrollment failed: HTTP ${response.code}")
                }
                body
            }
            val enrollment = runCatching { gson.fromJson(responseJson, RenderEnrollmentResponse::class.java) }.getOrNull()
            requireNotNull(enrollment) { "Render returned an empty or invalid enrollment response. Verify the URL and deploy the latest backend." }
            val token = enrollment.token
            val enrolledDevice = enrollment.device
            require(!token.isNullOrBlank() && enrolledDevice != null) {
                "Render did not return a device token. Verify the database migration and create a fresh enrollment code."
            }
            deviceToken = token
            enrolledRole = enrolledDevice.role
            prefs.edit().putString("branch_id", enrolledDevice.branchId).remove("key").apply()
            lastSyncStatus = "Enrolled as ${deviceRoleLabel}"
            if (enrolledDevice.role == "manager") claimManagerAuthority().getOrThrow() else syncNowInternal()
        }
    }

    fun clearOperationalSyncState() {
        mappingPrefs.edit().clear().apply()
        val editor = syncPrefs.edit()
        syncPrefs.all.keys
            .filter { it.startsWith("remote_order:") }
            .forEach { key -> editor.remove(key) }
        editor.apply()
        prefs.edit().putLong("render_sync_cursor", 0L).apply()
        lastSyncTime = 0L
        promotionPrefs.edit().clear().apply()
    }

    private fun fetchOperationalResetState(): OperationalResetState {
        val request = withResetHeaders(
            Request.Builder()
                .url("$renderCloudUrl/sync/v1/reset-state")
                .header("Authorization", "Bearer $deviceToken")
        ).get().build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // Older backend versions do not expose this endpoint. Treat them as
            // generation zero until the reset-aware backend rollout completes.
            if (isLegacyResetStateResponse(response.code)) return@use OperationalResetState()
            if (!response.isSuccessful) {
                throw Exception("Render reset check failed: HTTP ${response.code}: $body")
            }
            gson.fromJson(body, OperationalResetState::class.java) ?: OperationalResetState()
        }
    }

    private suspend fun applyOperationalResetIfRequired(): Boolean {
        val resetState = fetchOperationalResetState()
        if (!shouldApplyOperationalReset(appliedResetGeneration, resetState.generation)) return false
        clearOperationalHistoryPreservingInventory(db)
        clearOperationalSyncState()
        appliedResetGeneration = resetState.generation
        Log.i(TAG, "Applied operational reset generation ${resetState.generation}")
        return true
    }

    fun startSyncLoop(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (true) {
                if (isConfigured()) {
                    try {
                        syncNowInternal()
                        consecutiveFailures = 0
                    } catch (e: Exception) {
                        consecutiveFailures++
                        Log.e(TAG, "Sync loop error", e)
                        lastSyncStatus = "Sync failed: ${e.localizedMessage}"
                    }
                } else {
                    lastSyncStatus = "Not configured"
                }
                val baseDelay = if (consecutiveFailures == 0) SYNC_POLL_INTERVAL_MS
                    else min(MAX_RETRY_INTERVAL_MS, SYNC_POLL_INTERVAL_MS * (1L shl min(consecutiveFailures, 4)))
                delay(baseDelay + if (consecutiveFailures > 0) Random.nextLong(250L, 1250L) else 0L)
            }
        }
    }

    suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Render Cloud is not configured"))
        }
        try {
            syncNowInternal()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Manual sync error", e)
            Result.failure(e)
        }
    }

    suspend fun claimManagerAuthority(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(Exception("Render Cloud is not configured"))
        runCatching {
            syncMutex.withLock {
                val existing = refreshAuthority()
                if (existing != null && existing.managerDeviceId != deviceId) {
                    val tombstones = downloadCloudTombstones()
                    applyCloudTombstones(tombstones)
                    pullCatalog(tombstoneSet(tombstones))
                    pullInventoryBalances()
                }
                val nextRevision = (existing?.revision ?: 0L) + 1L
                val row = mapOf(
                    "branch_id" to branchId,
                    "manager_device_id" to deviceId,
                    "manager_device_name" to deviceName.ifBlank { "Manager Tablet" },
                    "revision" to nextRevision,
                    "updated_at" to System.currentTimeMillis()
                )
                makeRequest("sync_device_authority?on_conflict=branch_id", "POST", gson.toJson(listOf(row)))
                managerDeviceId = deviceId
                managerDeviceName = row["manager_device_name"].toString()
                authorityRevision = nextRevision
                if (existing == null) seedMissingInventoryBalances()
            }
            syncNowInternal()
        }
    }

    private suspend fun syncNowInternal() {
        syncMutex.withLock {
            if (isSyncing) return
            isSyncing = true
            lastSyncStatus = "Syncing..."
            Log.d(TAG, "Sync starting. Device ID: $deviceId")

            try {
                val appliedOperationalReset = applyOperationalResetIfRequired()
                refreshChangeCursor()
                refreshAuthority()
                // 1. Delete tombstones must win before normal catalog upload/download.
                var tombstoneSyncReady = true
                val localTombstones = if (isManagerTablet) localPendingTombstones() else emptyList()
                val confirmedCloudTombstones = try {
                    if (isManagerTablet) uploadPendingLocalTombstones()
                    downloadCloudTombstones()
                } catch (e: Exception) {
                    if (isMissingSyncTombstoneError(e)) {
                        tombstoneSyncReady = false
                        Log.w(TAG, "sync_tombstone table is not available yet; continuing without delete-sync tombstones.")
                        emptyList()
                    } else {
                        throw e
                    }
                }
                val effectiveTombstones = mergeTombstones(localTombstones, confirmedCloudTombstones)
                if (tombstoneSyncReady) {
                    markConfirmedPendingDeletes(confirmedCloudTombstones)
                }
                applyCloudTombstones(effectiveTombstones)

                // 2. Sync Catalog (Two-way push/pull, excluding tombstoned rows)
                syncCatalog(effectiveTombstones, uploadAllowed = isManagerTablet)

                // 3. Sync Local Shifts (Upload local shifts)
                uploadShifts()

                // 4. Sync Remote Shifts (Download shifts from other devices and map them)
                downloadShifts()

                // 5. Sync Transactions (Orders, Lines, Payments, Receipts, Snapshots, Adjustments)
                downloadTransactions()
                uploadTransactions()
                if (managerDeviceId.isNotBlank()) syncInventoryLedger()
                var orderAddOnSyncReady = true
                try {
                    syncOrderInventoryAddOns()
                } catch (e: Exception) {
                    if (isMissingOrderInventoryAddOnError(e)) {
                        orderAddOnSyncReady = false
                        Log.w(TAG, "order_inventory_add_on is not available yet; local add-on tracking remains active.")
                    } else {
                        throw e
                    }
                }

                // Save synced ingredient metadata. Inventory quantity is synchronized separately.
                val finalIngredients = db.inventoryDao().ingredientsNow()
                val editor = syncPrefs.edit()
                for (ing in finalIngredients) {
                    editor.putString(ingredientMetadataKey(ing.id), ingredientMetadataJson(ing))
                }
                db.settingsDao().settingsNow()?.let { settings ->
                    editor.putString(storeSettingsFingerprintKey(settings.id), storeSettingsFingerprint(settings))
                }
                editor.apply()

                lastSyncTime = System.currentTimeMillis()
                lastSyncStatus = when {
                    appliedOperationalReset -> "Cloud reset applied; sync successful"
                    !orderAddOnSyncReady -> "Sync partial: deploy the Render migration for order add-ons"
                    !tombstoneSyncReady -> "Sync successful; local deletes held until the Render migration runs"
                    managerDeviceId.isBlank() -> "Choose a Manager Tablet in Sync Settings"
                    else -> "Sync successful"
                }
                Log.d(TAG, "Sync completed successfully")
            } catch (e: Exception) {
                lastSyncStatus = if (isAuthoritySchemaError(e)) {
                    "Render database migration required"
                } else {
                    "Sync failed: ${e.localizedMessage}"
                }
                Log.e(TAG, "Sync internal error", e)
                throw e
            } finally {
                isSyncing = false
            }
        }
    }

    // HTTP compatibility layer retained only so existing Room synchronization code can migrate safely.
    private fun makeRequest(
        path: String,
        method: String,
        jsonPayload: String? = null,
        prefer: String = "resolution=merge-duplicates"
    ): String {
        if (isEnrolled && method == "POST" && !path.startsWith("rpc/") && !path.startsWith("inventory_balance")) {
            return pushVersioned(path.substringBefore('?'), jsonPayload ?: "[]")
        }
        val url = when {
            isEnrolled && method == "GET" -> "$renderCloudUrl/sync/v1/records/$path"
            isEnrolled && path.startsWith("rpc/") -> "$renderCloudUrl/sync/v1/$path"
            else -> "$renderCloudUrl/rest/v1/$path"
        }
        val credential = deviceToken.ifBlank { supabaseKey }
        val requestBuilder = withResetHeaders(Request.Builder())
            .url(url)
            .header("Content-Type", "application/json")
            .header("Prefer", prefer)

        requestBuilder.header("Authorization", "Bearer $credential")

        if (jsonPayload != null) {
            val body = jsonPayload.toRequestBody(jsonMediaType)
            requestBuilder.method(method, body)
        } else {
            requestBuilder.method(method, null)
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw Exception("$method $path failed: HTTP ${response.code}: $errorBody")
            }
            return response.body?.string() ?: ""
        }
    }

    private fun rpcRequest(function: String, payload: Map<String, Any?> = emptyMap()): String =
        if (isEnrolled && function == "apply_inventory_event") {
            pushOperations(listOf(mapOf(
                "mutationId" to inventoryEventMutationId(
                    eventId = payload["p_event_id"],
                    generatedId = UUID.randomUUID().toString()
                ),
                "type" to "inventory_event",
                "data" to payload
            )))
        } else makeRequest("rpc/$function", "POST", gson.toJson(payload), prefer = "return=representation")

    private fun pushVersioned(entity: String, jsonPayload: String): String {
        val element = gson.fromJson(jsonPayload, com.google.gson.JsonElement::class.java)
        val rows: List<Map<String, Any?>> = if (element.isJsonArray) {
            gson.fromJson(element, object : TypeToken<List<Map<String, Any?>>>() {}.type)
        } else listOf(gson.fromJson(element, object : TypeToken<Map<String, Any?>>() {}.type))
        var lastResponse = "{}"
        rows.chunked(100).forEach { chunk ->
            val operations = chunk.map { row ->
                val canonical = gson.toJson(row)
                val mutationId = versionedMutationId(deviceId, entity, canonical)
                if (entity == "sync_tombstone") mapOf("mutationId" to mutationId, "type" to "tombstone", "data" to row)
                else mapOf("mutationId" to mutationId, "type" to "upsert", "entity" to entity, "data" to row)
            }
            lastResponse = pushOperations(operations)
        }
        return lastResponse
    }

    private fun pushOperations(operations: List<Map<String, Any?>>): String {
        val request = withResetHeaders(Request.Builder()).url("$renderCloudUrl/sync/v1/push")
            .header("Authorization", "Bearer $deviceToken")
            .header("Content-Type", "application/json")
            .post(gson.toJson(mapOf("operations" to operations)).toRequestBody(jsonMediaType)).build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception("Render push failed: HTTP ${response.code}: $body")
            body
        }
    }

    private fun refreshChangeCursor() {
        if (!isEnrolled) return
        val cursor = prefs.getLong("render_sync_cursor", 0L)
        val request = withResetHeaders(Request.Builder()).url("$renderCloudUrl/sync/v1/changes?cursor=$cursor")
            .header("Authorization", "Bearer $deviceToken").get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception("Render change check failed: HTTP ${response.code}: $body")
            val parsed = gson.fromJson(body, Map::class.java)
            val next = (parsed["nextCursor"] as? Number)?.toLong() ?: cursor
            prefs.edit().putLong("render_sync_cursor", next).apply()
        }
    }

    suspend fun getPromotionConfig(): Result<PromotionConfig> = withContext(Dispatchers.IO) {
        runCatching {
            gson.fromJson(rpcRequest("get_promotion_config"), PromotionConfig::class.java)
                ?: PromotionConfig(available = false, message = "Promotion is not installed on Render.")
        }.recoverCatching { error ->
            val details = error.message.orEmpty()
            if (details.contains("PGRST202", ignoreCase = true) ||
                details.contains("get_promotion_config", ignoreCase = true) &&
                details.contains("schema cache", ignoreCase = true)
            ) {
                PromotionConfig(
                    available = false,
                    message = "Promotion support is not installed on this Render service. Deploy the current database migrations, then tap Retry."
                )
            } else {
                throw error
            }
        }
    }

    suspend fun getPromotionResult(orderId: String): Result<PromotionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val response = rpcRequest(
                "get_promotion_result",
                mapOf("p_order_id" to orderId, "p_device_id" to deviceId)
            )
            gson.fromJson(response, PromotionResult::class.java)
        }
    }

    /**
     * Uploads only the records required for Render Cloud to determine this order's
     * promotion result. This intentionally stays separate from the full sync
     * mutex so receipt preparation is not delayed by catalog or history sync.
     */
    suspend fun syncPromotionOrder(orderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(Exception("Render Cloud is not configured"))
        }
        runCatching {
            promotionSyncMutex.withLock {
                val order = db.orderDao().orderNow(orderId)
                    ?: throw IllegalArgumentException("Order not found: $orderId")
                val source = getShiftSource(order.shiftId)
                val shift = db.shiftDao().getShiftByIdNow(order.shiftId)
                    ?: throw IllegalArgumentException("Shift not found for order: $orderId")

                // A locally-owned shift must exist before its order is uploaded.
                // Mapped remote shifts already exist in Render PostgreSQL under their source ID.
                if (source.first == deviceId) {
                    makeRequest(
                        "shift?on_conflict=device_id,id",
                        "POST",
                        gson.toJson(listOf(shiftPayload(shift, source)))
                    )
                    markShiftSynced(shift, source)
                }

                val orderPayload = posOrderUploadPayload(order, source)
                makeRequest("pos_order?on_conflict=id", "POST", gson.toJson(listOf(orderPayload)))

                val lines = db.orderDao().orderLinesForOrder(orderId)
                val legacyLines = lines.map { line ->
                    mapOf(
                        "device_id" to deviceId,
                        "id" to line.id,
                        "order_id" to line.orderId,
                        "item_id" to line.itemId,
                        "name" to line.name,
                        "quantity" to line.quantity,
                        "unit_price_cents" to line.unitPriceCents,
                        "modifiers" to line.modifiers,
                        "notes" to line.notes
                    )
                }
                val linePayloads = lines.mapIndexed { index, line ->
                    legacyLines[index] + mapOf(
                        "discount_category" to line.discountCategory,
                        "discount_cents" to line.discountCents
                    )
                }
                if (linePayloads.isNotEmpty()) {
                    try {
                        makeRequest(
                            "order_line?on_conflict=device_id,id",
                            "POST",
                            gson.toJson(linePayloads)
                        )
                    } catch (error: Exception) {
                        val message = error.message.orEmpty()
                        val missingDiscountColumns = message.contains("PGRST204") &&
                            (message.contains("discount_category") || message.contains("discount_cents"))
                        if (!missingDiscountColumns) throw error
                        Log.w(TAG, "Remote order_line is missing discount columns; retrying focused sync without them.")
                        makeRequest(
                            "order_line?on_conflict=device_id,id",
                            "POST",
                            gson.toJson(legacyLines)
                        )
                    }
                }

                // Payment is deliberately last because its insert fires the
                // cloud-authoritative promotion counting trigger.
                val paymentPayloads = db.orderDao().paymentsForOrder(orderId).map { payment ->
                    mapOf(
                        "device_id" to deviceId,
                        "id" to payment.id,
                        "order_id" to payment.orderId,
                        "method" to payment.method,
                        "amount_cents" to payment.amountCents,
                        "amount_tendered_cents" to payment.amountTenderedCents,
                        "change_cents" to payment.changeCents,
                        "created_at" to payment.createdAt,
                        "payment_category" to payment.paymentCategory
                    )
                }
                if (paymentPayloads.isEmpty()) {
                    throw IllegalStateException("Payment not found for order: $orderId")
                }
                makeRequest(
                    "payment?on_conflict=device_id,id",
                    "POST",
                    gson.toJson(paymentPayloads)
                )
            }
            Unit
        }
    }

    suspend fun lookupPromotionClaim(claimCode: String): Result<PromotionClaim> = withContext(Dispatchers.IO) {
        runCatching {
            val response = rpcRequest("lookup_promotion_claim", mapOf("p_claim_code" to claimCode))
            gson.fromJson(response, PromotionClaim::class.java)
        }
    }

    suspend fun reservePromotionClaim(claimCode: String, employeeId: String): Result<PromotionClaim> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = rpcRequest(
                    "reserve_promotion_claim",
                    mapOf("p_claim_code" to claimCode, "p_device_id" to deviceId, "p_employee_id" to employeeId)
                )
                gson.fromJson(response, PromotionClaim::class.java)
            }
        }

    suspend fun releasePromotionClaim(reservationToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rpcRequest(
                "release_promotion_claim",
                mapOf("p_reservation_token" to reservationToken, "p_device_id" to deviceId)
            )
            Unit
        }
    }

    suspend fun finalizePromotionClaim(
        reservationToken: String,
        redemptionOrderId: String,
        employeeId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rpcRequest(
                "finalize_promotion_claim",
                mapOf(
                    "p_reservation_token" to reservationToken,
                    "p_redemption_order_id" to redemptionOrderId,
                    "p_device_id" to deviceId,
                    "p_employee_id" to employeeId
                )
            )
            Unit
        }
    }

    suspend fun markPromotionPrinted(awardId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rpcRequest("mark_promotion_printed", mapOf("p_award_id" to awardId, "p_device_id" to deviceId))
            Unit
        }
    }

    fun queuePromotionCheck(orderId: String) {
        val pending = pendingPromotionOrderIds().toMutableSet().apply { add(orderId) }
        promotionPrefs.edit().putStringSet("pending_order_ids", pending).apply()
    }

    fun clearPromotionCheck(orderId: String) {
        val pending = pendingPromotionOrderIds().toMutableSet().apply { remove(orderId) }
        promotionPrefs.edit().putStringSet("pending_order_ids", pending).apply()
    }

    fun pendingPromotionOrderIds(): Set<String> =
        promotionPrefs.getStringSet("pending_order_ids", emptySet())?.toSet().orEmpty()

    fun queuePromotionPrintedAward(awardId: String) {
        val pending = pendingPromotionPrintedAwardIds().toMutableSet().apply { add(awardId) }
        promotionPrefs.edit().putStringSet("pending_printed_award_ids", pending).apply()
    }

    fun clearPromotionPrintedAward(awardId: String) {
        val pending = pendingPromotionPrintedAwardIds().toMutableSet().apply { remove(awardId) }
        promotionPrefs.edit().putStringSet("pending_printed_award_ids", pending).apply()
    }

    fun pendingPromotionPrintedAwardIds(): Set<String> =
        promotionPrefs.getStringSet("pending_printed_award_ids", emptySet())?.toSet().orEmpty()

    // --- TOMBSTONE DELETE SYNC ---
    private data class CloudTombstone(
        val branchId: String,
        val entityType: String,
        val entityId: String,
        val deletedAt: String? = null,
        val deletedByDevice: String? = null
    )

    val branchId: String
        get() = prefs.getString("branch_id", "").orEmpty().ifBlank {
            UUID.nameUUIDFromBytes("coffee-pos:$renderCloudUrl:default-branch".toByteArray()).toString()
        }

    private fun isAuthoritySchemaError(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return (message.contains("sync_device_authority", true) ||
            message.contains("inventory_balance", true) ||
            message.contains("apply_inventory_event", true)) &&
            (message.contains("PGRST", true) || message.contains("schema cache", true) || message.contains("HTTP 404"))
    }

    private fun refreshAuthority(): BranchDeviceAuthority? {
        val json = makeRequest("sync_device_authority?select=*&branch_id=eq.$branchId", "GET")
        val type = object : TypeToken<List<BranchDeviceAuthority>>() {}.type
        val authority = (gson.fromJson<List<BranchDeviceAuthority>>(json, type)).firstOrNull()
        managerDeviceId = authority?.managerDeviceId.orEmpty()
        managerDeviceName = authority?.managerDeviceName.orEmpty()
        authorityRevision = authority?.revision ?: 0L
        return authority
    }

    private fun tombstoneSet(tombstones: List<CloudTombstone>): Map<String, Set<String>> {
        return tombstones
            .groupBy { it.entityType }
            .mapValues { (_, rows) -> rows.map { it.entityId }.toSet() }
    }

    private fun isTombstoned(tombstones: Map<String, Set<String>>, entityType: String, entityId: String): Boolean {
        return entityId in tombstones[entityType].orEmpty()
    }

    private suspend fun localPendingTombstones(): List<CloudTombstone> {
        return db.pendingDeleteDao().allNow().map {
            CloudTombstone(
                branchId = branchId,
                entityType = it.entityType,
                entityId = it.entityId,
                deletedByDevice = deviceId
            )
        }
    }

    private fun mergeTombstones(
        localTombstones: List<CloudTombstone>,
        cloudTombstones: List<CloudTombstone>
    ): List<CloudTombstone> {
        return (cloudTombstones + localTombstones)
            .distinctBy { it.entityType to it.entityId }
    }

    private fun isMissingSyncTombstoneError(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("sync_tombstone", ignoreCase = true) &&
            (message.contains("PGRST205", ignoreCase = true) ||
                message.contains("schema cache", ignoreCase = true) ||
                message.contains("Could not find the table", ignoreCase = true))
    }

    private fun isMissingOrderInventoryAddOnError(error: Exception): Boolean {
        val message = error.message.orEmpty()
        return message.contains("order_inventory_add_on", ignoreCase = true) &&
            (message.contains("PGRST205", ignoreCase = true) ||
                message.contains("schema cache", ignoreCase = true) ||
                message.contains("Could not find the table", ignoreCase = true))
    }

    private suspend fun uploadPendingLocalTombstones() {
        val pending = db.pendingDeleteDao().unsyncedNow()
        if (pending.isEmpty()) return

        val payload = pending.map {
            mapOf(
                "branch_id" to branchId,
                "entity_type" to it.entityType,
                "entity_id" to it.entityId,
                "deleted_by_device" to deviceId
            )
        }

        makeRequest(
            "sync_tombstone?on_conflict=branch_id,entity_type,entity_id",
            "POST",
            gson.toJson(payload),
            prefer = "resolution=ignore-duplicates"
        )
    }

    private fun downloadCloudTombstones(): List<CloudTombstone> {
        val json = makeRequest("sync_tombstone?select=*&branch_id=eq.$branchId", "GET")
        val type = object : TypeToken<List<CloudTombstone>>() {}.type
        return gson.fromJson(json, type)
    }

    private suspend fun markConfirmedPendingDeletes(cloudTombstones: List<CloudTombstone>) {
        val confirmed = cloudTombstones.map { it.entityType to it.entityId }.toSet()
        db.pendingDeleteDao().unsyncedNow()
            .filter { it.entityType to it.entityId in confirmed }
            .forEach { db.pendingDeleteDao().markSynced(it.entityType, it.entityId) }
    }

    private suspend fun applyCloudTombstones(cloudTombstones: List<CloudTombstone>) {
        val byType = tombstoneSet(cloudTombstones)
        val menuDao = db.menuDao()
        val inventoryDao = db.inventoryDao()
        val settingsDao = db.settingsDao()

        byType[SyncEntityType.MENU_ITEM_MODIFIER_GROUP].orEmpty().forEach { id ->
            SyncEntityId.splitComposite(id)?.let { (itemId, groupId) ->
                menuDao.deleteItemGroup(itemId, groupId)
            }
        }
        byType[SyncEntityType.RECIPE_INGREDIENT].orEmpty().forEach { id ->
            SyncEntityId.splitComposite(id)?.let { (itemId, ingredientId) ->
                inventoryDao.deleteRecipe(itemId, ingredientId)
            }
        }
        byType[SyncEntityType.MODIFIER_RECIPE_INGREDIENT].orEmpty().forEach { id ->
            SyncEntityId.splitComposite(id)?.let { (optionId, ingredientId) ->
                inventoryDao.deleteModifierRecipe(optionId, ingredientId)
            }
        }
        byType[SyncEntityType.MODIFIER_OPTION].orEmpty().forEach { id ->
            menuDao.deleteOption(id)
            inventoryDao.deleteModifierRecipe(id)
        }
        byType[SyncEntityType.MENU_ITEM].orEmpty().forEach { id ->
            menuDao.deleteItemGroups(id)
            inventoryDao.deleteRecipesForItem(id)
            menuDao.deleteItem(id)
        }
        byType[SyncEntityType.MODIFIER_GROUP].orEmpty().forEach { id ->
            menuDao.deleteItemGroupsForGroup(id)
            menuDao.deleteOptionsForGroup(id)
            menuDao.deleteGroup(id)
        }
        byType[SyncEntityType.MENU_CATEGORY].orEmpty().forEach { id ->
            menuDao.deleteCategory(id)
        }
        byType[SyncEntityType.INGREDIENT].orEmpty().forEach { id ->
            inventoryDao.recipesForIngredient(id).forEach { inventoryDao.deleteRecipe(it.itemId, it.ingredientId) }
            inventoryDao.modifierRecipesForIngredient(id).forEach { inventoryDao.deleteModifierRecipe(it.optionId, it.ingredientId) }
            inventoryDao.deleteIngredient(id)
            syncPrefs.edit()
                .remove("ingredient:$id")
                .remove(ingredientMetadataKey(id))
                .apply()
        }
        byType[SyncEntityType.PAYMENT_METHOD].orEmpty().forEach { id ->
            settingsDao.deletePaymentMethod(id)
        }
    }

    // --- CATALOG SYNC ---
    private suspend fun syncCatalog(cloudTombstones: List<CloudTombstone>, uploadAllowed: Boolean) {
        val tombstones = tombstoneSet(cloudTombstones)
        // Upload local updates
        if (uploadAllowed) {
        val categories = db.menuDao().categoriesNow()
            .filterNot { isTombstoned(tombstones, SyncEntityType.MENU_CATEGORY, it.id) }
        if (categories.isNotEmpty()) {
            makeRequest("menu_category?on_conflict=id", "POST", gson.toJson(categories))
        }

        val items = db.menuDao().itemsNow()
            .filterNot { isTombstoned(tombstones, SyncEntityType.MENU_ITEM, it.id) }
        if (items.isNotEmpty()) {
            makeRequest("menu_item?on_conflict=id", "POST", gson.toJson(items))
        }

        val groups = db.menuDao().modifierGroupsNow()
            .filterNot { isTombstoned(tombstones, SyncEntityType.MODIFIER_GROUP, it.id) }
        if (groups.isNotEmpty()) {
            makeRequest("modifier_group?on_conflict=id", "POST", gson.toJson(groups))
        }

        val options = db.menuDao().modifierOptionsNow()
            .filterNot { isTombstoned(tombstones, SyncEntityType.MODIFIER_OPTION, it.id) }
        if (options.isNotEmpty()) {
            makeRequest("modifier_option?on_conflict=id", "POST", gson.toJson(options))
        }

        val itemGroups = db.menuDao().itemGroupsNow()
            .filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.MENU_ITEM_MODIFIER_GROUP,
                    SyncEntityId.menuItemModifierGroup(it.itemId, it.groupId)
                )
            }
        if (itemGroups.isNotEmpty()) {
            makeRequest("menu_item_modifier_group?on_conflict=item_id,group_id", "POST", gson.toJson(itemGroups))
        }

        val ingredients = db.inventoryDao().ingredientsNow()
        val uploadIngredients = ingredients.filter { ing ->
            val lastSyncedMetadata = lastSyncedIngredientMetadataJson(ing.id)
            val currentMetadata = ingredientMetadataJson(ing)
            lastSyncedMetadata != currentMetadata &&
                !isTombstoned(tombstones, SyncEntityType.INGREDIENT, ing.id)
        }
        if (uploadIngredients.isNotEmpty()) {
            makeRequest("ingredient?on_conflict=id", "POST", gson.toJson(uploadIngredients))
        }

        val recipes = db.inventoryDao().recipes()
            .filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.RECIPE_INGREDIENT,
                    SyncEntityId.recipeIngredient(it.itemId, it.ingredientId)
                ) || isTombstoned(tombstones, SyncEntityType.INGREDIENT, it.ingredientId)
            }
        if (recipes.isNotEmpty()) {
            makeRequest("recipe_ingredient?on_conflict=item_id,ingredient_id", "POST", gson.toJson(recipes))
        }

        val modifierRecipes = db.inventoryDao().modifierRecipesNow()
            .filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.MODIFIER_RECIPE_INGREDIENT,
                    SyncEntityId.modifierRecipeIngredient(it.optionId, it.ingredientId)
                ) || isTombstoned(tombstones, SyncEntityType.INGREDIENT, it.ingredientId)
            }
        if (modifierRecipes.isNotEmpty()) {
            makeRequest("modifier_recipe_ingredient?on_conflict=option_id,ingredient_id", "POST", gson.toJson(modifierRecipes))
        }

        val employees = db.employeeDao().employeesNow()
        if (employees.isNotEmpty()) {
            makeRequest("employee?on_conflict=id", "POST", gson.toJson(employees))
        }

        val settings = db.settingsDao().settingsNow()
        val lastSyncedSettings = settings?.let {
            syncPrefs.getString(storeSettingsFingerprintKey(it.id), null)
        }
        if (settings != null && storeSettingsFingerprint(settings) != lastSyncedSettings) {
            makeRequest("store_settings?on_conflict=id", "POST", gson.toJson(storeSettingsPayload(settings)))
        }
        }

        // Pull remote changes
        pullCatalog(tombstones)
    }

    private suspend fun pullCatalog(tombstones: Map<String, Set<String>>) {
        // 1. Menu Categories
        val jsonCat = makeRequest("menu_category?select=*", "GET")
        val catType = object : TypeToken<List<MenuCategory>>() {}.type
        val remoteCatAll: List<MenuCategory> = gson.fromJson(jsonCat, catType)
        val remoteCat = remoteCatAll.filterNot { isTombstoned(tombstones, SyncEntityType.MENU_CATEGORY, it.id) }
        if (remoteCat.isNotEmpty()) {
            db.menuDao().upsertCategories(remoteCat)
        }

        // 2. Menu Items
        val jsonItems = makeRequest("menu_item?select=*", "GET")
        val itemType = object : TypeToken<List<MenuItem>>() {}.type
        val remoteItemsAll: List<MenuItem> = gson.fromJson(jsonItems, itemType)
        val remoteItems = remoteItemsAll.filterNot { isTombstoned(tombstones, SyncEntityType.MENU_ITEM, it.id) }
        if (remoteItems.isNotEmpty()) {
            db.menuDao().upsertItems(remoteItems)
        }

        // 3. Modifier Groups
        val jsonGroups = makeRequest("modifier_group?select=*", "GET")
        val groupType = object : TypeToken<List<ModifierGroup>>() {}.type
        val remoteGroupsAll: List<ModifierGroup> = gson.fromJson(jsonGroups, groupType)
        val remoteGroups = remoteGroupsAll.filterNot { isTombstoned(tombstones, SyncEntityType.MODIFIER_GROUP, it.id) }
        if (remoteGroups.isNotEmpty()) {
            db.menuDao().upsertGroups(remoteGroups)
        }

        // 4. Modifier Options
        val jsonOptions = makeRequest("modifier_option?select=*", "GET")
        val optionType = object : TypeToken<List<ModifierOption>>() {}.type
        val remoteOptionsAll: List<ModifierOption> = gson.fromJson(jsonOptions, optionType)
        val remoteOptions = remoteOptionsAll.filterNot { isTombstoned(tombstones, SyncEntityType.MODIFIER_OPTION, it.id) }
        if (remoteOptions.isNotEmpty()) {
            db.menuDao().upsertOptions(remoteOptions)
        }

        // 5. MenuItemModifierGroup
        val jsonItemGroups = makeRequest("menu_item_modifier_group?select=*", "GET")
        val itemGroupType = object : TypeToken<List<MenuItemModifierGroup>>() {}.type
        val remoteItemGroupsAll: List<MenuItemModifierGroup> = gson.fromJson(jsonItemGroups, itemGroupType)
        val remoteItemGroups = remoteItemGroupsAll.filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.MENU_ITEM_MODIFIER_GROUP,
                    SyncEntityId.menuItemModifierGroup(it.itemId, it.groupId)
                )
            }
        if (remoteItemGroups.isNotEmpty()) {
            db.menuDao().upsertItemGroups(remoteItemGroups)
        }

        // 6. Ingredients
        val jsonIngredients = makeRequest("ingredient?select=*", "GET")
        val ingType = object : TypeToken<List<Ingredient>>() {}.type
        val remoteIngredientsAll: List<Ingredient> = gson.fromJson(jsonIngredients, ingType)
        val remoteIngredients = remoteIngredientsAll.filterNot { isTombstoned(tombstones, SyncEntityType.INGREDIENT, it.id) }
        if (remoteIngredients.isNotEmpty()) {
            val localIngredients = db.inventoryDao().ingredientsNow().associateBy { it.id }
            val ingredientsToSave = remoteIngredients.map { remote ->
                val local = localIngredients[remote.id]
                val lastSyncedMetadata = lastSyncedIngredientMetadataJson(remote.id)
                val preserveLocalMetadata = isManagerTablet && local != null &&
                    lastSyncedMetadata != null && ingredientMetadataJson(local) != lastSyncedMetadata
                resolveRemoteIngredient(local, remote, preserveLocalMetadata)
            }
            db.inventoryDao().upsertIngredients(ingredientsToSave)
        }

        // 7. Recipes
        val jsonRecipes = makeRequest("recipe_ingredient?select=*", "GET")
        val recType = object : TypeToken<List<RecipeIngredient>>() {}.type
        val remoteRecipesAll: List<RecipeIngredient> = gson.fromJson(jsonRecipes, recType)
        val remoteRecipes = remoteRecipesAll.filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.RECIPE_INGREDIENT,
                    SyncEntityId.recipeIngredient(it.itemId, it.ingredientId)
                ) || isTombstoned(tombstones, SyncEntityType.INGREDIENT, it.ingredientId)
            }
        if (remoteRecipes.isNotEmpty()) {
            db.inventoryDao().upsertRecipes(remoteRecipes)
        }

        // 8. Modifier Recipes
        val jsonModRecipes = makeRequest("modifier_recipe_ingredient?select=*", "GET")
        val modRecType = object : TypeToken<List<ModifierRecipeIngredient>>() {}.type
        val remoteModRecipesAll: List<ModifierRecipeIngredient> = gson.fromJson(jsonModRecipes, modRecType)
        val remoteModRecipes = remoteModRecipesAll.filterNot {
                isTombstoned(
                    tombstones,
                    SyncEntityType.MODIFIER_RECIPE_INGREDIENT,
                    SyncEntityId.modifierRecipeIngredient(it.optionId, it.ingredientId)
                ) || isTombstoned(tombstones, SyncEntityType.INGREDIENT, it.ingredientId)
            }
        if (remoteModRecipes.isNotEmpty()) {
            db.inventoryDao().upsertModifierRecipes(remoteModRecipes)
        }

        // 9. Payment Methods
        val jsonPaymentMethods = makeRequest("payment_method?select=*", "GET")
        val paymentMethodType = object : TypeToken<List<PaymentMethod>>() {}.type
        val remotePaymentMethodsAll: List<PaymentMethod> = gson.fromJson(jsonPaymentMethods, paymentMethodType)
        val remotePaymentMethods = remotePaymentMethodsAll.filterNot { isTombstoned(tombstones, SyncEntityType.PAYMENT_METHOD, it.id) }
        if (remotePaymentMethods.isNotEmpty()) {
            db.settingsDao().upsertPaymentMethods(remotePaymentMethods)
        }

        // 10. Website-managed discount rules
        val jsonDiscountRules = makeRequest("discount_rule?select=*&order=sort_order.asc", "GET")
        val discountRuleType = object : TypeToken<List<DiscountRule>>() {}.type
        val remoteDiscountRules: List<DiscountRule> = gson.fromJson(jsonDiscountRules, discountRuleType)
        db.settingsDao().clearDiscountRules()
        if (remoteDiscountRules.isNotEmpty()) {
            db.settingsDao().upsertDiscountRules(remoteDiscountRules)
        }

        // 11. Employees
        val jsonEmployees = makeRequest("employee?select=*", "GET")
        val empType = object : TypeToken<List<Employee>>() {}.type
        val remoteEmployees: List<Employee> = gson.fromJson(jsonEmployees, empType)
        if (remoteEmployees.isNotEmpty()) {
            db.employeeDao().upsertEmployees(remoteEmployees)
        }

        // 12. Store Settings
        val jsonSettings = makeRequest("store_settings?select=*", "GET")
        val settingsType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val remoteSettings: List<Map<String, Any?>> = gson.fromJson(jsonSettings, settingsType)
        if (remoteSettings.isNotEmpty()) {
            val localSettings = db.settingsDao().settingsNow()
            val remote = storeSettingsFromRemote(remoteSettings.first(), localSettings)
            val lastSyncedSettings = localSettings?.let {
                syncPrefs.getString(storeSettingsFingerprintKey(it.id), null)
            }
            val preserveLocalSettings = isManagerTablet && localSettings != null &&
                lastSyncedSettings != null && storeSettingsFingerprint(localSettings) != lastSyncedSettings
            db.settingsDao().upsert(resolveRemoteStoreSettings(localSettings, remote, preserveLocalSettings))
        }
    }

    private fun storeSettingsPayload(settings: StoreSettings): List<Map<String, Any>> {
        val row = mutableMapOf<String, Any>(
            "id" to settings.id,
            "store_name" to settings.storeName,
            "tax_rate_percent" to settings.taxRatePercent,
            "tip_presets" to settings.tipPresets,
            "receipt_footer" to settings.receiptFooter,
            "business_day_cutoff_minutes" to settings.businessDayCutoffMinutes,
            "business_day_settings_updated_at" to settings.businessDaySettingsUpdatedAt
        )
        return listOf(row)
    }

    private fun storeSettingsFromRemote(row: Map<String, Any?>, localSettings: StoreSettings?): StoreSettings {
        fun stringValue(key: String, fallback: String): String {
            return (row[key] as? String) ?: fallback
        }

        fun doubleValue(key: String, fallback: Double): Double {
            return (row[key] as? Number)?.toDouble() ?: fallback
        }

        return StoreSettings(
            id = stringValue("id", localSettings?.id ?: "store"),
            storeName = stringValue("store_name", localSettings?.storeName ?: "Kanlungan"),
            taxRatePercent = doubleValue("tax_rate_percent", localSettings?.taxRatePercent ?: 8.25),
            tipPresets = stringValue("tip_presets", localSettings?.tipPresets ?: "10,15,20"),
            receiptFooter = stringValue("receipt_footer", localSettings?.receiptFooter ?: "Thanks for visiting Kanlungan."),
            seniorDiscountPercent = doubleValue("senior_discount_percent", localSettings?.seniorDiscountPercent ?: 20.0),
            pwdDiscountPercent = doubleValue("pwd_discount_percent", localSettings?.pwdDiscountPercent ?: 20.0),
            discountSettingsUpdatedAt = (row["discount_settings_updated_at"] as? Number)?.toLong()
                ?: localSettings?.discountSettingsUpdatedAt
                ?: 0,
            voidRefundPin = stringValue("void_refund_pin", localSettings?.voidRefundPin ?: "1234")
                .ifBlank { localSettings?.voidRefundPin?.takeIf { it.isNotBlank() } ?: "1234" },
            paymentVoidSettingsUpdatedAt = (row["payment_void_settings_updated_at"] as? Number)?.toLong()
                ?: localSettings?.paymentVoidSettingsUpdatedAt
                ?: 0,
            businessDayCutoffMinutes = (row["business_day_cutoff_minutes"] as? Number)?.toInt()
                ?: localSettings?.businessDayCutoffMinutes
                ?: DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES,
            businessDaySettingsUpdatedAt = (row["business_day_settings_updated_at"] as? Number)?.toLong()
                ?: localSettings?.businessDaySettingsUpdatedAt
                ?: 0
        )
    }

    // --- SHIFT SYNC ---
    private fun getLocalMappedShiftId(remoteDeviceId: String, remoteShiftId: Long): Long? {
        val key = "$remoteDeviceId:$remoteShiftId"
        val localId = mappingPrefs.getLong(key, -1L)
        return if (localId == -1L) null else localId
    }

    private fun saveShiftMapping(remoteDeviceId: String, remoteShiftId: Long, localShiftId: Long) {
        val key = "$remoteDeviceId:$remoteShiftId"
        mappingPrefs.edit().putLong(key, localShiftId).apply()
    }

    private fun getShiftSource(localShiftId: Long): Pair<String, Long> {
        for ((key, value) in mappingPrefs.all) {
            val mappedId = (value as? Number)?.toLong()
            if (mappedId == localShiftId) {
                val parts = key.split(":")
                if (parts.size == 2) {
                    val remoteDeviceId = parts[0]
                    val remoteShiftId = parts[1].toLongOrNull()
                    if (remoteShiftId != null) {
                        return Pair(remoteDeviceId, remoteShiftId)
                    }
                }
            }
        }
        return Pair(deviceId, localShiftId)
    }

    private fun isMappedRemoteShift(localShiftId: Long): Boolean {
        return mappingPrefs.all.values.any { (it as? Number)?.toLong() == localShiftId }
    }

    private fun shiftPayload(shift: Shift, source: Pair<String, Long>): Map<String, Any?> {
        return mapOf(
            "device_id" to source.first,
            "id" to source.second,
            "employee_id" to shift.employeeId,
            "opened_at" to shift.openedAt,
            "closed_at" to shift.closedAt,
            "starting_cash_cents" to shift.startingCashCents,
            "ending_cash_cents" to shift.endingCashCents,
            "cash_added_cents" to shift.cashAddedCents,
            "cash_removed_cents" to shift.cashRemovedCents
        )
    }

    private fun shiftSyncKey(source: Pair<String, Long>): String {
        return "shift:${source.first}:${source.second}"
    }

    private fun markShiftSynced(shift: Shift, source: Pair<String, Long>) {
        syncPrefs.edit().putString(shiftSyncKey(source), gson.toJson(shiftPayload(shift, source))).apply()
    }

    private fun isDownloadedRemoteOrder(orderId: String): Boolean {
        return syncPrefs.getBoolean("remote_order:$orderId", false)
    }

    private fun markDownloadedRemoteOrder(orderId: String) {
        syncPrefs.edit().putBoolean("remote_order:$orderId", true).apply()
    }

    private suspend fun uploadShifts() {
        val shifts = db.shiftDao().allShiftsNow()
        val shiftsToUpload = shifts.filter { shift ->
            val source = getShiftSource(shift.id)
            val payloadJson = gson.toJson(shiftPayload(shift, source))
            val lastSynced = syncPrefs.getString(shiftSyncKey(source), null)
            if (isMappedRemoteShift(shift.id) && lastSynced == null) {
                markShiftSynced(shift, source)
                false
            } else {
                !isMappedRemoteShift(shift.id) || lastSynced != payloadJson
            }
        }
        val uploadList = shiftsToUpload.map { s ->
            val source = getShiftSource(s.id)
            shiftPayload(s, source)
        }
        if (uploadList.isNotEmpty()) {
            makeRequest("shift?on_conflict=device_id,id", "POST", gson.toJson(uploadList))
            shiftsToUpload.forEach { shift ->
                markShiftSynced(shift, getShiftSource(shift.id))
            }
        }
    }

    private suspend fun downloadShifts() {
        val cutoff = db.settingsDao().settingsNow()?.businessDayCutoffMinutes
            ?: DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES
        val window = businessDayWindow(cutoffMinutes = cutoff)
        val lookbackStart = minOf(window.startMs, (System.currentTimeMillis() - RECENT_TRANSACTION_LOOKBACK_MS).coerceAtLeast(0L))
        val jsonShifts = makeRequest("shift?select=*&device_id=neq.$deviceId&opened_at=gte.$lookbackStart", "GET")
        val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val remoteShifts: List<Map<String, Any>> = gson.fromJson(jsonShifts, listType)

        for (remoteShift in remoteShifts) {
            val rDeviceId = remoteShift["device_id"] as String
            val rId = remoteLong(remoteShift["id"], "shift.id")
            val employeeId = remoteShift["employee_id"] as String
            val openedAt = remoteLong(remoteShift["opened_at"], "shift.opened_at")
            val closedAt = remoteLongOrNull(remoteShift["closed_at"], "shift.closed_at")
            val startingCashCents = remoteInt(remoteShift["starting_cash_cents"], "shift.starting_cash_cents")
            val endingCashCents = remoteIntOrNull(remoteShift["ending_cash_cents"], "shift.ending_cash_cents")
            val cashAddedCents = remoteInt(remoteShift["cash_added_cents"], "shift.cash_added_cents")
            val cashRemovedCents = remoteInt(remoteShift["cash_removed_cents"], "shift.cash_removed_cents")
            val remoteShiftRow = Shift(
                id = rId,
                employeeId = employeeId,
                openedAt = openedAt,
                closedAt = closedAt,
                startingCashCents = startingCashCents,
                endingCashCents = endingCashCents,
                cashAddedCents = cashAddedCents,
                cashRemovedCents = cashRemovedCents
            )
            var localId = getLocalMappedShiftId(rDeviceId, rId)
            if (localId == null) {
                // Shift does not exist locally. Insert a new shift record.
                val newShift = remoteShiftRow.copy(id = 0L)
                localId = db.shiftDao().openShift(newShift)
                saveShiftMapping(rDeviceId, rId, localId)
                markShiftSynced(newShift.copy(id = localId), Pair(rDeviceId, rId))
            } else {
                // Shift exists. Preserve locally accumulated cash movements
                // when the downloaded copy is older than the local row.
                val localShift = db.shiftDao().getShiftByIdNow(localId)
                val updatedShift = mergeRemoteShiftTotals(localShift, remoteShiftRow.copy(id = localId))
                db.shiftDao().updateShift(updatedShift)
                // Keep the remote payload as the baseline. If local totals
                // were preserved, uploadShifts will publish the merged row.
                markShiftSynced(remoteShiftRow.copy(id = localId), Pair(rDeviceId, rId))
            }
        }
    }

    // --- TRANSACTION SYNC ---
    private suspend fun uploadTransactions() {
        // 1. Orders
        val orders = db.orderDao().ordersNow()
        val localOrders = orders.filter { !isDownloadedRemoteOrder(it.id) || it.status == "void" || it.status == "refunded" }
        val uploadOrders = localOrders.map { order ->
            posOrderUploadPayload(order, getShiftSource(order.shiftId))
        }
        if (uploadOrders.isNotEmpty()) {
            makeRequest("pos_order?on_conflict=id", "POST", gson.toJson(uploadOrders))
        }

        // 2. Order Lines
        val lines = db.orderDao().orderLinesNow()
        val ordersMap = orders.associateBy { it.id }
        val locallyCreatedOrderIds = orders
            .filter { !isDownloadedRemoteOrder(it.id) }
            .map { it.id }
            .toSet()
        val localLines = lines.filter { l ->
            l.orderId in locallyCreatedOrderIds
        }
        val legacyUploadLines = localLines.map { l ->
            mapOf(
                "device_id" to deviceId,
                "id" to l.id,
                "order_id" to l.orderId,
                "item_id" to l.itemId,
                "name" to l.name,
                "quantity" to l.quantity,
                "unit_price_cents" to l.unitPriceCents,
                "modifiers" to l.modifiers,
                "notes" to l.notes
            )
        }
        val uploadLines = localLines.mapIndexed { index, line ->
            legacyUploadLines[index] + mapOf(
                "discount_category" to line.discountCategory,
                "discount_cents" to line.discountCents
            )
        }
        if (uploadLines.isNotEmpty()) {
            try {
                makeRequest("order_line?on_conflict=device_id,id", "POST", gson.toJson(uploadLines))
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                val missingDiscountColumns = message.contains("PGRST204") &&
                    (message.contains("discount_category") || message.contains("discount_cents"))
                if (!missingDiscountColumns) throw error
                Log.w(TAG, "Remote order_line is missing item discount columns; retrying transaction sync without them.")
                makeRequest("order_line?on_conflict=device_id,id", "POST", gson.toJson(legacyUploadLines))
            }
        }

        // 3. Payments
        val payments = db.orderDao().paymentsNow()
        val localPayments = payments.filter { p ->
            p.orderId in locallyCreatedOrderIds
        }
        val uploadPayments = localPayments.map { p ->
            mapOf(
                "device_id" to deviceId,
                "id" to p.id,
                "order_id" to p.orderId,
                "method" to p.method,
                "amount_cents" to p.amountCents,
                "amount_tendered_cents" to p.amountTenderedCents,
                "change_cents" to p.changeCents,
                "created_at" to p.createdAt,
                "payment_category" to p.paymentCategory
            )
        }
        if (uploadPayments.isNotEmpty()) {
            makeRequest("payment?on_conflict=device_id,id", "POST", gson.toJson(uploadPayments))
        }

        // 4. Receipts
        val receipts = db.orderDao().receiptsNow()
        val localReceipts = receipts.filter { r ->
            r.orderId in locallyCreatedOrderIds
        }
        if (localReceipts.isNotEmpty()) {
            makeRequest("receipt?on_conflict=order_id", "POST", gson.toJson(localReceipts))
        }

        // 5. Stock Snapshots
        val snapshots = db.stockSnapshotDao().snapshotsNow()
        val uploadSnapshots = snapshots.map { s ->
            val source = getShiftSource(s.shiftId)
            mapOf(
                "device_id" to source.first,
                "shift_id" to source.second,
                "ingredient_id" to s.ingredientId,
                "quantity" to s.quantity
            )
        }
        if (uploadSnapshots.isNotEmpty()) {
            makeRequest("stock_snapshot?on_conflict=device_id,shift_id,ingredient_id", "POST", gson.toJson(uploadSnapshots))
        }

        // Inventory adjustments use the idempotent shared ledger below.
    }

    private suspend fun syncInventoryLedger() {
        val inventoryDao = db.inventoryDao()
        inventoryDao.unsyncedAdjustmentsNow().forEach { adjustment ->
            rpcRequest(
                "apply_inventory_event",
                mapOf(
                    "p_event_id" to adjustment.eventId,
                    "p_branch_id" to branchId,
                    "p_device_id" to deviceId,
                    "p_ingredient_id" to adjustment.ingredientId,
                    "p_delta_quantity" to adjustment.deltaQuantity,
                    "p_reason" to adjustment.reason,
                    "p_created_at" to adjustment.createdAt
                )
            )
            inventoryDao.markAdjustmentSynced(adjustment.eventId)
        }
        if (isManagerTablet) seedMissingInventoryBalances()
        pullInventoryBalances()
    }

    private suspend fun seedMissingInventoryBalances() {
        val ingredients = db.inventoryDao().ingredientsNow()
        if (ingredients.isEmpty()) return
        val rows = ingredients.map {
            mapOf(
                "branch_id" to branchId,
                "ingredient_id" to it.id,
                "quantity" to it.quantityOnHand,
                "updated_at" to System.currentTimeMillis()
            )
        }
        makeRequest(
            "inventory_balance?on_conflict=branch_id,ingredient_id",
            "POST",
            gson.toJson(rows),
            prefer = "resolution=ignore-duplicates"
        )
    }

    private suspend fun pullInventoryBalances() {
        val json = makeRequest("inventory_balance?select=ingredient_id,quantity&branch_id=eq.$branchId", "GET")
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val rows: List<Map<String, Any?>> = gson.fromJson(json, type)
        rows.forEach { row ->
            val ingredientId = row["ingredient_id"] as? String ?: return@forEach
            val quantity = (row["quantity"] as? Number)?.toDouble() ?: return@forEach
            db.inventoryDao().setQuantity(ingredientId, quantity)
        }
    }

    private suspend fun syncOrderInventoryAddOns() {
        val inventoryDao = db.inventoryDao()
        val localRows = inventoryDao.orderInventoryAddOnsNow()
        if (localRows.isNotEmpty()) {
            val payload = localRows.map(::orderInventoryAddOnPayload)
            makeRequest("order_inventory_add_on?on_conflict=id", "POST", gson.toJson(payload))
        }

        val path = if (lastSyncTime > 0) {
            val changedSince = (lastSyncTime - RECENT_TRANSACTION_LOOKBACK_MS).coerceAtLeast(0L)
            "order_inventory_add_on?select=*&updated_at=gte.$changedSince"
        } else {
            "order_inventory_add_on?select=*"
        }
        val response = makeRequest(path, "GET")
        val rowType = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val remoteRows: List<Map<String, Any?>> = gson.fromJson(response, rowType)
        if (remoteRows.isEmpty()) return

        val localById = inventoryDao.orderInventoryAddOnsNow().associateBy { it.id }
        val mergedRows = remoteRows.map { remote ->
            val id = remote["id"] as String
            val local = localById[id]
            val remoteRestoredAt = (remote["restored_at"] as? Number)?.toLong()
            val restoredAt = listOfNotNull(local?.restoredAt, remoteRestoredAt).maxOrNull()
            OrderInventoryAddOn(
                id = id,
                orderId = remote["order_id"] as String,
                ingredientId = remote["ingredient_id"] as String,
                quantity = (remote["quantity"] as Number).toDouble(),
                createdAt = (remote["created_at"] as Number).toLong(),
                restoredAt = restoredAt,
                updatedAt = maxOf(
                    local?.updatedAt ?: 0L,
                    (remote["updated_at"] as Number).toLong()
                ),
                localAdjustmentId = local?.localAdjustmentId
            )
        }
        inventoryDao.upsertOrderInventoryAddOns(mergedRows)
    }

    private suspend fun downloadTransactions() {
        val now = System.currentTimeMillis()
        val openShiftStart = db.shiftDao()
            .allShiftsNow()
            .filter { it.closedAt == null }
            .minOfOrNull { it.openedAt }
        val defaultLookbackStart = (now - RECENT_TRANSACTION_LOOKBACK_MS).coerceAtLeast(0L)
        val lookbackStart = if (lastSyncTime > 0) {
            (lastSyncTime - RECENT_TRANSACTION_LOOKBACK_MS).coerceAtLeast(0L)
        } else {
            minOf(openShiftStart ?: defaultLookbackStart, defaultLookbackStart)
        }
        val jsonOrders = makeRequest(
            "pos_order?select=*&created_at=gte.$lookbackStart",
            "GET"
        )
        val orderListType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val remoteOrders: List<Map<String, Any>> = gson.fromJson(jsonOrders, orderListType)

        for (rOrder in remoteOrders) {
            val oId = rOrder["id"] as String
            val status = rOrder["status"] as String
            val employeeId = rOrder["employee_id"] as String
            val rShiftId = remoteLong(rOrder["shift_id"], "pos_order.shift_id")
            val rShiftDeviceId = rOrder["shift_device_id"] as String
            val subtotalCents = remoteInt(rOrder["subtotal_cents"], "pos_order.subtotal_cents")
            val discountCents = remoteInt(rOrder["discount_cents"], "pos_order.discount_cents")
            val taxCents = remoteInt(rOrder["tax_cents"], "pos_order.tax_cents")
            val tipCents = remoteInt(rOrder["tip_cents"], "pos_order.tip_cents")
            val totalCents = remoteInt(rOrder["total_cents"], "pos_order.total_cents")
            val createdAt = remoteLong(rOrder["created_at"], "pos_order.created_at")
            val paidAt = remoteLongOrNull(rOrder["paid_at"], "pos_order.paid_at")
            val voidReason = rOrder["void_reason"] as? String
            val customerName = rOrder["customer_name"] as? String
            val tableNumber = rOrder["table_number"] as? String
            val orderType = rOrder["order_type"] as? String ?: "Dine-In"

            var localShiftId = if (rShiftDeviceId == deviceId) {
                rShiftId
            } else {
                getLocalMappedShiftId(rShiftDeviceId, rShiftId)
            }

            if (localShiftId == null) {
                val placeholderShift = Shift(
                    employeeId = employeeId,
                    openedAt = createdAt,
                    closedAt = null,
                    startingCashCents = 0,
                    endingCashCents = null
                )
                localShiftId = db.shiftDao().openShift(placeholderShift)
                saveShiftMapping(rShiftDeviceId, rShiftId, localShiftId)
            }

            val remoteOrder = PosOrder(
                id = oId,
                status = status,
                employeeId = employeeId,
                shiftId = localShiftId,
                subtotalCents = subtotalCents,
                discountCents = discountCents,
                discountRuleId = rOrder["discount_rule_id"] as? String,
                discountCategory = rOrder["discount_category"] as? String,
                discountPercent = remoteDoubleOrNull(rOrder["discount_percent"], "pos_order.discount_percent"),
                discountScope = rOrder["discount_scope"] as? String,
                discountReference = rOrder["discount_reference"] as? String,
                taxCents = taxCents,
                tipCents = tipCents,
                totalCents = totalCents,
                createdAt = createdAt,
                paidAt = paidAt,
                voidReason = voidReason,
                customerName = customerName,
                tableNumber = tableNumber,
                orderType = orderType
            )

            val localOrder = db.orderDao().orderNow(oId)
            if (localOrder == null) {
                db.orderDao().insertOrder(remoteOrder)
                markDownloadedRemoteOrder(oId)
                downloadOrderLines(oId)
                downloadPayments(oId)
                downloadReceipt(oId)
            } else if (localOrder.status != status) {
                val isLocalTerminal = localOrder.status == "void" || localOrder.status == "refunded"
                val isRemoteTerminal = status == "void" || status == "refunded"
                if (!isLocalTerminal && isRemoteTerminal) {
                    db.orderDao().insertOrder(remoteOrder)
                    markDownloadedRemoteOrder(oId)
                    downloadPayments(oId)
                    downloadReceipt(oId)
                } else if (shouldApplyRemoteOrderTypeCorrection(
                        isDownloadedRemoteOrder = isDownloadedRemoteOrder(oId),
                        localOrderType = localOrder.orderType,
                        remoteOrderType = orderType
                    )
                ) {
                    db.orderDao().insertOrder(localOrder.copy(orderType = orderType))
                }
            } else if (shouldApplyRemoteOrderTypeCorrection(
                    isDownloadedRemoteOrder = isDownloadedRemoteOrder(oId),
                    localOrderType = localOrder.orderType,
                    remoteOrderType = orderType
                )
            ) {
                db.orderDao().insertOrder(localOrder.copy(orderType = orderType))
            }
        }
    }

    private suspend fun downloadOrderLines(orderId: String) {
        val jsonLines = makeRequest("order_line?select=*&order_id=eq.$orderId", "GET")
        val lineListType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val remoteLines: List<Map<String, Any>> = gson.fromJson(jsonLines, lineListType)
        
        val localLines = remoteLines.map { rl ->
            OrderLine(
                orderId = orderId,
                itemId = rl["item_id"] as String,
                name = rl["name"] as String,
                quantity = remoteInt(rl["quantity"], "order_line.quantity"),
                unitPriceCents = remoteInt(rl["unit_price_cents"], "order_line.unit_price_cents"),
                modifiers = rl["modifiers"] as String,
                notes = rl["notes"] as String,
                discountCategory = rl["discount_category"] as? String,
                discountCents = remoteIntOrNull(rl["discount_cents"], "order_line.discount_cents") ?: 0
            )
        }.distinctBy { line ->
            listOf(
                line.orderId,
                line.itemId,
                line.name,
                line.quantity,
                line.unitPriceCents,
                line.modifiers,
                line.notes,
                line.discountCategory,
                line.discountCents
            )
        }
        db.orderDao().deleteOrderLinesForOrder(orderId)
        if (localLines.isNotEmpty()) {
            db.orderDao().insertLines(localLines)
        }
    }

    private suspend fun downloadPayments(orderId: String) {
        val jsonPayments = makeRequest("payment?select=*&order_id=eq.$orderId", "GET")
        val paymentListType = object : TypeToken<List<Map<String, Any>>>() {}.type
        val remotePayments: List<Map<String, Any>> = gson.fromJson(jsonPayments, paymentListType)

        val localPayments = remotePayments.map { rp ->
            Payment(
                orderId = orderId,
                method = rp["method"] as String,
                amountCents = remoteInt(rp["amount_cents"], "payment.amount_cents"),
                amountTenderedCents = remoteInt(rp["amount_tendered_cents"], "payment.amount_tendered_cents"),
                changeCents = remoteInt(rp["change_cents"], "payment.change_cents"),
                createdAt = remoteLong(rp["created_at"], "payment.created_at"),
                paymentCategory = (rp["payment_category"] as? String)
                    ?: PaymentCategories.fromLegacyMethod(rp["method"] as String)
            )
        }.distinctBy { payment ->
            listOf(payment.orderId, payment.method, payment.amountCents, payment.amountTenderedCents, payment.changeCents, payment.createdAt)
        }
        db.orderDao().deletePaymentsForOrder(orderId)
        localPayments.forEach { payment ->
            db.orderDao().insertPayment(payment)
        }
    }

    private suspend fun downloadReceipt(orderId: String) {
        try {
            val jsonReceipt = makeRequest("receipt?select=*&order_id=eq.$orderId", "GET")
            val receiptListType = object : TypeToken<List<Receipt>>() {}.type
            val remoteReceipts: List<Receipt> = gson.fromJson(jsonReceipt, receiptListType)
            if (remoteReceipts.isNotEmpty()) {
                db.orderDao().insertReceipt(remoteReceipts.first())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download receipt for order $orderId", e)
        }
    }

    private companion object {
        const val SYNC_POLL_INTERVAL_MS = 5000L
        const val MAX_RETRY_INTERVAL_MS = 60_000L
        const val RECENT_TRANSACTION_LOOKBACK_MS = 48L * 60L * 60L * 1000L
    }
}

private object ResilientDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (systemFailure: UnknownHostException) {
            val fallbackAddresses = PUBLIC_DNS_SERVERS
                .asSequence()
                .flatMap { server ->
                    runCatching { queryARecords(hostname, server) }
                        .getOrElse { emptyList() }
                        .asSequence()
                }
                .distinctBy { it.hostAddress }
                .toList()

            if (fallbackAddresses.isNotEmpty()) {
                fallbackAddresses
            } else {
                throw systemFailure
            }
        }
    }

    private fun queryARecords(hostname: String, dnsServer: String): List<InetAddress> {
        val queryId = (System.nanoTime() and 0xffff).toInt()
        val query = buildDnsQuery(hostname, queryId)
        DatagramSocket().use { socket ->
            socket.soTimeout = 2500
            val server = InetAddress.getByName(dnsServer)
            socket.send(DatagramPacket(query, query.size, server, 53))

            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return parseDnsARecords(hostname, buffer.copyOf(response.length), queryId)
        }
    }

    private fun buildDnsQuery(hostname: String, queryId: Int): ByteArray {
        return ByteArrayOutputStream().use { out ->
            out.write((queryId shr 8) and 0xff)
            out.write(queryId and 0xff)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            hostname.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x01)
            out.toByteArray()
        }
    }

    private fun parseDnsARecords(hostname: String, response: ByteArray, queryId: Int): List<InetAddress> {
        if (response.size < 12) return emptyList()
        val responseId = readU16(response, 0)
        if (responseId != queryId) return emptyList()
        val answerCount = readU16(response, 6)

        var offset = 12
        offset = skipDnsName(response, offset)
        if (offset + 4 > response.size) return emptyList()
        offset += 4

        val results = mutableListOf<InetAddress>()
        repeat(answerCount) {
            offset = skipDnsName(response, offset)
            if (offset + 10 > response.size) return@repeat
            val type = readU16(response, offset)
            val clazz = readU16(response, offset + 2)
            val dataLength = readU16(response, offset + 8)
            offset += 10
            if (offset + dataLength > response.size) return@repeat
            if (type == 1 && clazz == 1 && dataLength == 4) {
                val addressBytes = response.copyOfRange(offset, offset + 4)
                results += InetAddress.getByAddress(hostname, addressBytes)
            }
            offset += dataLength
        }
        return results
    }

    private fun skipDnsName(packet: ByteArray, startOffset: Int): Int {
        var offset = startOffset
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xff
            if (length == 0) return offset + 1
            if ((length and 0xc0) == 0xc0) return offset + 2
            offset += length + 1
        }
        return packet.size
    }

    private fun readU16(packet: ByteArray, offset: Int): Int {
        if (offset + 1 >= packet.size) return 0
        return ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
    }
}
