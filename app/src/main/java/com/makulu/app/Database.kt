package com.makulu.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// ENTITIES
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "menu_items",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("categoryId")]
)
data class MenuItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val categoryId: Long,
    val isAvailable: Boolean = true,
    val sortOrder: Int = 0
)

@Entity(tableName = "tables")
data class TableInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // up to 3 chars alphanumeric
    val sortOrder: Int = 0
)

enum class OrderStatus { DRAFT, PLACED, COMPLETED }

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNumber: String, // ORD-YYYYMMDD-001
    val tableId: Long,
    val tableName: String, // snapshot at time of order
    val status: OrderStatus = OrderStatus.DRAFT,
    val totalAmount: Double = 0.0,
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    val completedAt: String? = null
)

@Entity(
    tableName = "order_items",
    foreignKeys = [ForeignKey(
        entity = Order::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("orderId")]
)
data class OrderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val menuItemId: Long,
    val menuItemName: String, // snapshot
    val price: Double, // snapshot
    val quantity: Int
) {
    val lineTotal: Double get() = price * quantity
}

@Entity(tableName = "shop_spends")
data class ShopSpend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemName: String,
    val amount: Double,
    val date: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val createdAt: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "receipt_fields")
data class ReceiptField(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fieldName: String, // e.g., "Shop Name", "Address", "Phone", "GSTN", custom
    val fieldValue: String,
    val isEnabled: Boolean = true,
    val isHeader: Boolean = true, // true = header field, false = body toggle
    val sortOrder: Int = 0
)

// Relation
data class OrderWithItems(
    @Embedded val order: Order,
    @Relation(parentColumn = "id", entityColumn = "orderId")
    val items: List<OrderItem>
)

// ─────────────────────────────────────────────────────────────────────────────
// TYPE CONVERTERS
// ─────────────────────────────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromOrderStatus(status: OrderStatus): String = status.name

    @TypeConverter
    fun toOrderStatus(value: String): OrderStatus = OrderStatus.valueOf(value)
}

// ─────────────────────────────────────────────────────────────────────────────
// DAOs
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC")
    suspend fun getAllSync(): List<Category>

    @Insert
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("UPDATE categories SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)
}

@Dao
interface MenuItemDao {
    @Query("SELECT * FROM menu_items WHERE categoryId = :categoryId ORDER BY sortOrder ASC")
    fun getByCategory(categoryId: Long): Flow<List<MenuItem>>

    @Query("SELECT * FROM menu_items WHERE isAvailable = 1 ORDER BY sortOrder ASC")
    fun getAvailable(): Flow<List<MenuItem>>

