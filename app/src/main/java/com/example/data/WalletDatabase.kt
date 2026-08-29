package com.example.data

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
  version = 3,
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
            populateInitialData(database.walletDao())
          }
        }
      }

      suspend fun populateInitialData(dao: WalletDao) {
        if (dao.getWalletCount() == 0) {
          val w1 = AgentWalletEntity(
            id = "wallet_research_01",
            name = "Research Agent",
            organization = "AI Corp Enterprises",
            usdcBalance = 125.50,
            monthlyLimit = 100.0,
            monthlySpent = 12.40,
            perRequestLimit = 1.0,
            allowedVendors = "You.com,OpenAI,Perplexity,Semantic Scholar",
            allowedChains = "Tempo,Base",
            requireApproval = false,
            didIdentity = "did:agent:enterprise:research-01"
          )
          val w2 = AgentWalletEntity(
            id = "wallet_trading_02",
            name = "Trading Research",
            organization = "AI Corp Enterprises",
            usdcBalance = 840.00,
            monthlyLimit = 500.0,
            monthlySpent = 145.20,
            perRequestLimit = 25.0,
            allowedVendors = "Bloomberg Terminal API,Polygon.io,Financial APIs,AlphaVantage",
            allowedChains = "Base,Solana,Tempo",
            requireApproval = true,
            didIdentity = "did:agent:enterprise:trading-02"
          )
          val w3 = AgentWalletEntity(
            id = "wallet_ops_03",
            name = "Operations",
            organization = "AI Corp Enterprises",
            usdcBalance = 42.10,
            monthlyLimit = 50.0,
            monthlySpent = 7.90,
            perRequestLimit = 5.0,
            allowedVendors = "GitHub,AWS Cloud,SaaS Vendors,Vercel,Slack",
            allowedChains = "Base,Solana",
            requireApproval = false,
            didIdentity = "did:agent:enterprise:ops-03"
          )

          dao.insertWallet(w1)
          dao.insertWallet(w2)
          dao.insertWallet(w3)

          val now = System.currentTimeMillis()
          dao.insertTransaction(
            LedgerTransactionEntity(
              id = "tx_" + UUID.randomUUID().toString().substring(0, 8),
              timestamp = now - 3600000 * 2,
              walletId = "wallet_research_01",
              agentName = "Research Agent",
              vendorName = "You.com",
              amountUsdc = 0.01,
              debitAccountId = "asset:usdc:wallet_research_01",
              creditAccountId = "liability:vendor:you.com",
              protocol = "MPP",
              chain = "Tempo",
              status = "APPROVED",
              receiptHash = "0x8f9b2e1a4c6d7e8f...90ab",
              challengeDetails = "HTTP 402 Challenge -> MPP Credential -> Receipt Verified"
            )
          )
          dao.insertTransaction(
            LedgerTransactionEntity(
              id = "tx_" + UUID.randomUUID().toString().substring(0, 8),
              timestamp = now - 3600000 * 5,
              walletId = "wallet_trading_02",
              agentName = "Trading Research",
              vendorName = "Polygon.io",
              amountUsdc = 12.50,
              debitAccountId = "asset:usdc:wallet_trading_02",
              creditAccountId = "liability:vendor:polygon.io",
              protocol = "x402",
              chain = "Base",
              status = "APPROVED",
              receiptHash = "0x3c4d5e6f7a8b9c0d...12ef",
              challengeDetails = "x402 Payment Required -> Base USDC Transfer -> Cryptographic Audit Record"
            )
          )

          dao.insertAuditLog(
            AuditLogEntity(
              id = "audit_" + UUID.randomUUID().toString().substring(0, 8),
              timestamp = now - 3600000 * 2,
              eventType = "PAYMENT_AUTH",
              description = "Successfully authorized MPP payment of $0.01 to You.com for Research Agent",
              actor = "did:agent:enterprise:research-01",
              cryptographicHash = "SHA256:a1b2c3d4e5f6..."
            )
          )

          dao.insertAtwAsset(
            AtwAssetEntity(
              id = "asset_root_01",
              name = "Samsung ATW Workshop Root CA",
              assetType = "SELF_SIGNED_CERT",
              keySize = 4096,
              encrypted = false,
              commonName = "Samsung ATW Testing Root CA",
              organization = "Samsung Electronics",
              organizationalUnit = "ATW Testing Workshop",
              country = "KR",
              state = "Gyeonggi-do",
              locality = "Suwon",
              email = "atw-support@samsung.com",
              validityDays = 365,
              pemData = "-----BEGIN CERTIFICATE-----\nMIIDXTCCAkWgAwIBAgIJAL9T6v...SamsungATWRootCA...\n-----END CERTIFICATE-----",
              createdAt = now - 86400000 * 10
            )
          )
        }
      }
    }
  }
}
