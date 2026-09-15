package com.bilto.gochmott.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.bilto.gochmott.repository.AppInfoRepository
import com.bilto.gochmott.repository.ReviewPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Оценка в Google Play и письмо разработчику. */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val appInfo: AppInfoRepository,
    private val reviewPrompt: ReviewPrompt
) : ViewModel() {

    fun rateApp() = appInfo.openStorePage()

    fun sendFeedback() = appInfo.sendFeedbackEmail()

    fun onEntryOpened() = reviewPrompt.onEntryOpened()

    suspend fun askForReviewIfDue(activity: Activity) = reviewPrompt.askIfDue(activity)
}
