package com.homebase.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homebase.android.ui.inbox.InboxScreen
import com.homebase.android.ui.inbox.InboxViewModel
import com.homebase.android.ui.login.LoginScreen
import com.homebase.android.ui.theme.HomeBaseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HomeBaseApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeBaseTheme {
                val token by container.authRepository.tokenFlow.collectAsStateWithLifecycle(null)

                if (token == null) {
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
                } else {
                    val vm: InboxViewModel = viewModel(
                        factory = rememberInboxViewModelFactory(token!!)
                    )
                    InboxScreen(viewModel = vm)
                }
            }
        }
    }

    @Composable
    private fun rememberInboxViewModelFactory(token: String) =
        remember(token) {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return InboxViewModel(container.todoRepository, token) as T
                }
            }
        }
}
