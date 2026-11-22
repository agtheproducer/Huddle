import androidx.compose.ui.window.ComposeUIViewController
import com.agentboard.mobile.App

/** Called from the Swift app (iosApp/ContentView.swift) as Main_iosKt.MainViewController(). */
fun MainViewController() = ComposeUIViewController { App() }
