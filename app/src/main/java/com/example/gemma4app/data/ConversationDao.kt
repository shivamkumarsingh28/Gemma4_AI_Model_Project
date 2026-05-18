package com.example.gemma4app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.gemma4app.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

// Simplified entity classes for non-Room implementation
data class ConversationEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "drai.db", null, 1) {

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversations = _conversations.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT, createdAt INTEGER, updatedAt Long, messageCount INTEGER)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, conversationId TEXT, text TEXT, isUser INTEGER, timestamp INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun getAllConversations() {
        val list = mutableListOf<ConversationEntity>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM conversations ORDER BY updatedAt DESC", null)
        while (cursor.moveToNext()) {
            list.add(ConversationEntity(
                cursor.getString(0), cursor.getString(1),
                cursor.getLong(2), cursor.getLong(3), cursor.getInt(4)
            ))
        }
        cursor.close()
        _conversations.value = list
    }

    fun getMessages(convId: String): List<Message> {
        val list = mutableListOf<Message>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM messages WHERE conversationId = ? ORDER BY timestamp ASC", arrayOf(convId))
        while (cursor.moveToNext()) {
            list.add(Message(
                cursor.getString(2), cursor.getInt(3) == 1,
                cursor.getLong(4), UUID.randomUUID().toString()
            ))
        }
        cursor.close()
        return list
    }

    fun insertConversation(conv: ConversationEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("createdAt", conv.createdAt)
            put("updatedAt", conv.updatedAt)
            put("messageCount", conv.messageCount)
        }
        db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        getAllConversations()
    }

    fun insertMessage(convId: String, msg: Message) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("conversationId", convId)
            put("text", msg.text)
            put("isUser", if (msg.isUser) 1 else 0)
            put("timestamp", msg.timestamp)
        }
        db.insert("messages", null, values)
    }

    fun updateConversation(id: String, title: String, updatedAt: Long, count: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title)
            put("updatedAt", updatedAt)
            put("messageCount", count)
        }
        db.update("conversations", values, "id = ?", arrayOf(id))
        getAllConversations()
    }

    fun deleteConversation(id: String) {
        val db = writableDatabase
        db.delete("conversations", "id = ?", arrayOf(id))
        db.delete("messages", "conversationId = ?", arrayOf(id))
        getAllConversations()
    }

    fun getLatestConversation(): ConversationEntity? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1", null)
        var conv: ConversationEntity? = null
        if (cursor.moveToFirst()) {
            conv = ConversationEntity(
                cursor.getString(0), cursor.getString(1),
                cursor.getLong(2), cursor.getLong(3), cursor.getInt(4)
            )
        }
        cursor.close()
        return conv
    }

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: AppDatabase(context).also { INSTANCE = it }
        }
    }
}
