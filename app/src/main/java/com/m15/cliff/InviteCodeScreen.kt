package com.m15.cliff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// One-time-per-app-run gate for "Request invite"
private var inviteRequestSentThisRun: Boolean = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteCodeScreen(
    isBusy: Boolean,
    errorText: String?,
    onSubmit: (inviteCode: String) -> Unit,
    onExit: () -> Unit,
    onRequestInvite: suspend () -> Unit = {}
) {
    var code by remember { mutableStateOf("") }
    val canSubmit = code.trim().isNotEmpty() && !isBusy

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var requestInFlight by remember { mutableStateOf(false) }
    var requestSent by remember { mutableStateOf(inviteRequestSentThisRun) }
    val canRequestInvite = !isBusy && !requestInFlight && !requestSent

    LaunchedEffect(errorText) {
        if (!errorText.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = "Invite code not accepted. Please contact the Cliff team for your invite code.",
                withDismissAction = false,
                duration = SnackbarDuration.Short
            )
            delay(7_000)
            onExit()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Box(modifier = Modifier.padding(top = 24.dp)) {
                        Text(
                            text = "Cliff",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 36.sp,
                            letterSpacing = 1.5.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Enter your invite code",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "This code unlocks your device.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(14.dp))

                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Invite code") },
                            singleLine = true,
                            enabled = !isBusy,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedLabelColor = Color.White,
                                cursorColor = Color.White
                            )
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = { onSubmit(code.trim()) },
                            enabled = canSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White.copy(alpha = 0.3f),
                                disabledContentColor = Color.Black.copy(alpha = 0.6f)
                            )
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Verifying…", fontWeight = FontWeight.Bold)
                            } else {
                                Text("UNLOCK", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onExit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(24.dp),
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444))
                        ) {
                            Text("EXIT")
                        }

                        Spacer(Modifier.height(6.dp))

                        TextButton(
                            enabled = canRequestInvite,
                            onClick = {
                                requestInFlight = true
                                scope.launch {
                                    try {
                                        onRequestInvite()
                                        inviteRequestSentThisRun = true
                                        requestSent = true
                                        snackbarHostState.showSnackbar(
                                            message = "Request sent. Your device ID was shared with the Cliff server.",
                                            duration = SnackbarDuration.Short
                                        )
                                    } catch (t: Throwable) {
                                        snackbarHostState.showSnackbar(
                                            message = "Could not send request. Please try again.",
                                            duration = SnackbarDuration.Short
                                        )
                                    } finally {
                                        requestInFlight = false
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            val label = when {
                                requestSent -> "Invite request already sent"
                                requestInFlight -> "Sending…"
                                else -> "Request invite (send device ID)"
                            }
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                color = if (canRequestInvite) Color.White else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }
}
