package com.example.ui.modelselector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AiProvider
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ModelCategory
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    catalog: List<AiModelDefinition>,
    selectedModelId: String,
    searchQuery: String,
    selectedCategory: ModelCategory?,
    isRefreshing: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (ModelCategory?) -> Unit,
    onRefreshCatalog: () -> Unit,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("model_selector_sheet")
    ) {
        ModelSelectorContent(
            catalog = catalog,
            selectedModelId = selectedModelId,
            searchQuery = searchQuery,
            selectedCategory = selectedCategory,
            isRefreshing = isRefreshing,
            onSearchQueryChanged = onSearchQueryChanged,
            onCategorySelected = onCategorySelected,
            onRefreshCatalog = onRefreshCatalog,
            onModelSelected = onModelSelected,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSelectorContent(
    catalog: List<AiModelDefinition>,
    selectedModelId: String,
    searchQuery: String,
    selectedCategory: ModelCategory?,
    isRefreshing: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (ModelCategory?) -> Unit,
    onRefreshCatalog: () -> Unit,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredModels = remember(catalog, searchQuery, selectedCategory) {
        catalog.filter { model ->
            val categoryMatch = when (selectedCategory) {
                null -> true
                ModelCategory.RECOMMENDED -> model.isRecommended
                else -> model.category == selectedCategory
            }
            val q = searchQuery.trim().lowercase()
            val queryMatch = q.isEmpty() ||
                model.displayName.lowercase().contains(q) ||
                model.providerModelId.lowercase().contains(q) ||
                model.provider.displayName.lowercase().contains(q) ||
                model.description.lowercase().contains(q) ||
                model.capabilities.any { it.label.lowercase().contains(q) }
            categoryMatch && queryMatch
        }
    }

    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Smarter AI Model Catalog",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${filteredModels.size} of ${catalog.size} models available • Chat, Coding & Vision",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefreshCatalog,
                enabled = !isRefreshing,
                modifier = Modifier.testTag("refresh_model_catalog_button")
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh live model availability"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text("Search models by name or capability…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_search_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) },
                label = { Text("All (${catalog.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("category_chip_ALL")
            )
            ModelCategory.entries.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = {
                        onCategorySelected(if (selectedCategory == category) null else category)
                    },
                    label = { Text(category.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("category_chip_${category.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // Models List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("model_catalog_list"),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredModels.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("empty_model_catalog_notice")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "No chat models configured",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "All previous chat AI providers and models have been removed. Configure a chat provider to populate available models.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(
                items = filteredModels,
                key = { it.providerModelId }
            ) { model ->
                val isSelected = model.providerModelId == selectedModelId
                val isAvailable = model.availabilityStatus != AvailabilityStatus.UNAVAILABLE

                Surface(
                    onClick = { onModelSelected(model.providerModelId) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isAvailable) 0.65f else 0.3f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_item_${model.providerModelId}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = com.example.domain.models.AiModelCatalog.formatUserFacingModelName(model.displayName),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (model.isRecommended) {
                                    Icon(
                                        imageVector = Icons.Outlined.Star,
                                        contentDescription = "Recommended",
                                        tint = StatusWarningAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected model",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Clean Subtitle (Never expose raw 'free/' internal prefix in user-facing UI)
                        Text(
                            text = "${model.provider.displayName} • ${model.category.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Description
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )

                        // Metadata & Capability Chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Availability badge
                            val statusColor = when (model.availabilityStatus) {
                                AvailabilityStatus.AVAILABLE -> StatusAvailableGreen
                                AvailabilityStatus.UNVERIFIED -> StatusWarningAmber
                                AvailabilityStatus.UNAVAILABLE -> StatusErrorRed
                            }
                            MetadataBadge(
                                text = model.availabilityStatus.label,
                                dotColor = statusColor
                            )

                            // Category badge
                            MetadataBadge(text = model.category.displayName)

                            // Context window badge
                            model.contextWindowTokens?.let { tokens ->
                                val label = if (tokens >= 1_000_000) "${tokens / 1_000_000}M ctx" else "${tokens / 1000}K ctx"
                                MetadataBadge(text = label)
                            }

                            // Capabilities
                            model.capabilities.forEach { cap ->
                                MetadataBadge(text = cap.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataBadge(
    text: String,
    dotColor: androidx.compose.ui.graphics.Color? = null
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (dotColor != null) {
                Surface(
                    shape = CircleShape,
                    color = dotColor,
                    modifier = Modifier.size(6.dp)
                ) {}
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
