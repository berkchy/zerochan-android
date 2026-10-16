package net.berkchy.zerochan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.berkchy.zerochan.core.data.ZerochanRepository
import net.berkchy.zerochan.ui.downloads.LibraryScreen
import net.berkchy.zerochan.ui.downloads.LibraryViewModel
import net.berkchy.zerochan.ui.home.FeedScreen
import net.berkchy.zerochan.ui.home.FeedViewModel
import net.berkchy.zerochan.ui.home.PopularScreen
import net.berkchy.zerochan.ui.home.PopularViewModel
import net.berkchy.zerochan.ui.post.PostScreen
import net.berkchy.zerochan.ui.post.PostViewModel
import net.berkchy.zerochan.ui.settings.SettingsScreen

private const val ARG_FEED = "feed"
private const val ARG_POST = "postId"
private const val ARG_CHILDREN = "children"

object Destination {
    const val HOME = "home"
    const val POPULAR = "feed/popular"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val POST = "post/{$ARG_POST}?{$ARG_CHILDREN}"
    const val TAG = "feed/{$ARG_FEED}"

    fun postRoute(id: Long, children: List<Long>) =
        "post/$id?$ARG_CHILDREN=${children.joinToString("-")}"

    fun tagRoute(encoded: String) = "feed/$encoded"
}

@Composable
fun ZerochanNavHost(
    repository: ZerochanRepository,
    startTab: String? = null,
    onShare: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var searchError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    // zerochan answers /search?q= with a 301 onto the canonical tag, so the redirect has to be
    // resolved before there is anything to list.
    val submitSearch: (String) -> Unit = { query ->
        if (query.isBlank() || searching) return@let
        scope.launch {
            searching = true
            searchError = null
            runCatching { repository.resolveSearch(query) }
                .onSuccess { path -> navController.navigate(Destination.tagRoute(encodeTag(path))) }
                .onFailure { searchError = it.message ?: "No results for \"$query\"" }
            searching = false
        }
    }

    val start = remember(startTab) {
        when (startTab) {
            "library" -> Destination.LIBRARY
            "settings" -> Destination.SETTINGS
            else -> Destination.HOME
        }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBars = currentRoute in setOf(
        Destination.HOME,
        Destination.POPULAR,
        Destination.LIBRARY,
        Destination.SETTINGS,
    )

    Scaffold(
        bottomBar = {
            AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut()) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Destination.HOME,
                        onClick = { navController.navigateTop(Destination.HOME) },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destination.POPULAR,
                        onClick = { navController.navigateTop(Destination.POPULAR) },
                        icon = { Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null) },
                        label = { Text("Popular") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destination.LIBRARY,
                        onClick = { navController.navigateTop(Destination.LIBRARY) },
                        icon = { Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Destination.SETTINGS,
                        onClick = { navController.navigateTop(Destination.SETTINGS) },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        // Consume the window insets once here so nested screens (the post screen has its own
        // Scaffold with a TopAppBar) never stack a second status bar / navigation bar padding.
        val safePadding = Modifier
            .consumeWindowInsets(padding)
            .padding(padding)

        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Destination.HOME) {
                val vm: FeedViewModel = viewModel(factory = FeedViewModel.Factory(repository, null))
                FeedScreen(
                    viewModel = vm,
                    onPostClick = { navController.navigate(Destination.postRoute(it.id, it.children)) },
                    onTagClick = { navController.navigate(Destination.tagRoute(encodeTag(it))) },
                    topContent = {
                        Column {
                            SearchField(
                                onSubmit = submitSearch,
                                busy = searching,
                                error = searchError,
                                onDismissError = { searchError = null },
                            )
                        }
                    },
                    modifier = safePadding,
                )
            }

            composable(Destination.POPULAR) {
                val vm: PopularViewModel = viewModel(factory = PopularViewModel.Factory(repository))
                PopularScreen(
                    viewModel = vm,
                    onOpenPost = { navController.navigate(Destination.postRoute(it, emptyList())) },
                    modifier = safePadding,
                )
            }

            composable(Destination.LIBRARY) {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository))
                LibraryScreen(
                    viewModel = vm,
                    onFavoriteClick = { navController.navigate(Destination.postRoute(it, emptyList())) },
                    modifier = safePadding,
                )
            }

            composable(Destination.SETTINGS) {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(repository))
                SettingsScreen(vm, Modifier.padding(bottom = padding.calculateBottomPadding()))
            }

            composable(
                route = Destination.TAG,
                arguments = listOf(navArgument(ARG_FEED) { type = NavType.StringType }),
            ) { entry ->
                val path = entry.arguments?.getString(ARG_FEED).orEmpty()
                val vm: FeedViewModel = viewModel(
                    key = "feed-$path",
                    factory = FeedViewModel.Factory(repository, path),
                )
                LaunchedEffect(path) { searchError = null }
                FeedScreen(
                    viewModel = vm,
                    onPostClick = { navController.navigate(Destination.postRoute(it.id, it.children)) },
                    onTagClick = { navController.navigate(Destination.tagRoute(encodeTag(it))) },
                    modifier = safePadding,
                    topContent = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                            SearchField(
                                initial = path.replace("%20", " "),
                                onSubmit = { navController.navigate(Destination.tagRoute(encodeTag(it))) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                )
            }

            composable(
                route = Destination.POST,
                arguments = listOf(
                    navArgument(ARG_POST) { type = NavType.LongType },
                    navArgument(ARG_CHILDREN) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val id = entry.arguments?.getLong(ARG_POST) ?: 0L
                val vm: PostViewModel = viewModel(
                    key = "post-$id",
                    factory = PostViewModel.Factory(repository, id),
                )
                val state by vm.state.collectAsStateWithLifecycle()

                PostScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onPageChanged = vm::onPageChanged,
                    onRetry = vm::retry,
                    onToggleFavorite = vm::toggleFavorite,
                    onDownload = vm::download,
                    onShare = { state.detail?.let { onShare(it.postUrl, "${it.displayName} · zerochan") } },
                    onOpenSource = { state.detail?.source?.takeIf { it.isNotBlank() }?.let(onOpenUrl) },
                    onTagClick = { navController.navigate(Destination.tagRoute(encodeTag(it))) },
                    onToast = vm::consumeToast,
                    modifier = safePadding,
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    initial: String = "",
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    error: String? = null,
    onDismissError: () -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(initial) { if (initial.isNotBlank()) text = initial }
    LaunchedEffect(error) { if (error != null) keyboard?.hide() }

    Column(modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            placeholder = { Text("Search tags, characters, artists") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotBlank()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Search,
            ),
            enabled = !busy,
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (text.isNotBlank()) {
                        onSubmit(text.trim())
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                },
            ),
        )
    }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismissError)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun encodeTag(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")