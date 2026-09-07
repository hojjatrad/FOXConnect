package com.foxconnect.app.update

internal sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: AvailableUpdate) : UpdateUiState
    data class Downloading(val update: AvailableUpdate) : UpdateUiState
    data class Ready(val update: AvailableUpdate, val apkPath: String) : UpdateUiState
    data class Failed(val reason: UpdateFailure) : UpdateUiState
}
