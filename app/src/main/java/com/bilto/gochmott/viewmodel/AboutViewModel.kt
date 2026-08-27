package com.bilto.gochmott.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilto.gochmott.BuildConfig
import com.bilto.gochmott.repository.AppInfo
import com.bilto.gochmott.repository.AppInfoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import javax.inject.Inject


data class AboutState(
    val appVersion: String,
    val buildNumber: String,
    val dictVersion: Int,
    val feedbackEmail: String,
)

sealed class AboutIntent {
    object SendFeedbackEmail : AboutIntent()
}

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val infoRepository: AppInfoRepository,
) : ViewModel() {

    val state: StateFlow<AboutState> = infoRepository.info.map {
        it.asDomain
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, infoRepository.info.value.asDomain)

    fun onIntent(intent: AboutIntent) {
        when (intent) {
            is AboutIntent.SendFeedbackEmail -> infoRepository.sendFeedbackEmail()
        }
    }

    private val AppInfo.asDomain: AboutState
        get() = AboutState(
            appVersion = appVersion,
            buildNumber = buildNumber,
            dictVersion = dictVersion,
            feedbackEmail = feedbackEmail
        )
}
