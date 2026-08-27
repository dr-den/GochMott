package com.bilto.gochmott

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.bilto.gochmott.model.SearchDirection
import com.bilto.gochmott.ui.DictDeepLink
import com.bilto.gochmott.ui.GochMottNavGraph
import com.bilto.gochmott.ui.theme.GochMottTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val deepLink = mutableStateOf<DictDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) deepLink.value = deepLinkFrom(intent)
        setContent {
            GochMottTheme {
                GochMottNavGraph(
                    deepLink = deepLink.value,
                    onDeepLinkHandled = { deepLink.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = deepLinkFrom(intent)
    }

    private fun deepLinkFrom(intent: Intent): DictDeepLink? {
        val lemmaId = intent.getLongExtra(EXTRA_LEMMA_ID, NO_LEMMA)
        if (lemmaId != NO_LEMMA) return DictDeepLink.Entry(lemmaId)

        val query = intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() } ?: return null
        val direction = intent.getStringExtra(EXTRA_DIRECTION)
            ?.let { name -> SearchDirection.entries.firstOrNull { it.name == name } }
        return DictDeepLink.Search(query, direction)
    }

    companion object {
        private const val EXTRA_QUERY = "com.bilto.gochmott.extra.QUERY"
        private const val EXTRA_DIRECTION = "com.bilto.gochmott.extra.DIRECTION"
        private const val EXTRA_LEMMA_ID = "com.bilto.gochmott.extra.LEMMA_ID"
        private const val NO_LEMMA = -1L

        fun searchIntent(context: Context, query: String, direction: SearchDirection): Intent =
            intentTo(context)
                .putExtra(EXTRA_QUERY, query)
                .putExtra(EXTRA_DIRECTION, direction.name)

        fun entryIntent(context: Context, lemmaId: Long): Intent =
            intentTo(context).putExtra(EXTRA_LEMMA_ID, lemmaId)


        private fun intentTo(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
