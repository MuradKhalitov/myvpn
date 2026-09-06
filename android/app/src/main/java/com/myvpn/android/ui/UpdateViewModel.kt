package com.myvpn.android.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvpn.android.data.AppVersionResponse
import com.myvpn.android.data.AppVersionSource
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AvailableUpdate(val versionName: String, val apkUrl: String, val changelog: String)

sealed interface UpdateDecision {
    data object None : UpdateDecision
    data class Optional(val update: AvailableUpdate) : UpdateDecision
    data class Mandatory(val update: AvailableUpdate) : UpdateDecision
}

object UpdatePolicy {
    fun decide(currentVersionCode: Int, metadata: AppVersionResponse): UpdateDecision {
        if (metadata.latestVersionCode <= 0 || metadata.minimumSupportedVersionCode <= 0 ||
            metadata.minimumSupportedVersionCode > metadata.latestVersionCode || !isHttpsUrl(metadata.apkUrl)) return UpdateDecision.None
        val update = AvailableUpdate(metadata.latestVersionName, metadata.apkUrl, metadata.changelog)
        return when {
            currentVersionCode < metadata.minimumSupportedVersionCode -> UpdateDecision.Mandatory(update)
            currentVersionCode < metadata.latestVersionCode -> UpdateDecision.Optional(update)
            else -> UpdateDecision.None
        }
    }

    fun isHttpsUrl(value: String): Boolean = runCatching {
        URI(value).let { it.isAbsolute && it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }
    }.getOrDefault(false)
}

fun interface UpdateDiagnostics { fun versionCheckFailed(error: Throwable) }

object AndroidUpdateDiagnostics : UpdateDiagnostics {
    override fun versionCheckFailed(error: Throwable) {
        Log.w("MyVpnUpdate", "Version check failed: ${error::class.java.simpleName}")
    }
}

class UpdateViewModel(
    source: AppVersionSource,
    currentVersionCode: Int,
    private val diagnostics: UpdateDiagnostics = AndroidUpdateDiagnostics
) : ViewModel() {
    private val _decision = MutableStateFlow<UpdateDecision>(UpdateDecision.None)
    val decision = _decision.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { source.current() }
                .onSuccess { _decision.value = UpdatePolicy.decide(currentVersionCode, it) }
                .onFailure(diagnostics::versionCheckFailed)
        }
    }

    fun dismissOptional() {
        if (_decision.value is UpdateDecision.Optional) _decision.value = UpdateDecision.None
    }
}
