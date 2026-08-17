package com.vaynah.gochmott.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.vaynah.gochmott.BuildConfig
import com.vaynah.gochmott.R
import com.vaynah.gochmott.db.DatabaseHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppInfo(
    val appVersion: String,
    val buildNumber: String,
    val dictVersion: Int,
    val feedbackEmail: String,
)
class AppInfoRepository @Inject constructor(
    coroutineScope: CoroutineScope,
    @ApplicationContext private val appContext: Context,
    dbHelper: DatabaseHelper,
) {

    val info: StateFlow<AppInfo> = dbHelper.dbVersion.map {
        AppInfo(
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE.toString(),
            dictVersion = it,
            feedbackEmail = FEEDBACK_EMAIL
        )
    }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), AppInfo(
        appVersion = BuildConfig.VERSION_NAME,
        buildNumber = BuildConfig.VERSION_CODE.toString(),
        dictVersion = -1,
        feedbackEmail = FEEDBACK_EMAIL
    ))

    fun sendFeedbackEmail() {
        val email = info.value.feedbackEmail
        val appName = appContext.getString(R.string.app_name)
        val feedbackLabel = appContext.getString(R.string.feedback)

        val subject = appContext.getString(
            R.string.feedback_subject_template,
            appName,
            info.value.appVersion,
            info.value.buildNumber,
            info.value.dictVersion,
            feedbackLabel
        )

        val uriString = "mailto:$email?subject=${Uri.encode(subject)}"

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = uriString.toUri()
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            appContext.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.email_client_not_found),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    companion object{
        const val FEEDBACK_EMAIL = "gochmottapp@gmail.com"
    }
}