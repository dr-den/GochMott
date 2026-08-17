package com.vaynah.gochmott.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaynah.gochmott.repository.AppInfo
import com.vaynah.gochmott.repository.AppInfoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import javax.inject.Inject


data class PrivacyState(
    val feedbackEmail: String,
)

sealed class PrivacyIntent {
    object SendFeedbackEmail : PrivacyIntent()
}

@HiltViewModel
class PrivacyPolicyViewModel @Inject constructor(
    private val infoRepository: AppInfoRepository,
) : ViewModel() {

    val state: StateFlow<PrivacyState> = infoRepository.info.map {
        it.asDomain
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, infoRepository.info.value.asDomain)

    fun onIntent(intent: PrivacyIntent) {
        when (intent) {
            is PrivacyIntent.SendFeedbackEmail -> infoRepository.sendFeedbackEmail()
        }
    }

    private val AppInfo.asDomain: PrivacyState
        get() = PrivacyState(
            feedbackEmail = feedbackEmail
        )
}
