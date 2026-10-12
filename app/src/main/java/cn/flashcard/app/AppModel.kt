package cn.flashcard.app

import android.app.Application
import androidx.lifecycle.*
import androidx.room.Room
import cn.flashcard.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AppModel(app: Application) : AndroidViewModel(app) {
    val repo =
        Repository(
            app,
            Room.databaseBuilder(app, FlashDatabase::class.java, "flashcards.db").build(),
        )
    val decks = repo.dao.decksFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = repo.dao.notesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cards = repo.dao.cardsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val reviews =
        repo.dao.reviewsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings =
        repo.dao
            .preferenceFlow()
            .map { it?.value ?: Settings() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val ready = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val replacing = MutableStateFlow(false)

    init {
        run {
            repo.initialize()
            ready.value = true
        }
    }

    fun run(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                message.value = e.message?.take(300) ?: "操作失败"
            }
        }
    }
}
