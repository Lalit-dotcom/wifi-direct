package com.example.wifidirectmesh

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data class Chat(val peerId: String) : NavKey

@Serializable data object GroupChat : NavKey
