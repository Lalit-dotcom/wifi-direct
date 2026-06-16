package com.example.wifidirectmesh

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.wifidirectmesh.data.MeshManager
import com.example.wifidirectmesh.ui.main.MainScreen
import com.example.wifidirectmesh.ui.chat.ChatScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val activeSosAlert by MeshManager.activeSosAlert.collectAsState()

  Box(modifier = Modifier.fillMaxSize()) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<Main> {
            MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
          }
          entry<Chat> { key ->
            ChatScreen(
              peerId = key.peerId,
              onBack = { backStack.removeLastOrNull() },
              modifier = Modifier.safeDrawingPadding().padding(16.dp)
            )
          }
        },
    )

    if (activeSosAlert != null) {
      val alert = activeSosAlert!!
      val dateStr = remember(alert.timestamp) {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(alert.timestamp))
      }
      AlertDialog(
        onDismissRequest = { /* Persist: user must tap Dismiss button */ },
        title = {
          Text("⚠️ SOS EMERGENCY ALERT", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
        },
        text = {
          Column {
            Text(text = "A critical emergency alert was received from the mesh network.", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Sender Node ID:", fontWeight = FontWeight.Bold)
            Text(text = alert.senderId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Received At: $dateStr", fontSize = 13.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Content:", fontWeight = FontWeight.Bold)
            Text(text = alert.text, color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
          }
        },
        confirmButton = {
          Button(
            onClick = { MeshManager.dismissSosAlert() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
          ) {
            Text("Dismiss", color = Color.White)
          }
        }
      )
    }
  }
}
