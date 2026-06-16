package com.example.wifidirectmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.wifidirectmesh.theme.WiFiDirectMeshTheme

class MainActivity : ComponentActivity() {
  companion object {
    @Volatile var currentContext: android.content.Context? = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    currentContext = this

    if (android.os.Build.VERSION.SDK_INT >= 33) {
      requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    enableEdgeToEdge()
    setContent {
      WiFiDirectMeshTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (currentContext === this) {
      currentContext = null
    }
  }
}
