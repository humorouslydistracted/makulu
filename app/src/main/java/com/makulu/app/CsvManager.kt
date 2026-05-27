package com.makulu.app

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// CSV MANAGER
// Handles: auto-save on order complete, manual export, dual folder save,
// monthly files + latest file, health check
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CsvManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderDao: OrderDao,
    private val menuItemDao: MenuItemDao,
    private val categoryDao: CategoryDao,
    private val shopSpendDao: ShopSpendDao,
    private val appSettingsDao: AppSettingsDao,
    private val receiptFieldDao: ReceiptFieldDao
) {
    data class FolderHealth(
        val appFolderOk: Boolean,
        val documentsFolderOk: Boolean,
        val appFolderPath: String,
        val documentsFolderPath: String
    )

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    // App's external files directory
    private fun getAppFolder(): File {
        val dir = File(context.getExternalFilesDir(null), "makulu_backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Documents/Makulu folder
    private fun getDocumentsFolder(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docs, "Makulu")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun checkFolderHealth(): FolderHealth {
        val appFolder = getAppFolder()
        val docsFolder = getDocumentsFolder()
        return FolderHealth(
            appFolderOk = appFolder.exists() && appFolder.canWrite(),
            documentsFolderOk = docsFolder.exists() && docsFolder.canWrite(),
            appFolderPath = appFolder.absolutePath,
            documentsFolderPath = docsFolder.absolutePath
        )
    }

    // Called every time an order is completed
    suspend fun onOrderCompleted() {
        withContext(Dispatchers.IO) {
            exportAll()
        }
    }

    // Manual full export
    suspend fun exportAll() {
        withContext(Dispatchers.IO) {
            val month = LocalDate.now().format(monthFormat)
            exportOrders(month)
            exportSpending(month)
            exportMenuItems(month)
            exportConsolidated(month)
        }
    }

    private suspend fun exportOrders(month: String) {
        val allOrders = orderDao.getAllCompletedOrdersSync()

        val header = "OrderNumber,Table,Date,ItemName,Quantity,Price,LineTotal,OrderTotal,Status\n"
        val rows = StringBuilder(header)
        allOrders.forEach { owi ->
            owi.items.forEach { item ->
                rows.append("${owi.order.orderNumber},${owi.order.tableName},${owi.order.completedAt},")
                rows.append("${escapeCsv(item.menuItemName)},${item.quantity},${item.price},${item.lineTotal},")
                rows.append("${owi.order.totalAmount},${owi.order.status}\n")
            }
        }

        val content = rows.toString()
        writeToFile("makulu_orders_$month.csv", content)
        writeToFile("makulu_orders_latest.csv", content)
    }

    private suspend fun exportSpending(month: String) {
        val spends = shopSpendDao.getAllSync()
        val header = "ItemName,Amount,Date,CreatedAt\n"
        val rows = StringBuilder(header)
        spends.forEach { s ->
            rows.append("${escapeCsv(s.itemName)},${s.amount},${s.date},${s.createdAt}\n")
        }

        val content = rows.toString()
        writeToFile("makulu_spending_$month.csv", content)
        writeToFile("makulu_spending_latest.csv", content)
    }

    private suspend fun exportMenuItems(month: String) {
        val categories = categoryDao.getAllSync()
        val items = menuItemDao.getAllSync()
        val header = "ItemName,Price,Category,IsAvailable,SortOrder\n"
        val rows = StringBuilder(header)
        items.forEach { item ->
            val catName = categories.find { it.id == item.categoryId }?.name ?: "Unknown"
            rows.append("${escapeCsv(item.name)},${item.price},${escapeCsv(catName)},${item.isAvailable},${item.sortOrder}\n")
        }

        val content = rows.toString()
        writeToFile("makulu_menu_items_$month.csv", content)
        writeToFile("makulu_menu_items_latest.csv", content)
    }

    private suspend fun exportConsolidated(month: String) {
        val allOrders = orderDao.getAllCompletedOrdersSync()
        val spends = shopSpendDao.getAllSync()

        val totalRevenue = allOrders.sumOf { it.order.totalAmount }
        val totalSpending = spends.sumOf { it.amount }
        val totalOrders = allOrders.size

        val header = "Type,Metric,Value\n"
        val rows = StringBuilder(header)
        rows.append("Summary,TotalOrders,$totalOrders\n")
        rows.append("Summary,TotalRevenue,$totalRevenue\n")
        rows.append("Summary,TotalSpending,$totalSpending\n")
        rows.append("Summary,NetProfit,${totalRevenue - totalSpending}\n")
        rows.append("\n")

        // Item-wise sales summary
        val salesMap = mutableMapOf<String, Int>()
        allOrders.forEach { owi ->
            owi.items.forEach { item ->
                salesMap[item.menuItemName] = (salesMap[item.menuItemName] ?: 0) + item.quantity
            }
        }
        salesMap.entries.sortedByDescending { it.value }.forEach { (name, qty) ->
            rows.append("ItemSales,${escapeCsv(name)},$qty\n")
        }

        val content = rows.toString()
        writeToFile("makulu_consolidated_$month.csv", content)
        writeToFile("makulu_consolidated_latest.csv", content)
    }

    private fun writeToFile(fileName: String, content: String) {
        try {
            val appFile = File(getAppFolder(), fileName)
            FileWriter(appFile).use { it.write(content) }
        } catch (_: Exception) {}

        try {
            val docsFile = File(getDocumentsFolder(), fileName)
            FileWriter(docsFile).use { it.write(content) }
        } catch (_: Exception) {}
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    data class BackupFileInfo(val name: String, val sizeKb: Long, val lastModified: Long)

    fun listBackupFiles(): Pair<List<BackupFileInfo>, List<BackupFileInfo>> {
        val appFiles = getAppFolder().listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupFileInfo(it.name, it.length() / 1024, it.lastModified()) }
            ?: emptyList()

        val docFiles = getDocumentsFolder().listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupFileInfo(it.name, it.length() / 1024, it.lastModified()) }
            ?: emptyList()

        return appFiles to docFiles
    }
}
