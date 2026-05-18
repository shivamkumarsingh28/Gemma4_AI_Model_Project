package com.example.gemma4app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gemma4app.ui.theme.DrAITheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import com.example.gemma4app.data.ConversationEntity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import android.content.Context
import android.net.Uri
import java.io.FileOutputStream
import java.io.File
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DrAITheme { DrAIApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrAIApp(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val convList by vm.conversations.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackHost = remember { SnackbarHostState() }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val scanner = remember { PrescriptionScanner(context) }
    val voiceAssistant = remember { VoiceAssistant(context) {} }
    var isListening by remember { mutableStateOf(false) }
    var isOcrRunning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { voiceAssistant.shutdown() }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isListening = true
            val locale = getLocale(state.selectedLanguage)
            val (askPrefix, replyPrefix) = getPrefixes(state.selectedLanguage)
            voiceAssistant.setLanguage(locale)
            voiceAssistant.startListening(
                locale = locale,
                onResult = { text ->
                    isListening = false
                    voiceAssistant.speak("$askPrefix: $text")
                    vm.sendMessage(text) { reply ->
                        voiceAssistant.speak("$replyPrefix: $reply")
                    }
                },
                onError = { err -> 
                    isListening = false
                    scope.launch { snackHost.showSnackbar(err) }
                }
            )
        } else {
            scope.launch { snackHost.showSnackbar("Microphone permission required") }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val path = getPathFromUri(context, it)
            if (path != null) vm.setCustomModel(path)
        }
    }

    val prescriptionPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isOcrRunning = true
            scanner.scanPrescription(it) { text -> 
                isOcrRunning = false
                if (text.startsWith("Error")) {
                    scope.launch { snackHost.showSnackbar(text) }
                } else {
                    vm.analyzePrescription(text)
                }
            }
        }
    }

    LaunchedEffect(Unit) { vm.loadModel() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackHost.showSnackbar(it, duration = SnackbarDuration.Long)
            vm.dismissError()
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = state.selectedLanguage,
            onSelect = { 
                vm.setLanguage(it)
                showLanguageDialog = false 
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Dr. AI Conversations", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                    Button(
                        onClick = { 
                            vm.startNewConversation()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("New Chat")
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(convList) { conv ->
                            NavigationDrawerItem(
                                label = { Text(text = conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = conv.id == state.currentConversationId,
                                onClick = {
                                    vm.loadConversation(conv.id)
                                    scope.launch { drawerState.close() }
                                },
                                badge = {
                                    IconButton(onClick = { vm.deleteConversation(conv.id) }) {
                                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackHost) },
            topBar = {
                DrAITopBar(
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewChat = { vm.startNewConversation() },
                    onSettingsClick = { modelPicker.launch(arrayOf("*/*")) },
                    onDefaultModelClick = { vm.setCustomModel(null) },
                    onLanguageClick = { showLanguageDialog = true },
                    selectedLanguage = state.selectedLanguage
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!state.modelReady) {
                    LoadingScreen(state.statusText)
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        MessageList(state.messages, state.isLoading || isOcrRunning, Modifier.fillMaxSize())
                        
                        if (voiceAssistant.isSpeakingVoice.value) {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                                Button(
                                    onClick = { voiceAssistant.stopSpeaking() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.VolumeOff, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Stop Voice", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    
                    InputBar(
                        enabled = state.modelReady,
                        isLoading = state.isLoading || isOcrRunning,
                        onSend = { 
                            voiceAssistant.stopSpeaking()
                            vm.sendMessage(it) 
                        },
                        onStop = {
                            vm.stopGeneration()
                            voiceAssistant.stopSpeaking()
                        },
                        onScanPrescription = { 
                            voiceAssistant.stopSpeaking()
                            prescriptionPicker.launch("image/*") 
                        },
                        onMicClick = {
                            voiceAssistant.stopSpeaking()
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                if (isListening) {
                                    isListening = false
                                } else {
                                    isListening = true
                                    val locale = getLocale(state.selectedLanguage)
                                    val (askPrefix, replyPrefix) = getPrefixes(state.selectedLanguage)
                                    
                                    voiceAssistant.setLanguage(locale)
                                    scope.launch {
                                        kotlinx.coroutines.delay(300)
                                        voiceAssistant.startListening(
                                            locale = locale,
                                            onResult = { text ->
                                                isListening = false
                                                voiceAssistant.speak("$askPrefix: $text")
                                                vm.sendMessage(text) { reply ->
                                                    voiceAssistant.speak("$replyPrefix: $reply")
                                                }
                                            },
                                            onError = { err -> 
                                                isListening = false
                                                scope.launch { snackHost.showSnackbar(err) }
                                            }
                                        )
                                    }
                                }
                            } else {
                                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        isListening = isListening
                    )
                    DisclaimerBar()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrAITopBar(
    onMenuClick: () -> Unit,
    onNewChat: () -> Unit,
    onSettingsClick: () -> Unit,
    onDefaultModelClick: () -> Unit,
    onLanguageClick: () -> Unit,
    selectedLanguage: String
) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("Dr", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text("Dr AI", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Medical Assistant", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Outlined.Menu, null) } },
        actions = {
            IconButton(onClick = onNewChat) { Icon(Icons.Outlined.Add, null) }
            IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, null) }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("Language ($selectedLanguage)") }, onClick = { showMenu = false; onLanguageClick() }, leadingIcon = { Icon(Icons.Outlined.Language, null) })
                DropdownMenuItem(text = { Text("Settings (Choose Model)") }, onClick = { showMenu = false; onSettingsClick() }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                DropdownMenuItem(text = { Text("Restore Default Model") }, onClick = { showMenu = false; onDefaultModelClick() }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
            }
        }
    )
}

@Composable
fun LanguageDialog(currentLanguage: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val languages = listOf("English", "Hindi", "Bengali", "Spanish", "Arabic", "Urdu", "French")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            Column {
                languages.forEach { lang ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(lang) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = (lang == currentLanguage), onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(lang)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

fun getPathFromUri(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File(context.filesDir, "custom_model.gguf")
        val outputStream = FileOutputStream(file)
        inputStream?.use { input -> outputStream.use { output -> input.copyTo(output) } }
        file.absolutePath
    } catch (e: Exception) { null }
}

fun getLocale(language: String): Locale {
    return when (language) {
        "Hindi" -> Locale("hi", "IN")
        "Bengali" -> Locale("bn", "IN")
        "Spanish" -> Locale("es", "ES")
        "Arabic" -> Locale("ar", "SA")
        "Urdu" -> Locale("ur", "PK")
        "French" -> Locale.FRANCE
        else -> Locale.US
    }
}

fun getPrefixes(language: String): Pair<String, String> {
    return when (language) {
        "Hindi" -> "आपने पूछा" to "आपके सवाल का जवाब है"
        "Bengali" -> "আপনি জিজ্ঞাসা করেছেন" to "আপনার প্রশ্নের উত্তর হল"
        "Spanish" -> "Preguntaste" to "La respuesta a tu pregunta es"
        "Arabic" -> "سألت" to "إجابة سؤالك هي"
        "Urdu" -> "آپ نے پوچھا" to "آپ کے سوال کا جواب ہے"
        "French" -> "Vous avez demandé" to "La réponse à votre question est"
        else -> "You asked" to "Your question answer is"
    }
}

@Composable
fun MessageList(messages: List<Message>, isLoading: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LazyColumn(state = listState, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        items(messages) { msg ->
            val color = if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val textColor = if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            val alignment = if (msg.isUser) Alignment.End else Alignment.Start
            val shape = if (msg.isUser) RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
            
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = alignment) {
                Surface(color = color, shape = shape, shadowElevation = 1.dp) {
                    Text(text = msg.text, modifier = Modifier.padding(14.dp), fontSize = 15.sp, color = textColor)
                }
            }
        }
        if (isLoading) {
            item {
                Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Dr. AI is processing...", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun InputBar(
    enabled: Boolean, 
    isLoading: Boolean,
    onSend: (String) -> Unit, 
    onStop: () -> Unit,
    onScanPrescription: () -> Unit, 
    onMicClick: () -> Unit, 
    isListening: Boolean
) {
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onScanPrescription, enabled = enabled && !isLoading) {
                Icon(Icons.Outlined.Description, "Scan", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onMicClick, enabled = enabled && !isLoading) {
                Icon(if (isListening) Icons.Outlined.Mic else Icons.Outlined.MicNone, "Mic", tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            
            OutlinedTextField(
                value = text, 
                onValueChange = { text = it }, 
                modifier = Modifier.weight(1f), 
                enabled = enabled && !isLoading,
                placeholder = { Text(if (isListening) "Listening..." else if (isLoading) "Processing..." else "Message Dr. AI...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.LightGray
                ),
                maxLines = 4
            )
            
            Spacer(Modifier.width(8.dp))
            
            if (isLoading) {
                IconButton(onClick = onStop, modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape)) {
                    Icon(Icons.Outlined.Stop, "Stop", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else {
                IconButton(
                    onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }, 
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, tint = if (text.isNotBlank()) Color.White else Color.Gray)
                }
            }
        }
    }
}

@Composable
fun DisclaimerBar() { 
    Text("⚕ Guidance only · Consult a professional doctor", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray) 
}

@Composable
fun LoadingScreen(status: String) { 
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(status, fontWeight = FontWeight.Medium)
        }
    } 
}
