@file:Suppress("SpellCheckingInspection")
package com.kape.coffeepos

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import com.kape.coffeepos.data.CartLine
import com.kape.coffeepos.data.ReportDateRange
import com.kape.coffeepos.data.Ingredient
import com.kape.coffeepos.data.MenuItem
import com.kape.coffeepos.data.ModifierGroup
import com.kape.coffeepos.data.ModifierOption
import com.kape.coffeepos.data.PaymentCategories
import com.kape.coffeepos.printer.PrinterDevice
import com.kape.coffeepos.printer.PRINTER_INTERFACE_BLUETOOTH
import com.kape.coffeepos.printer.PRINTER_INTERFACE_WINDOWS_BRIDGE
import com.kape.coffeepos.viewmodel.AppScreen
import com.kape.coffeepos.viewmodel.PosUiState
import com.kape.coffeepos.viewmodel.PosViewModel
import com.kape.coffeepos.viewmodel.PosViewModelFactory
import com.kape.coffeepos.viewmodel.ReceiptCopyStage
import com.kape.coffeepos.viewmodel.ReceiptPromotionState
import com.kape.coffeepos.viewmodel.RECEIPT_PREPARING_LABEL
import com.kape.coffeepos.viewmodel.orderPaymentCategoryLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

private val MANILA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Manila")

private fun manilaDateFormat(pattern: String): SimpleDateFormat =
    SimpleDateFormat(pattern, Locale.US).apply { timeZone = MANILA_TIME_ZONE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CoffeePosApplication
        val factory = PosViewModelFactory(app.container)
        val vm: PosViewModel by viewModels { factory }
        setContent {
            CoffeePosApp(vm)
        }
    }
}

