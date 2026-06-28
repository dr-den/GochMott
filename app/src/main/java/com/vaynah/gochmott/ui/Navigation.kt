package com.vaynah.gochmott.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vaynah.gochmott.viewmodel.SearchViewModel

sealed class Screen(val route: String) {
    object Search : Screen("search")
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

            SearchScreen(
                viewModel = searchViewModel,
                onNavigateToDetail = { lemmaId ->
                    navController.navigate(Screen.Detail.createRoute(lemmaId))
                }
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
