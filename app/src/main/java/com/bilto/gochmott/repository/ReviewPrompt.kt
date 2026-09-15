package com.bilto.gochmott.repository

import android.app.Activity
import android.content.Context
import com.bilto.gochmott.settingsrepo.SettingKeys
import com.bilto.gochmott.settingsrepo.SettingsRepository
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Когда просить оценку в Google Play.
 *
 * Спрашиваем того, кто словарём уже пользуется: открыл достаточно статей и не
 * в первые дни. Окно показывает сам Google Play и сам же ограничивает частоту,
 * но на его лимиты не полагаемся — повторяем не чаще раза в [REASK_AFTER_MS].
 * Пользователь, который хочет оценить сам, найдёт пункт в боковом меню.
 */
@Singleton
class ReviewPrompt @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext context: Context
) {
    private val manager = ReviewManagerFactory.create(context)

    fun onEntryOpened() {
        settings.launch {
            val count = settings.getOrNull(SettingKeys.entriesOpened) ?: 0
            settings.set(SettingKeys.entriesOpened, count + 1).join()
            if ((settings.getOrNull(SettingKeys.firstEntryOpenedAt) ?: 0L) == 0L) {
                settings.set(SettingKeys.firstEntryOpenedAt, System.currentTimeMillis())
            }
        }
    }

    /** Показывает окно оценки, если пора. Вне Google Play тихо ничего не делает. */
    suspend fun askIfDue(activity: Activity) {
        val now = System.currentTimeMillis()
        val due = isDue(
            entriesOpened = settings.getOrNull(SettingKeys.entriesOpened) ?: 0,
            firstEntryOpenedAt = settings.getOrNull(SettingKeys.firstEntryOpenedAt) ?: 0L,
            askedAt = settings.getOrNull(SettingKeys.reviewAskedAt) ?: 0L,
            now = now
        )
        if (!due) return

        try {
            val info = manager.requestReview()
            settings.set(SettingKeys.reviewAskedAt, now)
            manager.launchReview(activity, info)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Нет Google Play или нет связи: спросим в другой раз.
        }
    }

    companion object {
        const val MIN_ENTRIES = 15
        const val MIN_USAGE_MS = 3L * 24 * 60 * 60 * 1000
        const val REASK_AFTER_MS = 120L * 24 * 60 * 60 * 1000

        fun isDue(entriesOpened: Int, firstEntryOpenedAt: Long, askedAt: Long, now: Long): Boolean =
            entriesOpened >= MIN_ENTRIES &&
                firstEntryOpenedAt > 0L && now - firstEntryOpenedAt >= MIN_USAGE_MS &&
                (askedAt == 0L || now - askedAt >= REASK_AFTER_MS)
    }
}
