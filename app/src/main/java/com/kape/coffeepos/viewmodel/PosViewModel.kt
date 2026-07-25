package com.kape.coffeepos.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kape.coffeepos.AppContainer
import com.kape.coffeepos.data.CartLine
import com.kape.coffeepos.data.DailyReport
import com.kape.coffeepos.data.DiscountRule
import com.kape.coffeepos.data.ReportDateRange
import com.kape.coffeepos.data.Employee
import com.kape.coffeepos.data.Ingredient
import com.kape.coffeepos.data.ItemDiscountSelection
import com.kape.coffeepos.data.MenuCatalog
import com.kape.coffeepos.data.MenuCategory
import com.kape.coffeepos.data.MenuItem
import com.kape.coffeepos.data.ModifierGroup
import com.kape.coffeepos.data.ModifierOption
import com.kape.coffeepos.data.ModifierRecipeIngredient
import com.kape.coffeepos.data.OrderRepository
import com.kape.coffeepos.data.ClosedShiftAdjustment
import com.kape.coffeepos.data.PosOrder
import com.kape.coffeepos.data.RecipeIngredient
import com.kape.coffeepos.data.Shift
import com.kape.coffeepos.data.StoreSettings
import com.kape.coffeepos.data.PaymentMethod
import com.kape.coffeepos.data.Payment
import com.kape.coffeepos.data.PaymentCategories
import com.kape.coffeepos.data.PromotionClaim
import com.kape.coffeepos.data.PromotionConfig
import com.kape.coffeepos.data.PromotionResult
import com.kape.coffeepos.data.calculateSingleItemDiscountCents
import com.kape.coffeepos.data.calculateWholeOrderDiscountCents
import com.kape.coffeepos.data.customReportRangeError
import com.kape.coffeepos.data.normalizeIngredientId
import com.kape.coffeepos.printer.DEFAULT_WINDOWS_BRIDGE_PRINT_URL
import com.kape.coffeepos.printer.PRINTER_INTERFACE_BLUETOOTH
import com.kape.coffeepos.printer.PRINTER_INTERFACE_WINDOWS_BRIDGE
import com.kape.coffeepos.printer.PrinterDevice
import com.kape.coffeepos.printer.PrinterProfile
import com.kape.coffeepos.printer.buildPromotionTestQrUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class AppScreen { POS, ORDERS, INVENTORY, REPORTS, DEVICES, SETTINGS, MENU, MANAGER, DRAWER }
enum class ReceiptPromotionState { NONE, CHECKING, READY, RETRY_REQUIRED }
enum class ReceiptCopyStage { FIRST_COPY, SECOND_COPY }
internal const val RECEIPT_PREPARING_LABEL = "Preparing receipt..."
internal const val RECEIPT_PREPARATION_ERROR =
    "Could not prepare receipt. Check the connection and tap Retry & Print."
internal const val SECOND_RECEIPT_DELAY_SECONDS = 5
internal fun secondReceiptCountdownValues(): List<Int> =
    (SECOND_RECEIPT_DELAY_SECONDS downTo 1).toList()
internal fun shouldKickDrawerForReceiptCopy(copyNumber: Int): Boolean = copyNumber == 1

internal fun orderPaymentCategoryLabel(payments: List<Payment>): String? {
    val categories = payments.mapNotNull { payment ->
        payment.paymentCategory ?: PaymentCategories.fromLegacyMethod(payment.method)
    }.toSet()
    return listOf(PaymentCategories.CASH, PaymentCategories.ONLINE)
        .filter { it in categories }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" + ")
}

internal fun terminalAuditReceiptText(
    receiptText: String,
    status: String,
    reason: String?
): String {
    val label = when (status.lowercase(Locale.US)) {
        "void" -> "VOIDED ORDER - AUDIT COPY"
        "refunded" -> "REFUNDED ORDER - AUDIT COPY"
        else -> return receiptText
    }
    val safeReason = reason?.trim().orEmpty()
    return buildString {
        appendLine("********************************")
        appendLine(label)
        if (safeReason.isNotBlank()) appendLine("Reason: $safeReason")
        appendLine("********************************")
        append(receiptText)
    }
}

internal fun discountSettingsValidationError(senior: String, pwd: String): String? {
    fun validate(label: String, value: String): String? {
        if (value.isBlank()) return "$label discount is required."
        val percent = value.toDoubleOrNull() ?: return "$label discount must be a number."
        if (percent !in 0.0..100.0) return "$label discount must be between 0 and 100."
        return null
    }
    return validate("Senior", senior) ?: validate("PWD", pwd)
}

internal fun safeReenrollmentCodeError(value: String): String? {
    val code = value.trim()
    if (code.isBlank()) return "Enter the re-enrollment code from the admin website."
    if (!code.matches(Regex("""[A-Za-z0-9_-]{6,32}"""))) {
        return "Enter the complete re-enrollment code exactly as shown on the website."
    }
    return null
}

internal fun promotionReceiptText(result: PromotionResult): String = buildString {
    appendLine("CONGRATULATIONS!")
    appendLine("FREE DRINK REWARD")
    appendLine("--------------------------------")
    appendLine("Winning order: ${result.sequenceNumber}")
    appendLine("Claim code: ${result.claimCode.orEmpty()}")
    result.expiresAt?.let { expiresAt ->
        val date = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Manila")
        }.format(Date(expiresAt))
        appendLine("Valid until: $date")
    }
    appendLine("--------------------------------")
    appendLine("Scan the QR code and follow the link.")
    appendLine("Present this claim code to staff.")
    appendLine("One base drink of your choice is free.")
    appendLine("Paid modifiers are not included.")
}

data class PosUiState(
    val catalog: MenuCatalog = MenuCatalog(),
    val employee: Employee? = null,
    val activeShift: Shift? = null,
    val screen: AppScreen = AppScreen.POS,
    val selectedCategoryId: String = "espresso",
    val cart: List<CartLine> = emptyList(),
    val heldCarts: List<List<CartLine>> = emptyList(),
    val discountCents: Int = 0,
    val discountType: String = "cash", // "cash" or "percent"
    val discountInput: String = "0",
    val tipCents: Int = 0,
    val settings: StoreSettings = StoreSettings(
        storeName = "Kanlungan",
        taxRatePercent = 8.25,
        tipPresets = "10,15,20",
        receiptFooter = "Thanks for visiting Kanlungan."
    ),
    val ingredients: List<Ingredient> = emptyList(),
    val lowStock: List<Ingredient> = emptyList(),
    val orders: List<PosOrder> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val dailyReport: DailyReport = DailyReport(),
    val reportDateRange: ReportDateRange = ReportDateRange.TODAY,
    val reportCustomStart: Long? = null,
    val reportCustomEnd: Long? = null,
    val reportRangeError: String? = null,
    val selectedReportCashierId: String? = null,
    val loginError: String? = null,
    val statusMessage: String? = null,
    val pendingItem: MenuItem? = null,
    val selectedModifiers: List<ModifierOption> = emptyList(),
    val showOrderSummary: Boolean = false,
    val paymentMethod: String = "",
    val amountPaidInput: String = "",
    val orderTypeError: String? = null,
    val paymentError: String? = null,
    val receiptText: String? = null,
    val receiptOrderId: String? = null,
    val receiptAuditStatus: String? = null,
    val receiptPromotionState: ReceiptPromotionState = ReceiptPromotionState.NONE,
    val receiptPromotionResult: PromotionResult? = null,
    val receiptCopyStage: ReceiptCopyStage = ReceiptCopyStage.FIRST_COPY,
    val receiptSecondCopyCountdown: Int? = null,
    val showCategoryEditor: Boolean = false,
    val categoryEditorId: String? = null,
    val categoryEditorName: String = "",
    val categoryEditorError: String? = null,
    val showModifierEditor: Boolean = false,
    val modifierEditorId: String? = null,
    val modifierEditorName: String = "",
    val modifierEditorError: String? = null,
    val modifierOptionEditorId: String? = null,
    val modifierOptionEditorName: String = "",
    val modifierOptionEditorPrice: String = "",
    val modifierOptionEditorError: String? = null,
    val modifierRecipes: List<ModifierRecipeIngredient> = emptyList(),
    val modifierOptionEditorIngredientId: String? = null,
    val modifierOptionEditorQty: String = "",
    val modifierOptionEditorReplacesId: String? = null,
    val showManagerAuthorityDialog: Boolean = false,
    val managerAuthorityPin: String = "",
    val managerAuthorityError: String? = null,
    val showSafeReenrollmentDialog: Boolean = false,
    val safeReenrollmentCode: String = "",
    val safeReenrollmentBusy: Boolean = false,
    val safeReenrollmentError: String? = null,
    val menuFormEditingItemId: String? = null,
    val menuFormName: String = "",
    val menuFormDescription: String = "",
    val menuFormCategoryId: String = "espresso",
    val menuFormPrice: String = "",
    val menuFormModifierGroupIds: Set<String> = emptySet(),
    val menuFormRecipeQuantities: Map<String, String> = emptyMap(),
    val menuFormComplementaryExclusions: Set<String> = emptySet(),
    val menuFormError: String? = null,
    val printerDevices: List<PrinterDevice> = emptyList(),
    val connectedPrinter: PrinterDevice? = null,
    val savedPrinterAddress: String? = null,
    val printerProfile: PrinterProfile = PrinterProfile(),
    val printerFormName: String = "POS-58",
    val printerFormModel: String = "POS-58",
    val printerFormInterface: String = PRINTER_INTERFACE_BLUETOOTH,
    val printerFormAddress: String? = null,
    val printerFormBridgeUrl: String = DEFAULT_WINDOWS_BRIDGE_PRINT_URL,
    val printerFormPaperWidthMm: Int = 58,
    val printerFormPrintReceipts: Boolean = true,
    val printerFormAutoPrintReceipts: Boolean = false,
    val printerFormKickCashDrawer: Boolean = true,
    val printerFormPesoSignStyle: String = "p",
    val printerFormLineCharacters: Int = 32,
    val printerPermissionNeeded: Boolean = false,
    val printerScanPermissionNeeded: Boolean = false,
    val printerBusy: Boolean = false,
    val printerScanning: Boolean = false,
    val printerMessage: String? = null,
    // Ingredient editor
    val showIngredientEditor: Boolean = false,
    val ingredientEditorId: String? = null,
    val ingredientEditorName: String = "",
    val ingredientEditorUnit: String = "",
    val ingredientEditorQty: String = "",
    val ingredientEditorThreshold: String = "",
    val ingredientEditorTakeoutOnly: Boolean = false,
    val ingredientEditorError: String? = null,
    val ingredientSearchQuery: String = "",
    val splitCashInput: String = "",
    val splitGCashInput: String = "",
    val showAddOnDialog: Boolean = false,
    val addOnOrderId: String? = null,
    val addOnSearchQuery: String = "",
    val addOnSelectedQuantities: Map<String, Double> = emptyMap(),
    val orderDateRange: ReportDateRange = ReportDateRange.TODAY,
    val orderCustomStart: Long? = null,
    val orderCustomEnd: Long? = null,
    // Settings form
    val settingsFormName: String = "",
    val settingsFormTaxRate: String = "",
    val settingsFormTipPresets: String = "",
    val settingsFormFooter: String = "",
    val settingsFormError: String? = null,
    // Discount settings & inputs
    val selectedDiscountCategory: String = "None",
    val seniorPwdIdInput: String = "",
    val selectedDiscountLineId: String? = null,
    val discountRules: List<DiscountRule> = emptyList(),
    val settingsFormSeniorPercent: String = "20.0",
    val settingsFormPwdPercent: String = "20.0",
    val discountSettingsError: String? = null,
    val paymentMethods: List<PaymentMethod> = emptyList(),
    // Employee management
    val allEmployees: List<Employee> = emptyList(),
    val showEmployeeEditor: Boolean = false,
    val employeeEditorId: String? = null,
    val employeeEditorName: String = "",
    val employeeEditorPin: String = "",
    val employeeEditorRole: String = "cashier", // cashier or manager
    val employeeEditorActive: Boolean = true,
    val employeeEditorError: String? = null,
    // Customer tracking
    val customerNameInput: String = "",


    val tableNumberInput: String = "",
    val orderTypeInput: String = "",
    val activeShiftCashSales: Int = 0,
    val activeShiftGCashSales: Int = 0,
    val showAddCashDialog: Boolean = false,
    val showRemoveCashDialog: Boolean = false,
    val showCloseShiftDialog: Boolean = false,
    val startingCashInput: String = "150.00",
    val cashAddedInput: String = "",
    val cashAddedReasonInput: String = "",
    val cashRemovedInput: String = "",
    val cashRemovedReasonInput: String = "",
    val cashCountedInput: String = "",
    val activeShiftAdjustments: List<ClosedShiftAdjustment> = emptyList(),
    val pendingVoidOrderId: String? = null,
    val pendingRefundOrderId: String? = null,
    val voidPinInput: String = "",
    val voidPinError: String? = null,
    val promotionConfig: PromotionConfig = PromotionConfig(available = false),
    val promotionBusy: Boolean = false,
    val promotionError: String? = null,
    val showPromotionClaimDialog: Boolean = false,
    val promotionClaimCodeInput: String = "",
    val promotionClaim: PromotionClaim? = null,
    val promotionReservationToken: String? = null,
    val promotionAppliedClaimCode: String? = null
) {
    val isManager: Boolean get() = employee?.role == "manager"
    val selectedCustomDiscount: DiscountRule?
        get() = selectedDiscountCategory.removePrefix("RULE:")
            .takeIf { selectedDiscountCategory.startsWith("RULE:") }
            ?.let { id -> discountRules.firstOrNull { it.id == id && it.active } }
    val selectedDiscountName: String
        get() = selectedCustomDiscount?.name ?: selectedDiscountCategory
    val selectedDiscountScope: String
        get() = selectedCustomDiscount?.scope ?: "item"
    val selectedDiscountRequiresReference: Boolean
        get() = selectedDiscountCategory == "Senior" || selectedDiscountCategory == "PWD" ||
            selectedCustomDiscount?.requiresReference == true
    val selectedDiscountPercent: Double
        get() = when (selectedDiscountCategory) {
            "Senior" -> settings.seniorDiscountPercent
            "PWD" -> settings.pwdDiscountPercent
            else -> selectedCustomDiscount?.percent ?: 0.0
        }
    val isReportRangeReady: Boolean get() = reportDateRange != ReportDateRange.CUSTOM || reportRangeError == null
    val totals = OrderRepository.calculateTotals(cart, discountCents, tipCents, settings.taxRatePercent)
}

class PosViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(PosUiState())
    val uiState: StateFlow<PosUiState> = _uiState.asStateFlow()

    private var reportJob: kotlinx.coroutines.Job? = null
    private var activeShiftSalesJob: kotlinx.coroutines.Job? = null
    private var activeShiftGCashSalesJob: kotlinx.coroutines.Job? = null
    private var activeShiftAdjustmentsJob: kotlinx.coroutines.Job? = null

    val canEditSharedConfiguration: Boolean
        get() = _uiState.value.isManager && container.supabaseSyncManager.isManagerTablet

    val canRemoveCash: Boolean
        get() = _uiState.value.isManager && container.supabaseSyncManager.isManagerTablet

    private fun requireConfigurationAuthority(area: String): Boolean {
        val state = _uiState.value
        val message = when {
            !state.isManager -> "Manager PIN required to edit $area."
            !container.supabaseSyncManager.isConfigured() -> "Configure Render Cloud and enroll this device before editing $area."
            !container.supabaseSyncManager.isManagerTablet -> "Only the designated Manager Tablet can edit $area."
            else -> return true
        }
        _uiState.update { it.copy(statusMessage = message) }
        return false
    }

    fun changeReportDateRange(range: ReportDateRange) {
        _uiState.update {
            it.copy(
                reportDateRange = range,
                reportRangeError = if (range == ReportDateRange.CUSTOM) {
                    customReportRangeError(it.reportCustomStart, it.reportCustomEnd)
                } else {
                    null
                },
                selectedReportCashierId = null
            )
        }
        triggerReportJob()
    }

    fun updateCustomReportStart(start: Long) {
        _uiState.update {
            it.copy(
                reportCustomStart = start,
                reportRangeError = customReportRangeError(start, it.reportCustomEnd)
            )
        }
        triggerReportJob()
    }

    fun updateCustomReportEnd(end: Long) {
        _uiState.update {
            it.copy(
                reportCustomEnd = end,
                reportRangeError = customReportRangeError(it.reportCustomStart, end)
            )
        }
        triggerReportJob()
    }

    fun changeReportCashierId(employeeId: String?) {
        _uiState.update { it.copy(selectedReportCashierId = employeeId) }
        triggerReportJob()
    }

    private fun triggerReportJob() {
        reportJob?.cancel()
        val state = _uiState.value
        val rangeError = if (state.reportDateRange == ReportDateRange.CUSTOM) {
            customReportRangeError(state.reportCustomStart, state.reportCustomEnd)
        } else {
            null
        }
        if (rangeError != null) {
            _uiState.update { it.copy(reportRangeError = rangeError) }
            return
        }
        reportJob = viewModelScope.launch {
            container.reportsRepository.reportFlow(
                dateRange = state.reportDateRange,
                customStart = state.reportCustomStart,
                customEnd = state.reportCustomEnd,
                cashierEmployeeId = state.selectedReportCashierId
            ).collect { report ->
                _uiState.update { it.copy(dailyReport = report) }
            }
        }
    }

    private fun selectedReportCashierName(state: PosUiState): String? {
        val employeeId = state.selectedReportCashierId ?: return null
        return state.allEmployees.firstOrNull { it.id == employeeId }?.name ?: "Unknown Cashier"
    }

    private fun isDrinkCategory(categoryId: String): Boolean {
        return categoryId in DRINK_CATEGORY_IDS
    }

    private fun sharedDrinkComplementaryExclusions(items: List<MenuItem>): Set<String> {
        return items
            .filter { isDrinkCategory(it.categoryId) }
            .flatMap { it.complementaryExclusions.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    init {
        viewModelScope.launch {
            container.seedData.ensureSeeded()
            container.shiftRepository.recoverLocalActiveShiftIfNeeded()
            container.shiftRepository.ensureTodayShift()
        }
        viewModelScope.launch {
            container.menuRepository.catalog.collect { catalog ->
                _uiState.update { it.copy(catalog = catalog) }
            }
        }
        viewModelScope.launch {
            container.shiftRepository.activeShift.collect { shift ->
                _uiState.update { it.copy(activeShift = shift) }
                activeShiftSalesJob?.cancel()
                activeShiftGCashSalesJob?.cancel()
                activeShiftAdjustmentsJob?.cancel()
                val activeId = shift?.id
                if (activeId != null) {
                    activeShiftSalesJob = viewModelScope.launch {
                        container.shiftRepository.getShiftCashSales(activeId).collect { sales ->
                            _uiState.update { it.copy(activeShiftCashSales = sales) }
                        }
                    }
                    activeShiftGCashSalesJob = viewModelScope.launch {
                        container.shiftRepository.getShiftGCashSales(activeId).collect { sales ->
                            _uiState.update { it.copy(activeShiftGCashSales = sales) }
                        }
                    }
                    activeShiftAdjustmentsJob = viewModelScope.launch {
                        container.reportsRepository.getClosedShiftAdjustmentsForShift(activeId).collect { adjs ->
                            _uiState.update { it.copy(activeShiftAdjustments = adjs) }
                        }
                    }
                } else {
                    _uiState.update { it.copy(activeShiftCashSales = 0, activeShiftGCashSales = 0, activeShiftAdjustments = emptyList()) }
                }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                if (settings != null) {
                    _uiState.update {
                        it.copy(
                            settings = settings,
                            settingsFormName = settings.storeName,
                            settingsFormTaxRate = settings.taxRatePercent.toString(),
                            settingsFormTipPresets = settings.tipPresets,
                            settingsFormFooter = settings.receiptFooter,
                            settingsFormSeniorPercent = settings.seniorDiscountPercent.toString(),
                            settingsFormPwdPercent = settings.pwdDiscountPercent.toString()
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container.inventoryRepository.ingredients.collect { rows ->
                _uiState.update { it.copy(ingredients = rows) }
            }
        }
        viewModelScope.launch {
            container.inventoryRepository.modifierRecipes.collect { rows ->
                _uiState.update { it.copy(modifierRecipes = rows) }
            }
        }
        viewModelScope.launch {
            container.reportsRepository.lowStock.collect { rows ->
                _uiState.update { it.copy(lowStock = rows) }
            }
        }
        viewModelScope.launch {
            container.orderRepository.orders.collect { rows ->
                _uiState.update { it.copy(orders = rows) }
            }
        }
        viewModelScope.launch {
            container.orderRepository.payments.collect { rows ->
                _uiState.update { it.copy(payments = rows) }
            }
        }
        viewModelScope.launch {
            container.employeeRepository.allEmployees.collect { rows ->
                _uiState.update { it.copy(allEmployees = rows) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.paymentMethods.collect { rows ->
                _uiState.update { it.copy(paymentMethods = rows) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.discountRules.collect { rows ->
                _uiState.update { state ->
                    val activeRows = rows.filter { it.active }
                    val selectedStillAvailable = !state.selectedDiscountCategory.startsWith("RULE:") ||
                        activeRows.any { "RULE:${it.id}" == state.selectedDiscountCategory }
                    if (selectedStillAvailable) {
                        state.copy(discountRules = rows)
                    } else {
                        state.copy(
                            discountRules = rows,
                            selectedDiscountCategory = "None",
                            seniorPwdIdInput = "",
                            selectedDiscountLineId = null,
                            discountCents = 0
                        )
                    }
                }
            }
        }
        changeReportDateRange(ReportDateRange.TODAY)
        refreshPrinterDevices()
        refreshPromotionConfig()
    }

    fun login(pin: String) {
        viewModelScope.launch {
            val employee = container.employeeRepository.login(pin)
            _uiState.update {
                if (employee == null) {
                    it.copy(loginError = "PIN not found. Try 1 for manager or 2 for cashier.")
                } else {
                    it.copy(employee = employee, loginError = null, statusMessage = "Signed in as ${employee.name}")
                }
            }
            if (employee != null) {
                triggerSupabaseSync()
                refreshPromotionConfig()
            }
        }
    }

    fun logout() {
        _uiState.value.promotionReservationToken?.let { token ->
            viewModelScope.launch { container.supabaseSyncManager.releasePromotionClaim(token) }
        }
        _uiState.update {
            it.copy(
                employee = null,
                cart = emptyList(),
                screen = AppScreen.POS,
                ingredientSearchQuery = "",
                splitCashInput = "",
                splitGCashInput = "",
                orderDateRange = ReportDateRange.TODAY,
                orderCustomStart = null,
                orderCustomEnd = null,
                orderTypeInput = "",
                orderTypeError = null,
                paymentMethod = "",
                amountPaidInput = "",
                paymentError = null,
                promotionReservationToken = null,
                promotionAppliedClaimCode = null,
                promotionClaim = null
            )
        }
    }

    fun selectScreen(screen: AppScreen) {
        val state = _uiState.value
        if ((screen == AppScreen.SETTINGS || screen == AppScreen.MENU || screen == AppScreen.MANAGER || screen == AppScreen.REPORTS || screen == AppScreen.INVENTORY || screen == AppScreen.DEVICES) && !state.isManager) {
            _uiState.update { it.copy(statusMessage = "Manager PIN required for ${screen.name.lowercase()} access.") }
            return
        }
        _uiState.update {
            it.copy(
                screen = screen,
                statusMessage = null,
                ingredientSearchQuery = "",
                splitCashInput = "",
                splitGCashInput = "",
                orderDateRange = ReportDateRange.TODAY,
                orderCustomStart = null,
                orderCustomEnd = null,
                orderTypeError = null,
                paymentError = null
            )
        }
        if (screen == AppScreen.SETTINGS || screen == AppScreen.POS) {
            refreshPromotionConfig()
            if (container.supabaseSyncManager.isConfigured()) {
                viewModelScope.launch { container.supabaseSyncManager.syncNow() }
            }
        }
    }

    fun refreshPrinterDevices() {
        val hasPermission = container.printerManager.hasBluetoothPermission()
        val hasScanPermission = container.printerManager.hasScanPermission()
        val devices = if (hasPermission) container.printerManager.allDevices() else emptyList()
        val connected = container.printerManager.connectedPrinter()
        val profile = container.printerManager.printerProfile
        _uiState.update {
            it.copy(
                printerDevices = devices,
                connectedPrinter = connected,
                savedPrinterAddress = container.printerManager.savedPrinterAddress,
                printerProfile = profile,
                printerFormName = profile.name,
                printerFormModel = profile.model,
                printerFormInterface = profile.interfaceType,
                printerFormAddress = profile.bluetoothAddress,
                printerFormBridgeUrl = profile.bridgeUrl,
                printerFormPaperWidthMm = profile.paperWidthMm,
                printerFormPrintReceipts = profile.printReceipts,
                printerFormAutoPrintReceipts = profile.autoPrintReceipts,
                printerFormKickCashDrawer = profile.kickCashDrawer,
                printerFormPesoSignStyle = profile.pesoSignStyle,
                printerFormLineCharacters = profile.lineCharacters,
                printerPermissionNeeded = !hasPermission,
                printerScanPermissionNeeded = !hasScanPermission,
                printerScanning = false,
                printerMessage = when {
                    !hasPermission -> "Bluetooth permission is needed to list paired printers."
                    !hasScanPermission -> "Bluetooth scan permission is needed to find nearby printers."
                    else -> null
                }
            )
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        if (granted) {
            refreshPrinterDevices()
        } else {
            _uiState.update {
                it.copy(
                    printerPermissionNeeded = true,
                    printerScanPermissionNeeded = true,
                    printerMessage = "Bluetooth permission is needed to connect printers."
                )
            }
        }
    }

    fun startPrinterScan() {
        val hasPermission = container.printerManager.hasBluetoothPermission()
        val hasScanPermission = container.printerManager.hasScanPermission()
        if (!hasPermission || !hasScanPermission) {
            _uiState.update {
                it.copy(
                    printerPermissionNeeded = !hasPermission,
                    printerScanPermissionNeeded = !hasScanPermission,
                    printerScanning = false,
                    printerMessage = "Bluetooth permission is needed to find nearby printers."
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                printerDevices = container.printerManager.allDevices(),
                printerScanning = true,
                printerMessage = "Scanning for nearby Bluetooth printers..."
            )
        }
        val started = container.printerManager.startDiscovery(
            onDevicesChanged = { devices ->
                _uiState.update { state ->
                    state.copy(
                        printerDevices = devices,
                        printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                        printerScanPermissionNeeded = !container.printerManager.hasScanPermission()
                    )
                }
            },
            onFinished = { message ->
                _uiState.update {
                    it.copy(
                        printerScanning = false,
                        printerMessage = message,
                        statusMessage = message
                    )
                }
            }
        )
        if (!started) {
            _uiState.update { it.copy(printerScanning = false) }
        }
    }

    fun connectPrinter(device: PrinterDevice) {
        viewModelScope.launch {
            _uiState.update { it.copy(printerBusy = true, printerMessage = "Connecting to ${device.name}...") }
            val result = container.printerManager.connect(device.address)
            val hasPermission = container.printerManager.hasBluetoothPermission()
            val hasScanPermission = container.printerManager.hasScanPermission()
            val devices = if (hasPermission) container.printerManager.allDevices() else emptyList()
            _uiState.update {
                it.copy(
                    printerDevices = devices,
                    printerBusy = false,
                    connectedPrinter = result.device ?: container.printerManager.connectedPrinter(),
                    savedPrinterAddress = container.printerManager.savedPrinterAddress,
                    printerPermissionNeeded = !hasPermission,
                    printerScanPermissionNeeded = !hasScanPermission,
                    printerMessage = result.message,
                    statusMessage = result.message
                )
            }
        }
    }

    fun updatePrinterName(value: String) {
        _uiState.update { it.copy(printerFormName = value.take(40), printerMessage = null) }
    }

    fun updatePrinterModel(value: String) {
        _uiState.update { it.copy(printerFormModel = value, printerMessage = null) }
    }

    fun updatePrinterInterface(value: String) {
        _uiState.update { it.copy(printerFormInterface = value, printerMessage = null) }
    }

    fun selectPrinterForProfile(device: PrinterDevice) {
        _uiState.update {
            it.copy(
                printerFormName = if (it.printerFormName.isBlank() || it.printerFormName == "POS-58") device.name else it.printerFormName,
                printerFormAddress = device.address,
                printerFormInterface = PRINTER_INTERFACE_BLUETOOTH,
                printerMessage = "${device.name} selected. Tap Save to use it for receipts."
            )
        }
    }

    fun updatePrinterPaperWidth(widthMm: Int) {
        _uiState.update {
            it.copy(
                printerFormPaperWidthMm = widthMm,
                printerFormLineCharacters = if (widthMm >= 80) 48 else 32,
                printerMessage = null
            )
        }
    }

    fun updatePrinterPesoSignStyle(style: String) {
        _uiState.update { it.copy(printerFormPesoSignStyle = style, printerMessage = null) }
    }

    fun updatePrinterLineCharacters(chars: Int) {
        _uiState.update { it.copy(printerFormLineCharacters = chars, printerMessage = null) }
    }

    fun togglePrintReceipts() {
        _uiState.update { it.copy(printerFormPrintReceipts = !it.printerFormPrintReceipts, printerMessage = null) }
    }

    fun toggleAutoPrintReceipts() {
        _uiState.update { it.copy(printerFormAutoPrintReceipts = !it.printerFormAutoPrintReceipts, printerMessage = null) }
    }

    fun toggleKickCashDrawer() {
        _uiState.update { it.copy(printerFormKickCashDrawer = !it.printerFormKickCashDrawer, printerMessage = null) }
    }

    fun savePrinterProfile() {
        val state = _uiState.value
        val profile = PrinterProfile(
            name = state.printerFormName.trim().ifBlank { "POS-58" },
            model = state.printerFormModel.ifBlank { "POS-58" },
            interfaceType = state.printerFormInterface,
            bluetoothAddress = state.printerFormAddress,
            bridgeUrl = state.printerFormBridgeUrl.ifBlank { DEFAULT_WINDOWS_BRIDGE_PRINT_URL },
            paperWidthMm = state.printerFormPaperWidthMm,
            printReceipts = state.printerFormPrintReceipts,
            autoPrintReceipts = state.printerFormAutoPrintReceipts,
            kickCashDrawer = state.printerFormKickCashDrawer,
            pesoSignStyle = state.printerFormPesoSignStyle,
            lineCharacters = state.printerFormLineCharacters
        )
        container.printerManager.savePrinterProfile(profile)
        _uiState.update {
            it.copy(
                printerProfile = profile,
                savedPrinterAddress = profile.bluetoothAddress,
                printerMessage = "${profile.name} printer saved.",
                statusMessage = "${profile.name} printer saved."
            )
        }
    }

    fun deletePrinterProfile() {
        container.printerManager.deletePrinterProfile()
        val profile = container.printerManager.printerProfile
        _uiState.update {
            it.copy(
                connectedPrinter = null,
                savedPrinterAddress = null,
                printerProfile = profile,
                printerFormName = profile.name,
                printerFormModel = profile.model,
                printerFormInterface = profile.interfaceType,
                printerFormAddress = profile.bluetoothAddress,
                printerFormBridgeUrl = profile.bridgeUrl,
                printerFormPaperWidthMm = profile.paperWidthMm,
                printerFormPrintReceipts = profile.printReceipts,
                printerFormAutoPrintReceipts = profile.autoPrintReceipts,
                printerFormKickCashDrawer = profile.kickCashDrawer,
                printerFormPesoSignStyle = profile.pesoSignStyle,
                printerFormLineCharacters = profile.lineCharacters,
                printerMessage = "Printer profile deleted.",
                statusMessage = "Printer profile deleted."
            )
        }
    }

    fun selectCategory(categoryId: String) {
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun openNewCategoryEditor() {
        if (!requireConfigurationAuthority("categories")) return
        _uiState.update {
            it.copy(
                showCategoryEditor = true,
                categoryEditorId = null,
                categoryEditorName = "",
                categoryEditorError = null
            )
        }
    }

    fun openEditCategoryEditor() {
        val state = _uiState.value
        if (!requireConfigurationAuthority("categories")) return
        val categoryId = if (state.screen == AppScreen.MENU) state.menuFormCategoryId else state.selectedCategoryId
        val category = state.catalog.categories.firstOrNull { it.id == categoryId } ?: return
        _uiState.update {
            it.copy(
                showCategoryEditor = true,
                categoryEditorId = category.id,
                categoryEditorName = category.name,
                categoryEditorError = null
            )
        }
    }

    fun closeCategoryEditor() {
        _uiState.update { it.copy(showCategoryEditor = false, categoryEditorError = null) }
    }

    fun updateCategoryEditorName(value: String) {
        _uiState.update { it.copy(categoryEditorName = value, categoryEditorError = null) }
    }

    fun selectCategoryInEditor(categoryId: String) {
        val category = _uiState.value.catalog.categories.firstOrNull { it.id == categoryId } ?: return
        _uiState.update {
            it.copy(
                categoryEditorId = category.id,
                categoryEditorName = category.name,
                categoryEditorError = null
            )
        }
    }

    fun startNewCategoryInEditor() {
        _uiState.update {
            it.copy(
                categoryEditorId = null,
                categoryEditorName = "",
                categoryEditorError = null
            )
        }
    }

    fun saveCategoryFromEditor() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("categories")) return@launch
            val name = state.categoryEditorName.trim()
            if (name.isBlank()) {
                _uiState.update { it.copy(categoryEditorError = "Enter a category name.") }
                return@launch
            }
            val existingId = state.categoryEditorId
            val id = existingId ?: name.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "category-${UUID.randomUUID().toString().take(8)}" }
            val sortOrder = state.catalog.categories.firstOrNull { it.id == existingId }?.sortOrder
                ?: ((state.catalog.categories.maxOfOrNull { it.sortOrder } ?: 0) + 1)
            container.menuRepository.saveCategory(MenuCategory(id, name, sortOrder))
            _uiState.update {
                it.copy(
                    selectedCategoryId = id,
                    menuFormCategoryId = id,
                    showCategoryEditor = true,
                    categoryEditorId = id,
                    categoryEditorName = name,
                    categoryEditorError = null,
                    statusMessage = "$name category saved."
                )
            }
        }
    }

    fun deleteSelectedCategory() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("categories")) return@launch
            val selectedId = state.categoryEditorId
                ?: if (state.screen == AppScreen.MENU) state.menuFormCategoryId else state.selectedCategoryId
            val selected = state.catalog.categories.firstOrNull { it.id == selectedId } ?: return@launch
            container.menuRepository.deleteCategory(selectedId)
            val nextCategoryId = state.catalog.categories.firstOrNull { it.id != selectedId }?.id.orEmpty()
            _uiState.update {
                it.copy(
                    selectedCategoryId = nextCategoryId,
                    menuFormCategoryId = nextCategoryId,
                    categoryEditorId = null,
                    categoryEditorName = "",
                    categoryEditorError = null,
                    statusMessage = "${selected.name} category deleted."
                )
            }
        }
    }

    fun openModifierEditor() {
        if (!requireConfigurationAuthority("modifiers")) return
        _uiState.update {
            val firstGroup = it.catalog.groups.firstOrNull()
            it.copy(
                showModifierEditor = true,
                modifierEditorId = firstGroup?.id,
                modifierEditorName = firstGroup?.name.orEmpty(),
                modifierEditorError = null,
                modifierOptionEditorId = null,
                modifierOptionEditorName = "",
                modifierOptionEditorPrice = "",
                modifierOptionEditorIngredientId = null,
                modifierOptionEditorQty = "",
                modifierOptionEditorReplacesId = null,
                modifierOptionEditorError = null
            )
        }
    }

    fun closeModifierEditor() {
        _uiState.update {
            it.copy(
                showModifierEditor = false,
                modifierEditorError = null,
                modifierOptionEditorError = null
            )
        }
    }

    fun selectModifierInEditor(groupId: String) {
        val group = _uiState.value.catalog.groups.firstOrNull { it.id == groupId } ?: return
        _uiState.update {
            it.copy(
                modifierEditorId = group.id,
                modifierEditorName = group.name,
                modifierEditorError = null,
                modifierOptionEditorId = null,
                modifierOptionEditorName = "",
                modifierOptionEditorPrice = "",
                modifierOptionEditorIngredientId = null,
                modifierOptionEditorQty = "",
                modifierOptionEditorReplacesId = null,
                modifierOptionEditorError = null
            )
        }
    }

    fun startNewModifierInEditor() {
        _uiState.update {
            it.copy(
                modifierEditorId = null,
                modifierEditorName = "",
                modifierEditorError = null,
                modifierOptionEditorId = null,
                modifierOptionEditorName = "",
                modifierOptionEditorPrice = "",
                modifierOptionEditorIngredientId = null,
                modifierOptionEditorQty = "",
                modifierOptionEditorReplacesId = null,
                modifierOptionEditorError = null
            )
        }
    }

    fun updateModifierEditorName(value: String) {
        _uiState.update { it.copy(modifierEditorName = value, modifierEditorError = null) }
    }

    fun saveModifierFromEditor() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("modifiers")) return@launch
            val name = state.modifierEditorName.trim()
            if (name.isBlank()) {
                _uiState.update { it.copy(modifierEditorError = "Enter a modifier name.") }
                return@launch
            }
            val existing = state.catalog.groups.firstOrNull { it.id == state.modifierEditorId }
            val id = existing?.id ?: name.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "modifier-${UUID.randomUUID().toString().take(8)}" }
            val group = ModifierGroup(
                id = id,
                name = name,
                required = existing?.required ?: false,
                maxSelections = existing?.maxSelections ?: 1
            )
            container.menuRepository.saveModifierGroup(group)
            _uiState.update {
                it.copy(
                    modifierEditorId = id,
                    modifierEditorName = name,
                    modifierEditorError = null,
                    modifierOptionEditorId = null,
                    modifierOptionEditorName = "",
                    modifierOptionEditorPrice = "",
                    modifierOptionEditorIngredientId = null,
                    modifierOptionEditorQty = "",
                    modifierOptionEditorReplacesId = null,
                    modifierOptionEditorError = null,
                    menuFormModifierGroupIds = it.menuFormModifierGroupIds + id,
                    statusMessage = "$name modifier saved."
                )
            }
        }
    }

    fun deleteSelectedModifier() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("modifiers")) return@launch
            val selectedId = state.modifierEditorId ?: return@launch
            val selected = state.catalog.groups.firstOrNull { it.id == selectedId } ?: return@launch
            container.menuRepository.deleteModifierGroup(selectedId)
            _uiState.update {
                it.copy(
                    modifierEditorId = null,
                    modifierEditorName = "",
                    modifierEditorError = null,
                    modifierOptionEditorId = null,
                    modifierOptionEditorName = "",
                    modifierOptionEditorPrice = "",
                    modifierOptionEditorIngredientId = null,
                    modifierOptionEditorQty = "",
                    modifierOptionEditorReplacesId = null,
                    modifierOptionEditorError = null,
                    menuFormModifierGroupIds = it.menuFormModifierGroupIds - selectedId,
                    statusMessage = "${selected.name} modifier deleted."
                )
            }
        }
    }

    fun selectModifierOptionInEditor(option: ModifierOption) {
        val recipe = _uiState.value.modifierRecipes.firstOrNull { it.optionId == option.id }
        _uiState.update {
            it.copy(
                modifierOptionEditorId = option.id,
                modifierOptionEditorName = option.name,
                modifierOptionEditorPrice = option.priceDeltaCents.formatCentsAsPrice(),
                modifierOptionEditorIngredientId = recipe?.ingredientId,
                modifierOptionEditorQty = recipe?.quantityUsed?.let { qty -> if (qty % 1.0 == 0.0) qty.toInt().toString() else "%.2f".format(Locale.US, qty) } ?: "",
                modifierOptionEditorReplacesId = recipe?.replacesIngredientId,
                modifierOptionEditorError = null
            )
        }
    }

    fun startNewModifierOptionInEditor() {
        _uiState.update {
            it.copy(
                modifierOptionEditorId = null,
                modifierOptionEditorName = "",
                modifierOptionEditorPrice = "",
                modifierOptionEditorIngredientId = null,
                modifierOptionEditorQty = "",
                modifierOptionEditorReplacesId = null,
                modifierOptionEditorError = null
            )
        }
    }

    fun updateModifierOptionEditorName(value: String) {
        _uiState.update { it.copy(modifierOptionEditorName = value, modifierOptionEditorError = null) }
    }

    fun updateModifierOptionEditorPrice(value: String) {
        _uiState.update {
            it.copy(
                modifierOptionEditorPrice = value.filter { char -> char.isDigit() || char == '.' }.take(8),
                modifierOptionEditorError = null
            )
        }
    }

    fun updateModifierOptionEditorIngredientId(value: String?) {
        _uiState.update { it.copy(modifierOptionEditorIngredientId = value, modifierOptionEditorError = null) }
    }

    fun updateModifierOptionEditorQty(value: String) {
        _uiState.update { it.copy(modifierOptionEditorQty = value, modifierOptionEditorError = null) }
    }

    fun updateModifierOptionEditorReplacesId(value: String?) {
        _uiState.update { it.copy(modifierOptionEditorReplacesId = value, modifierOptionEditorError = null) }
    }

    fun saveModifierOptionFromEditor() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("modifiers")) return@launch
            val groupId = state.modifierEditorId
            if (groupId == null) {
                _uiState.update { it.copy(modifierOptionEditorError = "Select or save a modifier group first.") }
                return@launch
            }
            val name = state.modifierOptionEditorName.trim()
            if (name.isBlank()) {
                _uiState.update { it.copy(modifierOptionEditorError = "Enter an option name.") }
                return@launch
            }
            val priceCents = ((state.modifierOptionEditorPrice.toDoubleOrNull() ?: 0.0) * 100).toInt()
            val ingredientId = state.modifierOptionEditorIngredientId
            val qty = state.modifierOptionEditorQty.toDoubleOrNull() ?: 0.0
            val replacesId = state.modifierOptionEditorReplacesId

            val existing = state.catalog.options.firstOrNull { it.id == state.modifierOptionEditorId }
            val id = existing?.id ?: "${groupId}-${name.lowercase(Locale.US)}"
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "modifier-option" } + "-" + UUID.randomUUID().toString().take(6)
            val option = ModifierOption(
                id = id,
                groupId = groupId,
                name = name,
                priceDeltaCents = priceCents
            )
            container.menuRepository.saveModifierOption(option)

            if (ingredientId != null) {
                container.inventoryRepository.saveModifierRecipe(
                    ModifierRecipeIngredient(
                        optionId = id,
                        ingredientId = ingredientId,
                        quantityUsed = qty,
                        replacesIngredientId = replacesId
                    )
                )
            } else {
                container.inventoryRepository.deleteModifierRecipe(id)
            }

            _uiState.update {
                it.copy(
                    modifierOptionEditorId = id,
                    modifierOptionEditorName = name,
                    modifierOptionEditorPrice = priceCents.formatCentsAsPrice(),
                    modifierOptionEditorError = null,
                    statusMessage = "$name option saved."
                )
            }
        }
    }

    fun deleteSelectedModifierOption() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("modifiers")) return@launch
            val selectedId = state.modifierOptionEditorId ?: return@launch
            val selected = state.catalog.options.firstOrNull { it.id == selectedId } ?: return@launch
            container.menuRepository.deleteModifierOption(selectedId)
            container.inventoryRepository.deleteModifierRecipe(selectedId)
            _uiState.update {
                it.copy(
                    modifierOptionEditorId = null,
                    modifierOptionEditorName = "",
                    modifierOptionEditorPrice = "",
                    modifierOptionEditorIngredientId = null,
                    modifierOptionEditorQty = "",
                    modifierOptionEditorReplacesId = null,
                    modifierOptionEditorError = null,
                    selectedModifiers = it.selectedModifiers.filterNot { option -> option.id == selectedId },
                    statusMessage = "${selected.name} option deleted."
                )
            }
        }
    }

    fun chooseItem(item: MenuItem) {
        _uiState.update { it.copy(pendingItem = item, selectedModifiers = emptyList()) }
    }

    fun toggleModifier(option: ModifierOption) {
        _uiState.update { state ->
            val current = state.selectedModifiers
            val sameGroup = current.filter { it.groupId == option.groupId }
            val alreadySelected = current.any { it.id == option.id }
            val maxSelections = state.catalog.groups
                .firstOrNull { it.id == option.groupId }
                ?.maxSelections
                ?.coerceAtLeast(1)
                ?: 1
            val next = when {
                alreadySelected -> current.filterNot { it.id == option.id }
                maxSelections == 1 ->
                    current.filterNot { it.groupId == option.groupId } + option
                sameGroup.size >= maxSelections -> current
                else -> current + option
            }
            state.copy(selectedModifiers = next)
        }
    }

    fun addPendingItem() {
        _uiState.update { state ->
            val item = state.pendingItem ?: return@update state
            val nextCart = state.cart + CartLine(item = item, modifiers = state.selectedModifiers)
            val nextCents = recalculateSelectedItemDiscount(state, nextCart)
            state.copy(
                cart = nextCart,
                discountCents = nextCents,
                pendingItem = null,
                selectedModifiers = emptyList(),
                statusMessage = "${item.name} added"
            )
        }
    }

    fun cancelPendingItem() {
        _uiState.update { it.copy(pendingItem = null, selectedModifiers = emptyList()) }
    }

    fun changeQuantity(index: Int, delta: Int) {
        _uiState.update { state ->
            val next = state.cart.mapIndexedNotNull { rowIndex, line ->
                if (rowIndex == index) {
                    val quantity = line.quantity + delta
                    if (quantity <= 0) null else line.copy(quantity = quantity)
                } else {
                    line
                }
            }
            val selectedLineId = state.selectedDiscountLineId?.takeIf { id -> next.any { it.id == id } }
            val nextCents = recalculateSelectedItemDiscount(state, next, selectedLineId)
            state.copy(
                cart = next,
                selectedDiscountLineId = selectedLineId,
                discountCents = nextCents
            )
        }
    }

    fun holdCart() {
        _uiState.value.promotionReservationToken?.let { token ->
            viewModelScope.launch { container.supabaseSyncManager.releasePromotionClaim(token) }
        }
        _uiState.update { state ->
            if (state.cart.isEmpty()) {
                state.copy(statusMessage = "Cart is empty.")
            } else {
                state.copy(
                    heldCarts = state.heldCarts + listOf(state.cart),
                    cart = emptyList(),
                    discountCents = 0,
                    tipCents = 0,
                    discountInput = "0",
                    selectedDiscountCategory = "None",
                    seniorPwdIdInput = "",
                    selectedDiscountLineId = null,
                    promotionReservationToken = null,
                    promotionAppliedClaimCode = null,
                    promotionClaim = null,
                    statusMessage = "Order held."
                )
            }
        }
    }

    fun cancelCart() {
        _uiState.value.promotionReservationToken?.let { token ->
            viewModelScope.launch { container.supabaseSyncManager.releasePromotionClaim(token) }
        }
        _uiState.update { state ->
            if (state.cart.isEmpty()) {
                state.copy(statusMessage = "Cart is empty.")
            } else {
                state.copy(
                    cart = emptyList(),
                    discountCents = 0,
                    tipCents = 0,
                    discountInput = "0",
                    selectedDiscountCategory = "None",
                    seniorPwdIdInput = "",
                    selectedDiscountLineId = null,
                    promotionReservationToken = null,
                    promotionAppliedClaimCode = null,
                    promotionClaim = null,
                    statusMessage = "Order canceled."
                )
            }
        }
    }

    fun resumeHeldCart(index: Int) {
        _uiState.update { state ->
            val resumedCart = state.heldCarts.getOrNull(index) ?: return@update state
            val updatedHeldCarts = if (state.cart.isNotEmpty()) {
                state.heldCarts.filterIndexed { rowIndex, _ -> rowIndex != index } + listOf(state.cart)
            } else {
                state.heldCarts.filterIndexed { rowIndex, _ -> rowIndex != index }
            }
            state.copy(
                cart = resumedCart,
                discountCents = 0,
                discountInput = "0",
                selectedDiscountCategory = "None",
                seniorPwdIdInput = "",
                selectedDiscountLineId = null,
                heldCarts = updatedHeldCarts,
                statusMessage = "Held order resumed."
            )
        }
    }

    private fun recalculateDiscount(cart: List<CartLine>, type: String, input: String): Int {
        val subtotal = cart.sumOf { it.lineTotalCents }
        return if (type == "percent") {
            val pct = input.toDoubleOrNull() ?: 0.0
            (subtotal * pct / 100.0).toInt()
        } else {
            ((input.toDoubleOrNull() ?: 0.0) * 100).toInt()
        }.coerceIn(0, subtotal)
    }

    fun setDiscountInput(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }.take(6)
        _uiState.update { state ->
            val cents = recalculateDiscount(state.cart, state.discountType, clean)
            state.copy(
                discountInput = clean,
                discountCents = cents
            )
        }
    }

    fun setDiscountType(type: String) {
        _uiState.update { state ->
            val cents = recalculateDiscount(state.cart, type, state.discountInput)
            state.copy(
                discountType = type,
                discountCents = cents
            )
        }
    }

    fun setDiscountAmount(value: String) {
        setDiscountInput(value)
    }

    fun setCustomTipAmount(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }.take(6)
        val cents = ((clean.toDoubleOrNull() ?: 0.0) * 100).toInt()
        _uiState.update { it.copy(tipCents = cents.coerceAtLeast(0)) }
    }

    fun setTipPercent(percent: Int) {
        _uiState.update { state ->
            val base = (state.totals.subtotalCents - state.totals.discountCents).coerceAtLeast(0)
            state.copy(tipCents = (base * percent / 100.0).toInt())
        }
    }

    fun showOrderSummary() {
        val state = _uiState.value
        when {
            state.employee == null -> _uiState.update { it.copy(statusMessage = "Sign in before checkout.") }
            state.cart.isEmpty() -> _uiState.update { it.copy(statusMessage = "Cart is empty.") }
            else -> _uiState.update {
                it.copy(
                    showOrderSummary = true,
                    paymentMethod = "",
                    amountPaidInput = "",
                    splitCashInput = "",
                    splitGCashInput = "",
                    orderTypeInput = "",
                    orderTypeError = null,
                    paymentError = null,
                    statusMessage = null
                )
            }
        }
    }

    fun hideOrderSummary() {
        _uiState.update {
            it.copy(
                showOrderSummary = false,
                paymentMethod = "",
                amountPaidInput = "",
                splitCashInput = "",
                splitGCashInput = "",
                orderTypeError = null,
                paymentError = null
            )
        }
    }

    private fun benefitDiscountPercent(state: PosUiState, category: String = state.selectedDiscountCategory): Double =
        when (category) {
            "Senior" -> state.settings.seniorDiscountPercent
            "PWD" -> state.settings.pwdDiscountPercent
            else -> category.removePrefix("RULE:")
                .takeIf { category.startsWith("RULE:") }
                ?.let { id -> state.discountRules.firstOrNull { it.id == id && it.active }?.percent }
                ?: 0.0
        }

    private fun recalculateSelectedItemDiscount(
        state: PosUiState,
        cart: List<CartLine> = state.cart,
        selectedLineId: String? = state.selectedDiscountLineId,
        category: String = state.selectedDiscountCategory
    ): Int {
        val rule = category.removePrefix("RULE:")
            .takeIf { category.startsWith("RULE:") }
            ?.let { id -> state.discountRules.firstOrNull { it.id == id && it.active } }
        return if (rule?.scope == "order") {
            calculateWholeOrderDiscountCents(cart, rule.percent)
        } else {
            calculateSingleItemDiscountCents(
                lines = cart,
                cartLineId = selectedLineId,
                percent = benefitDiscountPercent(state, category)
            )
        }
    }

    private fun currentItemDiscount(state: PosUiState): ItemDiscountSelection? {
        val lineId = state.selectedDiscountLineId
        if (state.selectedDiscountCategory == "PROMO_FREE_DRINK") {
            val promotionLineId = lineId ?: return null
            val basePrice = state.cart.firstOrNull { it.id == promotionLineId }?.item?.basePriceCents ?: return null
            return ItemDiscountSelection(
                cartLineId = promotionLineId,
                category = "PROMO_FREE_DRINK",
                percent = 0.0,
                discountCents = basePrice
            )
        }
        val customRule = state.selectedCustomDiscount
        if (state.selectedDiscountCategory != "Senior" &&
            state.selectedDiscountCategory != "PWD" &&
            customRule == null
        ) return null
        val percent = benefitDiscountPercent(state)
        val scope = customRule?.scope ?: "item"
        val cents = if (scope == "order") {
            calculateWholeOrderDiscountCents(state.cart, percent)
        } else {
            if (lineId == null) return null
            calculateSingleItemDiscountCents(state.cart, lineId, percent)
        }
        if (cents <= 0) return null
        return ItemDiscountSelection(
            cartLineId = if (scope == "item") lineId else null,
            category = customRule?.name ?: state.selectedDiscountCategory,
            percent = percent,
            discountCents = cents,
            ruleId = customRule?.id,
            scope = scope,
            reference = state.seniorPwdIdInput.trim().ifBlank { null }
        )
    }

    fun selectPaymentMethod(method: String) {
        if (method == "Complimentary" && _uiState.value.promotionReservationToken != null) {
            _uiState.update { it.copy(paymentError = "A free drink claim cannot be combined with a Complimentary order.") }
            return
        }
        _uiState.update { state ->
            state.copy(
                paymentMethod = method,
                amountPaidInput = "",
                splitCashInput = "",
                splitGCashInput = "",
                paymentError = null,
                selectedDiscountCategory = if (method == "Complimentary") "None" else state.selectedDiscountCategory,
                seniorPwdIdInput = if (method == "Complimentary") "" else state.seniorPwdIdInput,
                selectedDiscountLineId = if (method == "Complimentary") null else state.selectedDiscountLineId,
                discountInput = if (method == "Complimentary") "0" else state.discountInput,
                discountCents = if (method == "Complimentary") 0 else state.discountCents
            )
        }
    }

    fun updateAmountPaid(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val singleDecimal = if (filtered.count { it == '.' } <= 1) {
            filtered
        } else {
            val firstDot = filtered.indexOf('.')
            filtered.filterIndexed { index, char -> char != '.' || index == firstDot }
        }
        val normalized = singleDecimal.split('.', limit = 2).let { parts ->
            if (parts.size == 2) "${parts[0]}.${parts[1].take(2)}" else singleDecimal
        }
        _uiState.update { it.copy(amountPaidInput = normalized, paymentError = null) }
    }

    fun updateSplitCashInput(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val singleDecimal = if (filtered.count { it == '.' } <= 1) {
            filtered
        } else {
            val firstDot = filtered.indexOf('.')
            filtered.filterIndexed { index, char -> char != '.' || index == firstDot }
        }
        val normalized = singleDecimal.split('.', limit = 2).let { parts ->
            if (parts.size == 2) "${parts[0]}.${parts[1].take(2)}" else singleDecimal
        }
        _uiState.update { it.copy(splitCashInput = normalized, paymentError = null) }
    }

    fun updateSplitGCashInput(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' }
        val singleDecimal = if (filtered.count { it == '.' } <= 1) {
            filtered
        } else {
            val firstDot = filtered.indexOf('.')
            filtered.filterIndexed { index, char -> char != '.' || index == firstDot }
        }
        val normalized = singleDecimal.split('.', limit = 2).let { parts ->
            if (parts.size == 2) "${parts[0]}.${parts[1].take(2)}" else singleDecimal
        }
        _uiState.update { it.copy(splitGCashInput = normalized, paymentError = null) }
    }

    fun confirmCheckout() {
        val state = _uiState.value
        val totalCents = state.totals.totalCents
        val orderTypeError = if (state.orderTypeInput.isBlank()) "Select Dine-In or Take-Out." else null
        val paymentError = if (state.paymentMethod.isBlank()) "Select a payment method." else null
        if (orderTypeError != null || paymentError != null) {
            _uiState.update {
                it.copy(
                    orderTypeError = orderTypeError,
                    paymentError = paymentError
                )
            }
            return
        }
        if (state.paymentMethod == "Split") {
            val cashCents = parseMoneyCents(state.splitCashInput) ?: 0
            val gcashCents = parseMoneyCents(state.splitGCashInput) ?: 0
            val totalPaidCents = cashCents + gcashCents
            if (cashCents <= 0 || gcashCents <= 0) {
                _uiState.update { it.copy(paymentError = "Enter valid positive amounts for both Cash and GCash.") }
                return
            }
            if (totalPaidCents < totalCents) {
                _uiState.update { it.copy(paymentError = "Total split amount must cover the total (₱${String.format(Locale.US, "%.2f", totalCents / 100.0)}).") }
                return
            }
            checkoutSplit(cashCents, gcashCents)
        } else if (state.paymentMethod == "Complimentary") {
            checkout("Complimentary", 0)
        } else {
            val amountPaidCents = parseMoneyCents(state.amountPaidInput)
            when {
                amountPaidCents == null -> _uiState.update { it.copy(paymentError = "Enter the amount paid.") }
                amountPaidCents < totalCents -> _uiState.update { it.copy(paymentError = "Amount paid must cover the total.") }
                else -> checkout(state.paymentMethod, amountPaidCents)
            }
        }
    }

    private fun checkoutSplit(cashPaidCents: Int, gcashPaidCents: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val employee = state.employee
            val shift = state.activeShift
            val promotionReservationToken = state.promotionReservationToken
            when {
                employee == null -> _uiState.update { it.copy(statusMessage = "Sign in before checkout.") }
                shift == null -> _uiState.update { it.copy(statusMessage = "No active shift. Please open a shift before checking out.") }
                state.cart.isEmpty() -> _uiState.update { it.copy(statusMessage = "Cart is empty.") }
                state.selectedDiscountRequiresReference && state.seniorPwdIdInput.isBlank() ->
                    _uiState.update { it.copy(paymentError = "Enter the ${state.selectedDiscountName} ID or reference number.") }
                state.selectedDiscountCategory != "None" &&
                    state.selectedDiscountScope == "item" &&
                    state.selectedDiscountLineId == null ->
                    _uiState.update { it.copy(paymentError = "Choose one item for the ${state.selectedDiscountName} discount.") }
                else -> {
                    val profile = container.printerManager.printerProfile
                    val lineChars = if (profile.lineCharacters > 0) profile.lineCharacters else (if (profile.paperWidthMm >= 80) 48 else 32)
                    val order = container.orderRepository.checkoutSplit(
                        employee = employee,
                        shift = shift,
                        lines = state.cart,
                        itemDiscount = currentItemDiscount(state),
                        tipCents = state.tipCents,
                        cashAmountCents = cashPaidCents,
                        gcashAmountCents = gcashPaidCents,
                        customerName = state.customerNameInput,
                        tableNumber = state.tableNumberInput,
                        orderType = state.orderTypeInput,
                        lineCharacters = lineChars
                    )
                    val receipt = container.orderRepository.receipt(order.id)
                    val receiptText = receipt?.text
                    _uiState.update {
                        it.copy(
                            cart = emptyList(),
                            discountCents = 0,
                            tipCents = 0,
                            customerNameInput = "",
                            tableNumberInput = "",
                            selectedDiscountCategory = "None",
                            seniorPwdIdInput = "",
                            selectedDiscountLineId = null,
                            promotionReservationToken = null,
                            promotionAppliedClaimCode = null,
                            promotionClaim = null,
                            orderTypeInput = "",
                            showOrderSummary = false,
                            paymentMethod = "",
                            amountPaidInput = "",
                            splitCashInput = "",
                            splitGCashInput = "",
                            orderTypeError = null,
                            paymentError = null,
                            receiptText = receiptText,
                            receiptOrderId = order.id,
                            receiptAuditStatus = null,
                            receiptPromotionState = ReceiptPromotionState.CHECKING,
                            receiptPromotionResult = null,
                            receiptCopyStage = ReceiptCopyStage.FIRST_COPY,
                            receiptSecondCopyCountdown = null,
                            screen = AppScreen.ORDERS,
                            statusMessage = "Order paid. $RECEIPT_PREPARING_LABEL"
                        )
                    }
                    prepareReceiptForPrinting(
                        orderId = order.id,
                        receiptText = receiptText,
                        reservationToken = promotionReservationToken,
                        employeeId = employee.id,
                        autoPrint = profile.printReceipts && profile.autoPrintReceipts
                    )
                }
            }
        }
    }

    fun dismissReceipt() {
        _uiState.update {
            it.copy(
                receiptText = null,
                receiptOrderId = null,
                receiptAuditStatus = null,
                receiptPromotionState = ReceiptPromotionState.NONE,
                receiptPromotionResult = null,
                receiptCopyStage = ReceiptCopyStage.FIRST_COPY,
                receiptSecondCopyCountdown = null
            )
        }
    }

    fun printReceipt() {
        viewModelScope.launch {
            val current = _uiState.value
            if (!current.printerProfile.printReceipts) {
                _uiState.update { it.copy(statusMessage = "Receipt printing is turned off in Devices.") }
                return@launch
            }
            val receipt = current.receiptText
            if (receipt.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "No receipt to print.") }
                return@launch
            }
            val orderId = current.receiptOrderId
            when {
                current.receiptPromotionState == ReceiptPromotionState.CHECKING -> {
                    _uiState.update { it.copy(statusMessage = "Receipt is still being prepared. Please wait.") }
                }
                orderId.isNullOrBlank() -> printPreparedReceipt(copies = 1)
                current.receiptPromotionState != ReceiptPromotionState.READY -> {
                    prepareReceiptForPrinting(
                        orderId = orderId,
                        receiptText = receipt,
                        reservationToken = null,
                        employeeId = current.employee?.id.orEmpty(),
                        autoPrint = true
                    )
                }
                else -> printPreparedReceipt(copies = 1)
            }
        }
    }

    fun printReceipt2x() {
        viewModelScope.launch {
            val current = _uiState.value
            if (!current.printerProfile.printReceipts) {
                _uiState.update { it.copy(statusMessage = "Receipt printing is turned off in Devices.") }
                return@launch
            }
            val receipt = current.receiptText
            if (receipt.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "No receipt to print.") }
                return@launch
            }
            if (current.receiptCopyStage == ReceiptCopyStage.SECOND_COPY) {
                printSecondReceiptCopy()
                return@launch
            }
            val orderId = current.receiptOrderId
            when {
                current.receiptPromotionState == ReceiptPromotionState.CHECKING -> {
                    _uiState.update { it.copy(statusMessage = "Receipt is still being prepared. Please wait.") }
                }
                orderId.isNullOrBlank() -> printPreparedReceipt(copies = 2)
                current.receiptPromotionState != ReceiptPromotionState.READY -> {
                    prepareReceiptForPrinting(
                        orderId = orderId,
                        receiptText = receipt,
                        reservationToken = null,
                        employeeId = current.employee?.id.orEmpty(),
                        autoPrint = false
                    )
                    if (_uiState.value.receiptPromotionState == ReceiptPromotionState.READY) {
                        printPreparedReceipt(copies = 2)
                    }
                }
                else -> printPreparedReceipt(copies = 2)
            }
        }
    }

    private suspend fun printPreparedReceipt(copies: Int) {
        val current = _uiState.value
        val receipt = current.receiptText ?: return
        val promotion = current.receiptPromotionResult
        val includePromotion = shouldIncludePromotionOnReceipt(promotion)
        _uiState.update {
            it.copy(
                printerBusy = true,
                printerMessage = if (copies == 2) "Printing 2 receipts..." else "Printing receipt..."
            )
        }

        val first = printReceiptCopy(
            receipt = receipt,
            promotion = promotion,
            includePromotion = includePromotion,
            copyNumber = 1
        )
        if (!first.success) {
            _uiState.update {
                it.copy(
                    printerBusy = false,
                    connectedPrinter = first.device ?: container.printerManager.connectedPrinter(),
                    printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                    printerMessage = first.message,
                    statusMessage = first.message
                )
            }
            return
        }

        var acknowledgementPending = false
        if (includePromotion) {
            val awardId = promotion!!.awardId
            if (!awardId.isNullOrBlank()) {
                val acknowledged = container.supabaseSyncManager.markPromotionPrinted(awardId)
                if (acknowledged.isFailure) {
                    acknowledgementPending = true
                    container.supabaseSyncManager.queuePromotionPrintedAward(awardId)
                } else {
                    container.supabaseSyncManager.clearPromotionPrintedAward(awardId)
                }
            }
        }

        if (copies == 1) {
            val message = when {
                acknowledgementPending -> "Receipt printed. Cloud confirmation will retry automatically."
                includePromotion -> "Receipt printed successfully."
                else -> first.message
            }
            _uiState.update {
                it.copy(
                    printerBusy = false,
                    connectedPrinter = first.device ?: container.printerManager.connectedPrinter(),
                    savedPrinterAddress = container.printerManager.savedPrinterAddress,
                    printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                    printerMessage = message,
                    statusMessage = message,
                    receiptText = null,
                    receiptOrderId = null,
                    receiptAuditStatus = null,
                    receiptPromotionState = ReceiptPromotionState.NONE,
                    receiptPromotionResult = null,
                    receiptCopyStage = ReceiptCopyStage.FIRST_COPY,
                    receiptSecondCopyCountdown = null
                )
            }
            return
        }

        for (secondsRemaining in secondReceiptCountdownValues()) {
            _uiState.update {
                it.copy(
                    receiptCopyStage = ReceiptCopyStage.SECOND_COPY,
                    receiptSecondCopyCountdown = secondsRemaining,
                    printerMessage = "Remove first receipt - second copy prints in $secondsRemaining ${if (secondsRemaining == 1) "second" else "seconds"}."
                )
            }
            kotlinx.coroutines.delay(1_000)
        }

        _uiState.update {
            it.copy(
                receiptSecondCopyCountdown = null,
                printerMessage = "Printing second receipt..."
            )
        }
        val second = printReceiptCopy(
            receipt = receipt,
            promotion = promotion,
            includePromotion = includePromotion,
            copyNumber = 2
        )
        finishSecondReceiptCopy(second, acknowledgementPending)
    }

    private suspend fun printSecondReceiptCopy() {
        val current = _uiState.value
        val receipt = current.receiptText ?: return
        val promotion = current.receiptPromotionResult
        val includePromotion = shouldIncludePromotionOnReceipt(promotion)
        _uiState.update {
            it.copy(
                printerBusy = true,
                receiptSecondCopyCountdown = null,
                printerMessage = "Retrying second receipt..."
            )
        }
        val second = printReceiptCopy(
            receipt = receipt,
            promotion = promotion,
            includePromotion = includePromotion,
            copyNumber = 2
        )
        finishSecondReceiptCopy(second, acknowledgementPending = false)
    }

    private suspend fun printReceiptCopy(
        receipt: String,
        promotion: PromotionResult?,
        includePromotion: Boolean,
        copyNumber: Int
    ): com.kape.coffeepos.printer.PrinterResult {
        return if (includePromotion) {
            val promotionResult = requireNotNull(promotion)
            container.printerManager.printReceiptWithPromotion(
                receiptText = receipt,
                promotionText = promotionReceiptText(promotionResult),
                promotionQrPayload = requireNotNull(promotionResult.qrUrl),
                allowCashDrawerKick = shouldKickDrawerForReceiptCopy(copyNumber)
            )
        } else {
            container.printerManager.print(
                text = receipt,
                allowCashDrawerKick = shouldKickDrawerForReceiptCopy(copyNumber)
            )
        }
    }

    private fun finishSecondReceiptCopy(
        result: com.kape.coffeepos.printer.PrinterResult,
        acknowledgementPending: Boolean
    ) {
        val message = when {
            !result.success -> "First receipt printed, but the second copy failed: ${result.message} Tap Retry Second Copy."
            acknowledgementPending -> "Printed 2 receipts. Cloud confirmation will retry automatically."
            else -> "Printed 2 receipts successfully."
        }
        _uiState.update {
            it.copy(
                printerBusy = false,
                connectedPrinter = result.device ?: container.printerManager.connectedPrinter(),
                savedPrinterAddress = container.printerManager.savedPrinterAddress,
                printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                printerMessage = message,
                statusMessage = message,
                receiptText = if (result.success) null else it.receiptText,
                receiptOrderId = if (result.success) null else it.receiptOrderId,
                receiptAuditStatus = if (result.success) null else it.receiptAuditStatus,
                receiptPromotionState = if (result.success) ReceiptPromotionState.NONE else it.receiptPromotionState,
                receiptPromotionResult = if (result.success) null else it.receiptPromotionResult,
                receiptCopyStage = if (result.success) ReceiptCopyStage.FIRST_COPY else ReceiptCopyStage.SECOND_COPY,
                receiptSecondCopyCountdown = null
            )
        }
    }

    fun testPrinter() {
        viewModelScope.launch {
            val formState = _uiState.value
            val profile = PrinterProfile(
                name = formState.printerFormName.trim().ifBlank { "POS-58" },
                model = formState.printerFormModel.ifBlank { "POS-58" },
                interfaceType = formState.printerFormInterface,
                bluetoothAddress = formState.printerFormAddress,
                bridgeUrl = formState.printerFormBridgeUrl.ifBlank { DEFAULT_WINDOWS_BRIDGE_PRINT_URL },
                paperWidthMm = formState.printerFormPaperWidthMm,
                printReceipts = formState.printerFormPrintReceipts,
                autoPrintReceipts = formState.printerFormAutoPrintReceipts,
                kickCashDrawer = formState.printerFormKickCashDrawer,
                pesoSignStyle = formState.printerFormPesoSignStyle,
                lineCharacters = formState.printerFormLineCharacters
            )
            container.printerManager.savePrinterProfile(profile)
            _uiState.update { it.copy(printerProfile = profile, savedPrinterAddress = profile.bluetoothAddress, printerBusy = true, printerMessage = "Sending test print...") }
            val result = container.printerManager.print(
                """
                 Kanlungan POS
                 Printer Test

                ${profile.interfaceType} printer selected.
                Paper width: ${profile.paperWidthMm} mm
                Receipts are ready.
                """.trimIndent()
            )
            _uiState.update {
                val message = if (result.success) {
                    "Test print sent to ${result.device?.name ?: it.connectedPrinter?.name ?: "printer"}."
                } else {
                    result.message
                }
                it.copy(
                    printerBusy = false,
                    connectedPrinter = result.device ?: container.printerManager.connectedPrinter(),
                    savedPrinterAddress = container.printerManager.savedPrinterAddress,
                    printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                    printerScanPermissionNeeded = !container.printerManager.hasScanPermission(),
                    printerMessage = message,
                    statusMessage = message
                )
            }
        }
    }

    fun testPrinterQrCodes() {
        viewModelScope.launch {
            val formState = _uiState.value
            val profile = PrinterProfile(
                name = formState.printerFormName.trim().ifBlank { "POS-58" },
                model = formState.printerFormModel.ifBlank { "POS-58" },
                interfaceType = formState.printerFormInterface,
                bluetoothAddress = formState.printerFormAddress,
                bridgeUrl = formState.printerFormBridgeUrl.ifBlank { DEFAULT_WINDOWS_BRIDGE_PRINT_URL },
                paperWidthMm = formState.printerFormPaperWidthMm,
                printReceipts = formState.printerFormPrintReceipts,
                autoPrintReceipts = formState.printerFormAutoPrintReceipts,
                kickCashDrawer = formState.printerFormKickCashDrawer,
                pesoSignStyle = formState.printerFormPesoSignStyle,
                lineCharacters = formState.printerFormLineCharacters
            )
            if (profile.interfaceType != PRINTER_INTERFACE_BLUETOOTH) {
                val message = "QR printer test requires the Bluetooth printer interface."
                _uiState.update { it.copy(printerMessage = message, statusMessage = message) }
                return@launch
            }

            container.printerManager.savePrinterProfile(profile)
            val promotionUrl = buildPromotionTestQrUrl(
                template = formState.promotionConfig.googleFormUrlTemplate
            )
            val startingMessage = if (promotionUrl == null) {
                "Printing Facebook QR. Promotion QR is unavailable until its prefilled Google Form link is valid."
            } else {
                "Sending promotion and Facebook QR test..."
            }
            _uiState.update {
                it.copy(
                    printerProfile = profile,
                    savedPrinterAddress = profile.bluetoothAddress,
                    printerBusy = true,
                    printerMessage = startingMessage
                )
            }
            val result = container.printerManager.printQrTest(promotionUrl)
            _uiState.update {
                val message = when {
                    !result.success -> result.message
                    promotionUrl == null ->
                        "${result.message} Facebook QR was included; promotion QR needs a valid prefilled Google Form link."
                    else -> "${result.message} Scan both printed codes to verify them."
                }
                it.copy(
                    printerBusy = false,
                    connectedPrinter = result.device ?: container.printerManager.connectedPrinter(),
                    savedPrinterAddress = container.printerManager.savedPrinterAddress,
                    printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                    printerScanPermissionNeeded = !container.printerManager.hasScanPermission(),
                    printerMessage = message,
                    statusMessage = message
                )
            }
        }
    }

    private fun checkout(paymentMethod: String, amountTenderedCents: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val employee = state.employee
            val shift = state.activeShift
            val promotionReservationToken = state.promotionReservationToken
            when {
                employee == null -> _uiState.update { it.copy(statusMessage = "Sign in before checkout.") }
                shift == null -> {
                    _uiState.update { it.copy(statusMessage = "No active shift. Please open a shift before checking out.") }
                }
                state.cart.isEmpty() -> _uiState.update { it.copy(statusMessage = "Cart is empty.") }
                state.selectedDiscountRequiresReference && state.seniorPwdIdInput.isBlank() ->
                    _uiState.update { it.copy(paymentError = "Enter the ${state.selectedDiscountName} ID or reference number.") }
                state.selectedDiscountCategory != "None" &&
                    state.selectedDiscountScope == "item" &&
                    state.selectedDiscountLineId == null ->
                    _uiState.update { it.copy(paymentError = "Choose one item for the ${state.selectedDiscountName} discount.") }
                else -> {
                    val profile = container.printerManager.printerProfile
                    val lineChars = if (profile.lineCharacters > 0) profile.lineCharacters else (if (profile.paperWidthMm >= 80) 48 else 32)
                    val order = container.orderRepository.checkout(
                        employee = employee,
                        shift = shift,
                        lines = state.cart,
                        itemDiscount = currentItemDiscount(state),
                        tipCents = state.tipCents,
                        paymentMethod = paymentMethod,
                        paymentCategory = state.paymentMethods
                            .firstOrNull { it.name == paymentMethod }
                            ?.paymentCategory
                            ?: PaymentCategories.fromLegacyMethod(paymentMethod),
                        amountTenderedCents = amountTenderedCents,
                        customerName = state.customerNameInput,
                        tableNumber = state.tableNumberInput,
                        orderType = state.orderTypeInput,
                        lineCharacters = lineChars
                    )
                    val receipt = container.orderRepository.receipt(order.id)
                    val receiptText = receipt?.text
                    _uiState.update {
                        it.copy(
                            cart = emptyList(),
                            discountCents = 0,
                            tipCents = 0,
                            customerNameInput = "",
                            tableNumberInput = "",
                            selectedDiscountCategory = "None",
                            seniorPwdIdInput = "",
                            selectedDiscountLineId = null,
                            promotionReservationToken = null,
                            promotionAppliedClaimCode = null,
                            promotionClaim = null,
                            orderTypeInput = "",
                            showOrderSummary = false,
                            paymentMethod = "",
                            amountPaidInput = "",
                            splitCashInput = "",
                            splitGCashInput = "",
                            orderTypeError = null,
                            paymentError = null,
                            receiptText = receiptText,
                            receiptOrderId = order.id,
                            receiptAuditStatus = null,
                            receiptPromotionState = ReceiptPromotionState.CHECKING,
                            receiptPromotionResult = null,
                            receiptCopyStage = ReceiptCopyStage.FIRST_COPY,
                            receiptSecondCopyCountdown = null,
                            screen = AppScreen.ORDERS,
                            statusMessage = "Order paid. $RECEIPT_PREPARING_LABEL"
                        )
                    }
                    prepareReceiptForPrinting(
                        orderId = order.id,
                        receiptText = receiptText,
                        reservationToken = promotionReservationToken,
                        employeeId = employee.id,
                        autoPrint = profile.printReceipts && profile.autoPrintReceipts
                    )
                }
            }
        }
    }

    fun updateCustomerName(value: String) {
        _uiState.update { it.copy(customerNameInput = value) }
    }

    fun updateTableNumber(value: String) {
        _uiState.update { it.copy(tableNumberInput = value) }
    }

    fun updateOrderType(value: String) {
        _uiState.update { it.copy(orderTypeInput = value, orderTypeError = null) }
    }

    fun updateStartingCashInput(value: String) {
        _uiState.update { it.copy(startingCashInput = value) }
    }

    fun updateCashAddedInput(value: String) {
        _uiState.update { it.copy(cashAddedInput = value) }
    }

    fun updateCashAddedReasonInput(value: String) {
        _uiState.update { it.copy(cashAddedReasonInput = value) }
    }

    fun updateCashRemovedInput(value: String) {
        _uiState.update { it.copy(cashRemovedInput = value) }
    }

    fun updateCashRemovedReasonInput(value: String) {
        _uiState.update { it.copy(cashRemovedReasonInput = value) }
    }

    fun updateCashCountedInput(value: String) {
        _uiState.update { it.copy(cashCountedInput = value) }
    }

    fun showAddCashDialog(show: Boolean) {
        _uiState.update { it.copy(showAddCashDialog = show, cashAddedInput = "", cashAddedReasonInput = "") }
    }

    fun showRemoveCashDialog(show: Boolean) {
        _uiState.update { it.copy(showRemoveCashDialog = show, cashRemovedInput = "", cashRemovedReasonInput = "") }
    }

    fun showCloseShiftDialog(show: Boolean) {
        _uiState.update { it.copy(showCloseShiftDialog = show, cashCountedInput = "") }
    }

    fun openShift() {
        viewModelScope.launch {
            val state = _uiState.value
            val employee = state.employee
            if (employee == null) {
                _uiState.update { it.copy(statusMessage = "Sign in before opening shift.") }
                return@launch
            }
            val startingCashDouble = state.startingCashInput.toDoubleOrNull() ?: 150.00
            val startingCashCents = (startingCashDouble * 100).roundToInt()
            val result = container.shiftRepository.openShift(employeeId = employee.id, startingCashCents = startingCashCents)
            _uiState.update {
                it.copy(
                    statusMessage = String.format("Shift opened with float ₱%,.2f.", startingCashCents / 100.0),
                    startingCashInput = "150.00"
                )
            }
            if (result.joinedExisting) {
                _uiState.update { it.copy(statusMessage = "Joined existing shift #${result.shift.id}.") }
            }
            triggerSupabaseSync()
        }
    }

    fun closeShift() {
        viewModelScope.launch {
            val state = _uiState.value
            val shift = state.activeShift
            if (shift == null) {
                _uiState.update { it.copy(statusMessage = "No active shift to close.") }
                return@launch
            }
            val cashCountedDouble = state.cashCountedInput.toDoubleOrNull()
            if (cashCountedDouble == null) {
                _uiState.update { it.copy(statusMessage = "Please enter a valid amount for counted cash.") }
                return@launch
            }
            val cashCountedCents = (cashCountedDouble * 100).roundToInt()
            container.shiftRepository.closeShift(shiftId = shift.id, endingCashCents = cashCountedCents)
            _uiState.update {
                it.copy(
                    activeShift = null,
                    activeShiftCashSales = 0,
                    activeShiftGCashSales = 0,
                    showCloseShiftDialog = false,
                    cashCountedInput = "",
                    startingCashInput = formatPaymentInput(cashCountedCents),
                    statusMessage = "Shift #${shift.id} closed. Enter starting cash to open the next shift.",
                    screen = AppScreen.DRAWER
                )
            }
            triggerSupabaseSync()
        }
    }

    fun addCash() {
        viewModelScope.launch {
            val state = _uiState.value
            val shift = state.activeShift
            if (shift == null) {
                _uiState.update { it.copy(statusMessage = "No active shift.") }
                return@launch
            }
            val amountDouble = state.cashAddedInput.toDoubleOrNull()
            if (amountDouble == null || amountDouble <= 0) {
                _uiState.update { it.copy(statusMessage = "Please enter a valid positive amount.") }
                return@launch
            }
            val amountCents = (amountDouble * 100).roundToInt()
            container.shiftRepository.addCash(shiftId = shift.id, amountCents = amountCents)
            _uiState.update {
                it.copy(
                    showAddCashDialog = false,
                    cashAddedInput = "",
                    cashAddedReasonInput = "",
                    statusMessage = String.format("Added ₱%,.2f to drawer.", amountCents / 100.0)
                )
            }
            triggerSupabaseSync()
        }
    }

    fun removeCash() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isManager) {
                _uiState.update { it.copy(statusMessage = "Manager role required to remove cash.") }
                return@launch
            }
            if (!container.supabaseSyncManager.isConfigured()) {
                _uiState.update {
                    it.copy(statusMessage = "Configure Render Cloud and enroll this device before removing cash.")
                }
                return@launch
            }
            if (!container.supabaseSyncManager.isManagerTablet) {
                _uiState.update {
                    it.copy(statusMessage = "Only the designated Manager Tablet can remove cash.")
                }
                return@launch
            }
            val shift = state.activeShift
            if (shift == null) {
                _uiState.update { it.copy(statusMessage = "No active shift.") }
                return@launch
            }
            val amountDouble = state.cashRemovedInput.toDoubleOrNull()
            if (amountDouble == null || amountDouble <= 0) {
                _uiState.update { it.copy(statusMessage = "Please enter a valid positive amount.") }
                return@launch
            }
            val amountCents = (amountDouble * 100).roundToInt()
            container.shiftRepository.removeCash(shiftId = shift.id, amountCents = amountCents)
            _uiState.update {
                it.copy(
                    showRemoveCashDialog = false,
                    cashRemovedInput = "",
                    cashRemovedReasonInput = "",
                    statusMessage = String.format("Removed â‚±%,.2f from drawer.", amountCents / 100.0)
                )
            }
            triggerSupabaseSync()
        }
    }

    /** Called by the Manager screen to guarantee today's shift exists. */
    fun ensureTodayShift() {
        viewModelScope.launch {
            container.shiftRepository.ensureTodayShift()
        }
    }

    fun adjustInventory(ingredient: Ingredient, delta: Double) {
        viewModelScope.launch {
            container.inventoryRepository.adjust(ingredient.id, delta, "Manual manager adjustment")
            _uiState.update { it.copy(statusMessage = " adjusted.") }
            triggerSupabaseSync()
        }
    }

    fun restockIngredient(ingredient: Ingredient, qty: Double) {
        viewModelScope.launch {
            container.inventoryRepository.adjust(ingredient.id, qty, "Restock / Purchase Order")
            _uiState.update { it.copy(statusMessage = "Restocked   of .") }
            triggerSupabaseSync()
        }
    }

    fun updateIngredientSearchQuery(query: String) {
        _uiState.update { it.copy(ingredientSearchQuery = query) }
    }

    fun changeOrderDateRange(range: ReportDateRange) {
        _uiState.update { it.copy(orderDateRange = range) }
    }

    fun applyCustomOrderRange(start: Long, end: Long) {
        _uiState.update { it.copy(orderCustomStart = start, orderCustomEnd = end) }
    }

    fun openNewIngredientEditor() {
        if (!requireConfigurationAuthority("ingredient setup")) return
        _uiState.update {
            it.copy(
                showIngredientEditor = true,
                ingredientEditorId = null,
                ingredientEditorName = "",
                ingredientEditorUnit = "",
                ingredientEditorQty = "",
                ingredientEditorThreshold = "",
                ingredientEditorTakeoutOnly = false,
                ingredientEditorError = null
            )
        }
    }

    fun openEditIngredientEditor(ingredient: Ingredient) {
        if (!requireConfigurationAuthority("ingredient setup")) return
        _uiState.update {
            it.copy(
                showIngredientEditor = true,
                ingredientEditorId = ingredient.id,
                ingredientEditorName = ingredient.name,
                ingredientEditorUnit = ingredient.unit,
                ingredientEditorQty = ingredient.quantityOnHand.formatRecipeQuantity(),
                ingredientEditorThreshold = ingredient.lowStockThreshold.formatRecipeQuantity(),
                ingredientEditorTakeoutOnly = ingredient.takeoutOnly,
                ingredientEditorError = null
            )
        }
    }

    fun closeIngredientEditor() {
        _uiState.update { it.copy(showIngredientEditor = false, ingredientEditorError = null) }
    }

    fun updateIngredientEditorName(value: String) {
        _uiState.update { it.copy(ingredientEditorName = value, ingredientEditorError = null) }
    }

    fun updateIngredientEditorUnit(value: String) {
        _uiState.update { it.copy(ingredientEditorUnit = value, ingredientEditorError = null) }
    }

    fun updateIngredientEditorQty(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }.take(10)
        _uiState.update { it.copy(ingredientEditorQty = clean, ingredientEditorError = null) }
    }

    fun updateIngredientEditorThreshold(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }.take(10)
        _uiState.update { it.copy(ingredientEditorThreshold = clean, ingredientEditorError = null) }
    }

    fun updateIngredientEditorTakeoutOnly(value: Boolean) {
        _uiState.update { it.copy(ingredientEditorTakeoutOnly = value) }
    }

    fun saveIngredientFromEditor() {
        viewModelScope.launch {
            if (!requireConfigurationAuthority("ingredient setup")) return@launch
            val state = _uiState.value
            val name = state.ingredientEditorName.trim()
            val unit = state.ingredientEditorUnit.trim()
            if (name.isBlank()) {
                _uiState.update { it.copy(ingredientEditorError = "Enter an ingredient name.") }
                return@launch
            }
            if (unit.isBlank()) {
                _uiState.update { it.copy(ingredientEditorError = "Enter a unit (e.g. oz, ea, ml).") }
                return@launch
            }
            val qty = state.ingredientEditorQty.toDoubleOrNull() ?: 0.0
            val threshold = state.ingredientEditorThreshold.toDoubleOrNull() ?: 0.0
            val takeoutOnly = state.ingredientEditorTakeoutOnly
            val id = state.ingredientEditorId ?: normalizeIngredientId(name)
            if (state.ingredientEditorId == null) {
                val existing = state.ingredients.firstOrNull { it.id == id }
                if (existing != null) {
                    _uiState.update {
                        it.copy(ingredientEditorError = "An ingredient named \"${existing.name}\" already exists. Edit it instead.")
                    }
                    return@launch
                }
            }
            val ingredient = Ingredient(
                id = id,
                name = name,
                unit = unit,
                quantityOnHand = qty,
                lowStockThreshold = threshold,
                takeoutOnly = takeoutOnly
            )
            val saveResult = container.supabaseSyncManager.runManagerConfigurationMutation {
                if (state.ingredientEditorId == null) {
                    container.inventoryRepository.createIngredient(ingredient)
                } else {
                    val existing = container.inventoryRepository.ingredientById(id)
                    val currentQuantity = existing?.quantityOnHand ?: qty
                    container.inventoryRepository.saveIngredient(ingredient.copy(quantityOnHand = currentQuantity))
                    val quantityChange = qty - currentQuantity
                    if (kotlin.math.abs(quantityChange) > 0.000001) {
                        container.inventoryRepository.adjust(id, quantityChange, "Manager quantity correction")
                    }
                    true
                }
            }
            if (saveResult.isFailure) {
                _uiState.update {
                    it.copy(ingredientEditorError = saveResult.exceptionOrNull()?.localizedMessage
                        ?: "Only the designated Manager Tablet can edit ingredient setup.")
                }
                return@launch
            }
            if (saveResult.getOrNull() != true) {
                val existingName = container.inventoryRepository.ingredientById(id)?.name ?: name
                _uiState.update {
                    it.copy(ingredientEditorError = "An ingredient named \"$existingName\" already exists. Edit it instead.")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    showIngredientEditor = false,
                    ingredientEditorError = null,
                    statusMessage = " saved."
                )
            }
            triggerSupabaseSync()
        }
    }

    fun deleteIngredientById(ingredientId: String, name: String) {
        viewModelScope.launch {
            if (!requireConfigurationAuthority("ingredient setup")) return@launch
            container.inventoryRepository.deleteIngredient(ingredientId)
            _uiState.update { it.copy(statusMessage = " deleted.") }
            triggerSupabaseSync()
        }
    }

    fun openAddOnDialog(orderId: String) {
        _uiState.update {
            it.copy(
                showAddOnDialog = true,
                addOnOrderId = orderId,
                addOnSearchQuery = "",
                addOnSelectedQuantities = emptyMap()
            )
        }
    }

    fun closeAddOnDialog() {
        _uiState.update {
            it.copy(
                showAddOnDialog = false,
                addOnOrderId = null,
                addOnSearchQuery = ""
            )
        }
    }

    fun updateAddOnSearchQuery(value: String) {
        _uiState.update { it.copy(addOnSearchQuery = value) }
    }

    fun addIngredientToAddOns(ingredientId: String) {
        _uiState.update {
            val updated = it.addOnSelectedQuantities.toMutableMap()
            updated[ingredientId] = 1.0
            it.copy(addOnSelectedQuantities = updated, addOnSearchQuery = "")
        }
    }

    fun removeIngredientFromAddOns(ingredientId: String) {
        _uiState.update {
            val updated = it.addOnSelectedQuantities.toMutableMap()
            updated.remove(ingredientId)
            it.copy(addOnSelectedQuantities = updated)
        }
    }

    fun adjustAddOnQuantity(ingredientId: String, delta: Double) {
        _uiState.update {
            val updated = it.addOnSelectedQuantities.toMutableMap()
            val current = updated[ingredientId] ?: 0.0
            val next = (current + delta).coerceAtLeast(0.0)
            if (next == 0.0) {
                updated.remove(ingredientId)
            } else {
                updated[ingredientId] = next
            }
            it.copy(addOnSelectedQuantities = updated)
        }
    }
    fun submitAddOns() {
        val state = _uiState.value
        val orderId = state.addOnOrderId ?: return
        val itemsToDeduct = state.addOnSelectedQuantities.filter { it.value > 0 }
        
        viewModelScope.launch {
            itemsToDeduct.forEach { (ingId, qty) ->
                val ingName = state.ingredients.find { it.id == ingId }?.name ?: ingId
                container.inventoryRepository.recordOrderAddOn(
                    orderId = orderId,
                    ingredientId = ingId,
                    quantity = qty,
                    reason = "Post-checkout add-on (Order ${orderId.take(8).uppercase()}): $ingName"
                )
            }
            
            val detailsStr = itemsToDeduct.map { (ingId, qty) ->
                val name = state.ingredients.find { it.id == ingId }?.name ?: ingId
                "${qty.formatQty()} $name"
            }.joinToString(", ")
            
            _uiState.update {
                it.copy(
                    showAddOnDialog = false,
                    addOnOrderId = null,
                    statusMessage = "Deducted post-checkout add-ons ($detailsStr) for Order #${orderId.take(8).uppercase()} from stock."
                )
            }
            triggerSupabaseSync()
        }
    }

    private fun Double.formatQty(): String = if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(Locale.US, this)

    fun updateMenuFormName(value: String) {
        _uiState.update { it.copy(menuFormName = value, menuFormError = null) }
    }

    fun startNewMenuItem() {
        if (!requireConfigurationAuthority("the menu")) return
        val categoryId = _uiState.value.menuFormCategoryId
        val sharedDrinkExclusions = sharedDrinkComplementaryExclusions(_uiState.value.catalog.items)
        _uiState.update {
            it.copy(
                menuFormEditingItemId = null,
                menuFormName = "",
                menuFormDescription = "",
                menuFormCategoryId = categoryId,
                menuFormPrice = "",
                menuFormModifierGroupIds = emptySet(),
                menuFormRecipeQuantities = emptyMap(),
                menuFormComplementaryExclusions = if (isDrinkCategory(categoryId)) sharedDrinkExclusions else emptySet(),
                menuFormError = null,
                statusMessage = "Ready to add a new menu item."
            )
        }
    }

    fun editMenuItem(item: MenuItem) {
        viewModelScope.launch {
            if (!requireConfigurationAuthority("the menu")) return@launch
            val modifierGroupIds = container.menuRepository.modifierGroupIdsForItem(item.id).toSet()
            val recipeQuantities = container.inventoryRepository.recipeForItem(item.id)
                .associate { it.ingredientId to it.quantityUsed.formatRecipeQuantity() }
            val exclusions = if (isDrinkCategory(item.categoryId)) {
                sharedDrinkComplementaryExclusions(_uiState.value.catalog.items)
            } else {
                item.complementaryExclusions.split(",").filter { it.isNotBlank() }.toSet()
            }
            _uiState.update {
                it.copy(
                    selectedCategoryId = item.categoryId,
                    menuFormEditingItemId = item.id,
                    menuFormName = item.name,
                    menuFormDescription = item.description,
                    menuFormCategoryId = item.categoryId,
                    menuFormPrice = String.format(Locale.US, "%.2f", item.basePriceCents / 100.0),
                    menuFormModifierGroupIds = modifierGroupIds,
                    menuFormRecipeQuantities = recipeQuantities,
                    menuFormComplementaryExclusions = exclusions,
                    menuFormError = null,
                    statusMessage = "Editing ${item.name}."
                )
            }
        }
    }

    fun updateMenuFormDescription(value: String) {
        _uiState.update { it.copy(menuFormDescription = value, menuFormError = null) }
    }

    fun updateMenuFormCategory(categoryId: String) {
        _uiState.update {
            it.copy(
                menuFormCategoryId = categoryId,
                menuFormComplementaryExclusions = if (isDrinkCategory(categoryId)) {
                    sharedDrinkComplementaryExclusions(it.catalog.items)
                } else {
                    emptySet()
                },
                menuFormError = null
            )
        }
    }

    fun updateMenuFormPrice(value: String) {
        _uiState.update { state ->
            state.copy(
                menuFormPrice = value.filter { it.isDigit() || it == '.' }.take(8),
                menuFormError = null
            )
        }
    }

    fun toggleMenuFormModifierGroup(groupId: String) {
        _uiState.update { state ->
            val next = if (groupId in state.menuFormModifierGroupIds) {
                state.menuFormModifierGroupIds - groupId
            } else {
                state.menuFormModifierGroupIds + groupId
            }
            state.copy(menuFormModifierGroupIds = next, menuFormError = null)
        }
    }

    fun updateMenuFormRecipeQuantity(ingredientId: String, value: String) {
        _uiState.update { state ->
            val clean = value.filter { it.isDigit() || it == '.' }.take(8)
            val next = if (clean.isBlank()) {
                state.menuFormRecipeQuantities - ingredientId
            } else {
                state.menuFormRecipeQuantities + (ingredientId to clean)
            }
            state.copy(menuFormRecipeQuantities = next, menuFormError = null)
        }
    }

    fun applyMenuItemMeasurementTemplate(item: MenuItem) {
        val availableIngredientIds = _uiState.value.ingredients.map { it.id }.toSet()
        val template = measurementTemplateFor(item.id)
        _uiState.update { state ->
            state.copy(
                menuFormRecipeQuantities = template.filterKeys { it in availableIngredientIds },
                menuFormError = null,
                statusMessage = "${item.name} measurements applied."
            )
        }
    }

    fun toggleMenuFormComplementaryExclusion(ingredientId: String) {
        _uiState.update { state ->
            val next = if (ingredientId in state.menuFormComplementaryExclusions) {
                state.menuFormComplementaryExclusions - ingredientId
            } else {
                state.menuFormComplementaryExclusions + ingredientId
            }
            state.copy(menuFormComplementaryExclusions = next, menuFormError = null)
        }
    }

    fun saveMenuItemFromForm() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("the menu")) return@launch

            val name = state.menuFormName.trim()
            val description = state.menuFormDescription.trim()
            val categoryId = state.menuFormCategoryId.ifBlank { state.catalog.categories.firstOrNull()?.id.orEmpty() }
            val priceCents = ((state.menuFormPrice.toDoubleOrNull() ?: 0.0) * 100).toInt()
            val recipeRows = state.menuFormRecipeQuantities.mapNotNull { (ingredientId, value) ->
                val quantity = value.toDoubleOrNull() ?: 0.0
                if (quantity > 0.0) ingredientId to quantity else null
            }

            val error = when {
                name.isBlank() -> "Enter an item name."
                categoryId.isBlank() -> "Choose a category."
                priceCents <= 0 -> "Enter a price greater than ₱0."
                recipeRows.isEmpty() -> "Add at least one ingredient quantity for inventory deduction."
                else -> null
            }
            if (error != null) {
                _uiState.update { it.copy(menuFormError = error) }
                return@launch
            }

            val editingItemId = state.menuFormEditingItemId
            val itemId = editingItemId ?: name.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "menu-item" } + "-" + UUID.randomUUID().toString().take(6)
            val complementaryExclusionsStr = state.menuFormComplementaryExclusions.joinToString(",")
            val item = MenuItem(
                id = itemId,
                categoryId = categoryId,
                name = name,
                description = description.ifBlank { "Custom menu item" },
                basePriceCents = priceCents,
                active = true,
                complementaryExclusions = complementaryExclusionsStr
            )

            container.menuRepository.saveMenuItem(item, state.menuFormModifierGroupIds.toList())
            if (isDrinkCategory(categoryId)) {
                val sharedDrinkItemUpdates = state.catalog.items
                    .filter { isDrinkCategory(it.categoryId) && it.id != itemId }
                    .map { it.copy(complementaryExclusions = complementaryExclusionsStr) }
                container.menuRepository.saveMenuItems(sharedDrinkItemUpdates)
            }
            container.inventoryRepository.replaceRecipe(
                itemId,
                recipeRows.map { (ingredientId, quantity) ->
                    RecipeIngredient(itemId, ingredientId, quantity)
                }
            )
            _uiState.update {
                it.copy(
                    selectedCategoryId = categoryId,
                    menuFormEditingItemId = null,
                    menuFormName = "",
                    menuFormDescription = "",
                    menuFormCategoryId = categoryId,
                    menuFormPrice = "",
                    menuFormModifierGroupIds = emptySet(),
                    menuFormRecipeQuantities = emptyMap(),
                    menuFormComplementaryExclusions = emptySet(),
                    menuFormError = null,
                    screen = if (editingItemId == null) AppScreen.POS else AppScreen.MENU,
                    statusMessage = if (editingItemId == null) "$name added to menu." else "$name updated."
                )
            }
        }
    }

    fun updateSettingsName(value: String) {
        _uiState.update { it.copy(settingsFormName = value, settingsFormError = null) }
    }

    fun updateSettingsTaxRate(value: String) {
        val clean = value.filter { it.isDigit() || it == '.' }.take(6)
        _uiState.update { it.copy(settingsFormTaxRate = clean, settingsFormError = null) }
    }

    fun updateSettingsTipPresets(value: String) {
        val clean = value.filter { it.isDigit() || it == ',' || it == ' ' }.take(30)
        _uiState.update { it.copy(settingsFormTipPresets = clean, settingsFormError = null) }
    }

    fun updateSettingsFooter(value: String) {
        _uiState.update { it.copy(settingsFormFooter = value, settingsFormError = null) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("store settings")) return@launch
            val name = state.settingsFormName.trim()
            val footer = state.settingsFormFooter.trim()

            val error = when {
                name.isBlank() -> "Enter a store name."
                else -> null
            }
            if (error != null) {
                _uiState.update { it.copy(settingsFormError = error) }
                return@launch
            }

            val newSettings = com.kape.coffeepos.data.StoreSettings(
                storeName = name,
                taxRatePercent = 0.0,
                tipPresets = "",
                receiptFooter = footer,
                seniorDiscountPercent = state.settings.seniorDiscountPercent,
                pwdDiscountPercent = state.settings.pwdDiscountPercent,
                voidRefundPin = state.settings.voidRefundPin
            )
            val saveResult = container.supabaseSyncManager.runManagerConfigurationMutation {
                container.settingsRepository.saveSettings(newSettings)
            }
            if (saveResult.isFailure) {
                _uiState.update {
                    it.copy(statusMessage = saveResult.exceptionOrNull()?.localizedMessage
                        ?: "Only the designated Manager Tablet can edit store settings.")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    settings = newSettings,
                    settingsFormError = null,
                    statusMessage = "Settings saved successfully."
                )
            }
        }
    }

    fun updateSeniorDiscountPercent(value: String) {
        _uiState.update { it.copy(settingsFormSeniorPercent = value, discountSettingsError = null) }
    }

    fun updatePwdDiscountPercent(value: String) {
        _uiState.update { it.copy(settingsFormPwdPercent = value, discountSettingsError = null) }
    }

    fun updateSeniorPwdIdInput(value: String) {
        _uiState.update { it.copy(seniorPwdIdInput = value, paymentError = null) }
    }

    fun selectDiscountCategory(category: String) {
        if (_uiState.value.promotionReservationToken != null) {
            _uiState.update { it.copy(paymentError = "Remove the free drink reward before selecting another discount.") }
            return
        }
        _uiState.update { state ->
            val cleanPct = when (category) {
                "Senior" -> state.settings.seniorDiscountPercent
                "PWD" -> state.settings.pwdDiscountPercent
                else -> 0.0
            }
            val customRule = category.removePrefix("RULE:")
                .takeIf { category.startsWith("RULE:") }
                ?.let { id -> state.discountRules.firstOrNull { it.id == id && it.active } }
            val resolvedPercent = customRule?.percent ?: cleanPct
            val selectedLineId = if (category == "None" || customRule?.scope == "order") null else state.selectedDiscountLineId
            val discountCents = if (customRule?.scope == "order") {
                calculateWholeOrderDiscountCents(state.cart, resolvedPercent)
            } else {
                calculateSingleItemDiscountCents(state.cart, selectedLineId, resolvedPercent)
            }
            state.copy(
                selectedDiscountCategory = category,
                seniorPwdIdInput = if (category == "None") "" else state.seniorPwdIdInput,
                selectedDiscountLineId = selectedLineId,
                discountType = "percent",
                discountInput = resolvedPercent.toString(),
                discountCents = discountCents,
                paymentError = null
            )
        }
    }

    fun selectDiscountLine(lineId: String) {
        _uiState.update { state ->
            if (state.selectedDiscountCategory == "None" || state.cart.none { it.id == lineId }) {
                state
            } else {
                state.copy(
                    selectedDiscountLineId = lineId,
                    discountCents = recalculateSelectedItemDiscount(state, selectedLineId = lineId),
                    paymentError = null
                )
            }
        }
    }

    fun saveDiscountSettings() {
        viewModelScope.launch {
            if (!requireConfigurationAuthority("discount settings")) return@launch
            val state = _uiState.value
            val validationError = discountSettingsValidationError(
                state.settingsFormSeniorPercent,
                state.settingsFormPwdPercent
            )
            if (validationError != null) {
                _uiState.update { it.copy(discountSettingsError = validationError) }
                return@launch
            }
            val seniorVal = state.settingsFormSeniorPercent.toDouble()
            val pwdVal = state.settingsFormPwdPercent.toDouble()
            val updated = state.settings.copy(
                seniorDiscountPercent = seniorVal,
                pwdDiscountPercent = pwdVal
            )
            val saveResult = container.supabaseSyncManager.runManagerConfigurationMutation {
                container.settingsRepository.saveSettings(updated)
            }
            if (saveResult.isFailure) {
                _uiState.update {
                    it.copy(statusMessage = saveResult.exceptionOrNull()?.localizedMessage
                        ?: "Only the designated Manager Tablet can edit discount settings.")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    settings = updated,
                    discountSettingsError = null,
                    statusMessage = "Discount percentages saved."
                )
            }
        }
    }

    fun viewReceiptForOrder(orderId: String) {
        viewModelScope.launch {
            val receipt = container.orderRepository.receipt(orderId)
            val order = _uiState.value.orders.firstOrNull { it.id == orderId }
            if (receipt != null && order != null) {
                val isAuditReceipt = order.status == "void" || order.status == "refunded"
                val displayText = if (isAuditReceipt) {
                    terminalAuditReceiptText(receipt.text, order.status, order.voidReason)
                } else {
                    receipt.text
                }
                _uiState.update {
                    it.copy(
                        receiptText = displayText,
                        receiptOrderId = orderId,
                        receiptAuditStatus = order.status.takeIf { status -> isAuditReceipt },
                        receiptPromotionState = if (isAuditReceipt) ReceiptPromotionState.READY else ReceiptPromotionState.CHECKING,
                        receiptPromotionResult = null,
                        receiptCopyStage = ReceiptCopyStage.FIRST_COPY,
                        receiptSecondCopyCountdown = null
                    )
                }
                if (!isAuditReceipt) {
                    prepareReceiptForPrinting(
                        orderId = orderId,
                        receiptText = receipt.text,
                        reservationToken = null,
                        employeeId = _uiState.value.employee?.id.orEmpty(),
                        autoPrint = false
                    )
                }
            } else {
                _uiState.update { it.copy(statusMessage = "Receipt not found for order.") }
            }
        }
    }

    fun openNewEmployeeEditor() {
        if (!requireConfigurationAuthority("employees")) return
        _uiState.update {
            it.copy(
                showEmployeeEditor = true,
                employeeEditorId = null,
                employeeEditorName = "",
                employeeEditorPin = "",
                employeeEditorRole = "cashier",
                employeeEditorActive = true,
                employeeEditorError = null
            )
        }
    }

    fun openEditEmployeeEditor(employee: Employee) {
        if (!requireConfigurationAuthority("employees")) return
        _uiState.update {
            it.copy(
                showEmployeeEditor = true,
                employeeEditorId = employee.id,
                employeeEditorName = employee.name,
                employeeEditorPin = employee.pin,
                employeeEditorRole = employee.role,
                employeeEditorActive = employee.active,
                employeeEditorError = null
            )
        }
    }

    fun closeEmployeeEditor() {
        _uiState.update { it.copy(showEmployeeEditor = false, employeeEditorError = null) }
    }

    fun updateEmployeeEditorName(value: String) {
        _uiState.update { it.copy(employeeEditorName = value, employeeEditorError = null) }
    }

    fun updateEmployeeEditorPin(value: String) {
        val clean = value.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(employeeEditorPin = clean, employeeEditorError = null) }
    }

    fun updateEmployeeEditorRole(value: String) {
        _uiState.update { it.copy(employeeEditorRole = value, employeeEditorError = null) }
    }

    fun updateEmployeeEditorActive(value: Boolean) {
        _uiState.update { it.copy(employeeEditorActive = value, employeeEditorError = null) }
    }

    fun saveEmployeeFromEditor() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!requireConfigurationAuthority("employees")) return@launch
            val name = state.employeeEditorName.trim()
            val pin = state.employeeEditorPin.trim()
            val role = state.employeeEditorRole
            val active = state.employeeEditorActive

            val error = when {
                name.isBlank() -> "Enter an employee name."
                pin.length < 4 -> "PIN must be at least 4 digits."
                else -> null
            }
            if (error != null) {
                _uiState.update { it.copy(employeeEditorError = error) }
                return@launch
            }

            val pinConflict = state.allEmployees.any { it.pin == pin && it.id != state.employeeEditorId && it.active }
            if (pinConflict) {
                _uiState.update { it.copy(employeeEditorError = "PIN already in use.") }
                return@launch
            }

            val id = state.employeeEditorId
                ?: name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    .ifBlank { "emp-${UUID.randomUUID().toString().take(8)}" }

            val emp = Employee(
                id = id,
                name = name,
                pin = pin,
                role = role,
                active = active
            )
            container.employeeRepository.saveEmployee(emp)
            _uiState.update {
                it.copy(
                    showEmployeeEditor = false,
                    employeeEditorError = null,
                    statusMessage = "Employee $name saved."
                )
            }
        }
    }

    fun voidOrder(orderId: String) {
        viewModelScope.launch {
            val result = container.orderRepository.voidOrder(orderId)
            _uiState.update {
                it.copy(statusMessage = (listOf(result.message) + result.warnings).joinToString(" "))
            }
        }
    }

    fun refundOrder(orderId: String) {
        viewModelScope.launch {
            val result = container.orderRepository.refundOrder(orderId)
            _uiState.update { it.copy(statusMessage = result.message) }
        }
    }

    // --- Void/Refund PIN flow ---

    fun startVoidWithPin(orderId: String) {
        _uiState.update { it.copy(pendingVoidOrderId = orderId, pendingRefundOrderId = null, voidPinInput = "", voidPinError = null) }
    }

    fun startRefundWithPin(orderId: String) {
        _uiState.update { it.copy(pendingRefundOrderId = orderId, pendingVoidOrderId = null, voidPinInput = "", voidPinError = null) }
    }

    fun cancelVoidRefundPin() {
        _uiState.update { it.copy(pendingVoidOrderId = null, pendingRefundOrderId = null, voidPinInput = "", voidPinError = null) }
    }

    fun updateVoidPinInput(value: String) {
        if (value.length <= 4 && value.all { it.isDigit() }) {
            _uiState.update { it.copy(voidPinInput = value, voidPinError = null) }
        }
    }

    fun submitVoidRefundPin() {
        val state = _uiState.value
        val enteredPin = state.voidPinInput
        val correctPin = state.settings.voidRefundPin
        if (enteredPin != correctPin) {
            _uiState.update { it.copy(voidPinError = "Incorrect PIN. Please try again.") }
            return
        }
        val voidId = state.pendingVoidOrderId
        val refundId = state.pendingRefundOrderId
        _uiState.update { it.copy(pendingVoidOrderId = null, pendingRefundOrderId = null, voidPinInput = "", voidPinError = null) }
        if (voidId != null) {
            voidOrder(voidId)
        } else if (refundId != null) {
            refundOrder(refundId)
        }
    }

    fun getReportCsvContent(): String {
        val report = uiState.value.dailyReport
        val range = uiState.value.reportDateRange
        val start = uiState.value.reportCustomStart
        val end = uiState.value.reportCustomEnd

        val baseRangeName = when (range) {
            ReportDateRange.TODAY -> "Today"
            ReportDateRange.MONTH -> "Last 30 Days"
            ReportDateRange.ALL -> "All Time"
            ReportDateRange.CUSTOM -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                val startStr = if (start != null) sdf.format(java.util.Date(start)) else "Start"
                val endStr = if (end != null) sdf.format(java.util.Date(end)) else "End"
                "Custom Range ($startStr to $endStr)"
            }
        }
        val cashierName = selectedReportCashierName(uiState.value)
        val rangeName = if (cashierName != null) {
            "$baseRangeName (Cashier: $cashierName)"
        } else {
            baseRangeName
        }

        val sb = java.lang.StringBuilder()

        fun formatMoney(cents: Int): String {
            val pesos = cents / 100.0
            return "₱" + String.format(java.util.Locale.US, "%,.2f", pesos)
        }

        fun escapeCsv(value: String): String {
            var escaped = value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ')
            if (escaped.contains(",") || escaped.contains("\"")) {
                escaped = "\"$escaped\""
            }
            return escaped
        }


        sb.append("Kanlungan Coffee Garage POS - Daily Report\n")
        sb.append("Report Date Range,").append(escapeCsv(rangeName)).append("\n")
        sb.append("Generated At,").append(escapeCsv(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        }.format(java.util.Date()))).append("\n\n")

        // Summary Section
        sb.append("SUMMARY METRICS\n")
        sb.append("Metric,Value\n")
        sb.append("Total Orders,").append(report.orderCount).append("\n")
        sb.append("Gross Sales,").append(escapeCsv(formatMoney(report.grossSalesCents))).append("\n")
        if (report.discountsCents > 0) {
            sb.append("Less Discounts,").append(escapeCsv("-" + formatMoney(report.discountsCents))).append("\n")
        }
        sb.append("Net Sales,").append(escapeCsv(formatMoney(report.netSalesCents))).append("\n\n")

        // Payments Section
        sb.append("PAYMENT BREAKDOWN\n")
        sb.append("Payment Method,Total Amount\n")
        if (report.paymentTotals.isEmpty()) {
            sb.append("No transactions,0\n")
        } else {
            report.paymentTotals.forEach { (method, cents) ->
                sb.append(escapeCsv(method)).append(",").append(escapeCsv(formatMoney(cents))).append("\n")
            }
        }
        sb.append("\n")

        // Top Selling Items Section
        sb.append("TOP SELLING ITEMS\n")
        sb.append("Item Name,Quantity Sold,Revenue\n")
        if (report.topItems.isEmpty()) {
            sb.append("No items sold,0,₱0.00\n")
        } else {
            report.topItems.forEach { item ->
                sb.append(escapeCsv(item.name)).append(",")
                  .append(item.qtySold).append(",")
                  .append(escapeCsv(formatMoney(item.revenueCents))).append("\n")
            }
        }
        sb.append("\n")

        // Cash Drawer Summary Section
        val gcashSales = report.onlinePaymentSalesCents
        val totalCashAndGCash = report.cashDrawerExpected + gcashSales
        sb.append("CASH DRAWER SUMMARY\n")
        sb.append("Metric,Value\n")
        sb.append("Starting Cash,").append(escapeCsv(formatMoney(report.cashDrawerStarting))).append("\n")
        sb.append("Expected Cash Ending,").append(escapeCsv(formatMoney(report.cashDrawerExpected))).append("\n")
        sb.append("Online Payments,").append(escapeCsv(formatMoney(gcashSales))).append("\n")
        sb.append("Total Cash + Online Payment,").append(escapeCsv(formatMoney(totalCashAndGCash))).append("\n")
        sb.append("Actual Cash Ending,").append(escapeCsv(formatMoney(report.cashDrawerActual))).append("\n")
        sb.append("Difference,").append(escapeCsv(formatMoney(report.cashDrawerDifference))).append("\n")
        sb.append("Cash Sales,").append(escapeCsv(formatMoney(report.cashDrawerSales))).append("\n")
        sb.append("Cash Added,").append(escapeCsv(formatMoney(report.cashDrawerAdded))).append("\n")
        sb.append("Cash Removed,").append(escapeCsv(formatMoney(report.cashDrawerRemoved))).append("\n")

        return sb.toString()
    }

    fun getDailyReportExcelContent(): ByteArray {
        val state = uiState.value
        check(state.isReportRangeReady) { state.reportRangeError ?: "Select a valid report range." }
        val report = state.dailyReport
        val baseRangeName = when (state.reportDateRange) {
            ReportDateRange.TODAY -> "Today"
            ReportDateRange.MONTH -> "Last 30 Days"
            ReportDateRange.ALL -> "All Time"
            ReportDateRange.CUSTOM -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                val startStr = state.reportCustomStart?.let { sdf.format(java.util.Date(it)) } ?: "Start"
                val endStr = state.reportCustomEnd?.let { sdf.format(java.util.Date(it)) } ?: "End"
                "Custom Range ($startStr to $endStr)"
            }
        }
        val cashierName = selectedReportCashierName(state)
        val rangeName = if (cashierName != null) {
            "$baseRangeName (Cashier: $cashierName)"
        } else {
            baseRangeName
        }
        val generatedAt = java.text.SimpleDateFormat("M/d/yyyy HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        }.format(java.util.Date())

        fun xml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun textCell(ref: String, style: Int, value: String): String =
            """<c r="$ref" s="$style" t="inlineStr"><is><t>${xml(value)}</t></is></c>"""

        fun numberCell(ref: String, style: Int, value: Number): String =
            """<c r="$ref" s="$style"><v>$value</v></c>"""

        fun row(rowNumber: Int, cells: String): String = """<row r="$rowNumber">$cells</row>"""

        fun addEntry(zip: ZipOutputStream, name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val sheetData = StringBuilder()
        sheetData.append(row(1, textCell("A1", 1, "Kanlungan Coffee Garage POS - Daily Report")))
        sheetData.append(row(2, textCell("A2", 6, "Clean export layout - Black & White only")))

        sheetData.append(row(4, textCell("A4", 2, "REPORT DETAILS")))
        sheetData.append(row(5, textCell("A5", 3, "Report Field") + textCell("B5", 3, "Value")))
        sheetData.append(row(6, textCell("A6", 4, "Report Date Range") + textCell("B6", 4, rangeName)))
        sheetData.append(row(7, textCell("A7", 4, "Generated At") + textCell("B7", 4, generatedAt)))

        sheetData.append(row(9, textCell("A9", 2, "SUMMARY METRICS")))
        sheetData.append(row(10, textCell("A10", 3, "Metric") + textCell("B10", 3, "Value")))
        sheetData.append(row(11, textCell("A11", 4, "Total Orders") + numberCell("B11", 7, report.orderCount)))
        sheetData.append(row(12, textCell("A12", 4, "Gross Sales") + numberCell("B12", 5, report.grossSalesCents / 100.0)))
        if (report.discountsCents > 0) {
            sheetData.append(row(13, textCell("A13", 4, "Less Discounts") + numberCell("B13", 5, -(report.discountsCents / 100.0))))
            sheetData.append(row(14, textCell("A14", 4, "Net Sales") + numberCell("B14", 5, report.netSalesCents / 100.0)))
        } else {
            sheetData.append(row(13, textCell("A13", 4, "Net Sales") + numberCell("B13", 5, report.netSalesCents / 100.0)))
        }

        sheetData.append(row(15, textCell("A15", 2, "PAYMENT BREAKDOWN")))
        sheetData.append(row(16, textCell("A16", 3, "Payment Method") + textCell("B16", 3, "Total Amount")))
        var currentRow = 17
        if (report.paymentTotals.isEmpty()) {
            sheetData.append(row(currentRow, textCell("A$currentRow", 4, "No transactions") + numberCell("B$currentRow", 5, 0)))
            currentRow++
        } else {
            report.paymentTotals.forEach { (method, cents) ->
                sheetData.append(row(currentRow, textCell("A$currentRow", 4, method) + numberCell("B$currentRow", 5, cents / 100.0)))
                currentRow++
            }
        }

        currentRow++
        val topItemsTitleRow = currentRow
        sheetData.append(row(currentRow, textCell("A$currentRow", 2, "TOP SELLING ITEMS")))
        currentRow++
        sheetData.append(row(currentRow,
            textCell("A$currentRow", 3, "Item Name") +
                textCell("B$currentRow", 3, "Quantity Sold") +
                textCell("C$currentRow", 3, "Revenue")
        ))
        currentRow++
        if (report.topItems.isEmpty()) {
            sheetData.append(row(currentRow,
                textCell("A$currentRow", 4, "No items sold") +
                    numberCell("B$currentRow", 5, 0) +
                    numberCell("C$currentRow", 5, 0)
            ))
            currentRow++
        } else {
            report.topItems.forEach { item ->
                sheetData.append(row(currentRow,
                    textCell("A$currentRow", 4, item.name) +
                        numberCell("B$currentRow", 7, item.qtySold) +
                        numberCell("C$currentRow", 5, item.revenueCents / 100.0)
                ))
                currentRow++
            }
        }

        currentRow++
        val cashTitleRow = currentRow
        val gcashSales = report.onlinePaymentSalesCents
        val totalCashAndGCash = report.cashDrawerExpected + gcashSales
        sheetData.append(row(currentRow, textCell("A$currentRow", 2, "CASH DRAWER SUMMARY")))
        currentRow++
        sheetData.append(row(currentRow, textCell("A$currentRow", 3, "Metric") + textCell("B$currentRow", 3, "Value")))
        currentRow++
        listOf(
            "Starting Cash" to report.cashDrawerStarting,
            "Expected Cash Ending" to report.cashDrawerExpected,
            "Online Payments" to gcashSales,
            "Total Cash + Online Payment" to totalCashAndGCash,
            "Actual Cash Ending" to report.cashDrawerActual,
            "Difference" to report.cashDrawerDifference,
            "Cash Sales" to report.cashDrawerSales,
            "Cash Added" to report.cashDrawerAdded,
            "Cash Removed" to report.cashDrawerRemoved
        ).forEach { (label, cents) ->
            sheetData.append(row(currentRow, textCell("A$currentRow", 4, label) + numberCell("B$currentRow", 5, cents / 100.0)))
            currentRow++
        }

        currentRow++
        sheetData.append(row(currentRow, textCell("A$currentRow", 6, "End of Daily Report")))

        val worksheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheetViews><sheetView workbookViewId="0"/></sheetViews>
  <sheetFormatPr defaultRowHeight="15"/>
  <cols>
    <col min="1" max="1" width="28" customWidth="1"/>
    <col min="2" max="2" width="18" customWidth="1"/>
    <col min="3" max="3" width="18" customWidth="1"/>
    <col min="4" max="6" width="14" customWidth="1"/>
  </cols>
  <sheetData>$sheetData</sheetData>
  <mergeCells count="6">
    <mergeCell ref="A1:F1"/>
    <mergeCell ref="A2:F2"/>
    <mergeCell ref="A4:F4"/>
    <mergeCell ref="A9:F9"/>
    <mergeCell ref="A$topItemsTitleRow:F$topItemsTitleRow"/>
    <mergeCell ref="A$cashTitleRow:F$cashTitleRow"/>
  </mergeCells>
</worksheet>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="2">
    <numFmt numFmtId="164" formatCode="#,##0.00"/>
    <numFmt numFmtId="165" formatCode="#,##0"/>
  </numFmts>
  <fonts count="3">
    <font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="12"/><color rgb="FF000000"/><name val="Calibri"/><family val="2"/></font>
    <font><i/><sz val="10"/><color rgb="FF333333"/><name val="Calibri"/><family val="2"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFD9D9D9"/></left>
      <right style="thin"><color rgb="FFD9D9D9"/></right>
      <top style="thin"><color rgb="FFD9D9D9"/></top>
      <bottom style="thin"><color rgb="FFD9D9D9"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="8">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
  <dxfs count="0"/>
  <tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
</styleSheet>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Daily Report" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                addEntry(zip, "[Content_Types].xml", contentTypes)
                addEntry(zip, "_rels/.rels", rootRels)
                addEntry(zip, "xl/workbook.xml", workbook)
                addEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels)
                addEntry(zip, "xl/styles.xml", styles)
                addEntry(zip, "xl/worksheets/sheet1.xml", worksheet)
            }
            output.toByteArray()
        }
    }

    fun getInventoryReportCsvContent(): String {
        val state = uiState.value
        val ingredients = state.ingredients
        val usageMap = state.dailyReport.ingredientUsage.associateBy { it.ingredientId }
        val range = state.reportDateRange
        val start = state.reportCustomStart
        val end = state.reportCustomEnd

        val rangeName = when (range) {
            ReportDateRange.TODAY -> "Today"
            ReportDateRange.MONTH -> "Last 30 Days"
            ReportDateRange.ALL -> "All Time"
            ReportDateRange.CUSTOM -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                val startStr = if (start != null) sdf.format(java.util.Date(start)) else "Start"
                val endStr = if (end != null) sdf.format(java.util.Date(end)) else "End"
                "Custom Range ($startStr to $endStr)"
            }
        }

        val generatedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        }.format(java.util.Date())

        val sb = java.lang.StringBuilder()

        fun escapeCsv(value: String): String {
            var escaped = value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ')
            if (escaped.contains(",") || escaped.contains("\"")) {
                escaped = "\"$escaped\""
            }
            return escaped
        }

        // Title
        sb.append("Kanlungan Coffee Garage POS - Inventory Management Report\n\n")

        // Section: REPORT DETAILS
        sb.append("REPORT DETAILS\n")
        sb.append("Report Field,Value\n")
        sb.append("Report Date Range,").append(escapeCsv(rangeName)).append("\n")
        sb.append("Generated At,").append(escapeCsv(generatedAt)).append("\n\n")

        // Section: INVENTORY DETAILS
        sb.append("INVENTORY DETAILS\n")
        sb.append("No.,Ingredient Name,Current Stock,Unit,Low-Stock Threshold,Status,Qty Used (Period),Qty Restocked (Period)\n")

        val sorted = ingredients.sortedBy { it.name }
        sorted.forEachIndexed { idx, ing ->
            val usage = usageMap[ing.id]
            val used = usage?.usedToday ?: 0.0
            val restocked = usage?.restocked ?: 0.0
            val isLow = ing.quantityOnHand <= ing.lowStockThreshold
            val statusLabel = if (isLow) "Low Stock" else "Normal"

            sb.append(idx + 1).append(",")
              .append(escapeCsv(ing.name)).append(",")
              .append(ing.quantityOnHand).append(",")
              .append(escapeCsv(ing.unit)).append(",")
              .append(ing.lowStockThreshold).append(",")
              .append(escapeCsv(statusLabel)).append(",")
              .append(used).append(",")
              .append(restocked).append("\n")
        }

        return sb.toString()
    }

    fun getInventoryReportExcelContent(): ByteArray {
        val state = uiState.value
        val ingredients = state.ingredients.sortedBy { it.name }
        val usageMap = state.dailyReport.ingredientUsage.associateBy { it.ingredientId }
        val range = state.reportDateRange
        val start = state.reportCustomStart
        val end = state.reportCustomEnd

        val rangeName = when (range) {
            ReportDateRange.TODAY -> "Today"
            ReportDateRange.MONTH -> "Last 30 Days"
            ReportDateRange.ALL -> "All Time"
            ReportDateRange.CUSTOM -> {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                val startStr = if (start != null) sdf.format(java.util.Date(start)) else "Start"
                val endStr = if (end != null) sdf.format(java.util.Date(end)) else "End"
                "Custom Range ($startStr to $endStr)"
            }
        }

        val generatedAt = java.text.SimpleDateFormat("M/d/yyyy HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        }.format(java.util.Date())

        fun xml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun textCell(ref: String, style: Int, value: String): String =
            """<c r="$ref" s="$style" t="inlineStr"><is><t>${xml(value)}</t></is></c>"""

        fun numberCell(ref: String, style: Int, value: Number): String =
            """<c r="$ref" s="$style"><v>${value}</v></c>"""

        fun row(rowNumber: Int, cells: String): String = """<row r="$rowNumber">$cells</row>"""

        fun addEntry(zip: ZipOutputStream, name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val lowStockCount = ingredients.count { it.quantityOnHand <= it.lowStockThreshold }
        val sheetData = StringBuilder()
        sheetData.append(row(1, textCell("A1", 1, "Kanlungan Coffee Garage POS - Inventory Management Report")))
        sheetData.append(row(3, textCell("A3", 2, "REPORT DETAILS") +
            textCell("D3", 3, "Total Ingredients") +
            textCell("F3", 3, "Low Stock Items")))
        sheetData.append(row(4, textCell("A4", 2, "Report Field") +
            textCell("B4", 2, "Value") +
            numberCell("D4", 7, ingredients.size) +
            numberCell("F4", 7, lowStockCount)))
        sheetData.append(row(5, textCell("A5", 0, "Report Date Range") + textCell("B5", 0, rangeName)))
        sheetData.append(row(6, textCell("A6", 0, "Generated At") + textCell("B6", 0, generatedAt)))
        sheetData.append(row(8, textCell("A8", 2, "INVENTORY DETAILS")))
        sheetData.append(row(9,
            textCell("A9", 3, "No.") +
                textCell("B9", 3, "Ingredient Name") +
                textCell("C9", 3, "Current Stock") +
                textCell("D9", 3, "Unit") +
                textCell("E9", 3, "Low-Stock Threshold") +
                textCell("F9", 3, "Status") +
                textCell("G9", 3, "Qty Used (Period)") +
                textCell("H9", 3, "Qty Restocked (Period)")
        ))

        ingredients.forEachIndexed { index, ingredient ->
            val rowNumber = 10 + index
            val usage = usageMap[ingredient.id]
            val used = usage?.usedToday ?: 0.0
            val restocked = usage?.restocked ?: 0.0
            val statusLabel = if (ingredient.quantityOnHand <= ingredient.lowStockThreshold) "Low Stock" else "Normal"

            sheetData.append(row(rowNumber,
                numberCell("A$rowNumber", 5, index + 1) +
                    textCell("B$rowNumber", 4, ingredient.name) +
                    numberCell("C$rowNumber", 6, ingredient.quantityOnHand) +
                    textCell("D$rowNumber", 5, ingredient.unit) +
                    numberCell("E$rowNumber", 6, ingredient.lowStockThreshold) +
                    textCell("F$rowNumber", 5, statusLabel) +
                    numberCell("G$rowNumber", 6, used) +
                    numberCell("H$rowNumber", 6, restocked)
            ))
        }

        val worksheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheetViews><sheetView workbookViewId="0" showGridLines="1"/></sheetViews>
  <sheetFormatPr defaultRowHeight="15"/>
  <cols>
    <col min="1" max="1" width="8" customWidth="1"/>
    <col min="2" max="2" width="30" customWidth="1"/>
    <col min="3" max="3" width="16" customWidth="1"/>
    <col min="4" max="4" width="12" customWidth="1"/>
    <col min="5" max="5" width="22" customWidth="1"/>
    <col min="6" max="6" width="14" customWidth="1"/>
    <col min="7" max="8" width="20" customWidth="1"/>
  </cols>
  <sheetData>$sheetData</sheetData>
  <mergeCells count="5">
    <mergeCell ref="A1:H1"/>
    <mergeCell ref="A3:B3"/>
    <mergeCell ref="D3:E3"/>
    <mergeCell ref="F3:G3"/>
    <mergeCell ref="A8:H8"/>
  </mergeCells>
</worksheet>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1">
    <numFmt numFmtId="164" formatCode="#,##0.##"/>
  </numFmts>
  <fonts count="3">
    <font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="12"/><color rgb="FF000000"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="16"/><color rgb="FF000000"/><name val="Calibri"/><family val="2"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFD9D9D9"/></left>
      <right style="thin"><color rgb="FFD9D9D9"/></right>
      <top style="thin"><color rgb="FFD9D9D9"/></top>
      <bottom style="thin"><color rgb="FFD9D9D9"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="8">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
  <dxfs count="0"/>
  <tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
</styleSheet>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Inventory Report" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                addEntry(zip, "[Content_Types].xml", contentTypes)
                addEntry(zip, "_rels/.rels", rootRels)
                addEntry(zip, "xl/workbook.xml", workbook)
                addEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels)
                addEntry(zip, "xl/styles.xml", styles)
                addEntry(zip, "xl/worksheets/sheet1.xml", worksheet)
            }
            output.toByteArray()
        }
    }



    fun printSalesReport() {
        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isReportRangeReady) {
                _uiState.update { it.copy(statusMessage = state.reportRangeError ?: "Select a valid report range.") }
                return@launch
            }
            val report = state.dailyReport
            val baseRangeName = when (state.reportDateRange) {
                ReportDateRange.TODAY -> "Today"
                ReportDateRange.MONTH -> "Month"
                ReportDateRange.ALL -> "All Time"
                ReportDateRange.CUSTOM -> {
                    val sdf = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                    }
                    val startStr = state.reportCustomStart?.let { sdf.format(java.util.Date(it)) } ?: "Start"
                    val endStr = state.reportCustomEnd?.let { sdf.format(java.util.Date(it)) } ?: "End"
                    "$startStr - $endStr"
                }
            }
            val cashierName = selectedReportCashierName(state)
            val rangeName = if (cashierName != null) {
                "$baseRangeName (Cashier: $cashierName)"
            } else {
                baseRangeName
            }
            val profile = state.printerProfile
            val W = if (profile.lineCharacters > 0) profile.lineCharacters else (if (profile.paperWidthMm >= 80) 48 else 32)
            val reportText = buildSalesReportText(report, rangeName, W)

            _uiState.update { it.copy(printerBusy = true, printerMessage = "Printing sales report...") }
            val result = container.printerManager.print(reportText)
            _uiState.update {
                val message = if (result.success) {
                    "Sales report printed to ${result.device?.name ?: "printer"}."
                } else {
                    result.message
                }
                it.copy(
                    printerBusy = false,
                    connectedPrinter = result.device ?: container.printerManager.connectedPrinter(),
                    savedPrinterAddress = container.printerManager.savedPrinterAddress,
                    printerPermissionNeeded = !container.printerManager.hasBluetoothPermission(),
                    printerMessage = message,
                    statusMessage = message
                )
            }
        }
    }

    private fun buildSalesReportText(report: DailyReport, rangeName: String, W: Int): String {
        val div = "-".repeat(W)
        val doubleDiv = "=".repeat(W)

        fun center(text: String): String {
            val pad = ((W - text.length) / 2).coerceAtLeast(0)
            return " ".repeat(pad) + text
        }

        fun row(left: String, right: String, width: Int = W): String {
            val space = (width - left.length - right.length).coerceAtLeast(1)
            return left + " ".repeat(space) + right
        }

        fun formatMoney(cents: Int): String {
            return String.format(java.util.Locale.US, "₱%,.2f", cents / 100.0)
        }

        val sdf = java.text.SimpleDateFormat("MM/dd/yyyy h:mm a", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        }
        val dateStr = sdf.format(java.util.Date())

        val sb = java.lang.StringBuilder()
        sb.appendLine(center("SALES REPORT"))
        sb.appendLine(center(rangeName))
        sb.appendLine(center("Generated: $dateStr"))
        sb.appendLine(doubleDiv)

        // Summary
        sb.appendLine(row("Orders:", report.orderCount.toString()))
        sb.appendLine(row("Gross Sales:", formatMoney(report.grossSalesCents)))
        if (report.discountsCents > 0) {
            sb.appendLine(row("Less Discounts:", "-" + formatMoney(report.discountsCents)))
        }
        sb.appendLine(row("Net Sales:", formatMoney(report.netSalesCents)))
        sb.appendLine(div)

        // Payment breakdown
        sb.appendLine("Payment Breakdown:")
        if (report.paymentTotals.isEmpty()) {
            sb.appendLine("  No transactions")
        } else {
            report.paymentTotals.forEach { (method, cents) ->
                sb.appendLine("  " + row(method, formatMoney(cents), W - 2))
            }
        }
        sb.appendLine(div)

        // Top Selling Items
        sb.appendLine("Top Selling Items:")
        if (report.topItems.isEmpty()) {
            sb.appendLine("  No items sold")
        } else {
            report.topItems.forEach { item ->
                val cleanedName = item.name.replace('\n', ' ').replace('\r', ' ')
                val left = "${item.qtySold}x $cleanedName"
                val right = formatMoney(item.revenueCents)
                if (left.length + right.length + 3 <= W) {
                    sb.appendLine("  " + row(left, right, W - 2))
                } else {
                    sb.appendLine("  $left")
                    sb.appendLine("  " + row("", right, W - 2))
                }
            }
        }
        sb.appendLine(div)

        // Cash Drawer Summary
        sb.appendLine("Cash Drawer Summary:")
        sb.appendLine("  " + row("Starting Cash:", formatMoney(report.cashDrawerStarting), W - 2))
        val gcashSales = report.onlinePaymentSalesCents
        val totalCashAndGCash = report.cashDrawerExpected + gcashSales
        sb.appendLine("  " + row("Expected Cash:", formatMoney(report.cashDrawerExpected), W - 2))
        sb.appendLine("  " + row("Online Payments:", formatMoney(gcashSales), W - 2))
        sb.appendLine("  " + row("Total Cash + Online Payment:", formatMoney(totalCashAndGCash), W - 2))
        sb.appendLine("  " + row("Actual Cash:", formatMoney(report.cashDrawerActual), W - 2))
        sb.appendLine("  " + row("Difference:", formatMoney(report.cashDrawerDifference), W - 2))
        sb.appendLine(doubleDiv)
        sb.appendLine(center("End of Report"))
        return sb.toString()
    }

    fun refreshPromotionConfig() {
        if (!container.supabaseSyncManager.isConfigured()) {
            _uiState.update {
                it.copy(
                    promotionConfig = PromotionConfig(available = false),
                    promotionBusy = false,
                    promotionError = "Connect this POS to Render Cloud before configuring the promotion."
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(promotionBusy = true, promotionError = null) }
            container.supabaseSyncManager.getPromotionConfig()
                .onSuccess { config ->
                    _uiState.update {
                        it.copy(
                            promotionConfig = config,
                            promotionBusy = false,
                            promotionError = config.message
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            promotionConfig = PromotionConfig(available = false),
                            promotionBusy = false,
                            promotionError = error.localizedMessage ?: "Promotion configuration is unavailable."
                        )
                    }
                }
        }
    }

    fun openPromotionClaimDialog() {
        _uiState.update {
            it.copy(
                showPromotionClaimDialog = true,
                promotionClaimCodeInput = "",
                promotionClaim = null,
                promotionError = null
            )
        }
    }

    fun closePromotionClaimDialog() {
        _uiState.update { it.copy(showPromotionClaimDialog = false, promotionClaim = null, promotionError = null) }
    }

    fun updatePromotionClaimCode(value: String) {
        val clean = value.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '-' }.take(24)
        _uiState.update { it.copy(promotionClaimCodeInput = clean, promotionClaim = null, promotionError = null) }
    }

    fun lookupPromotionClaim() {
        val code = _uiState.value.promotionClaimCodeInput.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(promotionError = "Enter the claim code printed on the winning receipt.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(promotionBusy = true, promotionError = null) }
            container.supabaseSyncManager.lookupPromotionClaim(code)
                .onSuccess { claim ->
                    _uiState.update {
                        it.copy(
                            promotionBusy = false,
                            promotionClaim = claim,
                            promotionError = if (claim.valid) null else claim.message ?: "This claim is not redeemable."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(promotionBusy = false, promotionError = error.localizedMessage ?: "Claim verification failed.") }
                }
        }
    }

    fun applyPromotionToLine(lineId: String) {
        val state = _uiState.value
        val employee = state.employee ?: return
        val claim = state.promotionClaim ?: return
        val line = state.cart.firstOrNull { it.id == lineId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(promotionBusy = true, promotionError = null) }
            container.supabaseSyncManager.reservePromotionClaim(claim.claimCode ?: state.promotionClaimCodeInput, employee.id)
                .onSuccess { reserved ->
                    if (!reserved.valid || reserved.reservationToken.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(promotionBusy = false, promotionError = reserved.message ?: "The claim could not be reserved.")
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                promotionBusy = false,
                                showPromotionClaimDialog = false,
                                promotionClaim = reserved,
                                promotionReservationToken = reserved.reservationToken,
                                promotionAppliedClaimCode = reserved.claimCode,
                                selectedDiscountCategory = "PROMO_FREE_DRINK",
                                selectedDiscountLineId = lineId,
                                seniorPwdIdInput = "",
                                discountCents = line.item.basePriceCents,
                                statusMessage = "Free drink claim applied. Modifiers remain chargeable."
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(promotionBusy = false, promotionError = error.localizedMessage ?: "Unable to reserve claim.") }
                }
        }
    }

    fun removeAppliedPromotion() {
        val token = _uiState.value.promotionReservationToken
        if (!token.isNullOrBlank()) {
            viewModelScope.launch { container.supabaseSyncManager.releasePromotionClaim(token) }
        }
        _uiState.update {
            it.copy(
                promotionReservationToken = null,
                promotionAppliedClaimCode = null,
                promotionClaim = null,
                selectedDiscountCategory = "None",
                selectedDiscountLineId = null,
                discountCents = 0,
                statusMessage = "Promotion removed from this order."
            )
        }
    }

    private suspend fun prepareReceiptForPrinting(
        orderId: String,
        receiptText: String?,
        reservationToken: String?,
        employeeId: String,
        autoPrint: Boolean,
        orderAlreadySynced: Boolean = false
    ) {
        if (receiptText.isNullOrBlank()) {
            _uiState.update { it.copy(statusMessage = "Receipt not found for this order.") }
            return
        }
        _uiState.update { state ->
            if (state.receiptOrderId == orderId) {
                state.copy(
                    receiptAuditStatus = null,
                    receiptPromotionState = ReceiptPromotionState.CHECKING,
                    receiptPromotionResult = null,
                    printerMessage = null
                )
            } else {
                state.copy(
                    receiptText = receiptText,
                    receiptOrderId = orderId,
                    receiptAuditStatus = null,
                    receiptPromotionState = ReceiptPromotionState.CHECKING,
                    receiptPromotionResult = null,
                    printerMessage = null
                )
            }
        }
        val knownConfig = _uiState.value.promotionConfig
        if (knownConfig.available && !knownConfig.enabled) {
            container.supabaseSyncManager.clearPromotionCheck(orderId)
            _uiState.update { state ->
                if (state.receiptOrderId == orderId) state.copy(
                    receiptPromotionState = ReceiptPromotionState.READY,
                    receiptPromotionResult = PromotionResult(isWinner = false),
                    printerMessage = null
                ) else state
            }
            if (autoPrint && _uiState.value.receiptOrderId == orderId) {
                printPreparedReceipt(copies = 1)
            }
            return
        }
        val syncResult = if (orderAlreadySynced) {
            Result.success(Unit)
        } else {
            container.supabaseSyncManager.syncPromotionOrder(orderId)
        }
        if (syncResult.isFailure) {
            container.supabaseSyncManager.queuePromotionCheck(orderId)
            _uiState.update { state ->
                if (state.receiptOrderId == orderId) state.copy(
                    receiptPromotionState = ReceiptPromotionState.RETRY_REQUIRED,
                    printerMessage = RECEIPT_PREPARATION_ERROR,
                    statusMessage = RECEIPT_PREPARATION_ERROR
                ) else state
            }
            return
        }
        if (!reservationToken.isNullOrBlank()) {
            container.supabaseSyncManager.finalizePromotionClaim(reservationToken, orderId, employeeId)
                .onFailure {
                    _uiState.update { state -> state.copy(statusMessage = "Sale completed; cloud confirmation will retry.") }
                }
        }
        val promotionResult = container.supabaseSyncManager.getPromotionResult(orderId)
        if (promotionResult.isFailure) {
            container.supabaseSyncManager.queuePromotionCheck(orderId)
            _uiState.update { state ->
                if (state.receiptOrderId == orderId) state.copy(
                    receiptPromotionState = ReceiptPromotionState.RETRY_REQUIRED,
                    printerMessage = RECEIPT_PREPARATION_ERROR,
                    statusMessage = RECEIPT_PREPARATION_ERROR
                ) else state
            }
            return
        }

        val result = promotionResult.getOrThrow()
        if (result.isWinner && !result.printed && result.qrUrl.isNullOrBlank()) {
            container.supabaseSyncManager.queuePromotionCheck(orderId)
            _uiState.update { state ->
                if (state.receiptOrderId == orderId) state.copy(
                    receiptPromotionState = ReceiptPromotionState.RETRY_REQUIRED,
                    receiptPromotionResult = result,
                    printerMessage = RECEIPT_PREPARATION_ERROR,
                    statusMessage = RECEIPT_PREPARATION_ERROR
                ) else state
            }
            return
        }

        container.supabaseSyncManager.clearPromotionCheck(orderId)
        _uiState.update { state ->
            if (state.receiptOrderId == orderId) state.copy(
                receiptPromotionState = ReceiptPromotionState.READY,
                receiptPromotionResult = result,
                printerMessage = null
            ) else state
        }
        if (autoPrint && _uiState.value.receiptOrderId == orderId) {
            printPreparedReceipt(copies = 1)
        }
    }

    val supabaseSyncManager: com.kape.coffeepos.data.SupabaseSyncManager get() = container.supabaseSyncManager

    fun updateRenderCloudConfig(url: String, enrollmentCode: String, deviceName: String) {
        container.supabaseSyncManager.renderCloudUrl = url
        container.supabaseSyncManager.deviceName = deviceName
        viewModelScope.launch {
            val result = if (container.supabaseSyncManager.isEnrolled) {
                container.supabaseSyncManager.syncNow()
            } else {
                container.supabaseSyncManager.enroll(url, enrollmentCode, deviceName)
            }
            _uiState.update { state -> state.copy(statusMessage = result.exceptionOrNull()?.message ?: "Render Cloud synchronized.") }
        }
    }

    fun showSafeReenrollmentDialog(show: Boolean) {
        if (_uiState.value.safeReenrollmentBusy) return
        _uiState.update {
            it.copy(
                showSafeReenrollmentDialog = show,
                safeReenrollmentCode = "",
                safeReenrollmentError = null
            )
        }
    }

    fun updateSafeReenrollmentCode(value: String) {
        val clean = value.uppercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(32)
        _uiState.update {
            it.copy(
                safeReenrollmentCode = clean,
                safeReenrollmentError = null
            )
        }
    }

    fun confirmSafeReenrollment() {
        val code = _uiState.value.safeReenrollmentCode
        val validationError = safeReenrollmentCodeError(code)
        if (validationError != null) {
            _uiState.update { it.copy(safeReenrollmentError = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    safeReenrollmentBusy = true,
                    safeReenrollmentError = null,
                    statusMessage = "Re-enrolling this tablet..."
                )
            }
            val result = container.supabaseSyncManager.enroll(
                renderUrl = container.supabaseSyncManager.renderCloudUrl,
                enrollmentCode = code,
                requestedName = container.supabaseSyncManager.deviceName
            )
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        showSafeReenrollmentDialog = false,
                        safeReenrollmentCode = "",
                        safeReenrollmentBusy = false,
                        safeReenrollmentError = null,
                        statusMessage = "Device re-enrolled and synchronized. Local POS data was preserved."
                    )
                } else {
                    it.copy(
                        safeReenrollmentBusy = false,
                        safeReenrollmentError = result.exceptionOrNull()?.message
                            ?: "Re-enrollment failed. Check the code and try again.",
                        statusMessage = "Re-enrollment failed. Local POS data was not changed."
                    )
                }
            }
        }
    }

    fun triggerSupabaseSync() {
        viewModelScope.launch {
            val result = container.supabaseSyncManager.syncNow()
            if (result.isSuccess) {
                container.supabaseSyncManager.pendingPromotionPrintedAwardIds().toList().forEach { awardId ->
                    container.supabaseSyncManager.markPromotionPrinted(awardId)
                        .onSuccess { container.supabaseSyncManager.clearPromotionPrintedAward(awardId) }
                }
                val employeeId = _uiState.value.employee?.id.orEmpty()
                container.supabaseSyncManager.pendingPromotionOrderIds().toList().forEach { orderId ->
                    val receipt = container.orderRepository.receipt(orderId)?.text ?: return@forEach
                    prepareReceiptForPrinting(
                        orderId = orderId,
                        receiptText = receipt,
                        reservationToken = null,
                        employeeId = employeeId,
                        autoPrint = container.printerManager.printerProfile.printReceipts &&
                            container.printerManager.printerProfile.autoPrintReceipts,
                        orderAlreadySynced = true
                    )
                }
                refreshPromotionConfig()
            }
        }
    }

    fun showManagerAuthorityDialog(show: Boolean) {
        if (show && !_uiState.value.isManager) {
            _uiState.update { it.copy(statusMessage = "Manager login required to designate the Manager Tablet.") }
            return
        }
        _uiState.update {
            it.copy(
                showManagerAuthorityDialog = show,
                managerAuthorityPin = "",
                managerAuthorityError = null
            )
        }
    }

    fun updateManagerAuthorityPin(value: String) {
        _uiState.update {
            it.copy(
                managerAuthorityPin = value.filter(Char::isDigit).take(6),
                managerAuthorityError = null
            )
        }
    }

    fun confirmManagerAuthority() {
        viewModelScope.launch {
            val pin = _uiState.value.managerAuthorityPin
            val employee = container.employeeRepository.login(pin)
            if (employee?.role != "manager") {
                _uiState.update { it.copy(managerAuthorityError = "Enter a valid Manager PIN.") }
                return@launch
            }
            val result = container.supabaseSyncManager.claimManagerAuthority()
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        showManagerAuthorityDialog = false,
                        managerAuthorityPin = "",
                        managerAuthorityError = null,
                        statusMessage = "This device is now the Manager Tablet."
                    )
                } else {
                    it.copy(managerAuthorityError = result.exceptionOrNull()?.localizedMessage ?: "Authority transfer failed.")
                }
            }
        }
    }
}

