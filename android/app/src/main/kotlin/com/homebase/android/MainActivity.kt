package com.homebase.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homebase.android.ui.inbox.InboxScreen
import com.homebase.android.ui.inbox.InboxViewModel
import com.homebase.android.ui.login.LoginScreen
import com.homebase.android.ui.notes.NotesScreen
import com.homebase.android.ui.notes.NotesViewModel
import com.homebase.android.ui.shopping.ShoppingScreen
import com.homebase.android.ui.shopping.ShoppingViewModel
import com.homebase.android.ui.theme.HomeBaseTheme
import com.homebase.android.ui.time.TimeScreen
import com.homebase.android.ui.time.TimeViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HomeBaseApplication).container }

    private enum class Tab(val label: String) { INBOX("Inbox"), SHOPPING("Einkauf"), NOTES("Notizen"), TIME("Zeit") }

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
        var tab by rememberSaveable { mutableStateOf(Tab.INBOX) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.INBOX,
                        onClick = { tab = Tab.INBOX },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(Tab.INBOX.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SHOPPING,
                        onClick = { tab = Tab.SHOPPING },
                        icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
                        label = { Text(Tab.SHOPPING.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.NOTES,
                        onClick = { tab = Tab.NOTES },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        label = { Text(Tab.NOTES.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.TIME,
                        onClick = { tab = Tab.TIME },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text(Tab.TIME.label) },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                Tab.INBOX -> {
                    val vm: InboxViewModel = viewModel(
                        key = "inbox-$token",
                        factory = inboxFactory(token),
                    )
                    Box(Modifier.padding(padding)) {
                        InboxScreen(viewModel = vm)
                    }
                }
                Tab.SHOPPING -> {
                    val vm: ShoppingViewModel = viewModel(
                        key = "shopping-$token",
                        factory = shoppingFactory(token),
                    )
                    Box(Modifier.padding(padding)) {
                        ShoppingScreen(viewModel = vm)
                    }
                }
                Tab.NOTES -> {
                    val vm: NotesViewModel = viewModel(
                        key = "notes-$token",
                        factory = notesFactory(token),
                    )
                    Box(Modifier.padding(padding)) {
                        NotesScreen(viewModel = vm)
                    }
                }
                Tab.TIME -> {
                    val vm: TimeViewModel = viewModel(
                        key = "time-$token",
                        factory = timeFactory(token),
                    )
                    Box(Modifier.padding(padding)) {
                        TimeScreen(viewModel = vm)
                    }
                }
            }
        }
    }

    private fun inboxFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return InboxViewModel(container.todoRepository, token) as T
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

    private fun timeFactory(token: String) = object : ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return TimeViewModel(container.timeRepository, token, usernameFromToken(token)) as T
        }
    }

    /** Reads the `username` claim from the JWT payload so the running-timer banner only reflects this user. */
    private fun usernameFromToken(token: String): String? = runCatching {
        val payload = token.split(".")[1]
        val decoded = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        JSONObject(decoded).optString("username").takeIf { it.isNotEmpty() }
    }.getOrNull()
}
