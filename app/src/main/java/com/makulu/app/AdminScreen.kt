package com.makulu.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// Indian date format helper
private fun formatIndianDateTime(isoDateTime: String?): String {
    if (isoDateTime == null) return ""
    return try {
        val ldt = LocalDateTime.parse(isoDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ldt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm:ss.SS a"))
    } catch (_: Exception) { isoDateTime }
}

// Input sanitizer: allows letters, digits, spaces, and safe special chars
private fun sanitizeInput(input: String, maxLen: Int = 50): String {
    return input.filter { c ->
        c.isLetterOrDigit() || c in " -./()&@#:+₹%"
    }.take(maxLen)
}

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN PIN ENTRY SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminPinScreen(
    onPinVerified: () -> Unit,
    settingsRepo: SettingsRepository,
    onForgotPin: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var lastAttemptedPin by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun submitPin() {
        if (pin.length != 4 || verifying) return
        verifying = true
        val currentPin = pin
        lastAttemptedPin = currentPin
        scope.launch {
            val savedPin = settingsRepo.get(SettingsRepository.KEY_ADMIN_PIN)
            if (currentPin == savedPin) {
                keyboard?.hide()
                error = null
                onPinVerified()
            } else {
                error = "Incorrect PIN"
                pin = ""
            }
            verifying = false
        }
    }

    LaunchedEffect(pin) {
        if (pin.length < 4) lastAttemptedPin = null
        if (pin.length == 4 && pin != lastAttemptedPin) submitPin()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(this@BoxWithConstraints.maxHeight * 0.18f))
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Enter Admin PIN", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("4-digit PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submitPin() }),
                singleLine = true,
                colors = makuluOutlinedTextFieldColors()
            )

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { submitPin() }, enabled = pin.length == 4 && !verifying) {
                Text(if (verifying) "Checking..." else "Unlock")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onForgotPin) {
                Text("Forgot PIN?", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN MAIN SCREEN (after PIN verified)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminScreen(
    initialSection: Int = 0,
    adminVm: AdminViewModel = hiltViewModel(),
    ledgerVm: LedgerViewModel = hiltViewModel(),
    spendVm: SpendingViewModel = hiltViewModel(),
    analysisVm: AnalysisViewModel = hiltViewModel(),
    csvManager: CsvManager,
    settingsRepo: SettingsRepository
) {
    var selectedSection by remember(initialSection) { mutableStateOf(initialSection) }
    val sections = listOf("Tables", "Menu Items", "Ledger", "Spending", "Analysis", "CSV Backup", "Receipt")

    Column(modifier = Modifier.fillMaxSize()) {
        // Section content
        when (selectedSection) {
            0 -> AdminTablesSection(adminVm)
            1 -> AdminMenuItemsSection(adminVm)
            2 -> AdminLedgerSection(ledgerVm)
            3 -> AdminSpendingSection(spendVm)
            4 -> AdminAnalysisSection(analysisVm)
            5 -> AdminCsvSection(csvManager)
            6 -> AdminReceiptSection(adminVm, settingsRepo)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TABLES SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminTablesSection(vm: AdminViewModel) {
    val tables by vm.tables.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var newTableName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TableInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tables (${tables.size})", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add Table")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Drag to reorder • Tap to rename • Swipe to delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(tables, key = { it.id }) { table ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(table.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renameTarget = table; renameText = table.name }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = { vm.deleteTable(table) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // Add dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Table") },
            text = {
                    OutlinedTextField(
                        value = newTableName,
                        onValueChange = { if (it.length <= 3 && it.all { c -> c.isLetterOrDigit() }) newTableName = it },
                        label = { Text("Table Name (up to 3 chars)") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTableName.isNotBlank()) { vm.addTable(newTableName); newTableName = ""; showAddDialog = false }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    // Rename dialog
    renameTarget?.let { table ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Table") },
            text = {
                Column {
                    Text("Old orders in ledger will keep the previous name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { if (it.length <= 3 && it.all { c -> c.isLetterOrDigit() }) renameText = it },
                        label = { Text("New Name") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) { vm.renameTable(table, renameText); renameTarget = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MENU ITEMS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminMenuItemsSection(vm: AdminViewModel) {
    val categories by vm.categories.collectAsState()
    val allItems by vm.allItems.collectAsState()
    var expandedCat by remember { mutableStateOf<Long?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var addItemCategoryId by remember { mutableStateOf<Long?>(null) }
    var newCategoryName by remember { mutableStateOf("") }
    var newItemName by remember { mutableStateOf("") }
    var newItemPrice by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<MenuItem?>(null) }
    var editName by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }
    var categoryDeleteTarget by remember { mutableStateOf<Category?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Menu Items", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showAddCategoryDialog = true }) {
                Text("+ Category")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            categories.forEach { cat ->
                val catItems = allItems.filter { it.categoryId == cat.id }
                item(key = "cat_${cat.id}") {
                    Surface(
                        onClick = { expandedCat = if (expandedCat == cat.id) null else cat.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(cat.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                            Text("${catItems.size}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { addItemCategoryId = cat.id; showAddItemDialog = true }) {
                                Text("ADD", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = {
                                categoryDeleteTarget = cat
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Category", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (expandedCat == cat.id) {
                    items(catItems, key = { it.id }) { menuItem ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(menuItem.name, fontSize = 20.sp)
                                Text("₹${"%.2f".format(menuItem.price)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Availability checkbox
                            Checkbox(
                                checked = menuItem.isAvailable,
                                onCheckedChange = { vm.toggleAvailability(menuItem) }
                            )
                            IconButton(onClick = { editingItem = menuItem; editName = menuItem.name; editPrice = menuItem.price.toString() }) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.deleteItem(menuItem) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Category Dialog
    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("Add Category") },
            text = {
                OutlinedTextField(value = newCategoryName, onValueChange = { newCategoryName = sanitizeInput(it, 30) }, label = { Text("Category Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
            },
            confirmButton = { TextButton(onClick = { vm.addCategory(newCategoryName); newCategoryName = ""; showAddCategoryDialog = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") } }
        )
    }

    // Add Item Dialog
    if (showAddItemDialog) {
        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = { Text("Add Menu Item") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 40) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newItemPrice, onValueChange = { newItemPrice = it },
                        label = { Text("Price (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = makuluOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = newItemPrice.toDoubleOrNull()
                    if (newItemName.isNotBlank() && price != null && addItemCategoryId != null) {
                        vm.addItem(newItemName, price, addItemCategoryId!!)
                        newItemName = ""; newItemPrice = ""; showAddItemDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddItemDialog = false }) { Text("Cancel") } }
        )
    }

    // Edit Item Dialog
    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit ${item.name}") },
            text = {
                Column {
                    OutlinedTextField(value = editName, onValueChange = { editName = sanitizeInput(it, 40) }, label = { Text("Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = editPrice, onValueChange = { editPrice = it }, label = { Text("Price (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = editPrice.toDoubleOrNull()
                    if (editName.isNotBlank() && price != null) {
                        vm.updateItem(item.copy(name = editName, price = price))
                        editingItem = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingItem = null }) { Text("Cancel") } }
        )
    }

    // Category delete confirmation (Option B: delete items + category)
    categoryDeleteTarget?.let { category ->
        val catItems = allItems.filter { it.categoryId == category.id }
        AlertDialog(
            onDismissRequest = { categoryDeleteTarget = null },
            title = { Text("Delete Category") },
            text = {
                if (catItems.isNotEmpty()) {
                    Text("This will permanently delete \"${category.name}\" and all ${catItems.size} items inside it. Continue?")
                } else {
                    Text("Delete empty category \"${category.name}\"?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (catItems.isNotEmpty()) {
                        vm.deleteCategoryWithItems(category)
                    } else {
                        vm.deleteCategory(category)
                    }
                    categoryDeleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { categoryDeleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEDGER SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminLedgerSection(vm: LedgerViewModel) {
    val selectedTab by vm.selectedTab.collectAsState()
    val todayOrders by vm.todayOrders.collectAsState()
    val weekOrders by vm.weekOrders.collectAsState()
    val monthOrders by vm.monthOrders.collectAsState()
    val revenueToday by vm.revenueToday.collectAsState()
    val revenueWeek by vm.revenueWeek.collectAsState()
    val revenueMonth by vm.revenueMonth.collectAsState()

    var selectedOrder by remember { mutableStateOf<OrderWithItems?>(null) }
    var pendingDeleteOrderId by remember { mutableStateOf<Long?>(null) }

    val orders = when (selectedTab) { 0 -> todayOrders; 1 -> todayOrders; 2 -> weekOrders; 3 -> monthOrders; else -> todayOrders }
    val revenue = when (selectedTab) { 1 -> revenueToday; 2 -> revenueWeek; 3 -> revenueMonth; else -> revenueToday }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            listOf("History", "Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = selectedTab == i, onClick = { vm.selectTab(i) }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Total Orders: ${orders.size}", style = MaterialTheme.typography.titleMedium)
                    Text("Revenue: ₹${"%.2f".format(revenue)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Olive)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Order list
        LazyColumn {
            items(orders) { owi ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { selectedOrder = owi }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(owi.order.tableName, fontWeight = FontWeight.Bold)
                            Text("${owi.order.orderNumber} • ${owi.items.size} items", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₹${"%.2f".format(owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                        if (pendingDeleteOrderId == owi.order.id) {
                            IconButton(onClick = {
                                vm.deleteOrder(owi.order)
                                pendingDeleteOrderId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteOrderId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteOrderId = owi.order.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Order detail dialog
    selectedOrder?.let { owi ->
        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            title = { Text("${owi.order.orderNumber}") },
            text = {
                Column {
                    Text("Table: ${owi.order.tableName}")
                    Text("Date: ${formatIndianDateTime(owi.order.completedAt ?: owi.order.createdAt)}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    owi.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(item.menuItemName, modifier = Modifier.weight(1f))
                            Text("x${item.quantity}")
                            Text(" ₹${"%.2f".format(item.lineTotal)}", modifier = Modifier.width(80.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Total: ₹${"%.2f".format(owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { TextButton(onClick = { selectedOrder = null }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SPENDING SECTION (Full — Admin)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminSpendingSection(vm: SpendingViewModel) {
    val allSpends by vm.allSpends.collectAsState()
    val todayTotal by vm.todayTotal.collectAsState()
    val weekTotal by vm.weekTotal.collectAsState()
    val monthTotal by vm.monthTotal.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSpend by remember { mutableStateOf<ShopSpend?>(null) }
    var spendName by remember { mutableStateOf("") }
    var spendAmount by remember { mutableStateOf("") }
    var spendDate by remember { mutableStateOf("") }
    var pendingDeleteSpendId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Shop Spending", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                editingSpend = null
                spendName = ""
                spendAmount = ""
                spendDate = ""
                showAddDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add Spend")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = selectedTab) {
            listOf("History", "Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            val total = when (selectedTab) { 1 -> todayTotal; 2 -> weekTotal; 3 -> monthTotal; else -> allSpends.sumOf { it.amount } }
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Spent: ₹${"%.2f".format(total)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(allSpends) { spend ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    onClick = {
                        editingSpend = spend
                        spendName = spend.itemName
                        spendAmount = spend.amount.toString()
                        spendDate = spend.date
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spend.itemName, fontWeight = FontWeight.Medium)
                            Text(spend.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("₹${"%.2f".format(spend.amount)}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            editingSpend = spend
                            spendName = spend.itemName
                            spendAmount = spend.amount.toString()
                            spendDate = spend.date
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        if (pendingDeleteSpendId == spend.id) {
                            IconButton(onClick = {
                                vm.deleteSpend(spend)
                                pendingDeleteSpendId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteSpendId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteSpendId = spend.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Spend") },
            text = {
                Column {
                    OutlinedTextField(value = spendName, onValueChange = { spendName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = spendAmount, onValueChange = { spendAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (spendDate.isBlank()) "Today (tap to change)" else spendDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = spendAmount.toDoubleOrNull()
                    if (spendName.isNotBlank() && amount != null) {
                        vm.addSpend(spendName, amount, spendDate.ifBlank { null })
                        spendName = ""
                        spendAmount = ""
                        spendDate = ""
                        showAddDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { spendDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }

    editingSpend?.let { spend ->
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingSpend = null },
            title = { Text("Edit Spend") },
            text = {
                Column {
                    OutlinedTextField(value = spendName, onValueChange = { spendName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = spendAmount, onValueChange = { spendAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (spendDate.isBlank()) "Select date" else spendDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = spendAmount.toDoubleOrNull()
                    if (spendName.isNotBlank() && amount != null && spendDate.isNotBlank()) {
                        vm.updateSpend(spend.copy(itemName = spendName, amount = amount, date = spendDate))
                        editingSpend = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSpend = null }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { spendDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANALYSIS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminAnalysisSection(vm: AnalysisViewModel) {
    val period by vm.selectedPeriod.collectAsState()
    val summary by vm.summary.collectAsState()
    val showAll by vm.showAll.collectAsState()
    val ascending by vm.ascending.collectAsState()
    var showCategory by remember { mutableStateOf(false) } // false=List, true=Category

    LaunchedEffect(Unit) { vm.loadAnalysis() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Period selector
        TabRow(selectedTabIndex = period) {
            listOf("Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = period == i, onClick = { vm.selectPeriod(i) }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Revenue vs Spending
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Revenue", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.revenue)}", fontWeight = FontWeight.Bold, color = Olive)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Spending", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.spending)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Net", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.net)}", fontWeight = FontWeight.Bold, color = if (summary.net >= 0) Olive else MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Controls: Ascending/Descending + List/Category toggle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.toggleSortOrder() }) {
                Text(if (ascending) "↑ Ascending" else "↓ Descending")
            }
            TextButton(onClick = { showCategory = !showCategory }) {
                Text(if (showCategory) "📂 Category" else "📋 List")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Header row
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Item", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Qty", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("%", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        HorizontalDivider()

        val itemsToShow = if (showAll) summary.itemSales else summary.itemSales.take(20)
        val totalQty = summary.itemSales.sumOf { it.quantity }.coerceAtLeast(1)

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (showCategory) {
                // Group by category
                val grouped = itemsToShow.groupBy { it.categoryName }
                grouped.forEach { (catName, catItems) ->
                    val catQty = catItems.sumOf { it.quantity }
                    val catPct = (catQty * 100.0 / totalQty)
                    item(key = "cat_header_$catName") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(catName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                            Text("$catQty", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold)
                            Text("${"%.1f".format(catPct)}%", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    }
                    itemsIndexed(catItems) { index, sale ->
                        val pct = (sale.quantity * 100.0 / totalQty)
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                            Text("${index + 1}", modifier = Modifier.width(28.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sale.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text("${sale.quantity}", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                            Text("${"%.1f".format(pct)}%", modifier = Modifier.width(48.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Flat list
                itemsIndexed(itemsToShow) { index, sale ->
                    val pct = (sale.quantity * 100.0 / totalQty)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("${index + 1}", modifier = Modifier.width(28.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sale.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text("${sale.quantity}", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                        Text("${"%.1f".format(pct)}%", modifier = Modifier.width(48.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (summary.itemSales.size > 20 && !showAll) {
            TextButton(onClick = { vm.toggleShowAll() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Show All (${summary.itemSales.size} items)")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CSV BACKUP SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminCsvSection(csvManager: CsvManager) {
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf(csvManager.checkFolderHealth()) }
    var exporting by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf(csvManager.listBackupFiles()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("CSV Backup", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        // Explanation
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("What is this?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "CSV backup saves your data (orders, spending, menu items) as spreadsheet files " +
                    "(.csv) that can be opened in Excel or Google Sheets. This is your safety net — " +
                    "if the app is uninstalled or phone is lost, your data survives in these files.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("How it works:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "• Auto-save: Every time you complete an order, all CSV files are automatically updated.\n" +
                    "• Export Now: Immediately re-generates all CSV files from your current data.\n" +
                    "• Files are saved to TWO locations for redundancy.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("Files generated:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "• makulu_orders_YYYY-MM.csv — All completed orders\n" +
                    "• makulu_spending_YYYY-MM.csv — Shop spending entries\n" +
                    "• makulu_menu_items_YYYY-MM.csv — Menu catalog snapshot\n" +
                    "• makulu_consolidated_YYYY-MM.csv — Summary + item sales\n" +
                    "• *_latest.csv — Always-updated copy of the above",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Folder health
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Save Locations", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, contentDescription = null, tint = if (health.appFolderOk) TableFree else TablePlaced, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("App Folder: ${if (health.appFolderOk) "✓ OK" else "✗ NOT ACCESSIBLE"}", style = MaterialTheme.typography.bodyMedium)
                        Text(health.appFolderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, contentDescription = null, tint = if (health.documentsFolderOk) TableFree else TablePlaced, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Documents Folder: ${if (health.documentsFolderOk) "✓ OK" else "✗ NOT ACCESSIBLE"}", style = MaterialTheme.typography.bodyMedium)
                        Text(health.documentsFolderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                exporting = true
                exportDone = false
                scope.launch {
                    csvManager.exportAll()
                    exporting = false
                    exportDone = true
                    health = csvManager.checkFolderHealth()
                    backupFiles = csvManager.listBackupFiles()
                }
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (exporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.CloudUpload, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("Export Now") }
        }

        if (exportDone) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("✅ Export complete! All CSV files regenerated from current data.", color = Olive, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Show existing files
        Text("Backup Files (App Folder)", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        if (backupFiles.first.isEmpty()) {
            Text("No files yet. Complete an order or tap Export Now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            backupFiles.first.forEach { f ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(f.name, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Text("${f.sizeKb} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RECEIPT SETTINGS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminReceiptSection(vm: AdminViewModel, settingsRepo: SettingsRepository) {
    val fields by vm.receiptFields.collectAsState()
    var showAddField by remember { mutableStateOf(false) }
    var newFieldName by remember { mutableStateOf("") }
    var newFieldValue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Body toggles
    var showOrderNo by remember { mutableStateOf(true) }
    var showDateTime by remember { mutableStateOf(true) }
    var showTable by remember { mutableStateOf(true) }
    var showItems by remember { mutableStateOf(true) }
    var showTotal by remember { mutableStateOf(true) }
    var footerText by remember { mutableStateOf("Thank you, visit again!") }
    var footerEnabled by remember { mutableStateOf(true) }

    // Load settings
    LaunchedEffect(Unit) {
        showOrderNo = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO) != "false"
        showDateTime = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME) != "false"
        showTable = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TABLE) != "false"
        showItems = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS) != "false"
        showTotal = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL) != "false"
        footerText = settingsRepo.get(SettingsRepository.KEY_RECEIPT_FOOTER) ?: "Thank you, visit again!"
        footerEnabled = settingsRepo.get(SettingsRepository.KEY_RECEIPT_FOOTER_ENABLED) != "false"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Receipt Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        // Header fields
        Text("Header Fields", fontWeight = FontWeight.SemiBold)
        fields.filter { it.isHeader }.forEach { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = field.isEnabled, onCheckedChange = {
                    vm.updateReceiptField(field.copy(isEnabled = it))
                })
                Column(modifier = Modifier.weight(1f)) {
                    Text(field.fieldName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(field.fieldValue, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { vm.deleteReceiptField(field) }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        TextButton(onClick = { showAddField = true }) { Text("+ Add Header Field") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Body toggles
        Text("Body Fields", fontWeight = FontWeight.SemiBold)
        ReceiptToggle("Order Number", showOrderNo) { showOrderNo = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO, it.toString()) } }
        ReceiptToggle("Date/Time", showDateTime) { showDateTime = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME, it.toString()) } }
        ReceiptToggle("Table Number", showTable) { showTable = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TABLE, it.toString()) } }
        ReceiptToggle("Items + Prices", showItems) { showItems = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS, it.toString()) } }
        ReceiptToggle("Total", showTotal) { showTotal = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL, it.toString()) } }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Footer
        Text("Footer", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = footerEnabled, onCheckedChange = {
                footerEnabled = it
                scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_FOOTER_ENABLED, it.toString()) }
            })
            OutlinedTextField(
                value = footerText,
                onValueChange = { val s = sanitizeInput(it, 60); footerText = s; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_FOOTER, s) } },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = makuluOutlinedTextFieldColors()
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // Preview button
        var showPreview by remember { mutableStateOf(false) }
        Button(onClick = { showPreview = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Visibility, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Preview Receipt")
        }

        if (showPreview) {
            ReceiptPreviewDialog(
                headerFields = fields.filter { it.isHeader && it.isEnabled },
                showOrderNo = showOrderNo,
                showDateTime = showDateTime,
                showTable = showTable,
                showItems = showItems,
                showTotal = showTotal,
                footerEnabled = footerEnabled,
                footerText = footerText,
                onDismiss = { showPreview = false }
            )
        }
    }

    // Add field dialog
    if (showAddField) {
        AlertDialog(
            onDismissRequest = { showAddField = false },
            title = { Text("Add Header Field") },
            text = {
                Column {
                    OutlinedTextField(value = newFieldName, onValueChange = { newFieldName = sanitizeInput(it, 30) }, label = { Text("Label (e.g. Phone)") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newFieldValue, onValueChange = { newFieldValue = sanitizeInput(it, 50) }, label = { Text("Value") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFieldName.isNotBlank()) { vm.addReceiptField(newFieldName, newFieldValue, true); newFieldName = ""; newFieldValue = ""; showAddField = false }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddField = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ReceiptToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label)
    }
}

@Composable
fun ReceiptPreviewDialog(
    headerFields: List<ReceiptField>,
    showOrderNo: Boolean,
    showDateTime: Boolean,
    showTable: Boolean,
    showItems: Boolean,
    showTotal: Boolean,
    footerEnabled: Boolean,
    footerText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipt Preview (80mm)") },
        text = {
            // Mock 80mm thermal receipt (32 chars wide)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .width(240.dp), // approximately 80mm at screen density
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val mono = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    val monoBold = mono.copy(fontWeight = FontWeight.Bold)
                    val monoLarge = mono.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)

                    // Header
                    if (headerFields.isNotEmpty()) {
                        Text(headerFields.first().fieldValue, style = monoLarge)
                        headerFields.drop(1).forEach {
                            Text(it.fieldValue, style = mono)
                        }
                        Text("--------------------------------", style = mono)
                    }

                    // Body
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        if (showOrderNo) Text("Order: ORD-20260527-001", style = mono)
                        if (showDateTime) Text("Date: 2026-05-27 14:30", style = mono)
                        if (showTable) Text("Table: T01", style = mono)

                        if (showItems) {
                            Text("--------------------------------", style = mono)
                            Text("Item         Qty   Amount", style = monoBold)
                            Text("--------------------------------", style = mono)
                            Text("Chicken Roll  x2   ₹120.00", style = mono)
                            Text("Veg Pasta     x1    ₹80.00", style = mono)
                            Text("Cold Coffee   x3   ₹150.00", style = mono)
                        }

                        if (showTotal) {
                            Text("--------------------------------", style = mono)
                            Text("Total: ₹350.00", style = monoLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }

                    // Footer
                    if (footerEnabled && footerText.isNotBlank()) {
                        Text("--------------------------------", style = mono)
                        Text(footerText, style = mono)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PRINTER SETTINGS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminPrinterSection(printerManager: PrinterManager) {
    val isConnected by printerManager.isConnected.collectAsState()
    val printerName by printerManager.printerName.collectAsState()
    val pairedDevices = remember { printerManager.getPairedDevices() }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Printer Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // Status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Print,
                    contentDescription = null,
                    tint = if (isConnected) PrinterConnected else PrinterDisconnected,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(if (isConnected) "Connected" else "Not Connected", fontWeight = FontWeight.Bold)
                    printerName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Paired devices
        Text("Paired Bluetooth Devices", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        if (pairedDevices.isEmpty()) {
            Text("No paired devices. Pair your printer via Android Bluetooth Settings first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            pairedDevices.forEach { device ->
                @Suppress("MissingPermission")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        printerManager.connectToPrinter(device) { }
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        @Suppress("MissingPermission")
                        Text(device.name ?: device.address, modifier = Modifier.weight(1f))
                        if (isConnected && printerName == device.name) {
                            Text("Connected", color = PrinterConnected, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        if (isConnected) {
            Button(
                onClick = {
                    scope.launch { printerManager.printTestPage() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Print")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { printerManager.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = { printerManager.forgetPrinter() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Forget Printer")
        }

        // Printer Log
        Spacer(modifier = Modifier.height(24.dp))
        Text("Printer Log", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        val printerLogs by printerManager.logs.collectAsState()
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        if (printerLogs.isEmpty()) {
            Text("No activity yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            printerLogs.take(15).forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(entry.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                    Icon(
                        if (entry.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (entry.success) Olive else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(entry.event, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val logText = printerLogs.joinToString("\n") { "[${it.timestamp}] ${if (it.success) "OK" else "FAIL"} ${it.event}" }
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logText))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Logs")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHOP SPENDING SIDEBAR (no PIN required)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShopSpendingSidebar(vm: SpendingViewModel = hiltViewModel()) {
    val lastTen by vm.lastTen.collectAsState()
    val todayTotal by vm.todayTotal.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSpend by remember { mutableStateOf<ShopSpend?>(null) }
    var newItemName by remember { mutableStateOf("") }
    var newAmount by remember { mutableStateOf("") }
    var newDate by remember { mutableStateOf("") }
    var pendingDeleteSpendId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Shop Spending", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { newItemName = ""; newAmount = ""; newDate = ""; showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Today: ₹${"%.2f".format(todayTotal)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(12.dp))

        Text("Last 10 entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn {
            items(lastTen) { spend ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    onClick = {
                        editingSpend = spend
                        newItemName = spend.itemName
                        newAmount = spend.amount.toString()
                        newDate = spend.date
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spend.itemName, fontWeight = FontWeight.Medium)
                            Text(spend.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("₹${"%.2f".format(spend.amount)}", fontWeight = FontWeight.Bold)
                        if (pendingDeleteSpendId == spend.id) {
                            IconButton(onClick = {
                                vm.deleteSpend(spend)
                                pendingDeleteSpendId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteSpendId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteSpendId = spend.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Spend") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newAmount, onValueChange = { newAmount = it }, label = { Text("Amount (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newDate.isBlank()) "Today (tap to change)" else newDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = newAmount.toDoubleOrNull()
                    if (newItemName.isNotBlank() && amount != null) {
                        vm.addSpend(newItemName, amount, newDate.ifBlank { null })
                        newItemName = ""; newAmount = ""; newDate = ""; showAddDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { newDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }

    editingSpend?.let { spend ->
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingSpend = null },
            title = { Text("Edit Spend") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newAmount, onValueChange = { newAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newDate.isBlank()) "Select date" else newDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = newAmount.toDoubleOrNull()
                    if (newItemName.isNotBlank() && amount != null && newDate.isNotBlank()) {
                        vm.updateSpend(spend.copy(itemName = newItemName, amount = amount, date = newDate))
                        editingSpend = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSpend = null }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { newDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TODAY'S ORDERS SIDEBAR (no PIN)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TodayOrdersSidebar(
    ledgerVm: LedgerViewModel = hiltViewModel(),
    onPrintReceipt: (Long) -> Unit = {},
    onPrintKitchenOrder: (Long) -> Unit = {},
    onUpdateOrder: (Long) -> Unit = {}
) {
    val todayOrders by ledgerVm.todayOrders.collectAsState()
    var selectedOrder by remember { mutableStateOf<OrderWithItems?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today's Orders", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${todayOrders.size} orders completed today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn {
            items(todayOrders) { owi ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { selectedOrder = owi }
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(owi.order.tableName, fontWeight = FontWeight.Bold)
                            Text("${owi.items.size} items • ${formatIndianDateTime(owi.order.completedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₹${"%.2f".format(owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    selectedOrder?.let { owi ->
        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            title = { Text(owi.order.orderNumber) },
            text = {
                Column {
                    Text("Table: ${owi.order.tableName}")
                    Text("Time: ${formatIndianDateTime(owi.order.completedAt)}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    owi.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(item.menuItemName, modifier = Modifier.weight(1f))
                            Text("x${item.quantity}  ₹${"%.2f".format(item.lineTotal)}")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Total: ₹${"%.2f".format(owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedOrder = null; onPrintReceipt(owi.order.id) }, modifier = Modifier.weight(1f)) {
                            Text("Order Receipt", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { selectedOrder = null; onPrintKitchenOrder(owi.order.id) }, modifier = Modifier.weight(1f)) {
                            Text("Kitchen Receipt", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { selectedOrder = null; onUpdateOrder(owi.order.id) }, modifier = Modifier.weight(1f)) {
                            Text("Update", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedOrder = null }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATE PICKER COMPOSABLE
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakuluDatePicker(
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val instant = java.time.Instant.ofEpochMilli(millis)
                    val date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    onDateSelected(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}
