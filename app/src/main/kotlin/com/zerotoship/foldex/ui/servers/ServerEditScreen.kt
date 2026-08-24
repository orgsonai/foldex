// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zerotoship.foldex.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ServerEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.server_saved)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ServerEditViewModel.ServerEditEvent.Saved -> {
                    snackbar.showSnackbar(savedMessage)
                    onSaved()
                }
                is ServerEditViewModel.ServerEditEvent.Message ->
                    snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.server_add_title else R.string.server_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.server_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ServerTypeDropdown(
                selected = state.type,
                onSelected = viewModel::changeType,
            )
            if (state.type == ServerType.FTP) {
                ToggleRow(
                    title = "FTPS (Explicit TLS)",
                    description = stringResource(R.string.server_ftps_description),
                    checked = state.ftpsEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(ftpsEnabled = v) } },
                )
                if (state.ftpsEnabled) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.server_ftps_certificate_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.server_ftp_plain_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                stringResource(R.string.server_ftp_plain_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.port.toString(),
                onValueChange = { v ->
                    val parsed = v.toIntOrNull()
                    if (parsed != null) viewModel.update { it.copy(port = parsed) }
                },
                label = {
                    val defaultLabel = stringResource(R.string.server_default_port, state.type.name, state.type.defaultPort)
                    Text(stringResource(R.string.server_port_label, defaultLabel))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.rootPath,
                onValueChange = { v -> viewModel.update { it.copy(rootPath = v) } },
                label = { Text(stringResource(R.string.server_root_path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = stringResource(R.string.server_wifi_only_title),
                description = stringResource(R.string.server_wifi_only_description),
                checked = state.wifiOnlyMode,
                onCheckedChange = { v -> viewModel.update { it.copy(wifiOnlyMode = v) } },
            )
            ToggleRow(
                title = stringResource(R.string.server_read_only),
                description = stringResource(R.string.server_read_only_description),
                checked = state.readOnly,
                onCheckedChange = { v -> viewModel.update { it.copy(readOnly = v) } },
            )
            HorizontalDivider()
            AuthModeDropdown(
                selected = state.authMode,
                onSelected = viewModel::changeAuthMode,
            )
            if (state.authMode != ServerAuthMode.ANONYMOUS) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text(stringResource(R.string.connection_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.authMode == ServerAuthMode.PASSWORD ||
                state.authMode == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY
            ) {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                    label = {
                        Text(stringResource(if (state.isNew) R.string.server_password_required else R.string.server_new_password_optional))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.authMode == ServerAuthMode.PUBLIC_KEY ||
                state.authMode == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY
            ) {
                OutlinedTextField(
                    value = state.authorizedKeys,
                    onValueChange = { v -> viewModel.update { it.copy(authorizedKeys = v) } },
                    label = { Text(stringResource(R.string.server_authorized_keys)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider()
            ToggleRow(
                title = stringResource(R.string.server_autostart_app),
                description = stringResource(R.string.server_autostart_app_description),
                checked = state.autoStartOnAppLaunch,
                onCheckedChange = { v -> viewModel.update { it.copy(autoStartOnAppLaunch = v) } },
            )
            ToggleRow(
                title = stringResource(R.string.server_autostart_boot),
                description = stringResource(R.string.server_autostart_boot_description),
                checked = state.autoStartOnBoot,
                onCheckedChange = { v -> viewModel.update { it.copy(autoStartOnBoot = v) } },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (state.isNew) R.string.action_add else R.string.action_update))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ServerTypeDropdown(
    selected: ServerType,
    onSelected: (ServerType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = serverTypeLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.server_protocol)) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.server_select_protocol))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ServerType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(serverTypeLabel(type)) },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun serverTypeLabel(type: ServerType): String = when (type) {
    ServerType.SFTP -> stringResource(R.string.server_type_sftp)
    ServerType.FTP -> stringResource(R.string.server_type_ftp)
}

@Composable
private fun AuthModeDropdown(
    selected: ServerAuthMode,
    onSelected: (ServerAuthMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = authModeLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.connection_auth_method)) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.server_select_auth))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ServerAuthMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(authModeLabel(mode)) },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun authModeLabel(mode: ServerAuthMode): String = when (mode) {
    ServerAuthMode.ANONYMOUS -> stringResource(R.string.server_auth_anonymous)
    ServerAuthMode.PASSWORD -> stringResource(R.string.server_auth_password)
    ServerAuthMode.PUBLIC_KEY -> stringResource(R.string.server_auth_public_key)
    ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> stringResource(R.string.server_auth_password_or_key)
}
