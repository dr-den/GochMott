package com.vaynah.gochmott.ui

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vaynah.gochmott.viewmodel.AboutViewModel
import com.vaynah.gochmott.viewmodel.PrivacyPolicyViewModel
import com.vaynah.gochmott.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Search : Screen("search")
    object About : Screen("about")
    object PrivacyPolicy : Screen("privacy_policy")
    object Detail : Screen("detail/{lemmaId}") {
        fun createRoute(lemmaId: Long) = "detail/$lemmaId"
        const val ARG = "lemmaId"
    }
}

@Composable
fun GochMottNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Search.route
    ) {
        composable(Screen.Search.route) { backStackEntry ->
            val searchViewModel: SearchViewModel = hiltViewModel()

            // Pick up query passed back from DetailScreen via savedStateHandle
            val savedQuery = backStackEntry.savedStateHandle.get<String>("searchQuery")
            LaunchedEffect(savedQuery) {
                if (!savedQuery.isNullOrBlank()) {
                    searchViewModel.searchFor(savedQuery)
                    backStackEntry.savedStateHandle.remove<String>("searchQuery")
                }
            }


            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AppDrawerContent(
                        onAboutClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.About.route)
                        },
                        onPrivacyPolicyClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(Screen.PrivacyPolicy.route)
                        }
                    )
                }
            ) {
                SearchScreen(
                    viewModel = searchViewModel,
                    onNavigateToDetail = { lemmaId ->
                        navController.navigate(Screen.Detail.createRoute(lemmaId))
                    },
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
        }

        composable(Screen.About.route) {
            val viewModel: AboutViewModel = hiltViewModel()
            AboutScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) }
            )
        }

        composable(Screen.PrivacyPolicy.route) {
            val viewModel: PrivacyPolicyViewModel = hiltViewModel()

            PrivacyPolicyScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument(Screen.Detail.ARG) { type = NavType.LongType })
        ) { backStackEntry ->
            val lemmaId = backStackEntry.arguments?.getLong(Screen.Detail.ARG) ?: return@composable

            DetailScreen(
                lemmaId = lemmaId,
                onBack = { navController.popBackStack() },
                onNavigateToDetail = { id ->
                    navController.navigate(Screen.Detail.createRoute(id))
                },
                onSearchQuery = { query ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("searchQuery", query)
                    navController.popBackStack()
                }
            )
        }
    }
}
