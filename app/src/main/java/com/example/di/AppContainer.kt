package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.auth.SupabaseAuthRepository
import com.example.data.chat.ChatRepositoryImpl
import com.example.data.livedata.LiveDataIntelligenceService
import com.example.data.local.ChatCacheDao
import com.example.data.local.NexusAiLocalDatabase
import com.example.data.models.ModelCatalogRepositoryImpl
import com.example.data.network.SupabaseAndGatewayClient
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ChatRepository
import com.example.domain.repositories.ModelCatalogRepository
import com.example.domain.repositories.PreferencesRepository

interface AppContainer {
    val database: NexusAiLocalDatabase
    val chatCacheDao: ChatCacheDao
    val networkClient: SupabaseAndGatewayClient
    val authRepository: AuthRepository
    val modelCatalogRepository: ModelCatalogRepository
    val chatRepository: ChatRepository
    val preferencesRepository: PreferencesRepository
    val liveDataService: LiveDataIntelligenceService
}

class DefaultAppContainer(
    context: Context,
    inMemoryDb: Boolean = false
) : AppContainer {

    override val database: NexusAiLocalDatabase by lazy {
        if (inMemoryDb) {
            Room.inMemoryDatabaseBuilder(context.applicationContext, NexusAiLocalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        } else {
            Room.databaseBuilder(
                context.applicationContext,
                NexusAiLocalDatabase::class.java,
                "nexus_ai_cache.db"
            )
                .addMigrations(NexusAiLocalDatabase.MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }

    override val chatCacheDao: ChatCacheDao by lazy {
        database.chatCacheDao()
    }

    override val networkClient: SupabaseAndGatewayClient by lazy {
        if (inMemoryDb) {
            SupabaseAndGatewayClient(
                supabaseUrlProvider = { "https://your-project-ref.supabase.co" },
                supabaseAnonKeyProvider = { "your-supabase-publishable-anon-key" },
                aiBackendBaseUrlProvider = { "" }
            )
        } else {
            SupabaseAndGatewayClient()
        }
    }

    override val authRepository: AuthRepository by lazy {
        SupabaseAuthRepository(
            context = context.applicationContext,
            networkClient = networkClient,
            chatCacheDao = chatCacheDao
        )
    }

    override val modelCatalogRepository: ModelCatalogRepository by lazy {
        ModelCatalogRepositoryImpl(
            networkClient = networkClient,
            authRepository = authRepository
        )
    }

    override val chatRepository: ChatRepository by lazy {
        ChatRepositoryImpl(
            chatCacheDao = chatCacheDao,
            networkClient = networkClient,
            authRepository = authRepository,
            modelCatalogRepository = modelCatalogRepository
        )
    }

    override val preferencesRepository: PreferencesRepository by lazy {
        UserPreferencesRepository(context.applicationContext)
    }

    override val liveDataService: LiveDataIntelligenceService by lazy {
        LiveDataIntelligenceService()
    }
}