@Composable
fun CoffeePosApp(viewModel: PosViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showSplash by remember { mutableStateOf(true) }

    // Track alerted low-stock ingredient IDs
    var alertedIngredientIds by remember { mutableStateOf(emptySet<String>()) }
    var showLowStockDialog by remember { mutableStateOf(false) }
    var newLowStockItems by remember { mutableStateOf(emptyList<Ingredient>()) }
    var showLowStockRestockDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.lowStock) {
        val currentLowIds = state.lowStock.map { it.id }.toSet()
        val newLowIds = currentLowIds - alertedIngredientIds
        if (newLowIds.isNotEmpty()) {
            newLowStockItems = state.lowStock.filter { it.id in newLowIds }
            showLowStockDialog = true
            alertedIngredientIds = alertedIngredientIds + currentLowIds
        } else {
            alertedIngredientIds = alertedIngredientIds intersect currentLowIds
        }
    }

    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF1A1A1A),
            secondary = Color(0xFF1A1A1A),
            tertiary = Color(0xFF444444),
            surface = Color(0xFFFFFFFF),
            background = Color(0xFFF5F5F5),
            error = Color(0xFFB3261E)
        )
    ) {
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
        } else {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (state.employee == null) {
                    LoginScreen(state, viewModel)
                } else {
                    AppShell(state, viewModel, onShowLowStockRestock = { showLowStockRestockDialog = true })
                }
                ModifierDialog(state, viewModel)
                OrderSummaryDialog(state, viewModel)
                PromotionClaimDialog(state, viewModel)
                ReceiptDialog(state, viewModel)
                CategoryEditorDialog(state, viewModel)
                ModifierEditorDialog(state, viewModel)
                EmployeeEditorDialog(state, viewModel)
                AddOnDialog(state, viewModel)

                if (state.showPaymentMethodEditor) {
                    AlertDialog(
                        onDismissRequest = viewModel::closePaymentMethodEditor,
                        title = {
                            Text(
                                text = if (state.paymentMethodEditorId == null) "Add Payment Method" else "Edit Payment Method",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = state.paymentMethodEditorName,
                                    onValueChange = viewModel::updatePaymentMethodEditorName,
                                    label = { Text("Payment Method Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Payment Category", fontWeight = FontWeight.Medium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        PaymentCategories.CASH to "Cash",
                                        PaymentCategories.ONLINE to "Online"
                                    ).forEach { (category, label) ->
                                        val selected = state.paymentMethodEditorCategory == category
                                        Button(
                                            onClick = { viewModel.updatePaymentMethodEditorCategory(category) },
                                            modifier = Modifier.weight(1f).height(48.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Text(label)
                                        }
                                    }
                                }
                                state.paymentMethodEditorError?.let { error ->
                                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = viewModel::savePaymentMethodFromEditor) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::closePaymentMethodEditor) { Text("Cancel") }
                        }
                    )
                }

                if (showLowStockRestockDialog) {
                    LowStockRestockDialog(state, viewModel, onDismiss = { showLowStockRestockDialog = false })
                }

                // Low stock alert dialog
                if (showLowStockDialog && newLowStockItems.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { showLowStockDialog = false },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("⚠️", fontSize = 24.sp)
                                Text("Low Stock Warning", fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("The following ingredient(s) have dropped below their low stock threshold:")
                                Spacer(Modifier.height(4.dp))
                                newLowStockItems.forEach { ing ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(ing.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = "${ing.quantityOnHand.formatQty()} ${ing.unit} remaining (low threshold: ${ing.lowStockThreshold.formatQty()})",
                                            color = Color(0xFFC0392B),
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { showLowStockDialog = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE74C3C),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Dismiss")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    // Animate logo scale + alpha in
    val logoScale = remember { Animatable(0.6f) }
    // Keep the branded splash visible from the first frame. Starting all
    // content at zero alpha made the emulator appear completely black while
    // the entrance animation was still running.
    val logoAlpha = remember { Animatable(1f) }
    val textAlpha = remember { Animatable(1f) }
    val textTranslateY = remember { Animatable(0f) }

    // Sequenced entry animation then dismiss
    LaunchedEffect(Unit) {
        // Gently scale the already-visible logo in
        logoScale.animateTo(1f, animationSpec = tween(700, easing = FastOutSlowInEasing))
        // Hold for brand reading
        delay(1600.milliseconds)
        // Fade out everything
        logoAlpha.animateTo(0f, animationSpec = tween(400))
        textAlpha.animateTo(0f, animationSpec = tween(400))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2A1710), Color(0xFF1A1A1A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(196.dp, 188.dp)
                    .graphicsLayer {
                        scaleX = logoScale.value
                        scaleY = logoScale.value
                        alpha = logoAlpha.value
                    }
            ) {
                drawKanlunganShieldLogo()
            }

            Spacer(Modifier.height(20.dp))

            // Brand name
            Text(
                text = "KANLUNGAN",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Serif,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(textAlpha.value)
                    .graphicsLayer { translationY = textTranslateY.value }
            )

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.alpha(textAlpha.value)
            ) {
                Box(Modifier.size(44.dp, 2.dp).background(Color(0xFFF5C518)))
                Text(
                    text = "COFFEE   GARAGE",
                    color = Color.White,
                    fontSize = 13.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Box(Modifier.size(44.dp, 2.dp).background(Color(0xFFF5C518)))
            }

            Spacer(Modifier.height(10.dp))
            Canvas(
                Modifier
                    .size(76.dp, 48.dp)
                    .alpha(textAlpha.value)
            ) {
                drawCrossedGarageWrenches()
            }
        }
    }
}

/** Draws the Kanlungan Coffee Garage shield logo on a Canvas, matching the brand identity. */
private fun DrawScope.drawKanlunganShieldLogo() {
    val w = size.width
    val h = size.height
    val cx = w / 2f

    // ── Shield geometry ──────────────────────────────────────────────
    // The shield has a nearly-flat top with rounded corners, straight sides
    // that angle inward, a "shoulder" transition, and a clean pointed tip.
    val topY       = h * 0.01f
    val leftX      = w * 0.04f
    val rightX     = w * 0.96f
    val cornerR    = w * 0.13f          // top-corner radius
    val shoulderY  = h * 0.62f          // where sides start converging to tip
    val tipY       = h * 0.985f

    fun shieldPath(l: Float, r: Float, t: Float, tip: Float, sY: Float, cr: Float): Path {
        val midX = (l + r) / 2f
        return Path().apply {
            moveTo(midX, tip)
            lineTo(l, sY)
            lineTo(l, t + cr)
            quadraticTo(l, t, l + cr, t)
            lineTo(r - cr, t)
            quadraticTo(r, t, r, t + cr)
            lineTo(r, sY)
            close()
        }
    }

    // Layer 1 – Yellow/gold outer border
    drawPath(shieldPath(leftX, rightX, topY, tipY, shoulderY, cornerR), Color(0xFFF5C518))

    // Layer 2 – Thin black separator ring
    val s1 = w * 0.038f
    drawPath(
        shieldPath(leftX + s1, rightX - s1, topY + s1, tipY - h * 0.025f, shoulderY - h * 0.01f, cornerR - s1 * 0.5f),
        Color(0xFF0D0D0D)
    )

    // Layer 3 – Main dark black fill
    val s2 = w * 0.070f
    drawPath(
        shieldPath(leftX + s2, rightX - s2, topY + s2, tipY - h * 0.055f, shoulderY - h * 0.022f, cornerR - s2 * 0.7f),
        Color(0xFF1A1A1A)
    )

    // Layer 4 – Cream inner accent stroke (thin border inside the black)
    val s3 = w * 0.105f
    val accentPath = shieldPath(leftX + s3, rightX - s3, topY + s3, tipY - h * 0.095f, shoulderY - h * 0.040f, cornerR - s3 * 0.85f)
    drawPath(
        path = accentPath,
        color = Color(0xFFDDC87A),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.022f)
    )

    // ── Letter K ─────────────────────────────────────────────────────
    // Bold slab-serif style: thick vertical bar + wide diagonal arms
    val kColor   = Color(0xFFF5C518)
    val kLeft    = cx - w * 0.215f
    val kRight   = cx + w * 0.235f
    val kTop     = h * 0.14f
    val kBottom  = h * 0.74f
    val kBarW    = w * 0.145f           // thick vertical bar
    val kArmW    = w * 0.115f           // arm thickness
    // The diagonal arms meet the vertical bar at ~55% height (the V-notch)
    val kNotchY  = kTop + (kBottom - kTop) * 0.535f

    // Vertical bar (left stem of K)
    drawRect(
        color = kColor,
        topLeft = Offset(kLeft, kTop),
        size = Size(kBarW, kBottom - kTop)
    )

    // Subtle slab serifs make the monogram match the vintage garage wordmark.
    drawRect(kColor, Offset(kLeft - w * 0.035f, kTop), Size(kBarW + w * 0.07f, h * 0.032f))
    drawRect(kColor, Offset(kLeft - w * 0.035f, kBottom - h * 0.032f), Size(kBarW + w * 0.07f, h * 0.032f))

    // Upper diagonal arm: from notch (on the bar) → top-right
    val upperArm = Path().apply {
        // Inner edge meets bar at notch
        moveTo(kLeft + kBarW, kNotchY)
        // Outer edge of bar at notch (slightly below for thickness)
        lineTo(kLeft + kBarW * 0.55f, kNotchY + kArmW * 0.15f)
        // Inner-top of arm at top-right
        lineTo(kRight - kArmW * 0.85f, kTop)
        // Outer-top of arm at top-right
        lineTo(kRight, kTop)
        close()
    }
    drawPath(upperArm, kColor)

    // Lower diagonal arm: from notch → bottom-right
    val lowerArm = Path().apply {
        moveTo(kLeft + kBarW * 0.55f, kNotchY - kArmW * 0.15f)
        lineTo(kLeft + kBarW, kNotchY)
        lineTo(kRight, kBottom)
        lineTo(kRight - kArmW * 0.85f, kBottom)
        close()
    }
    drawPath(lowerArm, kColor)

    drawRect(kColor, Offset(kRight - kArmW, kTop), Size(kArmW * 1.18f, h * 0.032f))
    drawRect(kColor, Offset(kRight - kArmW, kBottom - h * 0.032f), Size(kArmW * 1.18f, h * 0.032f))
}

/** Compact crossed-wrench mark used beneath the Coffee Garage wordmark. */
private fun DrawScope.drawCrossedGarageWrenches() {
    val gold = Color(0xFFF5C518)
    val outline = Color(0xFF17130A)

    fun wrench(handle: Offset, jaw: Offset, jawA: Offset, jawB: Offset) {
        drawLine(outline, handle, jaw, strokeWidth = size.minDimension * 0.18f)
        drawLine(gold, handle, jaw, strokeWidth = size.minDimension * 0.105f)

        drawCircle(outline, radius = size.minDimension * 0.15f, center = handle)
        drawCircle(gold, radius = size.minDimension * 0.105f, center = handle)
        drawCircle(outline, radius = size.minDimension * 0.047f, center = handle)

        drawLine(outline, jaw, jawA, strokeWidth = size.minDimension * 0.16f)
        drawLine(outline, jaw, jawB, strokeWidth = size.minDimension * 0.16f)
        drawLine(gold, jaw, jawA, strokeWidth = size.minDimension * 0.085f)
        drawLine(gold, jaw, jawB, strokeWidth = size.minDimension * 0.085f)
    }

    wrench(
        handle = Offset(size.width * 0.18f, size.height * 0.82f),
        jaw = Offset(size.width * 0.67f, size.height * 0.25f),
        jawA = Offset(size.width * 0.68f, size.height * 0.06f),
        jawB = Offset(size.width * 0.84f, size.height * 0.20f)
    )
    wrench(
        handle = Offset(size.width * 0.82f, size.height * 0.82f),
        jaw = Offset(size.width * 0.33f, size.height * 0.25f),
        jawA = Offset(size.width * 0.32f, size.height * 0.06f),
        jawB = Offset(size.width * 0.16f, size.height * 0.20f)
    )
}


@Composable
private fun LiveTimeDisplay(modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = manilaDateFormat("MMMM d, yyyy  h:mm:ss a")
        while (true) {
            currentTime = sdf.format(Date())
            delay(1000.milliseconds)
        }
    }
    Text(
        text = currentTime,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.Gray,
        modifier = modifier
    )
}

@Composable
private fun LoginScreen(state: PosUiState, viewModel: PosViewModel) {
    var pin by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF000000), Color(0xFF1A1A1A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(
                    modifier = Modifier.size(106.dp, 102.dp)
                ) {
                    drawKanlunganShieldLogo()
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "KANLUNGAN",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Serif,
                        color = Color(0xFF1A1A1A),
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(28.dp, 2.dp).background(Color(0xFFF5C518)))
                        Text(
                            text = "COFFEE   GARAGE",
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF332B16)
                        )
                        Box(Modifier.size(28.dp, 2.dp).background(Color(0xFFF5C518)))
                    }
                    Canvas(Modifier.size(48.dp, 30.dp)) {
                        drawCrossedGarageWrenches()
                    }
                    Text(
                        text = "Enter employee PIN to sign in",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }

                // PIN display dots
                PinDotsRow(pinLength = pin.length)

                // Numeric keypad
                NumericKeypad(
                    onDigitClick = { digit ->
                        if (pin.length < 6) {
                            pin += digit
                        }
                    },
                    onBackspaceClick = {
                        if (pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                        }
                    },
                    onClearClick = {
                        pin = ""
                    }
                )

                OutlinedButton(
                    onClick = { viewModel.login(pin) },
                    enabled = pin.length >= 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFFFFFFF),
                        contentColor = Color(0xFF1A1A1A),
                        disabledContainerColor = Color(0xFFE0E0E0),
                        disabledContentColor = Color(0xFF9E9E9E)
                    ),
                    border = BorderStroke(1.5.dp, Color(0xFF1A1A1A))
                ) {
                    Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
                }

                state.loginError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun PinDotsRow(pinLength: Int, maxLen: Int = 6) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        for (i in 0 until maxLen) {
            val filled = i < pinLength
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        color = if (filled) Color(0xFF1A1A1A) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 2.dp,
                        color = Color(0xFF1A1A1A),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onClearClick: () -> Unit
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫")
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { key ->
                    OutlinedButton(
                        onClick = {
                            when (key) {
                                "⌫" -> onBackspaceClick()
                                "C" -> onClearClick()
                                else -> onDigitClick(key)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5DCD3)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (key == "⌫" || key == "C") Color(0xFFF5F5F5) else Color.White,
                            contentColor = Color(0xFF1A1A1A)
                        )
                    ) {
                        Text(
                            text = key,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun AppShell(state: PosUiState, viewModel: PosViewModel, onShowLowStockRestock: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val constraints = this
        // Treat common 10-inch tablet/emulator bounds (1280x800) as compact so
        // the POS remains fully visible without requiring manual zooming.
        val compactTablet = constraints.maxHeight < 900.dp || constraints.maxWidth < 1400.dp
        val landscapeTablet = constraints.maxWidth > constraints.maxHeight
        Column(Modifier.fillMaxSize()) {
            TopBar(state, viewModel, onShowLowStockRestock, compactTablet)
            Row(Modifier.fillMaxSize()) {
                NavigationRail(state, viewModel, compactTablet)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(if (compactTablet) 8.dp else 12.dp)
                ) {
                    when (state.screen) {
                        AppScreen.POS -> PosScreen(state, viewModel, landscapeTablet)
                        AppScreen.ORDERS -> OrdersScreen(state, viewModel)
                        AppScreen.INVENTORY -> InventoryScreen(state, viewModel)
                        AppScreen.REPORTS -> ReportsScreen(state, viewModel)
                        AppScreen.DEVICES -> DevicesScreen(state, viewModel)
                        AppScreen.SETTINGS -> SettingsScreen(state, viewModel)
                        AppScreen.MENU -> MenuScreen(state, viewModel)
                        AppScreen.MANAGER -> ManagerScreen(state, viewModel)
                        AppScreen.DRAWER -> DrawerScreen(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    state: PosUiState,
    viewModel: PosViewModel,
    onShowLowStockRestock: () -> Unit,
    compactTablet: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(
                horizontal = if (compactTablet) 12.dp else 16.dp,
                vertical = if (compactTablet) 6.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    state.settings.storeName,
                    style = if (compactTablet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (!compactTablet) LiveTimeDisplay()
            }
            val shiftLabel = if (state.activeShift == null) "No active shift" else "Shift #${state.activeShift.id} open"
            Text(
                "${state.employee?.name} (${state.employee?.role}) - $shiftLabel",
                style = if (compactTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
            if (compactTablet) {
                state.statusMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.lowStock.isNotEmpty()) {
                Surface(
                    color = Color(0xFFFDE8E8),
                    contentColor = Color(0xFF9B1C1C),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable {
                            onShowLowStockRestock()
                        }
                ) {
                    Text(
                        text = "⚠️ ${state.lowStock.size} low stock",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = if (compactTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!compactTablet) {
                state.statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
            }
            OutlinedButton(onClick = viewModel::logout) { Text("Lock") }
        }
    }
}

@Composable
private fun NavigationRail(state: PosUiState, viewModel: PosViewModel, compactTablet: Boolean) {
    val screens = AppScreen.entries.filter { screen ->
        state.isManager || (screen != AppScreen.INVENTORY && screen != AppScreen.DEVICES)
    }
    Column(
        Modifier
            .width(if (compactTablet) 96.dp else 112.dp)
            .fillMaxHeight()
            .background(Color(0xFF1A1A1A))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactTablet) 6.dp else 8.dp)
    ) {
        screens.forEach { screen ->
            val managerOnly = screen == AppScreen.SETTINGS || screen == AppScreen.MENU || screen == AppScreen.MANAGER || screen == AppScreen.REPORTS
            val enabled = !managerOnly || state.isManager
            val isSelected = state.screen == screen
            OutlinedButton(
                onClick = { viewModel.selectScreen(screen) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) Color(0xFFFFFFFF) else Color.Transparent,
                    contentColor = if (isSelected) Color(0xFF1A1A1A) else Color(0xFFFFFFFF),
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color(0xFF666666)
                ),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = if (isSelected) Color(0xFFFFFFFF) else Color(0xFF555555)
                )
            ) {
                Text(
                    text = screen.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (compactTablet) 12.sp else 14.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PosScreen(state: PosUiState, viewModel: PosViewModel, landscapeTablet: Boolean) {
    if (state.activeShift == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            OpenShiftPanel(state, viewModel)
        }
    } else {
        @Suppress("UnusedBoxWithConstraintsScope")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 760.dp
            if (!landscapeTablet && maxWidth < 700.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MenuPanel(state, viewModel, Modifier.weight(1f), compactHeight)
                    CartPanel(state, viewModel, Modifier.fillMaxWidth().heightIn(max = 300.dp), true)
                }
            } else {
                val cartWidth = (maxWidth * if (maxWidth < 700.dp) 0.42f else 0.36f)
                    .coerceIn(240.dp, 340.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp)) {
                    MenuPanel(state, viewModel, Modifier.weight(1f).fillMaxHeight(), compactHeight)
                    CartPanel(state, viewModel, Modifier.width(cartWidth).fillMaxHeight(), compactHeight)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MenuPanel(state: PosUiState, viewModel: PosViewModel, modifier: Modifier, compactHeight: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.catalog.categories.forEach { category ->
                FilterChip(
                    selected = state.selectedCategoryId == category.id,
                    onClick = { viewModel.selectCategory(category.id) },
                    label = { Text(category.name) }
                )
            }
        }
        val filteredItems = remember(state.catalog.items, state.selectedCategoryId) {
            state.catalog.items.filter { it.categoryId == state.selectedCategoryId }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compactHeight) 120.dp else 160.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            gridItems(filteredItems, key = { it.id }) { item ->
                MenuItemCard(item, viewModel, compactHeight)
            }
        }
    }
}

@Composable
private fun MenuItemCard(item: MenuItem, viewModel: PosViewModel, compactHeight: Boolean) {
    Card(
        onClick = { viewModel.chooseItem(item) },
        modifier = Modifier.height(if (compactHeight) 112.dp else 132.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, Color(0xFF1A1A1A))
    ) {
        Column(Modifier.padding(if (compactHeight) 10.dp else 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(item.name, fontWeight = FontWeight.Bold)
                Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
            Text(money(item.basePriceCents), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CartPanel(state: PosUiState, viewModel: PosViewModel, modifier: Modifier, compactHeight: Boolean) {
    Card(modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.padding(if (compactHeight) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp)
        ) {
            Text(
                "Current Order",
                style = if (compactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            LazyColumn(Modifier.weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.cart.isEmpty()) {
                    item { Text("No items yet. Tap a menu tile to start an order.") }
                }
                itemsIndexed(state.cart, key = { _, line -> line.id }) { index, line ->
                    CartLineRow(index, line, viewModel)
                }
            }
            if (state.heldCarts.isNotEmpty()) {
                Text("Held Orders", fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.heldCarts.chunked(3).forEachIndexed { chunkIndex, chunk ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            chunk.forEachIndexed { itemIndex, cart ->
                                val actualIndex = chunkIndex * 3 + itemIndex
                                OutlinedButton(
                                    onClick = { viewModel.resumeHeldCart(actualIndex) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${cart.size} item${if (cart.size == 1) "" else "s"}",
                                        maxLines = 1
                                    )
                                }
                            }
                            if (chunk.size < 3) {
                                repeat(3 - chunk.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (state.promotionAppliedClaimCode != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Free drink reward applied", fontWeight = FontWeight.Bold)
                        Text("Claim ${state.promotionAppliedClaimCode}. One base drink is free; modifiers remain chargeable.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::removeAppliedPromotion, modifier = Modifier.align(Alignment.End)) {
                            Text("Remove reward")
                        }
                    }
                }
            }
            TotalsBlock(state)
            OutlinedButton(
                onClick = viewModel::holdCart,
                enabled = state.cart.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("Hold Order")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::cancelCart,
                    enabled = state.cart.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = viewModel::showOrderSummary,
                    enabled = state.cart.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("Confirm")
                }
            }
        }
    }
}

@Composable
private fun CartLineRow(index: Int, line: CartLine, viewModel: PosViewModel) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)), border = BorderStroke(1.5.dp, Color(0xFF1A1A1A))) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(line.item.name, fontWeight = FontWeight.Bold)
                if (line.modifierLabel.isNotBlank()) Text(line.modifierLabel, style = MaterialTheme.typography.bodySmall)
                Text("${line.quantity} x ${money(line.unitPriceCents)}")
            }
            OutlinedButton(
                onClick = { viewModel.changeQuantity(index, -1) },
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("-") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = { viewModel.changeQuantity(index, 1) },
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("+") }
        }
    }
}

@Composable
private fun TotalsBlock(state: PosUiState) {
    val totals = state.totals
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TotalRow("Total", totals.totalCents, strong = true)
    }
}

@Composable
private fun TotalRow(label: String, cents: Int, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        Text(money(cents), fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ModifierDialog(state: PosUiState, viewModel: PosViewModel) {
    val item = state.pendingItem ?: return
    AlertDialog(
        onDismissRequest = viewModel::cancelPendingItem,
        title = { Text("Customize ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val assignedGroupIds = state.catalog.itemGroups
                    .filter { it.itemId == item.id }
                    .map { it.groupId }
                    .toSet()
                state.catalog.groups.filter { it.id in assignedGroupIds }.forEach { group ->
                    val options = availableModifierOptions(
                        item = item,
                        group = group,
                        options = state.catalog.options.filter { it.groupId == group.id }
                    )
                    if (options.isEmpty()) return@forEach
                    Column {
                        Text(group.name, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = state.selectedModifiers.any { it.id == option.id },
                                    onClick = { viewModel.toggleModifier(option) },
                                    label = {
                                        Text(if (option.priceDeltaCents == 0) option.name else "${option.name} +${money(option.priceDeltaCents)}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = viewModel::addPendingItem) { Text("Add") } },
        dismissButton = { TextButton(onClick = viewModel::cancelPendingItem) { Text("Cancel") } }
    )
}

@Composable
private fun PromotionClaimDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showPromotionClaimDialog) return
    Dialog(onDismissRequest = viewModel::closePromotionClaimDialog) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Redeem free drink", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Enter the code from the winning QR receipt. The customer must submit the Google Form before redemption.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = state.promotionClaimCodeInput,
                    onValueChange = viewModel::updatePromotionClaimCode,
                    label = { Text("Claim code") },
                    supportingText = { Text("Example: KAPE-A1B2-C3D4-E5F6") },
                    singleLine = true,
                    enabled = !state.promotionBusy,
                    isError = state.promotionError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                state.promotionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.promotionClaim?.valid == true) {
                    Text("Claim verified", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    Text("Choose the customer's free drink in this order:", fontWeight = FontWeight.SemiBold)
                    if (state.cart.isEmpty()) {
                        Text(
                            "Add the customer's chosen drink to the cart, then verify this code again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.cart.forEach { line ->
                        OutlinedButton(
                            onClick = { viewModel.applyPromotionToLine(line.id) },
                            enabled = !state.promotionBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text("Apply to 1 × ${line.item.name} (save ${money(line.item.basePriceCents)})")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = viewModel::closePromotionClaimDialog, enabled = !state.promotionBusy) { Text("Cancel") }
                    Button(
                        onClick = viewModel::lookupPromotionClaim,
                        enabled = state.promotionClaimCodeInput.isNotBlank() && !state.promotionBusy
                    ) {
                        if (state.promotionBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Verify code")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderSummaryDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showOrderSummary) return
    val totals = state.totals
    val amountPaidCents = parseMoneyCents(state.amountPaidInput) ?: 0
    val changeCents = (amountPaidCents - totals.totalCents).coerceAtLeast(0)
    Dialog(onDismissRequest = viewModel::hideOrderSummary) {
        Card(
            Modifier.fillMaxWidth().widthIn(max = 560.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Confirm Order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.cart.forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${line.quantity} x ${line.item.name}", fontWeight = FontWeight.Bold)
                                if (line.modifierLabel.isNotBlank()) {
                                    Text(line.modifierLabel, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(money(line.lineTotalCents), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // ── Customer Tracking & Order Type ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Order Type & Tracking", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = state.customerNameInput,
                            onValueChange = viewModel::updateCustomerName,
                            label = { Text("Customer Name (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("Dine-In", "Take-Out").forEach { type ->
                                FilterChip(
                                    selected = state.orderTypeInput == type,
                                    onClick = { viewModel.updateOrderType(type) },
                                    label = { Text(type) }
                                )
                            }
                        }
                        state.orderTypeError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Discount ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Applied Discount", fontWeight = FontWeight.Bold)
                        if (state.selectedDiscountCategory == "PROMO_FREE_DRINK") {
                            Text(
                                "Free drink claim ${state.promotionAppliedClaimCode.orEmpty()} is applied to one base drink. Remove it from the cart to use another discount.",
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("None" to "None", "Senior" to "Senior Citizen", "PWD" to "PWD").forEach { (cat, label) ->
                                val pctLabel = when (cat) {
                                    "Senior" -> " (${state.settings.seniorDiscountPercent.toString().removeSuffix(".0")}%)"
                                    "PWD" -> " (${state.settings.pwdDiscountPercent.toString().removeSuffix(".0")}%)"
                                    else -> ""
                                }
                                FilterChip(
                                    selected = state.selectedDiscountCategory == cat,
                                    onClick = { viewModel.selectDiscountCategory(cat) },
                                    enabled = state.promotionReservationToken == null,
                                    label = { Text(label + pctLabel) }
                                )
                            }
                        }
                        if (state.selectedDiscountCategory != "None") {
                            OutlinedTextField(
                                value = state.seniorPwdIdInput,
                                onValueChange = viewModel::updateSeniorPwdIdInput,
                                label = { Text("${state.selectedDiscountCategory} ID Number (Required)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                "Choose item for discount (Required)",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            val discountPercent = if (state.selectedDiscountCategory == "Senior") {
                                state.settings.seniorDiscountPercent
                            } else {
                                state.settings.pwdDiscountPercent
                            }
                            state.cart.forEach { line ->
                                val selected = state.selectedDiscountLineId == line.id
                                val expectedDiscount = (line.unitPriceCents * discountPercent / 100.0).roundToInt()
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clickable { viewModel.selectDiscountLine(line.id) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selected, onClick = null)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                if (line.quantity > 1) {
                                                    "1 of ${line.quantity} × ${line.item.name}"
                                                } else {
                                                    "1 × ${line.item.name}"
                                                },
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (line.modifierLabel.isNotBlank()) {
                                                Text(line.modifierLabel, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(
                                                "One unit: ${money(line.unitPriceCents)}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Text(
                                            "-${money(expectedDiscount)}",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            if (state.selectedDiscountLineId == null) {
                                Text(
                                    "Select the item for the customer's personal consumption.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    if (state.discountCents > 0) {
                        TotalRow("Subtotal", totals.subtotalCents)
                        val discountLabel = if (state.selectedDiscountCategory == "PROMO_FREE_DRINK") "Free Drink Reward" else state.selectedDiscountCategory
                        TotalRow("Discount ($discountLabel)", -state.discountCents)
                    }

                    val displayTotal = if (state.paymentMethod == "Complimentary") 0 else totals.totalCents
                    TotalRow("Total", displayTotal, strong = true)

                    val enabledMethods = remember(state.paymentMethods) {
                        state.paymentMethods.filter { it.enabled && (it.isSystem || it.paymentCategory != null) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Payment Method", fontWeight = FontWeight.Bold)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            enabledMethods.forEach { method ->
                                FilterChip(
                                    selected = state.paymentMethod == method.name,
                                    onClick = { viewModel.selectPaymentMethod(method.name) },
                                    label = {
                                        Text(
                                            when (method.id) {
                                                "gcash" -> "GCash"
                                                "split" -> "Split (Cash + GCash)"
                                                else -> method.name
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        if (state.paymentMethod.isBlank() && state.paymentError != null) {
                            Text(state.paymentError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                    if (state.paymentMethod == "Split") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = state.splitCashInput,
                                onValueChange = viewModel::updateSplitCashInput,
                                label = { Text("Cash Portion") },
                                prefix = { Text("₱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.splitGCashInput,
                                onValueChange = viewModel::updateSplitGCashInput,
                                label = { Text("GCash Portion") },
                                prefix = { Text("₱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (state.paymentError != null) {
                            Text(state.paymentError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                        val totalSplitPaidCents = (parseMoneyCents(state.splitCashInput) ?: 0) + (parseMoneyCents(state.splitGCashInput) ?: 0)
                        val splitChangeCents = (totalSplitPaidCents - totals.totalCents).coerceAtLeast(0)
                        TotalRow("Total Paid", totalSplitPaidCents)
                        TotalRow("Change (Returned in Cash)", splitChangeCents, strong = true)
                    } else if (state.paymentMethod == "Complimentary") {
                        Text(
                            text = "Complimentary Order (Bypasses payment, inventory will be deducted)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else if (state.paymentMethod.isNotBlank()) {
                        OutlinedTextField(
                            value = state.amountPaidInput,
                            onValueChange = viewModel::updateAmountPaid,
                            label = { Text("Amount paid") },
                            prefix = { Text("₱") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.paymentError != null,
                            supportingText = {
                                state.paymentError?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TotalRow("Amount Paid", amountPaidCents)
                        TotalRow("Change", changeCents, strong = true)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    TextButton(onClick = viewModel::hideOrderSummary) { Text("Cancel") }
                    val checkoutEnabled = when (state.selectedDiscountCategory) {
                        "None" -> true
                        "PROMO_FREE_DRINK" -> state.promotionReservationToken != null && state.selectedDiscountLineId != null
                        else -> state.seniorPwdIdInput.isNotBlank() && state.selectedDiscountLineId != null
                    }
                    Button(
                        onClick = viewModel::confirmCheckout,
                        enabled = checkoutEnabled
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptDialog(state: PosUiState, viewModel: PosViewModel) {
    val receiptText = state.receiptText ?: return
    Dialog(onDismissRequest = { if (!state.printerBusy) viewModel.dismissReceipt() }) {
        Card(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // ── Logo centered in header ──
                    Canvas(
                        Modifier
                            .size(width = 70.dp, height = 84.dp)
                            .padding(top = 4.dp, bottom = 8.dp)
                    ) {
                        drawKanlunganShieldLogo()
                    }

                    // "Receipt" heading
                    Text(
                        when (state.receiptAuditStatus) {
                            "void" -> "Voided Audit Receipt"
                            "refunded" -> "Refunded Audit Receipt"
                            else -> "Receipt"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111111)
                    )
                    Spacer(Modifier.height(14.dp))

                    // Receipt body in monospace to mirror thermal print
                    val annotatedReceipt = remember(receiptText) {
                        buildAnnotatedString {
                            val lines = receiptText.split("\n")
                            lines.forEachIndexed { index, line ->
                                val trimmedLine = line.trim()
                                val isOrderType = trimmedLine == "DINE-IN" || trimmedLine == "TAKE-OUT"
                                if (isOrderType) {
                                    withStyle(style = ParagraphStyle(textAlign = TextAlign.Center)) {
                                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)) {
                                            append(trimmedLine)
                                        }
                                    }
                                } else if (line.startsWith("TOTAL")) {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(line)
                                    }
                                } else {
                                    append(line)
                                }
                                if (index < lines.lastIndex) {
                                    append("\n")
                                }
                            }
                        }
                    }
                    Text(
                        text = annotatedReceipt,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF1A1A1A),
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    val qrBitmap = remember(FACEBOOK_PAGE_URL) {
                        generateQrCodeBitmap(FACEBOOK_PAGE_URL, 250)
                    }

                    Text("Scan to visit our Facebook page!", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR code for the Kanlungan Coffee Garage Facebook page",
                        modifier = Modifier.size(150.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "THIS IS NOT AN OFFICIAL RECEIPT!",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.Red,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Action buttons
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = viewModel::dismissReceipt,
                        enabled = !state.printerBusy
                    ) { Text("Close") }
                    Button(
                        onClick = viewModel::printReceipt2x,
                        enabled = !state.printerBusy &&
                            state.receiptPromotionState != ReceiptPromotionState.CHECKING,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        when {
                            state.receiptPromotionState == ReceiptPromotionState.CHECKING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(RECEIPT_PREPARING_LABEL)
                            }
                            state.receiptSecondCopyCountdown != null ->
                                Text("Second copy in ${state.receiptSecondCopyCountdown}s")
                            state.printerBusy -> Text("Printing...")
                            state.receiptCopyStage == ReceiptCopyStage.SECOND_COPY ->
                                Text("Retry Second Copy")
                            state.receiptPromotionState == ReceiptPromotionState.RETRY_REQUIRED ->
                                Text("Retry & Print")
                            else -> Text("Print Receipt")
                        }
                    }
                }
                state.printerMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (state.receiptPromotionState == ReceiptPromotionState.RETRY_REQUIRED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showCategoryEditor) return
    AlertDialog(
        onDismissRequest = viewModel::closeCategoryEditor,
        title = { Text("Manage Categories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select a category to edit, or start a new one.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.categories.forEach { category ->
                        FilterChip(
                            selected = state.categoryEditorId == category.id,
                            onClick = { viewModel.selectCategoryInEditor(category.id) },
                            label = { Text(category.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = state.categoryEditorName,
                    onValueChange = viewModel::updateCategoryEditorName,
                    label = { Text("Category name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                state.categoryEditorError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::startNewCategoryInEditor) { Text("New") }
                OutlinedButton(
                    onClick = viewModel::deleteSelectedCategory,
                    enabled = state.categoryEditorId != null
                ) {
                    Text("Delete")
                }
                Button(onClick = viewModel::saveCategoryFromEditor) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeCategoryEditor) { Text("Close") } }
    )
}

@Composable
private fun EmployeeEditorDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showEmployeeEditor) return
    AlertDialog(
        onDismissRequest = viewModel::closeEmployeeEditor,
        title = {
            Text(
                if (state.employeeEditorId == null) "Add Employee" else "Edit Employee",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.employeeEditorName,
                    onValueChange = viewModel::updateEmployeeEditorName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.employeeEditorPin,
                    onValueChange = viewModel::updateEmployeeEditorPin,
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Role Selection
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Role", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cashier", "manager").forEach { role ->
                            FilterChip(
                                selected = state.employeeEditorRole == role,
                                onClick = { viewModel.updateEmployeeEditorRole(role) },
                                label = { Text(role.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }

                // Active Switch (only if editing an existing employee)
                if (state.employeeEditorId != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active status", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Switch(
                            checked = state.employeeEditorActive,
                            onCheckedChange = viewModel::updateEmployeeEditorActive
                        )
                    }
                }

                if (state.employeeEditorError != null) {
                    Text(state.employeeEditorError, color = Color.Red, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = viewModel::saveEmployeeFromEditor) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeEmployeeEditor) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModifierEditorDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showModifierEditor) return
    val selectedGroup = state.catalog.groups.firstOrNull { it.id == state.modifierEditorId }
    val optionsForSelectedGroup = state.catalog.options.filter { it.groupId == state.modifierEditorId }
    AlertDialog(
        modifier = Modifier.widthIn(max = 640.dp),
        onDismissRequest = viewModel::closeModifierEditor,
        title = { Text("Manage Modifiers") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select a modifier to edit, or start a new one.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.groups.forEach { group ->
                        FilterChip(
                            selected = state.modifierEditorId == group.id,
                            onClick = { viewModel.selectModifierInEditor(group.id) },
                            label = { Text(group.name) }
                        )
                    }
                }
                OutlinedTextField(
                    value = state.modifierEditorName,
                    onValueChange = viewModel::updateModifierEditorName,
                    label = { Text("Modifier name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                state.modifierEditorError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Options", fontWeight = FontWeight.Bold)
                    Text(
                        if (selectedGroup == null) {
                            "Select or save a modifier group before adding options."
                        } else {
                            "Edit choices for ${selectedGroup.name}. These appear in the POS customize popup."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (selectedGroup != null) {
                        if (optionsForSelectedGroup.isEmpty()) {
                            Text("No options yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                optionsForSelectedGroup.forEach { option ->
                                    FilterChip(
                                        selected = state.modifierOptionEditorId == option.id,
                                        onClick = { viewModel.selectModifierOptionInEditor(option) },
                                        label = {
                                            Text(
                                                if (option.priceDeltaCents == 0) {
                                                    option.name
                                                } else {
                                                    "${option.name} +${money(option.priceDeltaCents)}"
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.modifierOptionEditorName,
                            onValueChange = viewModel::updateModifierOptionEditorName,
                            label = { Text("Option name") },
                            singleLine = true,
                            enabled = selectedGroup != null,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.modifierOptionEditorPrice,
                            onValueChange = viewModel::updateModifierOptionEditorPrice,
                            label = { Text("Price add-on") },
                            prefix = { Text("â‚±") },
                            singleLine = true,
                            enabled = selectedGroup != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(160.dp)
                        )
                    }
                    if (selectedGroup != null && state.modifierOptionEditorId != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("Inventory Link (Optional)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Choose which ingredient this modifier deducts and/or replaces.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        
                        Text("Deducts Ingredient:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = state.modifierOptionEditorIngredientId == null,
                                onClick = { viewModel.updateModifierOptionEditorIngredientId(null) },
                                label = { Text("None (No deduction)") }
                            )
                            state.ingredients.forEach { ing ->
                                FilterChip(
                                    selected = state.modifierOptionEditorIngredientId == ing.id,
                                    onClick = { viewModel.updateModifierOptionEditorIngredientId(ing.id) },
                                    label = { Text(ing.name) }
                                )
                            }
                        }

                        if (state.modifierOptionEditorIngredientId != null) {
                            val selectedIngredient = state.ingredients.firstOrNull { it.id == state.modifierOptionEditorIngredientId }
                            val unitStr = selectedIngredient?.unit ?: "qty"
                            OutlinedTextField(
                                value = state.modifierOptionEditorQty,
                                onValueChange = viewModel::updateModifierOptionEditorQty,
                                label = { Text("Deduction Quantity ($unitStr)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(2.dp))
                            Text("Substitutes / Replaces (Optional):", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("If this modifier replaces a base recipe ingredient (e.g. Oat Milk replacing Whole Milk), select it below. The system will use the quantity from the base recipe.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = state.modifierOptionEditorReplacesId == null,
                                    onClick = { viewModel.updateModifierOptionEditorReplacesId(null) },
                                    label = { Text("None (Add-on)") }
                                )
                                state.ingredients.forEach { ing ->
                                    FilterChip(
                                        selected = state.modifierOptionEditorReplacesId == ing.id,
                                        onClick = { viewModel.updateModifierOptionEditorReplacesId(ing.id) },
                                        label = { Text(ing.name) }
                                    )
                                }
                            }
                        }
                    }
                    state.modifierOptionEditorError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = viewModel::startNewModifierOptionInEditor,
                            enabled = selectedGroup != null
                        ) {
                            Text("New Option")
                        }
                        OutlinedButton(
                            onClick = viewModel::deleteSelectedModifierOption,
                            enabled = state.modifierOptionEditorId != null
                        ) {
                            Text("Delete Option")
                        }
                        Button(
                            onClick = viewModel::saveModifierOptionFromEditor,
                            enabled = selectedGroup != null
                        ) {
                            Text("Save Option")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::startNewModifierInEditor) { Text("New Group") }
                OutlinedButton(
                    onClick = viewModel::deleteSelectedModifier,
                    enabled = state.modifierEditorId != null
                ) {
                    Text("Delete Group")
                }
                Button(onClick = viewModel::saveModifierFromEditor) { Text("Save Group") }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeModifierEditor) { Text("Close") } }
    )
}

@Composable
private fun OrdersScreen(state: PosUiState, viewModel: PosViewModel) {
    val context = LocalContext.current

    val calStart = remember {
        Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
            if (state.orderCustomStart != null) {
                timeInMillis = state.orderCustomStart
            } else {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        }
    }
    val calEnd = remember {
        Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
            if (state.orderCustomEnd != null) {
                timeInMillis = state.orderCustomEnd
            } else {
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }
        }
    }

    var tempStartYear by remember { mutableStateOf(calStart.get(java.util.Calendar.YEAR)) }
    var tempStartMonth by remember { mutableStateOf(calStart.get(java.util.Calendar.MONTH)) }
    var tempStartDay by remember { mutableStateOf(calStart.get(java.util.Calendar.DAY_OF_MONTH)) }

    var tempEndYear by remember { mutableStateOf(calEnd.get(java.util.Calendar.YEAR)) }
    var tempEndMonth by remember { mutableStateOf(calEnd.get(java.util.Calendar.MONTH)) }
    var tempEndDay by remember { mutableStateOf(calEnd.get(java.util.Calendar.DAY_OF_MONTH)) }

    LaunchedEffect(state.orderCustomStart, state.orderCustomEnd) {
        if (state.orderCustomStart != null) {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
                timeInMillis = state.orderCustomStart
            }
            tempStartYear = c.get(java.util.Calendar.YEAR)
            tempStartMonth = c.get(java.util.Calendar.MONTH)
            tempStartDay = c.get(java.util.Calendar.DAY_OF_MONTH)
        }
        if (state.orderCustomEnd != null) {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
                timeInMillis = state.orderCustomEnd
            }
            tempEndYear = c.get(java.util.Calendar.YEAR)
            tempEndMonth = c.get(java.util.Calendar.MONTH)
            tempEndDay = c.get(java.util.Calendar.DAY_OF_MONTH)
        }
    }

    val filteredOrders = remember(state.orders, state.orderDateRange, state.orderCustomStart, state.orderCustomEnd) {
        val now = System.currentTimeMillis()
        var windowEnd = Long.MAX_VALUE
        val windowStart: Long = when (state.orderDateRange) {
            ReportDateRange.TODAY -> {
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            ReportDateRange.MONTH -> now - 30L * 24 * 60 * 60 * 1000
            ReportDateRange.ALL   -> 0L
            ReportDateRange.CUSTOM -> {
                windowEnd = state.orderCustomEnd ?: Long.MAX_VALUE
                state.orderCustomStart ?: 0L
            }
        }
        state.orders.filter { it.createdAt in windowStart..windowEnd }
    }

    val groupedOrders = remember(filteredOrders) {
        val sdf = SimpleDateFormat("M/d/yyyy", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
        filteredOrders.groupBy { order ->
            sdf.format(Date(order.createdAt))
        }
    }

    // PIN Authorization Dialog — shown for all users (cashier + manager)
    val isPinVoid = state.pendingVoidOrderId != null
    val isPinRefund = state.pendingRefundOrderId != null
    if (isPinVoid || isPinRefund) {
        val actionLabel = if (isPinVoid) "Void" else "Refund"
        val orderId = (state.pendingVoidOrderId ?: state.pendingRefundOrderId) ?: ""
        AlertDialog(
            onDismissRequest = { viewModel.cancelVoidRefundPin() },
            title = { Text("$actionLabel Order — Authorization Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Order #${orderId.take(8).uppercase()}",
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = state.voidPinInput,
                        onValueChange = { viewModel.updateVoidPinInput(it) },
                        label = { Text("4-Digit Authorization PIN") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = state.voidPinError != null
                    )
                    if (state.voidPinError != null) {
                        Text(state.voidPinError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitVoidRefundPin() },
                    enabled = state.voidPinInput.length == 4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPinVoid) Color(0xFFE83A1A) else Color(0xFFE67E22)
                    )
                ) {
                    Text("Confirm $actionLabel")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelVoidRefundPin() }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paid Orders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                // Date filter chips for orders
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ReportDateRange.entries.forEach { range ->
                        val label = range.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                        FilterChip(
                            selected = state.orderDateRange == range,
                            onClick = { viewModel.changeOrderDateRange(range) },
                            label = { Text(label) }
                        )
                    }
                }

                if (state.orderDateRange == ReportDateRange.CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFFEBF5FB),
                            border = BorderStroke(1.dp, Color(0xFF3498DB)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = "Custom Range",
                                    color = Color(0xFF2980B9),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // From Date picker
                        val fromStr = if (state.orderCustomStart != null) {
                            val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                            sdf.format(Date(state.orderCustomStart))
                        } else {
                            "mm/dd/yyyy"
                        }
                        Surface(
                            color = Color(0xFFEBF5FB),
                            border = BorderStroke(1.dp, Color(0xFF3498DB)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .clickable {
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                                            startCal.set(y, m, d, 0, 0, 0)
                                            startCal.set(java.util.Calendar.MILLISECOND, 0)

                                            val currentEnd = state.orderCustomEnd ?: System.currentTimeMillis()
                                            viewModel.applyCustomOrderRange(startCal.timeInMillis, currentEnd)
                                        },
                                        tempStartYear,
                                        tempStartMonth,
                                        tempStartDay
                                    ).show()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = fromStr,
                                    color = Color(0xFF2980B9),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(text = "🗓", color = Color(0xFF2980B9), fontSize = 14.sp)
                            }
                        }

                        // To Date picker
                        val toStr = if (state.orderCustomEnd != null) {
                            val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                            sdf.format(Date(state.orderCustomEnd))
                        } else {
                            "mm/dd/yyyy"
                        }
                        Surface(
                            color = Color(0xFFEBF5FB),
                            border = BorderStroke(1.dp, Color(0xFF3498DB)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .clickable {
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val endCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                                            endCal.set(y, m, d, 23, 59, 59)
                                            endCal.set(java.util.Calendar.MILLISECOND, 999)

                                            val currentStart = state.orderCustomStart ?: (System.currentTimeMillis() - 24L * 60 * 60 * 1000)
                                            viewModel.applyCustomOrderRange(currentStart, endCal.timeInMillis)
                                        },
                                        tempEndYear,
                                        tempEndMonth,
                                        tempEndDay
                                    ).show()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = toStr,
                                    color = Color(0xFF2980B9),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(text = "🗓", color = Color(0xFF2980B9), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        if (groupedOrders.isEmpty()) {
            item {
                Text(
                    "No orders match this date range.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        } else {
            groupedOrders.forEach { (dateStr, ordersForDate) ->
                item {
                    Text(
                        text = "Date $dateStr",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(ordersForDate, key = { it.id }) { order ->
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                val statusSuffix = when (order.status) {
                                    "void" -> order.voidReason?.takeIf { it.isNotBlank() }?.let { " (VOIDED: $it)" } ?: " (VOIDED)"
                                    "refunded" -> order.voidReason?.takeIf { it.isNotBlank() }?.let { " (REFUNDED: $it)" } ?: " (REFUNDED)"
                                    else -> ""
                                }
                                val paymentLabel = orderPaymentCategoryLabel(state.payments.filter { it.orderId == order.id })
                                    ?.let { " • $it" }
                                    .orEmpty()
                                Text("Order ${order.id.take(8).uppercase()}$paymentLabel$statusSuffix", fontWeight = FontWeight.Bold, color = if (order.status == "void" || order.status == "refunded") Color.Red else Color.Black)
                                Text("Shift #${order.shiftId}   -   ${date(order.createdAt)}")
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(money(order.totalCents), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))

                                OutlinedButton(
                                    onClick = { viewModel.viewReceiptForOrder(order.id) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        if (order.status == "void" || order.status == "refunded") "Audit Receipt" else "Receipt",
                                        fontSize = 12.sp
                                    )
                                }

                                if (order.status != "void" && order.status != "refunded") {
                                    OutlinedButton(
                                        onClick = { viewModel.openAddOnDialog(order.id) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("Add", fontSize = 12.sp)
                                    }
                                }

                                if (order.status != "void" && order.status != "refunded") {
                                    Button(
                                        onClick = { viewModel.startRefundWithPin(order.id) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22))
                                    ) {
                                        Text("Refund", fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = { viewModel.startVoidWithPin(order.id) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE83A1A))
                                    ) {
                                        Text("Void", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryScreen(state: PosUiState, viewModel: PosViewModel) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            try {
                val excelContent = viewModel.getInventoryReportExcelContent()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(excelContent)
                }
                android.widget.Toast.makeText(context, "Inventory Excel report saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to save inventory report: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Ingredient editor dialog
    if (state.showIngredientEditor) {
        AlertDialog(
            onDismissRequest = viewModel::closeIngredientEditor,
            title = {
                Text(
                    if (state.ingredientEditorId == null) "Add Ingredient" else "Edit Ingredient",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.ingredientEditorName,
                        onValueChange = viewModel::updateIngredientEditorName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.ingredientEditorUnit,
                        onValueChange = viewModel::updateIngredientEditorUnit,
                        label = { Text("Unit (e.g. oz, ea, ml)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp)
                    ) {
                        val suggestions = listOf("oz", "ml", "g", "kg", "L", "ea", "pcs", "tsp", "tbsp", "cup", "pack", "box", "can", "bottle")
                        suggestions.forEach { unit ->
                            val isSelected = state.ingredientEditorUnit.trim().lowercase() == unit
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.updateIngredientEditorUnit(unit) },
                                label = { Text(unit) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.ingredientEditorQty,
                            onValueChange = viewModel::updateIngredientEditorQty,
                            label = { Text("Qty on Hand") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.ingredientEditorThreshold,
                            onValueChange = viewModel::updateIngredientEditorThreshold,
                            label = { Text("Low-stock Alert") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = state.ingredientEditorTakeoutOnly,
                            onCheckedChange = viewModel::updateIngredientEditorTakeoutOnly
                        )
                        Text("Takeout Only (do not deduct for Dine-In)", fontSize = 14.sp)
                    }
                    state.ingredientEditorError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::saveIngredientFromEditor) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeIngredientEditor) { Text("Cancel") }
            }
        )
    }

    val filteredIngredients = remember(state.ingredients, state.ingredientSearchQuery) {
        if (state.ingredientSearchQuery.isBlank()) {
            state.ingredients
        } else {
            state.ingredients.filter {
                it.name.contains(state.ingredientSearchQuery, ignoreCase = true)
            }
        }
    }

    var isSearching by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ingredient Inventory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                isSearching = !isSearching
                                if (!isSearching) {
                                    viewModel.updateIngredientSearchQuery("")
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isSearching) "Close Search" else "🔍 Search")
                        }
                        OutlinedButton(
                            onClick = {
                                val defaultName = "Inventory_Report_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.xlsx"
                                exportLauncher.launch(defaultName)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📥 Export")
                        }
                        Button(
                            onClick = viewModel::openNewIngredientEditor,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+ Add")
                        }
                    }
                }
                if (isSearching) {
                    OutlinedTextField(
                        value = state.ingredientSearchQuery,
                        onValueChange = viewModel::updateIngredientSearchQuery,
                        label = { Text("Search ingredients...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        trailingIcon = {
                            if (state.ingredientSearchQuery.isNotEmpty()) {
                                Text(
                                    text = "✕",
                                    modifier = Modifier
                                        .clickable { viewModel.updateIngredientSearchQuery("") }
                                        .padding(8.dp),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                            }
                        }
                    )
                }
            }
        }
        if (filteredIngredients.isEmpty()) {
            item {
                Text(
                    if (state.ingredientSearchQuery.isBlank())
                        "No ingredients yet. Tap + Add to create one."
                    else
                        "No ingredients match your search.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
        items(filteredIngredients, key = { it.id }) { ingredient ->
            InventoryRow(ingredient, viewModel)
        }
    }
}

@Composable
private fun InventoryRow(ingredient: Ingredient, viewModel: PosViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Ingredient") },
            text = { Text("Delete \"${ingredient.name}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteIngredientById(ingredient.id, ingredient.name)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val isLow = ingredient.quantityOnHand <= ingredient.lowStockThreshold
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = if (isLow) Color(0xFFFFF0EC) else Color.White)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(ingredient.name, fontWeight = FontWeight.Bold)
                Text(
                    "${ingredient.quantityOnHand.formatQty()} ${ingredient.unit} on hand · low at ${ingredient.lowStockThreshold.formatQty()}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (isLow) {
                Text("LOW", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            // Edit button
            OutlinedButton(
                onClick = { viewModel.openEditIngredientEditor(ingredient) },
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(6.dp)
            ) { Text("Edit") }
            Spacer(Modifier.width(6.dp))
            // Delete button
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        }
    }
}

@Composable
private fun ReportsScreen(state: PosUiState, viewModel: PosViewModel) {
    val report = state.dailyReport
    val context = LocalContext.current
    val employeeNamesById = remember(state.allEmployees) {
        state.allEmployees.associate { it.id to it.name }
    }
    val reportCashiers = remember(report.shifts, state.allEmployees) {
        report.shifts
            .map { it.employeeId }
            .distinct()
            .map { employeeId ->
                employeeId to (state.allEmployees.firstOrNull { it.id == employeeId }?.name ?: "Unknown Cashier")
            }
            .sortedWith(
                compareBy<Pair<String, String>> { it.second.lowercase(Locale.US) }
                    .thenBy { it.first }
            )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            try {
                val excelContent = viewModel.getDailyReportExcelContent()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(excelContent)
                }
                android.widget.Toast.makeText(context, "Daily Excel report saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to save report: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val monthNames = remember {
        listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    }

    val calStart = remember {
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
            if (state.reportCustomStart != null) {
                timeInMillis = state.reportCustomStart
            } else {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        }
    }
    val calEnd = remember {
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
            if (state.reportCustomEnd != null) {
                timeInMillis = state.reportCustomEnd
            } else {
                set(java.util.Calendar.HOUR_OF_DAY, 23)
                set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59)
                set(java.util.Calendar.MILLISECOND, 999)
            }
        }
    }

    var tempStartYear by remember { mutableStateOf(calStart.get(java.util.Calendar.YEAR)) }
    var tempStartMonth by remember { mutableStateOf(calStart.get(java.util.Calendar.MONTH)) }
    var tempStartDay by remember { mutableStateOf(calStart.get(java.util.Calendar.DAY_OF_MONTH)) }

    var tempEndYear by remember { mutableStateOf(calEnd.get(java.util.Calendar.YEAR)) }
    var tempEndMonth by remember { mutableStateOf(calEnd.get(java.util.Calendar.MONTH)) }
    var tempEndDay by remember { mutableStateOf(calEnd.get(java.util.Calendar.DAY_OF_MONTH)) }

    var cashierExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.reportCustomStart, state.reportCustomEnd) {
        if (state.reportCustomStart != null) {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
                timeInMillis = state.reportCustomStart
            }
            tempStartYear = c.get(java.util.Calendar.YEAR)
            tempStartMonth = c.get(java.util.Calendar.MONTH)
            tempStartDay = c.get(java.util.Calendar.DAY_OF_MONTH)
        }
        if (state.reportCustomEnd != null) {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila")).apply {
                timeInMillis = state.reportCustomEnd
            }
            tempEndYear = c.get(java.util.Calendar.YEAR)
            tempEndMonth = c.get(java.util.Calendar.MONTH)
            tempEndDay = c.get(java.util.Calendar.DAY_OF_MONTH)
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Daily Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Date filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ReportDateRange.entries.forEach { range ->
                val label = range.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                FilterChip(
                    selected = state.reportDateRange == range,
                    onClick = { viewModel.changeReportDateRange(range) },
                    label = { Text(label) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(
                    onClick = { cashierExpanded = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1A1A1A)),
                    border = BorderStroke(1.dp, Color(0xFF1A1A1A)),
                    modifier = Modifier.height(38.dp)
                ) {
                    val selectedLabel = state.selectedReportCashierId?.let { employeeId ->
                        employeeNamesById[employeeId] ?: "Unknown Cashier"
                    } ?: "All Cashiers"
                    Text(selectedLabel, fontWeight = FontWeight.Medium)
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Cashier",
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                DropdownMenu(
                    expanded = cashierExpanded,
                    onDismissRequest = { cashierExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Cashiers") },
                        onClick = {
                            viewModel.changeReportCashierId(null)
                            cashierExpanded = false
                        }
                    )
                    reportCashiers.forEach { (employeeId, cashierName) ->
                        DropdownMenuItem(
                            text = { Text(cashierName) },
                            onClick = {
                                viewModel.changeReportCashierId(employeeId)
                                cashierExpanded = false
                            }
                        )
                    }
                }
            }

            if (state.reportDateRange == ReportDateRange.CUSTOM) {
                // 1. Custom Range pill
                Surface(
                    color = Color(0xFFEBF5FB),
                    border = BorderStroke(1.dp, Color(0xFF3498DB)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Text(
                            text = "Custom Range",
                            color = Color(0xFF2980B9),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // 2. From Date pill
                val fromStr = if (state.reportCustomStart != null) {
                    val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                    sdf.format(Date(state.reportCustomStart))
                } else {
                    "mm/dd/yyyy"
                }
                Surface(
                    color = Color(0xFFEBF5FB),
                    border = BorderStroke(1.dp, Color(0xFF3498DB)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .clickable {
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val startCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                                    startCal.set(y, m, d, 0, 0, 0)
                                    startCal.set(java.util.Calendar.MILLISECOND, 0)

                                    viewModel.updateCustomReportStart(startCal.timeInMillis)
                                },
                                tempStartYear,
                                tempStartMonth,
                                tempStartDay
                            ).show()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Text(
                            text = fromStr,
                            color = Color(0xFF2980B9),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(text = "🗓", color = Color(0xFF2980B9), fontSize = 14.sp)
                    }
                }

                // 3. To Date pill
                val toStr = if (state.reportCustomEnd != null) {
                    val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
                    sdf.format(Date(state.reportCustomEnd))
                } else {
                    "mm/dd/yyyy"
                }
                Surface(
                    color = Color(0xFFEBF5FB),
                    border = BorderStroke(1.dp, Color(0xFF3498DB)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .clickable {
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val endCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Manila"))
                                    endCal.set(y, m, d, 23, 59, 59)
                                    endCal.set(java.util.Calendar.MILLISECOND, 999)

                                    viewModel.updateCustomReportEnd(endCal.timeInMillis)
                                },
                                tempEndYear,
                                tempEndMonth,
                                tempEndDay
                            ).show()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    ) {
                        Text(
                            text = toStr,
                            color = Color(0xFF2980B9),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(text = "🗓", color = Color(0xFF2980B9), fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = viewModel::printSalesReport,
                enabled = state.isReportRangeReady && !state.printerBusy,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Text(if (state.printerBusy) "Printing..." else "Print Sales")
            }

            Button(
                onClick = {
                    val rangeName = when (state.reportDateRange) {
                        ReportDateRange.TODAY -> "Today"
                        ReportDateRange.MONTH -> "Month"
                        ReportDateRange.ALL -> "AllTime"
                        ReportDateRange.CUSTOM -> "Custom"
                    }
                    val defaultName = "POS_Report_${rangeName}_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.xlsx"
                    exportLauncher.launch(defaultName)
                },
                enabled = state.isReportRangeReady,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(38.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color.White
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Grayscale Excel-style icon drawn with Canvas
                    Canvas(modifier = Modifier.size(16.dp)) {
                        val w = size.width
                        val h = size.height
                        val iconColor = Color.White
                        val darkGray = Color(0xFF424242)
                        val black = Color(0xFF1A1A1A)

                        // Document body (white background)
                        drawRoundRect(
                            color = Color.White,
                            size = Size(w, h),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )

                        // Black header band at top
                        drawRoundRect(
                            color = black,
                            size = Size(w, h * 0.38f),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )

                        // Dark gray left column (row headers)
                        drawRect(
                            color = darkGray,
                            topLeft = Offset(0f, h * 0.38f),
                            size = Size(w * 0.35f, h * 0.62f)
                        )

                        // Grid lines (light gray horizontal lines)
                        val lineColor = Color(0xFFE0E0E0)
                        val lineY1 = h * 0.55f
                        val lineY2 = h * 0.72f
                        val lineY3 = h * 0.88f
                        val lineWidth = 0.5.dp.toPx()
                        drawLine(lineColor, Offset(w * 0.35f, lineY1), Offset(w, lineY1), lineWidth)
                        drawLine(lineColor, Offset(w * 0.35f, lineY2), Offset(w, lineY2), lineWidth)
                        drawLine(lineColor, Offset(w * 0.35f, lineY3), Offset(w, lineY3), lineWidth)

                        // "X" letter in white on the black header
                        val cx = w * 0.5f
                        val cy = h * 0.19f
                        val xHalf = w * 0.12f
                        val xStroke = 1.8.dp.toPx()
                        drawLine(iconColor, Offset(cx - xHalf, cy - xHalf), Offset(cx + xHalf, cy + xHalf), xStroke)
                        drawLine(iconColor, Offset(cx + xHalf, cy - xHalf), Offset(cx - xHalf, cy + xHalf), xStroke)
                    }
                    Text(
                        text = "Export Excel/CSV",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Top metric cards row ──
        if (!state.isReportRangeReady) {
            ReportCard(title = "Custom Range") {
                Text(
                    text = state.reportRangeError ?: "Select both From and To dates",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
        } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Orders", report.orderCount.toString(), Modifier.weight(1f))
            MetricCard("Gross Sales", money(report.grossSalesCents), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Avg. Order Value", money(report.avgOrderValueCents), Modifier.weight(1f))
            MetricCard("Methods", report.paymentTotals.size.toString(), Modifier.weight(1f))
        }

        // ── Payment Breakdown ──
        ReportCard(title = "Payment Breakdown") {
            if (report.paymentTotals.isEmpty()) {
                Text("No paid orders yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                report.paymentTotals.forEach { (method, cents) ->
                    ReportRow(method, money(cents))
                }
            }
        }

        // ── Top-Selling Items ──
        ReportCard(title = "Top-Selling Items") {
            if (report.topItems.isEmpty()) {
                Text("No sales yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                val maxQty = report.topItems.maxOf { it.qtySold }.coerceAtLeast(1)
                report.topItems.forEachIndexed { idx, item ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${idx + 1}. ${item.name}",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${item.qtySold} sold  •  ${money(item.revenueCents)}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        // Mini progress bar
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(Color(0xFFEEEEEE), RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(item.qtySold.toFloat() / maxQty)
                                    .fillMaxHeight()
                                    .background(Color(0xFF27AE60), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                    if (idx < report.topItems.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── Hourly Sales ──
        ReportCard(title = "Hourly Sales") {
            if (report.hourlySales.isEmpty()) {
                Text("No sales data yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                val maxHourly = report.hourlySales.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                val sorted = report.hourlySales.entries.sortedBy { it.key }
                sorted.forEach { (hour, cents) ->
                    val label = when {
                        hour == 0 -> "12 AM"
                        hour < 12 -> "$hour AM"
                        hour == 12 -> "12 PM"
                        else -> "${hour - 12} PM"
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.width(48.dp))
                            Text(money(cents), fontSize = 12.sp, color = Color.Gray)
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(Color(0xFFEEEEEE), RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(cents.toFloat() / maxHourly)
                                    .fillMaxHeight()
                                    .background(Color(0xFF1A1A1A), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ── Cash Drawer ──
        ReportCard(title = "Cash Drawer") {
            if (report.cashDrawerStarting == 0 && report.cashDrawerSales == 0 && report.cashDrawerAdded == 0 && report.cashDrawerRemoved == 0 && report.cashDrawerActual == 0) {
                Text("No shift data available.", color = Color.Gray, fontSize = 13.sp)
            } else {
                val gcashSales = report.onlinePaymentSalesCents
                val totalCashAndGCash = report.cashDrawerExpected + gcashSales

                ReportRow("Starting Cash", money(report.cashDrawerStarting))
                ReportRow("+ Cash Sales Today", money(report.cashDrawerSales))
                ReportRow("+ Online Payment Today", money(gcashSales))
                ReportRow("+ Cash Added", money(report.cashDrawerAdded))
                val closedShiftRefundsTotal = report.closedShiftAdjustments.sumOf { it.amountCents }
                if (closedShiftRefundsTotal > 0) {
                    val manualCashRemoved = (report.cashDrawerRemoved - closedShiftRefundsTotal).coerceAtLeast(0)
                    ReportRow("- Cash Removed", money(report.cashDrawerRemoved))
                    ReportRow("  • Manual Cash Removed", money(manualCashRemoved), labelColor = Color.Gray)
                    ReportRow("  • Closed Shift Voids/Refunds", money(closedShiftRefundsTotal), labelColor = Color.Gray)
                    report.closedShiftAdjustments.forEach { adj ->
                        ReportRow(
                            label = "    - Order #${adj.originalOrderId.take(8).uppercase(java.util.Locale.US)} (${adj.type})",
                            value = money(adj.amountCents),
                            labelColor = Color.Gray
                        )
                    }
                } else {
                    ReportRow("- Cash Removed", money(report.cashDrawerRemoved))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                ReportRow("Should Be in Drawer", money(report.cashDrawerExpected), valueWeight = FontWeight.Bold, valueColor = MaterialTheme.colorScheme.primary)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                ReportRow("Total Cash + Online Payment", money(totalCashAndGCash), valueWeight = FontWeight.Bold, valueColor = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                ReportRow("Cash Counted", money(report.cashDrawerActual))
                
                val diff = report.cashDrawerDifference
                val diffLabel = when {
                    diff > 0 -> "Over by"
                    diff < 0 -> "Short by"
                    else -> "Difference"
                }
                val diffColor = when {
                    diff > 0 -> Color(0xFF27AE60)
                    diff < 0 -> Color(0xFFE83A1A)
                    else -> Color.Gray
                }
                ReportRow(diffLabel, money(kotlin.math.abs(diff)), valueColor = diffColor, valueWeight = FontWeight.Bold)
                
                val statusText = when {
                    diff == 0 -> "Balanced"
                    diff < 0 -> "Missing Cash"
                    else -> "Extra Cash"
                }
                ReportRow("Status", statusText, valueColor = diffColor, valueWeight = FontWeight.Bold)
            }
        }

        // ── Per-Employee Breakdown ──
        ReportCard(title = "Per-Employee Breakdown") {
            if (report.employeeBreakdowns.isEmpty()) {
                Text("No employee sales data yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                report.employeeBreakdowns.forEachIndexed { idx, emp ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(emp.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text("${emp.orderCount} order${if (emp.orderCount != 1) "s" else ""}",
                                fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(money(emp.salesCents), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (idx < report.employeeBreakdowns.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        // ── Inventory Usage ──
        ReportCard(title = "Inventory Usage") {
            if (report.ingredientUsage.isEmpty()) {
                Text("No inventory usage recorded for this period.", color = Color.Gray, fontSize = 13.sp)
            } else {
                report.ingredientUsage.forEachIndexed { idx, usage ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(usage.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            val subText = buildString {
                                append("Used: ${usage.usedToday.formatQty()} ${usage.unit}")
                                if (usage.restocked > 0) {
                                    append("  •  Restocked: ${usage.restocked.formatQty()} ${usage.unit}")
                                }
                            }
                            Text(subText, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(
                            "${usage.endingStock.formatQty()} ${usage.unit}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (usage.isLow) Color(0xFFE83A1A) else Color.DarkGray
                        )
                    }
                    if (idx < report.ingredientUsage.lastIndex) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // ── Low Stock ──
        ReportCard(title = "Low Stock Alert") {
            if (state.lowStock.isEmpty()) {
                Text("All ingredient levels are healthy.", color = Color.Gray, fontSize = 13.sp)
            } else {
                state.lowStock.forEach {
                    ReportRow(it.name, "${it.quantityOnHand.formatQty()} ${it.unit}")
                }
            }
        }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReportCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
private fun ReportRow(
    label: String,
    value: String,
    labelColor: Color = Color.DarkGray,
    valueColor: Color = Color.Unspecified,
    labelWeight: FontWeight = FontWeight.Normal,
    valueWeight: FontWeight = FontWeight.Medium
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = labelColor, fontWeight = labelWeight)
        Text(value, fontSize = 13.sp, fontWeight = valueWeight, color = valueColor)
    }
}


@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp)) {
            Text(label)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicesScreen(state: PosUiState, viewModel: PosViewModel) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        viewModel.onBluetoothPermissionResult(grants.values.all { it })
    }
    val requestPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPrinterDevices()
    }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { viewModel.selectScreen(AppScreen.POS) }, modifier = Modifier.size(56.dp)) {
                Text("<", style = MaterialTheme.typography.headlineMedium, color = Color.Black)
            }
            Text("Edit Printer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = viewModel::savePrinterProfile,
                modifier = Modifier.width(96.dp).height(56.dp)
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }
        }

        state.printerMessage?.let {
            Text(
                it,
                color = if (
                    it.startsWith("Connected") ||
                    it.startsWith("Receipt sent") ||
                    it.startsWith("Test print sent") ||
                    it.contains("saved", ignoreCase = true) ||
                    it.contains("selected", ignoreCase = true) ||
                    it.contains("auto-printed", ignoreCase = true)
                ) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Bold
            )
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(
                    value = state.printerFormName,
                    onValueChange = viewModel::updatePrinterName,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                PrinterChoiceSection(
                    title = "Printer model",
                    options = listOf("POS-58", "Other model"),
                    selected = state.printerFormModel,
                    onSelect = viewModel::updatePrinterModel
                )

                PrinterChoiceSection(
                    title = "Interface",
                    options = listOf(PRINTER_INTERFACE_BLUETOOTH, PRINTER_INTERFACE_WINDOWS_BRIDGE),
                    selected = state.printerFormInterface,
                    onSelect = viewModel::updatePrinterInterface
                )

                if (state.printerFormInterface == PRINTER_INTERFACE_BLUETOOTH) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Bluetooth printer", fontWeight = FontWeight.Bold)
                                Text(
                                    state.printerDevices.firstOrNull { it.address == state.printerFormAddress }?.name
                                        ?: state.printerFormAddress
                                        ?: "No Bluetooth printer selected",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = {
                                    if (state.printerPermissionNeeded || state.printerScanPermissionNeeded) {
                                        requestPermission()
                                    } else {
                                        viewModel.startPrinterScan()
                                    }
                                },
                                enabled = !state.printerScanning,
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (state.printerScanning) "SEARCHING" else "SEARCH")
                            }
                        }

                        if (state.printerPermissionNeeded || state.printerScanPermissionNeeded) {
                            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Bluetooth permission required", fontWeight = FontWeight.Bold)
                                    Text("Allow Bluetooth so the POS can scan for nearby receipt printers.")
                                    Button(onClick = requestPermission, modifier = Modifier.height(48.dp)) {
                                        Text("Allow Bluetooth")
                                    }
                                }
                            }
                        } else if (state.printerDevices.isEmpty()) {
                            Text("No Bluetooth printers found. Turn on the printer, keep it near this tablet, then tap Search.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                state.printerDevices.forEach { device ->
                                    PrinterDeviceRow(
                                        device = device,
                                        connected = state.printerFormAddress == device.address,
                                        busy = state.printerBusy || state.printerScanning,
                                        onClick = { viewModel.selectPrinterForProfile(device) }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Windows printer bridge", fontWeight = FontWeight.Bold)
                            Text("Endpoint: ${state.printerFormBridgeUrl}")
                            Text("Use this when running the Android emulator and the local Java bridge on this PC.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                PrinterChoiceSection(
                    title = "Paper width",
                    options = listOf("58 mm", "80 mm"),
                    selected = "${state.printerFormPaperWidthMm} mm",
                    onSelect = { label -> viewModel.updatePrinterPaperWidth(label.filter(Char::isDigit).toIntOrNull() ?: 58) }
                )

                PrinterChoiceSection(
                    title = "Line character limit",
                    options = listOf("32", "40", "42", "48"),
                    selected = state.printerFormLineCharacters.toString(),
                    onSelect = { label -> viewModel.updatePrinterLineCharacters(label.toIntOrNull() ?: 32) }
                )

                PrinterChoiceSection(
                    title = "Peso sign style",
                    options = listOf("P", "Php", "₱ (UTF-8)", "₱ (Legacy)"),
                    selected = when (state.printerFormPesoSignStyle.lowercase(java.util.Locale.US)) {
                        "p" -> "P"
                        "php" -> "Php"
                        "utf8" -> "₱ (UTF-8)"
                        "legacy" -> "₱ (Legacy)"
                        else -> "P"
                    },
                    onSelect = { label ->
                        val styleValue = when (label) {
                            "P" -> "p"
                            "Php" -> "php"
                            "₱ (UTF-8)" -> "utf8"
                            "₱ (Legacy)" -> "legacy"
                            else -> "p"
                        }
                        viewModel.updatePrinterPesoSignStyle(styleValue)
                    }
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Advanced settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PrinterToggleRow("Print receipts", state.printerFormPrintReceipts, viewModel::togglePrintReceipts)
                    PrinterToggleRow("Automatically print receipt", state.printerFormAutoPrintReceipts, viewModel::toggleAutoPrintReceipts)
                    PrinterToggleRow("Open cash drawer", state.printerFormKickCashDrawer, viewModel::toggleKickCashDrawer)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = viewModel::testPrinter,
                        enabled = !state.printerBusy && !state.printerScanning,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (state.printerBusy) "PRINTING..." else "PRINT TEST")
                    }
                    OutlinedButton(
                        onClick = viewModel::deletePrinterProfile,
                        enabled = !state.printerBusy && !state.printerScanning,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DELETE PRINTER", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        BluetoothHelpCard(state.printerFormInterface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrinterChoiceSection(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) }
                )
            }
        }
    }
}

@Composable
private fun PrinterToggleRow(label: String, checked: Boolean, onCheckedChange: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun PrinterDeviceRow(
    device: PrinterDevice,
    connected: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = !busy,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(device.address, style = MaterialTheme.typography.titleLarge, color = Color(0xFF222222))
            }
            when {
                connected -> PrinterStatusPill("Connected", Color(0xFFFFA000), Color.White)
                device.paired -> PrinterStatusPill("Paired", Color(0xFF2F6B5F), Color.White)
                else -> PrinterStatusPill("Found", Color(0xFFECECEC), Color(0xFF333333))
            }
            Spacer(Modifier.width(12.dp))
            Text(">", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFBDBDBD))
        }
    }
}

@Composable
private fun PrinterStatusPill(label: String, containerColor: Color, textColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BluetoothHelpCard(selectedInterface: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Printer setup help", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (selectedInterface == PRINTER_INTERFACE_WINDOWS_BRIDGE) {
                Text("1. Start the Java print bridge on this Windows PC")
                Text("2. Run adb reverse tcp:9123 tcp:9123 when using the emulator")
                Text("3. Tap Print Test after saving the Windows Bridge profile")
            } else {
                Text("1. Check if the printer is powered on and Bluetooth is enabled")
                Text("2. Tap Search, then choose the printer row")
                Text("3. If it is found but will not connect, pair it in Android Bluetooth settings")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(state: PosUiState, viewModel: PosViewModel) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Store Settings", fontWeight = FontWeight.Bold)
                
                OutlinedTextField(
                    value = state.settingsFormName,
                    onValueChange = viewModel::updateSettingsName,
                    label = { Text("Store Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.settingsFormFooter,
                    onValueChange = viewModel::updateSettingsFooter,
                    label = { Text("Receipt Footer Text") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.settingsFormError != null) {
                    Text(state.settingsFormError, color = Color.Red, fontSize = 13.sp)
                }

                Button(
                    onClick = viewModel::saveSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Settings")
                }
            }
        }

        if (state.isManager) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Free Drink QR Promotion", fontWeight = FontWeight.Bold)
                            Text(
                                "Cloud-controlled across every POS connected to this Render service.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = state.promotionConfig.enabled,
                            onCheckedChange = viewModel::togglePromotionEnabled,
                            enabled = !state.promotionBusy && state.promotionConfig.available
                        )
                    }

                    if (!state.promotionConfig.available) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                state.promotionError ?: "Deploy the complete Render database migration before configuring this promotion.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = viewModel::refreshPromotionConfig,
                                enabled = !state.promotionBusy,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                if (state.promotionBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Retry Render Check")
                                }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.promotionIntervalInput,
                            onValueChange = viewModel::updatePromotionInterval,
                            label = { Text("Orders per QR reward") },
                            supportingText = { Text("1–100,000; default 300") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !state.promotionBusy && state.promotionConfig.available,
                            modifier = Modifier.weight(1f)
                        )
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text("Cycle progress", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${state.promotionConfig.cycleProgress} / ${state.promotionConfig.ordersPerReward}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Lifetime orders: ${state.promotionConfig.lifetimeOrderCount}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Changing the order number starts a fresh cycle at zero. Existing winning claims remain valid.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = state.promotionFormUrlInput,
                        onValueChange = viewModel::updatePromotionFormUrl,
                        label = { Text("Google Form prefilled URL template") },
                        supportingText = { Text("Include {CLAIM_CODE} where the unique code belongs.") },
                        singleLine = true,
                        enabled = !state.promotionBusy && state.promotionConfig.available,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "The customer may choose any drink in the order. One base price is discounted; paid modifiers remain chargeable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    state.promotionError?.takeIf { state.promotionConfig.available }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = viewModel::savePromotionConfig,
                        enabled = !state.promotionBusy && state.promotionConfig.available,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                    ) {
                        if (state.promotionBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Save Promotion")
                    }
                }
            }
        }

        // Manager-only: Void/Refund Authorization PIN card
        if (state.isManager) {
            var pinInput by remember(state.settingsFormVoidPin) { mutableStateOf(state.settingsFormVoidPin) }
            var showVoidRefundPin by remember { mutableStateOf(false) }
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Void / Refund Authorization PIN", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Cashiers must enter this 4-digit PIN to void or refund an order. Managers can change it here.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pinInput = it },
                            label = { Text("4-Digit PIN") },
                            visualTransformation = if (showVoidRefundPin) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else {
                                androidx.compose.ui.text.input.PasswordVisualTransformation()
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                            singleLine = true,
                            trailingIcon = {
                                TextButton(onClick = { showVoidRefundPin = !showVoidRefundPin }) {
                                    Text(if (showVoidRefundPin) "Hide" else "Show")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { viewModel.saveVoidRefundPin(pinInput) },
                            enabled = pinInput.length == 4,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save PIN")
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Payment Methods", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = viewModel::openNewPaymentMethodEditor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+ Add")
                    }
                }
                
                Text(
                    text = "Configure accepted payment methods. System methods cannot be edited, hidden, or deleted.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.paymentMethods.forEach { method ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (method.id == "gcash") "GCash" else method.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = when {
                                        !method.enabled -> "Hidden"
                                        method.isSystem -> "Active"
                                        method.paymentCategory == PaymentCategories.CASH -> "Active • Cash"
                                        method.paymentCategory == PaymentCategories.ONLINE -> "Active • Online"
                                        else -> "Needs category"
                                    },
                                    fontSize = 11.sp,
                                    color = if (method.enabled && (method.isSystem || method.paymentCategory != null)) Color(0xFF2F6B5F) else Color.Gray
                                )
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Toggle Hide/Show
                                OutlinedButton(
                                    onClick = { viewModel.togglePaymentMethodEnabled(method) },
                                    enabled = !method.isSystem,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(if (method.enabled) "Hide" else "Show", fontSize = 11.sp)
                                }
                                // Rename
                                OutlinedButton(
                                    onClick = { viewModel.openEditPaymentMethodEditor(method) },
                                    enabled = !method.isSystem,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Edit", fontSize = 11.sp)
                                }
                                // Delete
                                OutlinedButton(
                                    onClick = { viewModel.deletePaymentMethod(method) },
                                    enabled = !method.isSystem,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (method.isSystem) Color.LightGray else MaterialTheme.colorScheme.error
                                    ),
                                    border = BorderStroke(1.dp, if (method.isSystem) Color.LightGray else MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Delete", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Senior Citizen & PWD Discount Settings", fontWeight = FontWeight.Bold)
                Text(
                    text = "Configure the standard discount percentage applied at checkout.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.settingsFormSeniorPercent,
                        onValueChange = viewModel::updateSeniorDiscountPercent,
                        label = { Text("Senior Discount (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.settingsFormPwdPercent,
                        onValueChange = viewModel::updatePwdDiscountPercent,
                        label = { Text("PWD Discount (%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                state.discountSettingsError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    onClick = viewModel::saveDiscountSettings,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Discounts")
                }
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default Employee PINs", fontWeight = FontWeight.Bold)
                Text("Manager: ****")
                Text("Cashier: 2")
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Customization - Facebook QR Code", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Text(
                    "This is the active Facebook QR code shown on receipts. Scan it to verify the destination before printing.",
                    fontSize = 13.sp,
                    color = Color(0xFF5C5C5C),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))
                val qrBitmap = remember(FACEBOOK_PAGE_URL) {
                    generateQrCodeBitmap(FACEBOOK_PAGE_URL, 320)
                }
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(2.dp, Color(0xFFF5C518)),
                    shadowElevation = 2.dp
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR code for the Kanlungan Coffee Garage Facebook page",
                        modifier = Modifier.size(196.dp).padding(10.dp)
                    )
                }
                Text(
                    "Facebook destination",
                    fontSize = 12.sp,
                    color = Color(0xFF5C5C5C),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    FACEBOOK_PAGE_URL,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Render Cloud Synchronization", fontWeight = FontWeight.Bold)
                Text("Enroll this tablet to synchronize securely through your Render service.", fontSize = 13.sp, color = Color.Gray)

                val syncManager = viewModel.supabaseSyncManager
                var url by remember { mutableStateOf(syncManager.renderCloudUrl) }
                var enrollmentCode by remember { mutableStateOf("") }
                var name by remember { mutableStateOf(syncManager.deviceName) }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Render Cloud URL") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = enrollmentCode,
                    onValueChange = { enrollmentCode = it },
                    label = { Text(if (syncManager.isEnrolled) "Enrollment Code (already enrolled)" else "Enrollment Code") },
                    enabled = !syncManager.isEnrolled,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device Name (e.g. Counter 1, Manager Tablet)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Device ID: ${syncManager.deviceId.take(8)}...", fontSize = 11.sp, color = Color.Gray)
                        Text("Device Role: ${syncManager.deviceRoleLabel}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (syncManager.managerDeviceName.isNotBlank()) {
                            Text("Source of Truth: ${syncManager.managerDeviceName}", fontSize = 11.sp, color = Color.Gray)
                        }
                        Text("Status: ${syncManager.lastSyncStatus}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        val lastSyncStr = if (syncManager.lastSyncTime > 0) {
                            manilaDateFormat("MM/dd h:mm a").format(java.util.Date(syncManager.lastSyncTime))
                        } else {
                            "Never"
                        }
                        Text("Last Sync: $lastSyncStr", fontSize = 11.sp, color = Color.Gray)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.updateRenderCloudConfig(url, enrollmentCode, name)
                            }
                        ) {
                            Text(if (syncManager.isEnrolled) "Save & Sync" else "Enroll & Sync")
                        }
                        if (syncManager.isConfigured()) {
                            OutlinedButton(
                                onClick = { viewModel.triggerSupabaseSync() }
                            ) {
                                Text("Sync Now")
                            }
                        }
                    }
                }
                if (syncManager.isConfigured() && !syncManager.isManagerTablet) {
                    Text(
                        "Counters download menu and settings from the Manager Tablet but still upload sales and inventory activity.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    OutlinedButton(
                        onClick = { viewModel.showManagerAuthorityDialog(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (syncManager.managerDeviceId.isBlank()) "Designate as Manager Tablet" else "Transfer Manager Authority Here")
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Database Maintenance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("Clear all transactions, receipts, cash drawer shifts, reports, inventory history, and stock quantities. Menu items and ingredient definitions will NOT be deleted.", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.showResetConfirmDialog(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reset All Operations & Inventory")
                }
            }
        }

        if (state.showResetConfirmDialog) {
            ResetConfirmDialog(state, viewModel)
        }
        if (state.showManagerAuthorityDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showManagerAuthorityDialog(false) },
                title = { Text("Confirm Manager Tablet") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This tablet will become the only device allowed to publish menu, ingredient setup, employees, payment methods, and store settings.")
                        OutlinedTextField(
                            value = state.managerAuthorityPin,
                            onValueChange = viewModel::updateManagerAuthorityPin,
                            label = { Text("Manager PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            isError = state.managerAuthorityError != null,
                            supportingText = state.managerAuthorityError?.let { error -> { Text(error) } },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmManagerAuthority) { Text("Confirm Transfer") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showManagerAuthorityDialog(false) }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun ResetConfirmDialog(state: PosUiState, viewModel: PosViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.showResetConfirmDialog(false) },
        title = { Text("Confirm Reset") },
        text = { Text("Are you sure you want to delete all reports, transactions, shifts, and inventory history, and reset every stock quantity to zero? This action is permanent and cannot be undone.") },
        confirmButton = {
            Button(
                onClick = viewModel::truncateDailyReport,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear All")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.showResetConfirmDialog(false) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ManagerScreen(state: PosUiState, viewModel: PosViewModel) {
    // Close stale local shifts if the tablet was left open overnight.
    LaunchedEffect(Unit) { viewModel.ensureTodayShift() }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ── Today's Shift Status (auto-managed) ──
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today's Shift", fontWeight = FontWeight.Bold)
                if (state.activeShift != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Shift #${state.activeShift.id}", fontSize = 13.sp, color = Color.Gray)
                        Text("Opened: ${date(state.activeShift.openedAt)}", fontSize = 13.sp, color = Color.Gray)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Starting float", fontSize = 13.sp)
                        Text("₱150.00", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Status", fontSize = 13.sp)
                        Text("● Open", fontSize = 13.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Close the shift from the Drawer screen, then open the next shift with the new starting cash.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 15.sp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Setting up today's shift…", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Employee Management", fontWeight = FontWeight.Bold)
                    Button(onClick = viewModel::openNewEmployeeEditor) {
                        Text("Add Employee")
                    }
                }
                
                if (state.allEmployees.isEmpty()) {
                    Text("No employees defined.", color = Color.Gray, fontSize = 13.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.allEmployees.forEach { emp ->
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFFFAFAFA)).padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(emp.name, fontWeight = FontWeight.SemiBold)
                                    val status = if (emp.active) "Active" else "Inactive"
                                    val pinDisplay = if (emp.role == "manager") "****" else emp.pin
                                    Text("${emp.role.replaceFirstChar { it.uppercase() }}  •  PIN: $pinDisplay  •  $status", fontSize = 11.sp, color = Color.Gray)
                                }
                                OutlinedButton(onClick = { viewModel.openEditEmployeeEditor(emp) }) {
                                    Text("Edit")
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Restock / Purchase Order", fontWeight = FontWeight.Bold)
                
                var selectedIngredientId by remember { mutableStateOf(state.ingredients.firstOrNull()?.id.orEmpty()) }
                var restockQty by remember { mutableStateOf("") }
                
                if (state.ingredients.isEmpty()) {
                    Text("No ingredients available to restock.", color = Color.Gray, fontSize = 13.sp)
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Select Ingredient", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFAFAFA))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    state.ingredients.forEach { ing ->
                                        FilterChip(
                                            selected = selectedIngredientId == ing.id,
                                            onClick = { selectedIngredientId = ing.id },
                                            label = { Text(ing.name) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    val selectedIng = state.ingredients.firstOrNull { it.id == selectedIngredientId } ?: state.ingredients.firstOrNull()
                    if (selectedIng != null) {
                        Text("Current stock: ${selectedIng.quantityOnHand.formatQty()} ${selectedIng.unit}")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = restockQty,
                                onValueChange = { restockQty = it.filter { c -> c.isDigit() || c == '.' } },
                                label = { Text("Restock Quantity (${selectedIng.unit})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val qty = restockQty.toDoubleOrNull()
                                    if (qty != null && qty > 0) {
                                        viewModel.restockIngredient(selectedIng, qty)
                                        restockQty = ""
                                    }
                                },
                                enabled = restockQty.toDoubleOrNull()?.let { it > 0.0 } ?: false
                            ) {
                                Text("Restock")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuScreen(state: PosUiState, viewModel: PosViewModel) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Menu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        MenuEditorCard(state, viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuEditorCard(state: PosUiState, viewModel: PosViewModel) {
    val selectedCategory = state.catalog.categories.firstOrNull { it.id == state.menuFormCategoryId }
    val itemsInSelectedCategory = state.catalog.items.filter { it.categoryId == state.menuFormCategoryId }
    val editingItem = state.catalog.items.firstOrNull { it.id == state.menuFormEditingItemId }
    var showAllRecipeIngredients by remember(state.menuFormEditingItemId) { mutableStateOf(false) }
    var showExclusionSelector by remember(state.menuFormEditingItemId) { mutableStateOf(false) }
    val hasRecipe = editingItem != null && state.menuFormRecipeQuantities.any { (_, qtyStr) ->
        (qtyStr.toDoubleOrNull() ?: 0.0) > 0.0
    }
    val categoryIngredientIds = recipeIngredientIdsForCategory(state.menuFormCategoryId)
    val recipeIngredients = if (showAllRecipeIngredients) {
        state.ingredients
    } else if (hasRecipe) {
        state.ingredients.filter { ingredient ->
            val qty = state.menuFormRecipeQuantities[ingredient.id]?.toDoubleOrNull() ?: 0.0
            qty > 0.0
        }
    } else {
        if (categoryIngredientIds == null) {
            state.ingredients
        } else {
            state.ingredients.filter { it.id in categoryIngredientIds }
        }
    }
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (state.menuFormEditingItemId == null) "Add Menu Item" else "Edit Menu Item",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    editingItem?.let {
                        Text("Editing ${it.name}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = viewModel::startNewMenuItem,
                    enabled = state.menuFormEditingItemId != null
                ) {
                    Text("New Item")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.menuFormName,
                    onValueChange = viewModel::updateMenuFormName,
                    label = { Text("Item name") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.menuFormPrice,
                    onValueChange = viewModel::updateMenuFormPrice,
                    label = { Text("Price") },
                    prefix = { Text("₱") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(160.dp)
                )
            }
            OutlinedTextField(
                value = state.menuFormDescription,
                onValueChange = viewModel::updateMenuFormDescription,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Category", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.categories.forEach { category ->
                        FilterChip(
                            selected = state.menuFormCategoryId == category.id,
                            onClick = {
                                viewModel.updateMenuFormCategory(category.id)
                                viewModel.selectCategory(category.id)
                            },
                            label = { Text(category.name) }
                        )
                    }
                    Button(
                        onClick = viewModel::openNewCategoryEditor,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Modifiers", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.catalog.groups.forEach { group ->
                        FilterChip(
                            selected = group.id in state.menuFormModifierGroupIds,
                            onClick = { viewModel.toggleMenuFormModifierGroup(group.id) },
                            label = { Text(group.name) }
                        )
                    }
                    Button(
                        onClick = viewModel::openModifierEditor,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Complementary (Do Not Deduct)", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showExclusionSelector = true },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+")
                    }
                }
                Text("Ingredients added here will automatically not be deducted when this item is ordered as complimentary.", style = MaterialTheme.typography.bodySmall)
                val visibleExclusionIngredientIds = state.menuFormComplementaryExclusions
                if (visibleExclusionIngredientIds.isEmpty()) {
                    Text("No exclusions configured. Tap '+' to add exclusions.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visibleExclusionIngredientIds.forEach { ingredientId ->
                            val ingredient = state.ingredients.firstOrNull { it.id == ingredientId }
                            if (ingredient != null) {
                                FilterChip(
                                    selected = true,
                                    onClick = { viewModel.toggleMenuFormComplementaryExclusion(ingredientId) },
                                    label = { Text(ingredient.name + "  ✕") }
                                )
                            }
                        }
                    }
                }
            }

            if (showExclusionSelector) {
                AlertDialog(
                    onDismissRequest = { showExclusionSelector = false },
                    title = { Text("Select Ingredients to Exclude") },
                    text = {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .widthIn(max = 480.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Select any ingredients from inventory that should not be deducted for complimentary sales.", style = MaterialTheme.typography.bodySmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                state.ingredients.forEach { ingredient ->
                                    FilterChip(
                                        selected = ingredient.id in state.menuFormComplementaryExclusions,
                                        onClick = { viewModel.toggleMenuFormComplementaryExclusion(ingredient.id) },
                                        label = { Text(ingredient.name) }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showExclusionSelector = false }) {
                            Text("Done")
                        }
                    }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Items in Selected Category", fontWeight = FontWeight.Bold)
                Text("Tap an item from ${selectedCategory?.name ?: "this category"} to edit its details and measurements.", style = MaterialTheme.typography.bodySmall)
                if (itemsInSelectedCategory.isEmpty()) {
                    Text("No items in this category yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsInSelectedCategory.forEach { item ->
                            FilterChip(
                                selected = state.menuFormEditingItemId == item.id,
                                onClick = { viewModel.editMenuItem(item) },
                                label = { Text(item.name) }
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recipe Deduction", fontWeight = FontWeight.Bold)
                    FilterChip(
                        selected = showAllRecipeIngredients,
                        onClick = { showAllRecipeIngredients = !showAllRecipeIngredients },
                        label = { Text(if (showAllRecipeIngredients) "Showing All" else "Show All") }
                    )
                }

                if (editingItem != null && !hasRecipe) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)), // Light yellow/orange warning card
                        border = BorderStroke(1.dp, Color(0xFFFFEBAA)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "⚠️ No recipe configured",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF856404)
                            )
                            Text(
                                text = "The manager must input the recipe deduction first to save changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404)
                            )
                        }
                    }
                }

                Text("Enter how much stock this item uses each time it sells.", style = MaterialTheme.typography.bodySmall)
                if (!showAllRecipeIngredients) {
                    if (hasRecipe) {
                        Text("Showing only active recipe ingredients. Tap 'Show All' to add others.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Showing ingredients for ${selectedCategory?.name ?: "this category"}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                recipeIngredients.chunked(2).forEach { rowIngredients ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowIngredients.forEach { ingredient ->
                            OutlinedTextField(
                                value = state.menuFormRecipeQuantities[ingredient.id].orEmpty(),
                                onValueChange = { viewModel.updateMenuFormRecipeQuantity(ingredient.id, it) },
                                label = { Text("${ingredient.name} (${ingredient.unit})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowIngredients.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            state.menuFormError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = viewModel::saveMenuItemFromForm,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (state.menuFormEditingItemId == null) "Save Menu Item" else "Save Changes")
            }
        }
    }
}

private fun money(cents: Int): String = "₱" + String.format(Locale.US, "%,.2f", cents / 100.0)

private fun availableModifierOptions(
    item: MenuItem,
    group: ModifierGroup,
    options: List<ModifierOption>
): List<ModifierOption> {
    if (group.id != "temp") return options
    val fixedTemperature = when {
        item.isColdOnly() -> "iced"
        item.isHotOnly() -> "hot"
        else -> null
    }
    return fixedTemperature?.let { tempId -> options.filter { it.id == tempId } } ?: options
}

private fun MenuItem.isColdOnly(): Boolean {
    val text = "${id.lowercase(Locale.US)} ${name.lowercase(Locale.US)}"
    return categoryId == "cold" ||
        listOf("iced", "coldbrew", "cold brew", "frappe", "milkshake", "strawberry milk").any { it in text }
}

private fun MenuItem.isHotOnly(): Boolean {
    val text = "${id.lowercase(Locale.US)} ${name.lowercase(Locale.US)}"
    return categoryId in setOf("espresso", "signature") ||
        listOf("hot", "chai").any { it in text }
}

private fun parseMoneyCents(value: String): Int? {
    val amount = value.toDoubleOrNull() ?: return null
    return (amount * 100).roundToInt().takeIf { it >= 0 }
}

private fun date(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Manila")
    return sdf.format(Date(millis))
}

private fun Double.formatQty(): String = if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(Locale.US, this)

private fun recipeIngredientIdsForCategory(categoryId: String): Set<String>? = when (categoryId) {
    "espresso", "signature" -> setOf(
        "beans",
        "milk",
        "oat",
        "condensed-cream",
        "caramel-sauce",
        "chocolate-sauce",
        "white-chocolate-sauce"
    )
    "cold" -> setOf(
        "beans",
        "coldbrew-base",
        "milk",
        "oat",
        "matcha-powder",
        "frappe-base",
        "condensed-cream",
        "strawberry-base",
        "vanilla-base"
    )
    "tea-non-coffee" -> setOf(
        "matcha-powder",
        "chai-base",
        "lemon-tea-base",
        "milk",
        "oat",
        "chocolate-sauce",
        "strawberry-base",
        "vanilla-base"
    )
    "pastry" -> setOf(
        "croissant-stock",
        "chocolate-croissant-stock",
        "muffin-stock",
        "banana-bread-stock",
        "cinnamon-roll-stock",
        "cookie-stock"
    )
    "food" -> setOf("sandwich-stock")
    "combos" -> setOf(
        "beans",
        "milk",
        "croissant-stock",
        "banana-bread-stock",
        "cookie-stock",
        "sandwich-stock"
    )
    else -> null
}

@Composable
private fun OpenShiftPanel(state: PosUiState, viewModel: PosViewModel) {
    Card(
        modifier = Modifier.width(420.dp).padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Open Cash Drawer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = state.startingCashInput,
                onValueChange = viewModel::updateStartingCashInput,
                label = { Text("Starting Cash (₱)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cashier:", color = Color.Gray, fontSize = 14.sp)
                Text(state.employee?.name ?: "Unknown", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Date/Time:", color = Color.Gray, fontSize = 14.sp)
                LiveTimeDisplay()
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::openShift,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Open Shift", fontWeight = FontWeight.Bold)
            }
            
            Text(
                text = "This amount is the money already inside the drawer before selling.",
                color = Color.Gray,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun DrawerScreen(state: PosUiState, viewModel: PosViewModel) {
    if (state.activeShift == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            OpenShiftPanel(state, viewModel)
        }
    } else {
        val activeShift = state.activeShift
        val startingCash = activeShift.startingCashCents
        val cashSales = state.activeShiftCashSales
        val gcashSales = state.activeShiftGCashSales
        val cashAdded = activeShift.cashAddedCents
        val cashRemoved = activeShift.cashRemovedCents
        val shouldBeInDrawer = startingCash + cashSales + cashAdded - cashRemoved
        val totalCashAndGCash = shouldBeInDrawer + gcashSales

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Cash Drawer Summary", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Starting Cash", fontSize = 14.sp, color = Color.Gray)
                        Text(money(startingCash), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cash Sales Today", fontSize = 14.sp, color = Color.Gray)
                        Text(money(cashSales), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Online Payment Today", fontSize = 14.sp, color = Color.Gray)
                        Text(money(gcashSales), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cash Added", fontSize = 14.sp, color = Color.Gray)
                        Text(money(cashAdded), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cash Removed", fontSize = 14.sp, color = Color.Gray)
                        Text(money(cashRemoved), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    val closedShiftRefundsTotal = state.activeShiftAdjustments.sumOf { it.amountCents }
                    if (closedShiftRefundsTotal > 0) {
                        val manualCashRemoved = (cashRemoved - closedShiftRefundsTotal).coerceAtLeast(0)
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("• Manual Cash Removed", fontSize = 13.sp, color = Color.Gray)
                            Text(money(manualCashRemoved), fontSize = 13.sp, color = Color.Gray)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("• Closed Shift Voids/Refunds", fontSize = 13.sp, color = Color.Gray)
                            Text(money(closedShiftRefundsTotal), fontSize = 13.sp, color = Color.Gray)
                        }
                        state.activeShiftAdjustments.forEach { adj ->
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("- Order #${adj.originalOrderId.take(8).uppercase(java.util.Locale.US)} (${adj.type})", fontSize = 12.sp, color = Color.Gray)
                                Text(money(adj.amountCents), fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Should Be in Drawer", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(money(shouldBeInDrawer), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Cash + Online Payment", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(money(totalCashAndGCash), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                text = "Should Be in Drawer is physical cash only. Total Cash + Online Payment includes drawer cash plus online payments.",
                fontSize = 12.sp,
                color = Color.Gray
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.showAddCashDialog(true) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Cash")
                }
                Button(
                    onClick = { viewModel.showRemoveCashDialog(true) },
                    enabled = viewModel.canRemoveCash,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (viewModel.canRemoveCash) "Remove Cash" else "Manager Tablet Only")
                }
                Button(
                    onClick = { viewModel.showCloseShiftDialog(true) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close Shift")
                }
            }
        }

        // Dialogs
        if (state.showAddCashDialog) {
            AddCashDialog(state, viewModel)
        }
        if (state.showRemoveCashDialog) {
            RemoveCashDialog(state, viewModel)
        }
        if (state.showCloseShiftDialog) {
            CloseShiftDialog(state, viewModel, shouldBeInDrawer)
        }
    }
}

@Composable
private fun AddCashDialog(state: PosUiState, viewModel: PosViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.showAddCashDialog(false) },
        title = { Text("Add Cash") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the amount of cash you are adding to the drawer (e.g., extra change).", fontSize = 13.sp)
                OutlinedTextField(
                    value = state.cashAddedInput,
                    onValueChange = viewModel::updateCashAddedInput,
                    label = { Text("Amount (₱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.cashAddedReasonInput,
                    onValueChange = viewModel::updateCashAddedReasonInput,
                    label = { Text("Reason (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = viewModel::addCash) {
                Text("Add Cash")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.showAddCashDialog(false) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RemoveCashDialog(state: PosUiState, viewModel: PosViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.showRemoveCashDialog(false) },
        title = { Text("Remove Cash") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the amount of cash you are taking out of the drawer (e.g., payout, deposit).", fontSize = 13.sp)
                OutlinedTextField(
                    value = state.cashRemovedInput,
                    onValueChange = viewModel::updateCashRemovedInput,
                    label = { Text("Amount (₱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.cashRemovedReasonInput,
                    onValueChange = viewModel::updateCashRemovedReasonInput,
                    label = { Text("Reason (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = viewModel::removeCash) {
                Text("Remove Cash")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.showRemoveCashDialog(false) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CloseShiftDialog(state: PosUiState, viewModel: PosViewModel, shouldBeInDrawer: Int) {
    val countedDouble = state.cashCountedInput.toDoubleOrNull() ?: 0.0
    val countedCents = (countedDouble * 100).roundToInt()
    val difference = countedCents - shouldBeInDrawer
    
    val statusText = when {
        difference == 0 -> "Balanced"
        difference < 0 -> "Missing Cash"
        else -> "Extra Cash"
    }
    
    val statusColor = when {
        difference == 0 -> Color(0xFF2E7D32)
        difference < 0 -> Color(0xFFC62828)
        else -> Color(0xFF1565C0)
    }

    AlertDialog(
        onDismissRequest = { viewModel.showCloseShiftDialog(false) },
        title = { Text("Close Shift / Count Drawer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Count the physical cash in the drawer and enter it below.", fontSize = 13.sp)
                OutlinedTextField(
                    value = state.cashCountedInput,
                    onValueChange = viewModel::updateCashCountedInput,
                    label = { Text("Cash Counted (₱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Should Be in Drawer:", fontSize = 13.sp, color = Color.Gray)
                        Text(money(shouldBeInDrawer), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Cash Counted:", fontSize = 13.sp, color = Color.Gray)
                        Text(money(countedCents), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Difference:", fontSize = 13.sp, color = Color.Gray)
                        val diffSign = if (difference > 0) "+" else ""
                        Text("$diffSign${money(difference)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Status:", fontSize = 13.sp, color = Color.Gray)
                        Text(statusText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::closeShift,
                colors = ButtonDefaults.buttonColors(containerColor = statusColor)
            ) {
                Text("Confirm Close Shift")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.showCloseShiftDialog(false) }) {
                Text("Cancel")
            }
        }
    )
}

private fun generateQrCodeBitmap(text: String, size: Int = 300): android.graphics.Bitmap {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }
}

@Composable
private fun AddOnDialog(state: PosUiState, viewModel: PosViewModel) {
    if (!state.showAddOnDialog || state.addOnOrderId == null) return
    Dialog(onDismissRequest = viewModel::closeAddOnDialog) {
        Card(
            Modifier.fillMaxWidth().widthIn(max = 480.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Add Extra Ingredients",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Order: #${state.addOnOrderId.take(8).uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
                
                Text(
                    text = "Search and select any inventory items to deduct for this order. This will NOT affect sales totals.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                // 1. Selected Ingredients List
                Text("Ingredients to Deduct", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.addOnSelectedQuantities.isEmpty()) {
                        Text(
                            text = "No ingredients selected yet. Use the search below to add.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        state.addOnSelectedQuantities.forEach { (ingId, qty) ->
                            val ing = state.ingredients.find { it.id == ingId }
                            if (ing != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ing.name, fontWeight = FontWeight.Bold)
                                        Text("On hand: ${ing.quantityOnHand.formatQty()} ${ing.unit}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.adjustAddOnQuantity(ingId, -1.0) },
                                            modifier = Modifier.size(36.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("-") }
                                        Text(
                                            text = "${qty.formatQty()} ${ing.unit}",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.widthIn(min = 54.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        OutlinedButton(
                                            onClick = { viewModel.adjustAddOnQuantity(ingId, 1.0) },
                                            modifier = Modifier.size(36.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("+") }
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { viewModel.removeIngredientFromAddOns(ingId) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Text("✕", color = Color.Red, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color.LightGray))

                // 2. Search & Add Section
                OutlinedTextField(
                    value = state.addOnSearchQuery,
                    onValueChange = viewModel::updateAddOnSearchQuery,
                    label = { Text("Search ingredients to add...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                val filteredResults = remember(state.ingredients, state.addOnSearchQuery, state.addOnSelectedQuantities) {
                    if (state.addOnSearchQuery.isBlank()) {
                        emptyList()
                    } else {
                        state.ingredients.filter {
                            it.name.contains(state.addOnSearchQuery, ignoreCase = true) &&
                            !state.addOnSelectedQuantities.containsKey(it.id)
                        }
                    }
                }

                if (state.addOnSearchQuery.isNotBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (filteredResults.isEmpty()) {
                            Text("No matching ingredients found.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        } else {
                            filteredResults.forEach { ing ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(ing.name, style = MaterialTheme.typography.bodyMedium)
                                    Button(
                                        onClick = { viewModel.addIngredientToAddOns(ing.id) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("+ Add", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    TextButton(onClick = viewModel::closeAddOnDialog) { Text("Cancel") }
                    Button(
                        onClick = viewModel::submitAddOns,
                        enabled = state.addOnSelectedQuantities.any { it.value > 0 }
                    ) {
                        Text("Deduct Stock")
                    }
                }
            }
        }
    }
}

@Composable
private fun LowStockRestockDialog(
    state: PosUiState,
    viewModel: PosViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("⚠️", fontSize = 24.sp)
                Text("Restock Low Stock Items", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.lowStock.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "All ingredients are fully stocked!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(state.lowStock, key = { it.id }) { ingredient ->
                            LowStockRestockRow(ingredient = ingredient, viewModel = viewModel)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun LowStockRestockRow(ingredient: Ingredient, viewModel: PosViewModel) {
    var restockQty by remember(ingredient.id) { mutableStateOf("") }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0EC))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ingredient.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${ingredient.quantityOnHand.formatQty()} ${ingredient.unit} on hand · low at ${ingredient.lowStockThreshold.formatQty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = restockQty,
                    onValueChange = { restockQty = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Qty (${ingredient.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val qty = restockQty.toDoubleOrNull()
                        if (qty != null && qty > 0) {
                            viewModel.restockIngredient(ingredient, qty)
                            restockQty = ""
                        }
                    },
                    enabled = restockQty.toDoubleOrNull()?.let { it > 0.0 } ?: false,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Restock")
                }
            }
        }
    }
}
