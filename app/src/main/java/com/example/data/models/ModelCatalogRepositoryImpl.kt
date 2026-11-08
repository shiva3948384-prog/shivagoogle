package com.example.data.models

import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiModelDefinition
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ModelCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ModelCatalogRepositoryImpl(
    private val networkClient: SupabaseAndGatewayClient,
    private val authRepository: AuthRepository
) : ModelCatalogRepository {

    private val _catalogFlow = MutableStateFlow(AiModelCatalog.INITIAL_DISCOVERY_MODELS)
    override val catalogFlow: StateFlow<List<AiModelDefinition>> = _catalogFlow.asStateFlow()

    private val _isRefreshingFlow = MutableStateFlow(false)
    override val isRefreshingFlow: StateFlow<Boolean> = _isRefreshingFlow.asStateFlow()

    private val _lastVerifiedEpochMsFlow = MutableStateFlow<Long?>(null)
    override val lastVerifiedEpochMsFlow: StateFlow<Long?> = _lastVerifiedEpochMsFlow.asStateFlow()

    companion object {
        private const val CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    override suspend fun refreshModelCatalog(): Result<List<AiModelDefinition>> {
        val session = authRepository.sessionFlow.value
        if (session == null) {
            return Result.success(_catalogFlow.value)
        }
        if (_isRefreshingFlow.value) {
            return Result.success(_catalogFlow.value)
        }
        val lastVerified = _lastVerifiedEpochMsFlow.value
        val now = System.currentTimeMillis()
        if (lastVerified != null && (now - lastVerified) < CATALOG_CACHE_TTL_MS && _catalogFlow.value.isNotEmpty()) {
            return Result.success(_catalogFlow.value)
        }

        _isRefreshingFlow.value = true
        return try {
            val liveModelIds = networkClient.fetchLiveProviderModelIds(session.accessToken)
            val merged = if (networkClient.isLiveCloudConfigured && liveModelIds.isNotEmpty()) {
                AiModelCatalog.mergeWithLiveProviderModels(
                    baseCatalog = AiModelCatalog.INITIAL_DISCOVERY_MODELS,
                    liveModelIds = liveModelIds
                )
            } else {
                val allKnown = (AiModelCatalog.INITIAL_DISCOVERY_MODELS.map { it.providerModelId } + liveModelIds).toSet()
                AiModelCatalog.mergeWithLiveProviderModels(
                    baseCatalog = AiModelCatalog.INITIAL_DISCOVERY_MODELS,
                    liveModelIds = allKnown
                )
            }
            _catalogFlow.value = merged
            _lastVerifiedEpochMsFlow.value = System.currentTimeMillis()
            Result.success(merged)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isRefreshingFlow.value = false
        }
    }

    override fun getModelById(providerModelId: String): AiModelDefinition {
        return AiModelCatalog.findById(providerModelId, _catalogFlow.value)
    }
}