internal fun shouldIncludePromotionOnReceipt(result: PromotionResult?): Boolean =
    result?.isWinner == true && !result.printed && !result.qrUrl.isNullOrBlank()

private fun measurementTemplateFor(itemId: String): Map<String, String> = when (itemId) {
    "latte" -> mapOf("beans" to "0.65", "milk" to "10")
    "cappuccino" -> mapOf("beans" to "0.65", "milk" to "6")
    "americano" -> mapOf("beans" to "0.65")
    "drip" -> mapOf("beans" to "0.5")
    "spanish-latte" -> mapOf("beans" to "0.65", "milk" to "8", "condensed-cream" to "1.5")
    "salted-caramel-latte" -> mapOf("beans" to "0.65", "milk" to "9", "caramel-sauce" to "1")
    "mocha" -> mapOf("beans" to "0.65", "milk" to "8", "chocolate-sauce" to "1")
    "white-chocolate-mocha" -> mapOf("beans" to "0.65", "milk" to "8", "white-chocolate-sauce" to "1")
    "coldbrew" -> mapOf("coldbrew-base" to "8")
    "iced-americano" -> mapOf("beans" to "0.65")
    "iced-latte" -> mapOf("beans" to "0.65", "milk" to "8")
    "iced-spanish-latte" -> mapOf("beans" to "0.65", "milk" to "7", "condensed-cream" to "1.5")
    "coffee-frappe" -> mapOf("beans" to "0.65", "milk" to "6", "frappe-base" to "2")
    "matcha-frappe" -> mapOf("matcha-powder" to "8", "milk" to "7", "frappe-base" to "2")
    "matcha" -> mapOf("matcha-powder" to "8", "milk" to "8")
    "chai" -> mapOf("chai-base" to "6", "milk" to "6")
    "hot-chocolate" -> mapOf("milk" to "10", "chocolate-sauce" to "1.5")
    "lemon-iced-tea" -> mapOf("lemon-tea-base" to "8")
    "strawberry-milk" -> mapOf("milk" to "8", "strawberry-base" to "1.5")
    "vanilla-milkshake" -> mapOf("milk" to "8", "vanilla-base" to "2")
    "croissant" -> mapOf("croissant-stock" to "1")
    "chocolate-croissant" -> mapOf("chocolate-croissant-stock" to "1")
    "muffin" -> mapOf("muffin-stock" to "1")
    "banana-bread" -> mapOf("banana-bread-stock" to "1")
    "cinnamon-roll" -> mapOf("cinnamon-roll-stock" to "1")
    "cookies" -> mapOf("cookie-stock" to "2")
    "ham-cheese-sandwich" -> mapOf("sandwich-stock" to "1")
    "coffee-croissant-combo" -> mapOf("beans" to "0.5", "croissant-stock" to "1")
    "morning-set" -> mapOf("beans" to "0.65", "milk" to "10", "banana-bread-stock" to "1")
    "student-snack-combo" -> mapOf("beans" to "0.65", "milk" to "8", "cookie-stock" to "2")
    else -> emptyMap()
}

private val DRINK_CATEGORY_IDS = setOf("espresso", "signature", "cold", "tea-non-coffee")

private fun Double.formatRecipeQuantity(): String =
    if (this % 1.0 == 0.0) this.toInt().toString() else "%s".format(Locale.US, this)

private fun Int.formatCentsAsPrice(): String = String.format(Locale.US, "%.2f", this / 100.0)

private fun formatPaymentInput(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

private fun parseMoneyCents(value: String): Int? {
    val amount = value.toDoubleOrNull() ?: return null
    return (amount * 100).roundToInt().takeIf { it >= 0 }
}

class PosViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PosViewModel(container) as T
    }
}
