package com.example.gemma4app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemma4app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.UUID

data class Message(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString()
)

data class ChatUiState(
    val messages: List<Message>      = emptyList(),
    val isLoading: Boolean           = false,
    val modelReady: Boolean          = false,
    val statusText: String           = "Loading model…",
    val contextUsedPct: Float        = 0f,
    val turnsRemaining: Int          = 0,
    val errorMessage: String?        = null,
    val currentConversationId: String = UUID.randomUUID().toString(),
    val customModelPath: String?     = null,
    val selectedLanguage: String      = "English"
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val N_CTX             = 1024
    private val N_THREADS         = getPerformanceCores()
    private val N_BATCH           = 512
    private val MAX_TOKENS        = 512
    private val TEMPERATURE       = 0.3f
    private val TOP_P             = 0.85f
    private val TOP_K             = 30
    private val REPEAT_PENALTY    = 1.15f
    private val SYSTEM_TOKENS_EST = 200
    private val AVG_MSG_TOKENS    = 120

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val db = AppDatabase.get(app)
    val conversations: StateFlow<List<ConversationEntity>> = db.conversations

    private var generationJob: Job? = null

    private val SYSTEM_PROMPT = """
        You are "Dr. AI", a knowledgeable and compassionate medical assistant with expertise equivalent to an MBBS doctor. 
        Your goal is to help patients understand their health issues and medical documents in simple, clear language.

        When explaining conditions:
        - Use simple terms and explain "why" things are happening.
        - Provide actionable advice: "Dos" and "Don'ts".

        Guidelines:
        - Always respond in clear, concise, but thorough language.
        - If you don't know something, say so honestly.
        - Never make up information.
        - Always remind the patient that this is AI guidance and they should consult a real doctor for a final diagnosis.
    """.trimIndent()

    fun loadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, statusText = "Loading model…") }
            db.getAllConversations()
            try {
                val file = if (_state.value.customModelPath != null) {
                    File(_state.value.customModelPath!!)
                } else {
                    modelFile()
                }

                if (!file.exists()) {
                    _state.update { it.copy(
                        isLoading = false,
                        statusText = "Model not found",
                        errorMessage = "Model not found at: ${file.absolutePath}"
                    )}
                    return@launch
                }

                val ok = try {
                    LlamaBridge.loadModel(file.absolutePath, N_CTX, N_THREADS, N_BATCH)
                } catch (e: Exception) {
                    false
                } catch (e: Error) {
                    false
                }

                if (ok) {
                    val conv = db.getLatestConversation()
                    val convId = conv?.id ?: UUID.randomUUID().toString()
                    val savedMsgs = if (conv != null) db.getMessages(conv.id) else emptyList()

                    _state.update { it.copy(
                        isLoading = false,
                        modelReady = true,
                        statusText = "Ready",
                        messages = savedMsgs,
                        currentConversationId = convId,
                        contextUsedPct = calcContextPct(savedMsgs),
                        turnsRemaining = calcTurnsRemaining(savedMsgs)
                    )}
                } else {
                    _state.update { it.copy(
                        isLoading = false,
                        statusText = "Failed to load",
                        errorMessage = "Model failed to load."
                    )}
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, statusText = "Error", errorMessage = e.message) }
            }
        }
    }

    fun setCustomModel(path: String?) {
        _state.update { it.copy(customModelPath = path) }
        loadModel()
    }

    fun setLanguage(lang: String) {
        _state.update { it.copy(selectedLanguage = lang) }
    }

    fun sendMessage(userText: String, onComplete: ((String) -> Unit)? = null) {
        if (!_state.value.modelReady || userText.isBlank() || _state.value.isLoading) return

        val userMsg = Message(userText.trim(), isUser = true)
        val messagesWithUser = _state.value.messages + userMsg
        _state.update { it.copy(messages = messagesWithUser, isLoading = true, errorMessage = null) }

        viewModelScope.launch(Dispatchers.IO) { saveMessage(userMsg) }

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmed = trimHistory(messagesWithUser)
                val prompt  = buildPrompt(trimmed)

                val aiMessageIndex = messagesWithUser.size
                _state.update { it.copy(messages = messagesWithUser + Message("", isUser = false)) }

                LlamaBridge.generateStreaming(
                    prompt = prompt,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                    topP = TOP_P,
                    topK = TOP_K,
                    repeatPenalty = REPEAT_PENALTY,
                    callback = object : LlamaBridge.StreamingCallback {
                        override fun onToken(token: String) {
                            viewModelScope.launch {
                                val current = _state.value.messages.toMutableList()
                                if (aiMessageIndex < current.size) {
                                    val updatedMsg = current[aiMessageIndex].copy(
                                        text = current[aiMessageIndex].text + token
                                    )
                                    current[aiMessageIndex] = updatedMsg
                                    _state.update { it.copy(messages = current) }
                                }
                            }
                        }
                    }
                )

                val finalMsgs = _state.value.messages
                val botMsg = finalMsgs.last()
                saveMessage(botMsg)
                updateConversationMeta(finalMsgs)

                _state.update { it.copy(
                    isLoading = false,
                    contextUsedPct = calcContextPct(finalMsgs),
                    turnsRemaining = calcTurnsRemaining(finalMsgs)
                )}

                onComplete?.invoke(botMsg.text)

            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = e.message) }
            } finally {
                generationJob = null
            }
        }
    }

    fun stopGeneration() {
        LlamaBridge.stopGeneration()
        generationJob?.cancel()
        _state.update { it.copy(isLoading = false) }
        generationJob = null
    }

    private fun trimHistory(history: List<Message>): List<Message> {
        val budget = N_CTX - SYSTEM_TOKENS_EST - MAX_TOKENS
        var total  = 0
        val result = mutableListOf<Message>()
        for (msg in history.reversed()) {
            val est = (msg.text.length / 4) + 8
            if (total + est > budget) break
            result.add(0, msg)
            total += est
        }
        return if (result.isEmpty() && history.isNotEmpty()) listOf(history.last()) else result
    }

    private fun calcContextPct(history: List<Message>): Float {
        val budget  = (N_CTX - SYSTEM_TOKENS_EST - MAX_TOKENS).toFloat()
        val used    = history.sumOf { (it.text.length / 4) + 8 }.toFloat()
        return (used / budget).coerceIn(0f, 1f)
    }

    private fun calcTurnsRemaining(history: List<Message>): Int {
        val budget  = N_CTX - SYSTEM_TOKENS_EST - MAX_TOKENS
        val used    = history.sumOf { (it.text.length / 4) + 8 }
        return maxOf(0, (budget - used) / AVG_MSG_TOKENS)
    }

    private fun buildPrompt(history: List<Message>): String {
        val sb = StringBuilder()
        val langInstruction = "IMPORTANT: You must provide all medical advice and chat in ${_state.value.selectedLanguage}."
        sb.append("<start_of_turn>system\n$SYSTEM_PROMPT\n$langInstruction\n<end_of_turn>\n")
        for (msg in history) {
            val role = if (msg.isUser) "user" else "model"
            sb.append("<start_of_turn>$role\n${msg.text}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    fun startNewConversation() {
        _state.update { it.copy(
            messages = emptyList(),
            currentConversationId = UUID.randomUUID().toString(),
            contextUsedPct = 0f,
            turnsRemaining = calcTurnsRemaining(emptyList()),
            errorMessage = null
        )}
    }

    fun loadConversation(convId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = db.getMessages(convId)
            _state.update { it.copy(
                messages = msgs,
                currentConversationId = convId,
                contextUsedPct = calcContextPct(msgs),
                turnsRemaining = calcTurnsRemaining(msgs)
            )}
        }
    }

    fun deleteConversation(convId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteConversation(convId)
            if (convId == _state.value.currentConversationId) {
                startNewConversation()
            }
        }
    }

    fun dismissError() { _state.update { it.copy(errorMessage = null) } }

    fun analyzePrescription(rawText: String) {
        // Limit rawText to avoid context overflow and crashes
        val limitedText = if (rawText.length > 2000) {
            rawText.take(2000) + "..." 
        } else {
            rawText
        }

        val analysisPrompt = """
            Analyze this medical document text:
            ---
            $limitedText
            ---
            
            Identify and explain in simple terms:
            🏥 HEALTH ISSUE: (Condition explanation)
            📋 SYMPTOMS: (List symptoms found)
            💊 MEDICATIONS: (Name, Purpose, Schedule)
            ⚠️ DOs & DON'Ts: (Precautions)

            Be concise and use plain language.
        """.trimIndent()

        sendMessage(analysisPrompt)
    }

    private fun saveMessage(msg: Message) {
        val convId = _state.value.currentConversationId
        db.insertConversation(ConversationEntity(convId, "Conversation", System.currentTimeMillis(), System.currentTimeMillis(), 0))
        db.insertMessage(convId, msg)
    }

    private fun updateConversationMeta(msgs: List<Message>) {
        val title = msgs.firstOrNull { it.isUser }?.text?.take(40) ?: "New conversation"
        db.updateConversation(_state.value.currentConversationId, title, System.currentTimeMillis(), msgs.size)
    }

    private fun modelFile() = File(getApplication<Application>().filesDir, "model.gguf")

    private fun getPerformanceCores(): Int {
        val total = Runtime.getRuntime().availableProcessors()
        return if (total > 1) total - 1 else 1
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            try { LlamaBridge.freeModel() } catch (e: Exception) {}
        }
    }
}
