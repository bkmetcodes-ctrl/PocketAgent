package com.bekacodes.pocketagent.data

import android.content.Context
import androidx.compose.runtime.Immutable
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

/**
 * Immutable entity representing a single financial transaction record in SQLite.
 * Marked [@Immutable] for Jetpack Compose stability and skipped recompositions.
 */
@Immutable
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,
    val merchant: String,
    val dateLabel: String,
    val addedByAgent: Boolean = false
)

/**
 * Data Access Object (DAO) for local expense transactions.
 * All queries are executed on Room's background I/O dispatcher.
 */
@Dao
interface ExpenseDao {
    /**
     * Observes all expenses in descending order of ID as a reactive cold Flow.
     */
    @Query("SELECT * FROM expenses ORDER BY id DESC")
    fun observeAllExpenses(): Flow<List<ExpenseEntity>>

    /**
     * Queries expenses matching a specific category (case-insensitive).
     */
    @Query("SELECT * FROM expenses WHERE LOWER(category) = LOWER(:category) ORDER BY id DESC")
    suspend fun getExpensesByCategory(category: String): List<ExpenseEntity>

    /**
     * Retrieves a point-in-time snapshot of all recorded expenses.
     */
    @Query("SELECT * FROM expenses ORDER BY id DESC")
    suspend fun getAllExpensesSnapshot(): List<ExpenseEntity>

    /**
     * Inserts a new expense entity, returning the generated row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    /**
     * Deletes the most recently added expense row from the database.
     * @return Number of deleted rows (1 if deleted, 0 if empty).
     */
    @Query("DELETE FROM expenses WHERE id = (SELECT MAX(id) FROM expenses)")
    suspend fun deleteMostRecentExpense(): Int

    /**
     * Clears all entries from the expenses table.
     * @return Number of deleted rows.
     */
    @Query("DELETE FROM expenses")
    suspend fun clearAll(): Int

    /**
     * Returns total count of expense records in SQLite.
     */
    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int
}

/**
 * Main Room SQLite database instance for PocketAgent.
 * Uses thread-safe double-checked locking singleton pattern.
 */
@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = false)
abstract class PocketDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: PocketDatabase? = null

        /**
         * Returns the thread-safe singleton database instance.
         */
        fun getInstance(context: Context): PocketDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketDatabase::class.java,
                    "pocket_agent.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
