package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.data.livedata.LiveDataIntelligenceService
import com.example.domain.models.ChartDataPoint
import com.example.domain.models.LiveDataFreshness
import com.example.domain.models.LiveMarketDataPayload
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber

@Composable
fun LiveMarketChartCard(
    payload: LiveMarketDataPayload,
    onRefreshRequested: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val freshnessColor = when (payload.freshness) {
        LiveDataFreshness.LIVE -> StatusAvailableGreen
        LiveDataFreshness.REFERENCE_DAILY -> MaterialTheme.colorScheme.primary
        LiveDataFreshness.DELAYED, LiveDataFreshness.CACHED -> StatusWarningAmber
        LiveDataFreshness.UNAVAILABLE -> StatusErrorRed
    }

    val isPositiveChange = (payload.change24hPercent ?: 0.0) >= 0.0
    val changeColor = if (isPositiveChange) StatusAvailableGreen else StatusErrorRed

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_market_chart_card_${payload.symbolOrPair}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header Row: Title, Pair & Live/Delayed Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = payload.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${payload.symbolOrPair} • ${payload.currencyOrUnit.ifBlank { payload.category.displayTitle }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = freshnessColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, freshnessColor.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("live_data_status_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = freshnessColor,
                                modifier = Modifier.size(6.dp)
                            ) {}
                            Text(
                                text = payload.freshness.badgeLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = freshnessColor
                            )
                        }
                    }

                    if (onRefreshRequested != null) {
                        IconButton(
                            onClick = onRefreshRequested,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("live_market_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh live market data",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 2. Primary Value & 24H Change Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = payload.formattedPrimaryValue,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = if (payload.freshness == LiveDataFreshness.UNAVAILABLE) {
                        StatusErrorRed
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag("live_market_primary_value")
                )

                payload.change24hPercent?.let { pct ->
                    val sign = if (pct >= 0.0) "+" else ""
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = changeColor.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (pct >= 0.0) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                                contentDescription = null,
                                tint = changeColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "$sign${LiveDataIntelligenceService.formatDecimal(pct, 2)}%",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = changeColor
                            )
                        }
                    }
                }
            }

            // 3. Dedicated Precious Metals Breakdown (24K / 22K, per 1g / 10g / troy oz)
            payload.preciousMetalBreakdown?.let { metal ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("precious_metal_breakdown_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${metal.metalName} Spot Purity & Weight Breakdown",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Intl. Spot (Troy Oz):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = LiveDataIntelligenceService.formatCurrencyValue(metal.spotPricePerTroyOunceUsd, "USD"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (metal.price24KPerGramLocal != null && metal.price24KPer10GramsLocal != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "24K (99.9%) • 1g / 10g:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${LiveDataIntelligenceService.formatCurrencyValue(metal.price24KPerGramLocal, metal.localCurrencyCode)} / ${LiveDataIntelligenceService.formatCurrencyValue(metal.price24KPer10GramsLocal, metal.localCurrencyCode)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (metal.metalName.equals("Gold", ignoreCase = true) &&
                            metal.price22KPerGramLocal != null && metal.price22KPer10GramsLocal != null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "22K (91.6%) • 1g / 10g:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${LiveDataIntelligenceService.formatCurrencyValue(metal.price22KPerGramLocal, metal.localCurrencyCode)} / ${LiveDataIntelligenceService.formatCurrencyValue(metal.price22KPer10GramsLocal, metal.localCurrencyCode)}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Text(
                            text = metal.marketTypeNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Dedicated Currency Conversion Detail Card (PART 11)
            payload.currencyConversion?.let { conv ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("currency_conversion_detail_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Rate Used: 1 ${conv.fromCurrency} = ${LiveDataIntelligenceService.formatDecimal(conv.exchangeRate, 4)} ${conv.toCurrency}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = conv.spreadDisclaimer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 5. Historical Time-Series Chart (ONLY when >= 2 verified points exist; NEVER fabricated)
            if (payload.historicalPoints.size >= 2) {
                InteractiveTimeSeriesCanvas(
                    points = payload.historicalPoints,
                    currencyOrUnit = payload.currencyOrUnit,
                    timeframeLabel = payload.timeframeLabel,
                    lineColor = if (isPositiveChange) StatusAvailableGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_market_timeseries_canvas")
                )
            } else if (!payload.historicalUnavailableReason.isNullOrBlank() && payload.sourceCitations.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_market_no_chart_notice")
                ) {
                    Text(
                        text = payload.historicalUnavailableReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // 5b. Verified Live Research & News Citations (when present)
            if (payload.sourceCitations.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_research_citations_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Verified Sources & Live Citations",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        payload.sourceCitations.take(6).forEachIndexed { idx, citation ->
                            val hasValidLink = citation.url.startsWith("https://") || citation.url.startsWith("http://")
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (hasValidLink) {
                                            Modifier.clickable {
                                                runCatching { uriHandler.openUri(citation.url) }
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(vertical = 2.dp)
                                    .testTag("live_citation_item_$idx"),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}. ${citation.title} — ${citation.domain}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        textDecoration = if (hasValidLink) TextDecoration.Underline else TextDecoration.None
                                    ),
                                    color = if (hasValidLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                val checkedTs = citation.retrievedAtLabel.ifBlank { payload.formattedTimestamp }
                                val metaLine = buildString {
                                    append(citation.sourceName)
                                    if (citation.publishedAtLabel.isNotBlank()) {
                                        append(" • Published: ${citation.publishedAtLabel}")
                                    }
                                    append(" • Last checked: $checkedTs")
                                }
                                Text(
                                    text = metaLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

            // 6. Provider Attribution, Observation Date & Last Checked Timestamp Footer
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Source: ${payload.providerName}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_market_source_attribution")
                    )
                    if (payload.observationDate.isNotBlank()) {
                        Text(
                            text = "Observation date: ${payload.observationDate}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("live_market_observation_date")
                        )
                    }
                }
                Text(
                    text = "Last checked: ${payload.formattedTimestamp}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("live_market_timestamp")
                )
            }
        }
    }
}

@Composable
private fun InteractiveTimeSeriesCanvas(
    points: List<ChartDataPoint>,
    currencyOrUnit: String,
    timeframeLabel: String,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    val minVal = remember(points) { points.minOf { it.value } }
    val maxVal = remember(points) { points.maxOf { it.value } }
    val midVal = remember(minVal, maxVal) { (minVal + maxVal) / 2.0 }
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    val selectedPoint = selectedIndex?.let { idx -> points.getOrNull(idx) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Price Movement ($timeframeLabel)",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selectedPoint != null) {
                Text(
                    text = "${selectedPoint.label}: ${LiveDataIntelligenceService.formatDecimal(selectedPoint.value, 2)} $currencyOrUnit",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = "Range: ${LiveDataIntelligenceService.formatDecimal(minVal, 2)} – ${LiveDataIntelligenceService.formatDecimal(maxVal, 2)} $currencyOrUnit",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Labeled Y-Axis Column
            Column(
                modifier = Modifier
                    .height(148.dp)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = LiveDataIntelligenceService.formatDecimal(maxVal, 2),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = LiveDataIntelligenceService.formatDecimal(midVal, 2),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = LiveDataIntelligenceService.formatDecimal(minVal, 2),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chart Canvas
            val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectTapGestures { tapOffset ->
                            if (size.width > 0 && points.size > 1) {
                                val fraction = (tapOffset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                val idx = (fraction * (points.size - 1)).toInt().coerceIn(0, points.lastIndex)
                                selectedIndex = idx
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f || points.size < 2) return@Canvas

                    // Horizontal reference lines
                    listOf(0.08f, 0.5f, 0.92f).forEach { yRatio ->
                        val y = h * yRatio
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    val span = (maxVal - minVal).takeIf { it > 1e-9 } ?: 1.0
                    val topPad = h * 0.08f
                    val usableH = h * 0.84f

                    fun pointOffset(index: Int): Offset {
                        val x = (index.toFloat() / (points.size - 1).toFloat()) * w
                        val normalized = ((points[index].value - minVal) / span).toFloat().coerceIn(0f, 1f)
                        val y = topPad + usableH * (1f - normalized)
                        return Offset(x, y)
                    }

                    val strokePath = Path()
                    val fillPath = Path()

                    val firstPt = pointOffset(0)
                    strokePath.moveTo(firstPt.x, firstPt.y)
                    fillPath.moveTo(firstPt.x, h)
                    fillPath.lineTo(firstPt.x, firstPt.y)

                    for (i in 1 until points.size) {
                        val pt = pointOffset(i)
                        strokePath.lineTo(pt.x, pt.y)
                        fillPath.lineTo(pt.x, pt.y)
                    }

                    val lastPt = pointOffset(points.lastIndex)
                    fillPath.lineTo(lastPt.x, h)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                lineColor.copy(alpha = 0.28f),
                                lineColor.copy(alpha = 0.02f)
                            ),
                            startY = 0f,
                            endY = h
                        )
                    )

                    drawPath(
                        path = strokePath,
                        color = lineColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Draw endpoint or selected point marker
                    val activeIdx = selectedIndex ?: points.lastIndex
                    val markerPt = pointOffset(activeIdx)
                    drawCircle(
                        color = lineColor.copy(alpha = 0.25f),
                        radius = 7.dp.toPx(),
                        center = markerPt
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 3.5.dp.toPx(),
                        center = markerPt
                    )
                }
            }
        }

        // Labeled X-Axis Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val firstLabel = points.first().label
            val midLabel = points[points.size / 2].label
            val lastLabel = points.last().label
            Text(
                text = firstLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = midLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = lastLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
