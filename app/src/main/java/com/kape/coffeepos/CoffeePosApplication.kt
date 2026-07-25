package com.kape.coffeepos

import android.app.Application
import com.kape.coffeepos.data.AppDatabase
import com.kape.coffeepos.data.AuditLogRepository
import com.kape.coffeepos.data.EmployeeRepository
import com.kape.coffeepos.data.InventoryRepository
import com.kape.coffeepos.data.MenuRepository
import com.kape.coffeepos.data.OrderRepository
import com.kape.coffeepos.data.ReportsRepository
import com.kape.coffeepos.data.SeedData
import com.kape.coffeepos.data.SettingsRepository
import com.kape.coffeepos.data.ShiftRepository
import com.kape.coffeepos.data.SupabaseSyncManager
import com.kape.coffeepos.printer.BluetoothPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CoffeePosApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        val pendingDeleteDao = db.pendingDeleteDao()
        val settingsRepository = SettingsRepository(db.settingsDao(), pendingDeleteDao)
        val inventoryRepository = InventoryRepository(db.inventoryDao(), pendingDeleteDao)
        val syncManager = SupabaseSyncManager(this, db)
        val orderRepository = OrderRepository(db, db.orderDao(), db.shiftDao(), settingsRepository, inventoryRepository, db.menuDao(), syncManager)
        val auditLogRepository = AuditLogRepository(db)

        container = AppContainer(
            seedData = SeedData(db),
            menuRepository = MenuRepository(db.menuDao(), pendingDeleteDao),
            inventoryRepository = inventoryRepository,
            employeeRepository = EmployeeRepository(db.employeeDao()),
            shiftRepository = ShiftRepository(db.shiftDao(), db.stockSnapshotDao(), db.inventoryDao(), this),
            settingsRepository = settingsRepository,
            orderRepository = orderRepository,
            reportsRepository = ReportsRepository(orderRepository, inventoryRepository, db.orderDao(), db.employeeDao()),
            auditLogRepository = auditLogRepository,
            printerManager = BluetoothPrinterManager(this),
            supabaseSyncManager = syncManager
        )

        // Start the background synchronization loop
        syncManager.startSyncLoop(applicationScope)
    }
}

data class AppContainer(
    val seedData: SeedData,
    val menuRepository: MenuRepository,
    val inventoryRepository: InventoryRepository,
    val employeeRepository: EmployeeRepository,
    val shiftRepository: ShiftRepository,
    val settingsRepository: SettingsRepository,
    val orderRepository: OrderRepository,
    val reportsRepository: ReportsRepository,
    val auditLogRepository: AuditLogRepository,
    val printerManager: BluetoothPrinterManager,
    val supabaseSyncManager: SupabaseSyncManager
)

