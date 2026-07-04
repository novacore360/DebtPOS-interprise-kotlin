package com.marnie.pos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.marnie.pos.data.local.dao.*
import com.marnie.pos.data.local.entities.*
import com.marnie.pos.security.CryptoManager
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        ProductEntity::class,
        CustomerEntity::class,
        PurchaseEntity::class,
        PurchaseItemEntity::class,
        PaymentEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun customerDao(): CustomerDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun paymentDao(): PaymentDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        fun build(context: Context, cryptoManager: CryptoManager): AppDatabase {
            val passphrase = cryptoManager.getOrCreateDbPassphrase()
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, "marnie_pos.db")
                .openHelperFactory(factory) // SQLCipher: the .db file on disk is AES-256 encrypted
                .fallbackToDestructiveMigration() // acceptable for local cache; source of truth is Neon
                .build()
        }
    }
}
