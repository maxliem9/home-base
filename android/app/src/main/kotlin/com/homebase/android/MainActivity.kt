package com.homebase.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homebase.android.ui.aufgaben.AufgabenScreen
import com.homebase.android.ui.aufgaben.TodoViewModel
import com.homebase.android.ui.components.HbDrawerContent
import com.homebase.android.ui.components.HbRoute
import com.homebase.android.ui.heute.HeuteScreen
import com.homebase.android.ui.login.LoginScreen
import com.homebase.android.ui.notes.NotesScreen
import com.homebase.android.ui.notes.NotesViewModel
import com.homebase.android.ui.recipes.RecipesScreen
import com.homebase.android.ui.recipes.RecipesViewModel
import com.homebase.android.ui.shopping.ShoppingScreen
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HomeBaseTheme
import com.homebase.android.ui.time.TimeScreen
import com.homebase.android.ui.time.TimeViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HomeBaseApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeBaseTheme {
                val token by container.authRepository.tokenFlow.collectAsStateWithLifecycle(null)
                if (token == null) {
                    LoginGate()
                } else {
                    MainScaffold(token!!)
                }
            }
        }
    }

    @Composable
    private fun LoginGate() {
        var isLoading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LoginScreen(
            isLoading = isLoading,
            error = error,
            onLogin = { username, password ->
                isLoading = true
                error = null
                scope.launch {
                    container.authRepository.login(username, password)
                        .onFailure { e ->
                            isLoading = false
                            error = e.message ?: "Login failed"
                        }
                        .onSuccess { isLoading = false }
                }
            },
        )
    }

    @Composable
    private fun MainScaffold(token: String) {
        val currentUser = remember(token) { usernameFromToken(token) }

        // All five ViewModels are hoisted here so the drawer can show live badges, screens stay
        // alive across navigation, and the dashboard can read several domains at once.
        val todoVm: TodoViewModel = viewModel(key = "todo-$token", factory = todoFactory(token))
        val shoppingVm: ShoppingViewModel = viewModel(key = "shopping-$token", factory = shoppingFactory(token))
        val notesVm: NotesViewModel = viewModel(key = "notes-$token", factory = notesFactory(token))
        val timeVm: TimeViewModel = viewModel(key = "time-$token", factory = timeFactory(token, currentUser))
        val recipesVm: RecipesViewModel = viewModel(key = "recipes-$token", factory = recipesFactory(token))

        var route by rememberSaveable { mutableStateOf(HbRoute.HEUTE) }
        var drawerOpen by remember { mutableStateOf(false) }

        val household by produceState(initialValue = "Max & Lea") {
            container.configRepository.getHouseholdName().onSuccess { value = it }
        }

        val todoState by todoVm.uiState.collectAsState()
        val shoppingState by shoppingVm.uiState.collectAsState()
        val timeState by timeVm.uiState.collectAsState()

        val badges = mapOf(
            HbRoute.AUFGABEN to todoState.openCount,
            HbRoute.EINKAUF to shoppingState.openCount,
        )
        val dots = if (timeState.running != null) setOf(HbRoute.ZEIT) else emptySet()

        val openDrawer = { drawerOpen = true }

        BackHandler(enabled = drawerOpen) { drawerOpen = false }

        Box(Modifier.fillMaxSize().background(Hb.paper)) {
            when (route) {
                HbRoute.HEUTE -> HeuteScreen(
                    todoVm = todoVm,
                    shoppingVm = shoppingVm,
                    timeVm = timeVm,
                    currentUser = currentUser,
                    onOpenDrawer = openDrawer,
                    onNavigate = { route = it },
                )
                HbRoute.AUFGABEN -> AufgabenScreen(
                    viewModel = todoVm,
                    currentUser = currentUser,
                    onOpenDrawer = openDrawer,
                )
                HbRoute.EINKAUF -> ShoppingScreen(
                    viewModel = shoppingVm,
                    currentUser = currentUser,
                    onOpenDrawer = openDrawer,
                )
                HbRoute.NOTIZEN -> NotesScreen(
                    viewModel = notesVm,
                    currentUser = currentUser,
                    onOpenDrawer = openDrawer,
                )
                HbRoute.ZEIT -> TimeScreen(
                    viewModel = timeVm,
                    currentUser = currentUser,
                    onOpenDrawer = openDrawer,
                )
                HbRoute.REZEPTE -> RecipesScreen(
                    viewModel = recipesVm,
                    shoppingViewModel = shoppingVm,
                    onOpenDrawer = openDrawer,
                )
            }

            // Scrim
            AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Hb.scrim)
                        .clickable(interactionSource = interaction, indication = null) { drawerOpen = false },
                )
            }
            // Drawer
            AnimatedVisibility(
                visible = drawerOpen,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
            ) {
                HbDrawerContent(
                    active = route,
                    householdName = household,
                    currentUser = currentUser,
                    badges = badges,
                    dots = dots,
                    onSelect = { route = it; drawerOpen = false },
                )
            }
        }
    }

    // --- ViewModel factories ---

    private fun todoFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TodoViewModel(container.todoRepository, token) as T
        }
    }

    private fun shoppingFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ShoppingViewModel(container.shoppingRepository, token) as T
        }
    }

    private fun notesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(container.notesRepository, token) as T
        }
    }

    private fun timeFactory(token: String, username: String?) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TimeViewModel(container.timeRepository, token, username) as T
        }
    }

    private fun recipesFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return RecipesViewModel(container.recipesRepository, token) as T
        }
    }

    /** Reads the `username` claim from the JWT payload (drives greeting + delete permissions). */
    private fun usernameFromToken(token: String): String? = runCatching {
        val payload = token.split(".")[1]
        val decoded = String(
            android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            ),
        )
        JSONObject(decoded).optString("username").takeIf { it.isNotEmpty() }
    }.getOrNull()
}
