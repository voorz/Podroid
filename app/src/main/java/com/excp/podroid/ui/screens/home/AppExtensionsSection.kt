package com.excp.podroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.excp.podroid.R
import com.excp.podroid.service.PodroidService
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidStatus
import com.excp.podroid.ui.components.PodroidStatusColors
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.NetworkUtils

/**
 * Status of a single app extension (e.g. vohive) inside the running VM.
 */
sealed interface AppExtensionStatus {
    /** VM is not running — the entire section should be greyed out. */
    data object VmNotRunning : AppExtensionStatus
    /** App is not installed in the container. */
    data object NotInstalled : AppExtensionStatus
    /** App is installed but not running. */
    data object InstalledNotRunning : AppExtensionStatus
    /** App is running; [webUrl] is the full URL to the WebUI. */
    data class Running(val webUrl: String) : AppExtensionStatus
    /** Status could not be determined (probe failed, SSH error, etc.). */
    data object Unknown : AppExtensionStatus
}

/**
 * "App Extensions" section shown on the Home screen below the network section.
 *
 * When the VM is stopped the whole section is greyed out and non-interactive.
 * When the VM is running it shows the status of vohive (installed / running /
 * not installed) and provides install / start / open-browser actions.
 */
@Composable
fun AppExtensionsSection(
    isRunning: Boolean,
    vohiveStatus: AppExtensionStatus,
    phoneIp: String,
    onInstallVohive: () -> Unit,
    onStartVohive: () -> Unit,
) {
    val context = LocalContext.current

    PodroidSectionLabel(stringResource(R.string.app_extensions))

    if (!isRunning || vohiveStatus is AppExtensionStatus.VmNotRunning) {
        // Greyed out — VM not running, nothing to show or interact with.
        PodroidListRow(
            label = stringResource(R.string.vohive_name),
            value = stringResource(R.string.app_extensions_vm_offline),
            mono = false,
        )
        return
    }

    when (vohiveStatus) {
        is AppExtensionStatus.NotInstalled -> {
            PodroidListRow(
                label = stringResource(R.string.vohive_name),
                value = stringResource(R.string.app_extensions_not_installed),
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            PodroidPrimaryButton(
                text = stringResource(R.string.vohive_install),
                onClick = onInstallVohive,
            )
        }

        is AppExtensionStatus.InstalledNotRunning -> {
            PodroidListRow(
                label = stringResource(R.string.vohive_name),
                value = stringResource(R.string.app_extensions_installed),
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            PodroidGhostButton(
                text = stringResource(R.string.vohive_start),
                onClick = onStartVohive,
            )
        }

        is AppExtensionStatus.Running -> {
            PodroidListRow(
                label = stringResource(R.string.vohive_name),
                rightSlot = {
                    PodroidStatus(
                        label = stringResource(R.string.app_extensions_running),
                        dotColor = PodroidStatusColors.Running,
                    )
                },
                divider = false,
            )
            // WebUI row — tappable, opens external browser.
            val webUrl = vohiveStatus.webUrl
            PodroidListRow(
                label = stringResource(R.string.vohive_webui),
                value = webUrl.replace("http://", ""),
                mono = true,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                    }
                },
            )
        }

        is AppExtensionStatus.Unknown -> {
            PodroidListRow(
                label = stringResource(R.string.vohive_name),
                value = stringResource(R.string.app_extensions_unknown),
            )
        }

        AppExtensionStatus.VmNotRunning -> {
            // Already handled above; this branch is unreachable but satisfies
            // the exhaustive when.
        }
    }
}
