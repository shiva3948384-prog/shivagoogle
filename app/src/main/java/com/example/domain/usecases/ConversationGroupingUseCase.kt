package com.example.domain.usecases

import com.example.domain.models.Conversation
import com.example.domain.models.ConversationTimeGroup
import java.util.Calendar
import java.util.TimeZone

object ConversationGroupingUseCase {

    /**
     * Groups conversations into Today, Yesterday, Previous 7 Days, and Older
     * using the user's actual local timezone.
     */
    fun groupByLocalTime(
        conversations: List<Conversation>,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Map<ConversationTimeGroup, List<Conversation>> {
        if (conversations.isEmpty()) return emptyMap()

        val startOfTodayCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowEpochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfTodayMs = startOfTodayCal.timeInMillis

        val startOfYesterdayCal = (startOfTodayCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val startOfYesterdayMs = startOfYesterdayCal.timeInMillis

        val startOfPrev7DaysCal = (startOfTodayCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }
        val startOfPrev7DaysMs = startOfPrev7DaysCal.timeInMillis

        val sorted = conversations.sortedByDescending { it.updatedAtEpochMs }
        val result = linkedMapOf<ConversationTimeGroup, MutableList<Conversation>>()

        for (conv in sorted) {
            val group = when {
                conv.updatedAtEpochMs >= startOfTodayMs -> ConversationTimeGroup.TODAY
                conv.updatedAtEpochMs >= startOfYesterdayMs -> ConversationTimeGroup.YESTERDAY
                conv.updatedAtEpochMs >= startOfPrev7DaysMs -> ConversationTimeGroup.PREVIOUS_7_DAYS
                else -> ConversationTimeGroup.OLDER
            }
            result.getOrPut(group) { mutableListOf() }.add(conv)
        }

        return result
    }

    fun deriveTitleFromFirstPrompt(prompt: String): String {
        val cleaned = prompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("#")
            .trim()
        if (cleaned.isEmpty()) return "New Conversation"
        return if (cleaned.length <= 44) cleaned else cleaned.take(42).trimEnd() + "…"
    }
}
