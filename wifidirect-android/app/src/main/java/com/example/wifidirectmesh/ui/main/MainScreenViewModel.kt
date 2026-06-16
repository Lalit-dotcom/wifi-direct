package com.example.wifidirectmesh.ui.main

import androidx.lifecycle.ViewModel
import com.example.wifidirectmesh.data.MeshManager
import com.wifidirect.mesh.models.CongestionState
import com.wifidirect.mesh.models.DiscoveryMode
import com.wifidirect.mesh.models.WiFiPeer
import com.wifidirect.mesh.models.WiFiAuditLogEntry
import kotlinx.coroutines.flow.StateFlow

class MainScreenViewModel : ViewModel() {
    val isStarted: StateFlow<Boolean> = MeshManager.isStarted
    val nodeId: StateFlow<String> = MeshManager.nodeId
    val congestionState: StateFlow<CongestionState> = MeshManager.congestionState
    val operatingMode: StateFlow<DiscoveryMode> = MeshManager.operatingMode
    val peers: StateFlow<List<WiFiPeer>> = MeshManager.peers
    val logs: StateFlow<List<WiFiAuditLogEntry>> = MeshManager.logs

    fun startMesh() {
        MeshManager.start()
    }

    fun stopMesh() {
        MeshManager.stop()
    }

    fun triggerSOS() {
        MeshManager.sendSOS()
    }

    fun sendTestMessage(peerId: String, content: String) {
        MeshManager.sendTestMessage(peerId, content)
    }
}
