package com.bekacodes.pocketagent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,
    val merchant: String,
    val dateLabel: String,
    val addedByAgent: Boolean = false
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY id DESC")
    fun observeAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE LOWER(category) = LOWER(:category) ORDER BY id DESC")
    suspend fun getExpensesByCategory(category: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY id DESC")
    suspend fun getAllExpensesSnapshot(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("DELETE FROM expenses WHERE id = (SELECT MAX(id) FROM expenses)")
    suspend fun deleteMostRecentExpense(): Int

    @Query("DELETE FROM expenses")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int
}

@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = false)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: PocketDatabase? = null

        fun getInstance(context: Context): PocketDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PocketDatabase::class.java,
                    "pocket_agent.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/**
 * Seeds the database with demo rows so "How much did I spend on coffee?"
 * computes $42.50 across 9 rows right out of the box.
 */
suspend fun ExpenseDao.seedDemoDataIfEmpty() {
    if (count() == 0) {
        ResetDemoLedger(this)
    }
}

suspend fun ResetDemoLedger(dao: ExpenseDao) {
    dao.clearAll()
    val seedRows = listOf(
        ExpenseEntity(
            amount = 4.50,
            category = "Coffee",
            merchant = "Blue Bottle",
            dateLabel = "Oct 2"
        ),
        ExpenseEntity(
            amount = 5.00,
            category = "Coffee",
            merchant = "Intelligentsia",
            dateLabel = "Oct 4"
        ),
        ExpenseEntity(
            amount = 4.75,
            category = "Coffee",
            merchant = "Sightglass",
            dateLabel = "Oct 6"
        ),
        ExpenseEntity(
            amount = 4.25,
            category = "Coffee",
            merchant = "Philz Coffee",
            dateLabel = "Oct 8"
        ),
        ExpenseEntity(
            amount = 5.25,
            category = "Coffee",
            merchant = "Verve Coffee",
            dateLabel = "Oct 10"
        ),
        ExpenseEntity(
            amount = 4.50,
            category = "Coffee",
            merchant = "Stumptown",
            dateLabel = "Oct 12"
        ),
        ExpenseEntity(
            amount = 4.75,
            category = "Coffee",
            merchant = "Blue Bottle",
            dateLabel = "Oct 14"
        ),
        ExpenseEntity(
            amount = 5.00,
            category = "Coffee",
            merchant = "Ritual Roasters",
            dateLabel = "Oct 16"
        ),
        ExpenseEntity(
            amount = 4.50,
            category = "Coffee",
            merchant = "Four Barrel",
            dateLabel = "Oct 17"
        ),
        ExpenseEntity(
            amount = 64.20,
            category = "Groceries",
            merchant = "Whole Foods",
            dateLabel = "Oct 15"
        ),
        ExpenseEntity(
            amount = 18.00,
            category = "Transport",
            merchant = "Uber",
            dateLabel = "Oct 16"
        )
    )
    seedRows.forEach { dao.insertExpense(it) }
}