    @Query("SELECT * FROM menu_items ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<MenuItem>>

    @Query("SELECT * FROM menu_items ORDER BY sortOrder ASC")
    suspend fun getAllSync(): List<MenuItem>

    @Insert
    suspend fun insert(item: MenuItem): Long

    @Update
    suspend fun update(item: MenuItem)

    @Delete
    suspend fun delete(item: MenuItem)

    @Query("UPDATE menu_items SET isAvailable = :available WHERE id = :id")
    suspend fun setAvailability(id: Long, available: Boolean)

    @Query("UPDATE menu_items SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Query("DELETE FROM menu_items WHERE categoryId = :categoryId")
    suspend fun deleteByCategoryId(categoryId: Long)
}

@Dao
interface TableDao {
    @Query("SELECT * FROM tables ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<TableInfo>>

    @Query("SELECT * FROM tables ORDER BY sortOrder ASC")
    suspend fun getAllSync(): List<TableInfo>

    @Insert
    suspend fun insert(table: TableInfo): Long

    @Update
    suspend fun update(table: TableInfo)

    @Delete
    suspend fun delete(table: TableInfo)

    @Query("UPDATE tables SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)
}

@Dao
interface OrderDao {
    @Insert
    suspend fun insertOrder(order: Order): Long

    @Update
    suspend fun updateOrder(order: Order)

    @Insert
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("DELETE FROM order_items WHERE orderId = :orderId")
    suspend fun deleteOrderItems(orderId: Long)

    @Delete
    suspend fun deleteOrder(order: Order)

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderWithItems(orderId: Long): OrderWithItems?

    @Transaction
    @Query("SELECT * FROM orders WHERE tableId = :tableId AND status != 'COMPLETED' LIMIT 1")
    suspend fun getActiveOrderForTable(tableId: Long): OrderWithItems?

    @Transaction
    @Query("SELECT * FROM orders WHERE status = 'COMPLETED' AND completedAt LIKE :datePrefix || '%' ORDER BY completedAt DESC")
    fun getCompletedOrdersForDate(datePrefix: String): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getAllCompletedOrders(): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    suspend fun getAllCompletedOrdersSync(): List<OrderWithItems>

    @Transaction
    @Query("SELECT * FROM orders WHERE status = 'COMPLETED' AND completedAt >= :startDate ORDER BY completedAt DESC")
    fun getCompletedOrdersSince(startDate: String): Flow<List<OrderWithItems>>

    @Query("SELECT * FROM orders WHERE status != 'COMPLETED'")
    suspend fun getAllActiveOrders(): List<Order>

    @Query("SELECT COUNT(*) FROM orders WHERE completedAt LIKE :datePrefix || '%' AND status = 'COMPLETED'")
    suspend fun getOrderCountForDate(datePrefix: String): Int

    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0.0) FROM orders 
        WHERE status = 'COMPLETED' AND completedAt >= :startDate
    """)
    suspend fun getRevenueSince(startDate: String): Double

    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0.0) FROM orders 
        WHERE status = 'COMPLETED' AND completedAt LIKE :datePrefix || '%'
    """)
    suspend fun getRevenueForDate(datePrefix: String): Double
}

@Dao
interface ShopSpendDao {
    @Query("SELECT * FROM shop_spends ORDER BY createdAt DESC LIMIT 10")
    fun getLastTen(): Flow<List<ShopSpend>>

    @Query("SELECT * FROM shop_spends ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ShopSpend>>

    @Query("SELECT * FROM shop_spends ORDER BY createdAt DESC")
    suspend fun getAllSync(): List<ShopSpend>

    @Query("SELECT * FROM shop_spends WHERE date = :date ORDER BY createdAt DESC")
    fun getByDate(date: String): Flow<List<ShopSpend>>

    @Query("SELECT * FROM shop_spends WHERE date >= :startDate ORDER BY createdAt DESC")
    fun getSince(startDate: String): Flow<List<ShopSpend>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM shop_spends WHERE date = :date")
    suspend fun getTotalForDate(date: String): Double

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM shop_spends WHERE date >= :startDate")
    suspend fun getTotalSince(startDate: String): Double

    @Insert
    suspend fun insert(spend: ShopSpend): Long

    @Update
    suspend fun update(spend: ShopSpend)

    @Delete
    suspend fun delete(spend: ShopSpend)
}

@Dao
interface AppSettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: AppSettings)

    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<AppSettings>
}

@Dao
interface ReceiptFieldDao {
    @Query("SELECT * FROM receipt_fields ORDER BY sortOrder ASC")
    fun getAll(): Flow<List<ReceiptField>>

    @Query("SELECT * FROM receipt_fields WHERE isEnabled = 1 ORDER BY sortOrder ASC")
    suspend fun getEnabled(): List<ReceiptField>

    @Insert
    suspend fun insert(field: ReceiptField): Long

    @Update
    suspend fun update(field: ReceiptField)

    @Delete
    suspend fun delete(field: ReceiptField)
}

// ─────────────────────────────────────────────────────────────────────────────
// DATABASE
// ─────────────────────────────────────────────────────────────────────────────

@Database(
    entities = [
        Category::class,
        MenuItem::class,
        TableInfo::class,
        Order::class,
        OrderItem::class,
        ShopSpend::class,
        AppSettings::class,
        ReceiptField::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MakuluDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun menuItemDao(): MenuItemDao
    abstract fun tableDao(): TableDao
    abstract fun orderDao(): OrderDao
    abstract fun shopSpendDao(): ShopSpendDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun receiptFieldDao(): ReceiptFieldDao

    companion object {
        @Volatile
        private var INSTANCE: MakuluDatabase? = null

        fun getInstance(context: Context): MakuluDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MakuluDatabase::class.java,
                    "makulu_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
