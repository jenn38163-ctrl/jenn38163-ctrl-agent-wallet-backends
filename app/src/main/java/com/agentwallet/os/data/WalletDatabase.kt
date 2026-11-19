package com.agentwallet.os.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
  entities = [AgentWalletEntity::class, LedgerTransactionEntity::class, AuditLogEntity::class, AtwAssetEntity::class],
  version = 4,
  exportSchema = false
)
abstract class WalletDatabase : RoomDatabase() {
  abstract fun walletDao(): WalletDao

  companion object {
    @Volatile
    private var INSTANCE: WalletDatabase? = null

    fun getDatabase(context: Context): WalletDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          WalletDatabase::class.java,
          "agent_wallet_os.db"
        )
          .fallbackToDestructiveMigration()
          .addCallback(WalletDatabaseCallback())
          .build()
        INSTANCE = instance
        instance
      }
    }

    private class WalletDatabaseCallback : RoomDatabase.Callback() {
      override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        INSTANCE?.let { database ->
          CoroutineScope(Dispatchers.IO).launch {
            // Production Blocked: Wallet creation and initialization 
            // should happen strictly through the backend control plane.
            // No initial demonstration data will be populated here.
          }
        }
      }
    }
  }
}
