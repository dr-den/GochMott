package com.bilto.gochmott.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bilto.gochmott.model.SearchDirection
import android.net.Uri
import androidx.navigation.NavBackStackEntry
import com.bilto.gochmott.viewmodel.AboutViewModel
import com.bilto.gochmott.viewmodel.BookViewModel
import com.bilto.gochmott.viewmodel.FeedbackViewModel
import com.bilto.gochmott.viewmodel.OnboardingViewModel
import com.bilto.gochmott.viewmodel.PrivacyPolicyViewModel
import com.bilto.gochmott.viewmodel.SearchViewModel
import com.bilto.gochmott.viewmodel.UsagesViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Search : Screen("search")
    object About : Screen("about")
    object PrivacyPolicy : Screen("privacy_policy")
    object Onboarding : Screen("onboarding")

    /**
     * Вводные части словарей. Три уровня: список книг -> разделы книги -> текст.
     * Код книги стоит в маршруте, а не в состоянии: [BookViewModel] достаёт его
     * из `SavedStateHandle`, и каждый экран знает, чью книгу показывает.
     */
    object Books : Screen("books")
    object Book : Screen("book/{bookCode}") {
        fun createRoute(bookCode: String) = "book/$bookCode"
    }
    object BookAbbreviations : Screen("book/{bookCode}/abbreviations") {
        fun createRoute(bookCode: String) = "book/$bookCode/abbreviations"
    }
    object BookAlphabet : Screen("book/{bookCode}/alphabet") {
        fun createRoute(bookCode: String) = "book/$bookCode/alphabet"
    }
    object BookSection : Screen("book/{bookCode}/section/{sectionId}") {
        fun createRoute(bookCode: String, sectionId: String) = "book/$bookCode/section/$sectionId"
        const val ARG = "sectionId"
    }
    /**
     * Употребления чеченского слова, у которого нет своей статьи. В маршруте лежит
     * НОРМАЛИЗОВАННЫЙ ключ (`trans_index.word`), а не то, что набрал пользователь:
     * по нему построен индекс, и экран ищет ровно им. Слово кодируется — в нём
     * кириллица и палочка.
     */
    object Usages : Screen("usages/{word}") {
        fun createRoute(word: String) = "usages/" + Uri.encode(word)
    }
    object Detail : Screen("detail/{lemmaId}") {
        fun createRoute(lemmaId: Long) = "detail/$lemmaId"
        const val ARG = "lemmaId"
    }
}


/** Аргумент маршрута с кодом книги — его же читает [BookViewModel]. */
private fun bookCodeArg() =
    navArgument(BookViewModel.BOOK_CODE_ARG) { type = NavType.StringType }

private fun NavBackStackEntry.bookCode(): String? =
    arguments?.getString(BookViewModel.BOOK_CODE_ARG)

sealed class DictDeepLink {
    data class Search(val query: String, val direction: SearchDirection? = null) : DictDeepLink()

    data class Entry(val lemmaId: Long) : DictDeepLink()
}

