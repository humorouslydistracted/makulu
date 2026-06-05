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
    private val tableDao: TableDao,
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
            markExportTime()
        }
    }

    private suspend fun exportOrders(month: String) {
        val allOrders = orderDao.getAllCompletedOrdersSync()

        val header = "OrderNumber,Table,Date,ItemName,Quantity,Price,LineTotal,OrderTotal,Discount,CGST,SGST,FinalTotal,PaymentMode,Status\n"
        val rows = StringBuilder(header)
        allOrders.forEach { owi ->
            owi.items.forEach { item ->
                rows.append("${owi.order.orderNumber},${owi.order.tableName},${owi.order.completedAt},")
                rows.append("${escapeCsv(item.menuItemName)},${item.quantity},${item.price},${item.lineTotal},")
                rows.append("${owi.order.totalAmount},${owi.order.discountAmount},${owi.order.cgstAmount},${owi.order.sgstAmount},${owi.order.finalTotal},${escapeCsv(owi.order.paymentMode)},${owi.order.status.name}\n")
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

        val totalRevenue = allOrders.sumOf { owi ->
            val o = owi.order
            if (o.finalTotal > 0) o.finalTotal else o.totalAmount
        }
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

    private val LAST_EXPORT_KEY = "csv_last_export_timestamp"

    private suspend fun markExportTime() {
        appSettingsDao.set(AppSettings(LAST_EXPORT_KEY, System.currentTimeMillis().toString()))
    }

    /** Delete all CSV backup files from app and Documents folders. */
    suspend fun clearBackupFiles() = withContext(Dispatchers.IO) {
        getAppFolder().listFiles()?.filter { it.extension == "csv" }?.forEach { it.delete() }
        getDocumentsFolder().listFiles()?.filter { it.extension == "csv" }?.forEach { it.delete() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV IMPORT: Per-section import with filename + schema validation
    // ─────────────────────────────────────────────────────────────────────────

    enum class ImportSection { ORDERS, MENU, SPENDING }

    data class SectionValidationResult(
        val valid: Boolean,
        val errorMessage: String? = null,
        val missingHeaders: List<String> = emptyList(),
        val unexpectedHeaders: List<String> = emptyList(),
        val rowCount: Int = 0,
    )

    data class SectionImportResult(
        val success: Boolean,
        val message: String,
        val rowsImported: Int = 0,
    )

    private val ORDERS_HEADERS = listOf(
        "OrderNumber", "Table", "Date", "ItemName", "Quantity", "Price", "LineTotal",
        "OrderTotal", "Discount", "CGST", "SGST", "FinalTotal", "PaymentMode", "Status",
    )
    private val SPENDING_HEADERS = listOf("ItemName", "Amount", "Date", "CreatedAt")
    private val MENU_HEADERS = listOf("ItemName", "Price", "Category", "IsAvailable", "SortOrder")

    private val ORDERS_FILENAME = Regex("""(?i)makulu_orders(_latest|_\d{4}-\d{2})\.csv""")
    private val MENU_FILENAME = Regex("""(?i)makulu_menu_items(_latest|_\d{4}-\d{2})\.csv""")
    private val SPENDING_FILENAME = Regex("""(?i)makulu_spending(_latest|_\d{4}-\d{2})\.csv""")
    private val MONTHLY_ARCHIVE = Regex("""(?i)_\d{4}-\d{2}\.csv$""")
    private val ISO_DATE_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}""")

    fun expectedFilenameHint(section: ImportSection): String = when (section) {
        ImportSection.ORDERS -> "makulu_orders_latest.csv or makulu_orders_YYYY-MM.csv"
        ImportSection.MENU -> "makulu_menu_items_latest.csv or makulu_menu_items_YYYY-MM.csv"
        ImportSection.SPENDING -> "makulu_spending_latest.csv or makulu_spending_YYYY-MM.csv"
    }

    fun latestFilenameHint(section: ImportSection): String = when (section) {
        ImportSection.ORDERS -> "makulu_orders_latest.csv"
        ImportSection.MENU -> "makulu_menu_items_latest.csv"
        ImportSection.SPENDING -> "makulu_spending_latest.csv"
    }

    fun validateFilename(fileName: String, section: ImportSection): Boolean {
        val name = fileName.substringAfterLast('/')
        return when (section) {
            ImportSection.ORDERS -> ORDERS_FILENAME.matches(name)
            ImportSection.MENU -> MENU_FILENAME.matches(name)
            ImportSection.SPENDING -> SPENDING_FILENAME.matches(name)
        }
    }

    fun isMonthlyArchive(fileName: String): Boolean {
        val name = fileName.substringAfterLast('/')
        return MONTHLY_ARCHIVE.containsMatchIn(name) && !name.contains("_latest", ignoreCase = true)
    }

    fun validateHeaders(file: File, section: ImportSection): SectionValidationResult {
        val expected = when (section) {
            ImportSection.ORDERS -> ORDERS_HEADERS
            ImportSection.MENU -> MENU_HEADERS
            ImportSection.SPENDING -> SPENDING_HEADERS
        }
        val lines = file.readLines()
        if (lines.isEmpty()) {
            return SectionValidationResult(false, "File is empty.")
        }
        val actual = parseCsvLine(lines.first()).map { it.trim() }
        val expectedNorm = expected.map { it.lowercase() }
        val actualNorm = actual.map { it.lowercase() }

        if (actualNorm != expectedNorm) {
            val missing = expected.filter { it.lowercase() !in actualNorm }
            val unexpected = actual.filter { it.isNotBlank() && it.lowercase() !in expectedNorm }
            val detail = buildString {
                if (missing.isNotEmpty()) append("Missing: ${missing.joinToString(", ")}.")
                if (unexpected.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Unexpected: ${unexpected.joinToString(", ")}.")
                }
            }
            val base = "File structure is invalid. Expected columns are missing or incorrect. Please upload a valid backup file."
            return SectionValidationResult(
                valid = false,
                errorMessage = if (detail.isNotBlank()) "$base\n$detail" else base,
                missingHeaders = missing,
                unexpectedHeaders = unexpected,
            )
        }

        return SectionValidationResult(valid = true, rowCount = lines.size - 1)
    }

    /** Non-blocking hint when date columns may not work with Today/Week/Month filters. */
    fun checkDateFormatWarning(file: File, section: ImportSection): String? {
        val lines = file.readLines().drop(1).filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val sampleDates = lines.take(5).mapNotNull { line ->
            val cols = parseCsvLine(line)
            when (section) {
                ImportSection.ORDERS -> cols.getOrNull(2)?.trim()
                ImportSection.SPENDING -> cols.getOrNull(2)?.trim()
                ImportSection.MENU -> null
            }
        }
        if (sampleDates.isEmpty()) return null
        if (sampleDates.any { !ISO_DATE_PREFIX.containsMatchIn(it) }) {
            return "Some dates in this file are not in YYYY-MM-DD format. " +
                "Imported data may not appear under Today/Week/Month filters. Use Analysis or Ledger All/History, or re-export from Makulu."
        }
        return null
    }

    suspend fun importSection(file: File, fileName: String, section: ImportSection): SectionImportResult =
        withContext(Dispatchers.IO) {
            try {
                if (!validateFilename(fileName, section)) {
                    return@withContext SectionImportResult(
                        success = false,
                        message = "Expected filename: ${expectedFilenameHint(section)}. Please select the correct file.",
                    )
                }

                val headerCheck = validateHeaders(file, section)
                if (!headerCheck.valid) {
                    return@withContext SectionImportResult(
                        success = false,
                        message = headerCheck.errorMessage ?: "Invalid file structure.",
                    )
                }

                val rowsImported = when (section) {
                    ImportSection.ORDERS -> {
                        val parsed = parseOrders(file)
                        if (parsed.isEmpty()) {
                            return@withContext SectionImportResult(false, "File has no data rows.")
                        }
                        applyOrdersImport(parsed)
                    }
                    ImportSection.MENU -> {
                        val parsed = parseMenuItems(file)
                        if (parsed.items.isEmpty()) {
                            return@withContext SectionImportResult(false, "File has no data rows.")
                        }
                        applyMenuImport(parsed)
                    }
                    ImportSection.SPENDING -> {
                        val spends = parseSpending(file)
                        if (spends.isEmpty()) {
                            return@withContext SectionImportResult(false, "File has no data rows.")
                        }
                        applySpendingImport(spends)
                    }
                }

                val label = when (section) {
                    ImportSection.ORDERS -> "orders"
                    ImportSection.MENU -> "menu items"
                    ImportSection.SPENDING -> "spending entries"
                }
                SectionImportResult(
                    success = true,
                    message = "Imported $rowsImported $label.",
                    rowsImported = rowsImported,
                )
            } catch (e: Exception) {
                SectionImportResult(false, "Import failed: ${e.message}")
            }
        }

    private data class ParsedMenuImport(
        val categories: List<Pair<String, Int>>,
        val items: List<MenuItemDraft>
    )

    private data class MenuItemDraft(
        val name: String,
        val price: Double,
        val categoryName: String,
        val isAvailable: Boolean,
        val sortOrder: Int
    )

    private data class ParsedOrderImport(
        val order: Order,
        val items: List<OrderItemDraft>
    )

    private data class OrderItemDraft(
        val menuItemName: String,
        val price: Double,
        val quantity: Int
    )

    private suspend fun importMenuItems(file: File): Int {
        val parsed = parseMenuItems(file)
        if (parsed.items.isEmpty()) return 0
        return applyMenuImport(parsed)
    }

    private suspend fun applyMenuImport(parsed: ParsedMenuImport): Int {
        menuItemDao.deleteAll()
        categoryDao.deleteAll()

        val categoryMap = mutableMapOf<String, Long>()
        parsed.categories.forEach { (name, sortOrder) ->
            categoryMap[name] = categoryDao.insert(Category(name = name, sortOrder = sortOrder))
        }

        val items = parsed.items.map { draft ->
            MenuItem(
                name = draft.name,
                price = draft.price,
                categoryId = categoryMap[draft.categoryName]!!,
                isAvailable = draft.isAvailable,
                sortOrder = draft.sortOrder
            )
        }
        menuItemDao.insertAll(items)
        return items.size
    }

    private fun parseMenuItems(file: File): ParsedMenuImport {
        val lines = file.readLines().drop(1)
        if (lines.isEmpty()) return ParsedMenuImport(emptyList(), emptyList())

        val categoryOrder = linkedMapOf<String, Int>()
        var catOrder = 0
        val items = mutableListOf<MenuItemDraft>()

        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 4) {
                val catName = cols[2]
                if (!categoryOrder.containsKey(catName)) {
                    categoryOrder[catName] = catOrder++
                }
                items.add(
                    MenuItemDraft(
                        name = cols[0],
                        price = cols[1].toDoubleOrNull() ?: 0.0,
                        categoryName = catName,
                        isAvailable = cols[3].toBooleanStrictOrNull() ?: true,
                        sortOrder = cols.getOrNull(4)?.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return ParsedMenuImport(categoryOrder.map { it.key to it.value }, items)
    }

    private suspend fun importSpending(file: File): Int {
        val spends = parseSpending(file)
        if (spends.isEmpty()) return 0
        return applySpendingImport(spends)
    }

    private suspend fun applySpendingImport(spends: List<ShopSpend>): Int {
        shopSpendDao.deleteAll()
        shopSpendDao.insertAll(spends)
        return spends.size
    }

    private fun parseSpending(file: File): List<ShopSpend> {
        val lines = file.readLines().drop(1)
        if (lines.isEmpty()) return emptyList()

        val spends = mutableListOf<ShopSpend>()
        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 3) {
                val date = cols[2]
                spends.add(
                    ShopSpend(
                        itemName = cols[0],
                        amount = cols[1].toDoubleOrNull() ?: 0.0,
                        date = date,
                        createdAt = cols.getOrNull(3) ?: date
                    )
                )
            }
        }
        return spends
    }

    private suspend fun importOrders(file: File): Int {
        val parsed = parseOrders(file)
        if (parsed.isEmpty()) return 0
        return applyOrdersImport(parsed)
    }

    private suspend fun applyOrdersImport(parsed: List<ParsedOrderImport>): Int {
        orderDao.deleteAllOrderItems()
        orderDao.deleteAllOrders()

        val menuByName = menuItemDao.getAllSync().associateBy { it.name.trim().lowercase() }
        val tableByName = tableDao.getAllSync().associateBy { it.name.trim().lowercase() }

        parsed.forEach { entry ->
            val tableId = tableByName[entry.order.tableName.trim().lowercase()]?.id ?: 0L
            val orderId = orderDao.insertOrder(entry.order.copy(tableId = tableId))
            orderDao.insertOrderItems(
                entry.items.map { draft ->
                    val menuItemId = menuByName[draft.menuItemName.trim().lowercase()]?.id ?: 0L
                    OrderItem(
                        orderId = orderId,
                        menuItemId = menuItemId,
                        menuItemName = draft.menuItemName,
                        price = draft.price,
                        quantity = draft.quantity
                    )
                }
            )
        }
        return parsed.size
    }

    private fun parseOrders(file: File): List<ParsedOrderImport> {
        val lines = file.readLines().drop(1)
        if (lines.isEmpty()) return emptyList()

        val orderGroups = linkedMapOf<String, MutableList<List<String>>>()
        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 8) {
                orderGroups.getOrPut(cols[0]) { mutableListOf() }.add(cols)
            }
        }

        return orderGroups.map { (orderNumber, rows) ->
            val firstRow = rows.first()
            val tableName = firstRow[1]
            val completedAt = firstRow[2]
            val totalAmount = firstRow[7].toDoubleOrNull() ?: 0.0

            val discountAmount: Double
            val cgstAmount: Double
            val sgstAmount: Double
            val finalTotal: Double
            val paymentMode: String
            val status: OrderStatus

            if (firstRow.size >= 14) {
                discountAmount = firstRow[8].toDoubleOrNull() ?: 0.0
                cgstAmount = firstRow[9].toDoubleOrNull() ?: 0.0
                sgstAmount = firstRow[10].toDoubleOrNull() ?: 0.0
                finalTotal = firstRow[11].toDoubleOrNull() ?: totalAmount
                paymentMode = firstRow[12]
                status = parseOrderStatus(firstRow[13])
            } else {
                discountAmount = 0.0
                cgstAmount = 0.0
                sgstAmount = 0.0
                finalTotal = totalAmount
                paymentMode = ""
                status = parseOrderStatus(firstRow.getOrNull(8))
            }

            ParsedOrderImport(
                order = Order(
                    orderNumber = orderNumber,
                    tableId = 0,
                    tableName = tableName,
                    status = status,
                    totalAmount = totalAmount,
                    discountAmount = discountAmount,
                    cgstAmount = cgstAmount,
                    sgstAmount = sgstAmount,
                    finalTotal = finalTotal,
                    paymentMode = paymentMode,
                    completedAt = completedAt
                ),
                items = rows.map { cols ->
                    OrderItemDraft(
                        menuItemName = cols[3],
                        price = cols[5].toDoubleOrNull() ?: 0.0,
                        quantity = cols[4].toIntOrNull() ?: 1
                    )
                }
            )
        }
    }

    private fun parseOrderStatus(raw: String?): OrderStatus {
        val normalized = raw?.trim()?.uppercase() ?: return OrderStatus.COMPLETED
        return runCatching { OrderStatus.valueOf(normalized) }.getOrElse {
            when (normalized) {
                "COMPLETE" -> OrderStatus.COMPLETED
                "PLACED" -> OrderStatus.PLACED
                "DRAFT" -> OrderStatus.DRAFT
                else -> OrderStatus.COMPLETED
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }
}