@Composable
fun GochMottNavGraph(
    deepLink: DictDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val navController = rememberNavController()


    val searchViewModel: SearchViewModel = hiltViewModel()
    val feedbackViewModel: FeedbackViewModel = hiltViewModel()
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val showOnboarding by onboardingViewModel.showOnStart.collectAsStateWithLifecycle()

    val openDetail: (Long) -> Unit = { lemmaId ->
        feedbackViewModel.onEntryOpened()
        navController.navigate(Screen.Detail.createRoute(lemmaId))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Search.route
        ) {
            composable(Screen.Search.route) {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val activity = LocalActivity.current

                // Поиск снова на экране — как правило, пользователь вернулся из статьи.
                // Удачный момент спросить об оценке: дело сделано, ничего не прерываем.
                LaunchedEffect(Unit) {
                    if (activity != null && showOnboarding == false) {
                        feedbackViewModel.askForReviewIfDue(activity)
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        AppDrawerContent(
                            onBookClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(Screen.Books.route)
                            },
                            onAboutClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(Screen.About.route)
                            },
                            onPrivacyPolicyClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(Screen.PrivacyPolicy.route)
                            },
                            onTutorialClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(Screen.Onboarding.route)
                            },
                            onRateClick = {
                                scope.launch { drawerState.close() }
                                feedbackViewModel.rateApp()
                            },
                            onFeedbackClick = {
                                scope.launch { drawerState.close() }
                                feedbackViewModel.sendFeedback()
                            }
                        )
                    }
                ) {
                    SearchScreen(
                        viewModel = searchViewModel,
                        onNavigateToDetail = openDetail,
                        onNavigateToUsages = { word ->
                            navController.navigate(Screen.Usages.createRoute(word))
                        },
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
            }

            composable(Screen.Onboarding.route) {
                OnboardingScreen(onFinish = { navController.popBackStack() })
            }

            composable(Screen.About.route) {
                val viewModel: AboutViewModel = hiltViewModel()
                AboutScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) }
                )
            }

            composable(Screen.Books.route) {
                BooksListScreen(
                    onOpenBook = { code -> navController.navigate(Screen.Book.createRoute(code)) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Book.route, arguments = listOf(bookCodeArg())) { entry ->
                val code = entry.bookCode() ?: return@composable
                BookHubScreen(
                    onOpenSection = { id ->
                        navController.navigate(Screen.BookSection.createRoute(code, id))
                    },
                    onOpenAbbreviations = {
                        navController.navigate(Screen.BookAbbreviations.createRoute(code))
                    },
                    onOpenAlphabet = {
                        navController.navigate(Screen.BookAlphabet.createRoute(code))
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.BookSection.route,
                arguments = listOf(
                    bookCodeArg(),
                    navArgument(Screen.BookSection.ARG) { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString(Screen.BookSection.ARG) ?: return@composable
                BookSectionScreen(sectionId = id, onBack = { navController.popBackStack() })
            }

            composable(Screen.BookAbbreviations.route, arguments = listOf(bookCodeArg())) {
                AbbreviationsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.BookAlphabet.route, arguments = listOf(bookCodeArg())) {
                AlphabetScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.PrivacyPolicy.route) {
                val viewModel: PrivacyPolicyViewModel = hiltViewModel()

                PrivacyPolicyScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.Usages.route,
                arguments = listOf(navArgument(UsagesViewModel.WORD_ARG) { type = NavType.StringType })
            ) {
                UsagesScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToDetail = openDetail
                )
            }

            composable(
                route = Screen.Detail.route,
                arguments = listOf(navArgument(Screen.Detail.ARG) { type = NavType.LongType })
            ) { backStackEntry ->
                val lemmaId = backStackEntry.arguments?.getLong(Screen.Detail.ARG) ?: return@composable

                DetailScreen(
                    lemmaId = lemmaId,
                    onBack = { navController.popBackStack() },
                    onNavigateToDetail = openDetail,
                    onSearchQuery = { query ->
                        searchViewModel.searchFor(query)
                        navController.popBackStack(Screen.Search.route, inclusive = false)
                    }
                )
            }
        }

        // При первом запуске вводный показ ложится поверх поиска, а не отдельным
        // экраном в стеке: переход по ссылке из быстрого перевода открывает статью
        // под ним, и после «Начать» пользователь сразу видит то, за чем пришёл.
        // Пока настройка не прочитана, закрываем поиск фоном — иначе он мелькнёт.
        when (showOnboarding) {
            null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
            true -> OnboardingScreen(onFinish = onboardingViewModel::finish)
            false -> Unit
        }
    }

    LaunchedEffect(deepLink) {
        when (deepLink) {
            null -> return@LaunchedEffect

            is DictDeepLink.Search -> {
                searchViewModel.searchFor(deepLink.query, deepLink.direction)
                navController.popBackStack(Screen.Search.route, inclusive = false)
            }

            is DictDeepLink.Entry -> {
                navController.popBackStack(Screen.Search.route, inclusive = false)
                openDetail(deepLink.lemmaId)
            }
        }
        onDeepLinkHandled()
    }
}
