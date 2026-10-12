package cn.flashcard.app

import androidx.room.*
import cn.flashcard.core.*
import kotlinx.coroutines.flow.Flow

private const val LIBRARY_FILTER =
    " FROM notes n WHERE n.deckId IN (:deckIds) " +
        "AND (:search = '' OR instr(lower(n.title),lower(:search)) > 0 " +
        "OR instr(lower(n.front),lower(:search)) > 0 OR instr(lower(n.back),lower(:search)) > 0 " +
        "OR EXISTS (SELECT 1 FROM note_tags nt JOIN tags t ON t.id=nt.tagId WHERE nt.noteId=n.id AND instr(lower(t.name),lower(:search)) > 0)) " +
        "AND (:tag = '' OR EXISTS (SELECT 1 FROM note_tags nt JOIN tags t ON t.id=nt.tagId WHERE nt.noteId=n.id AND t.name=:tag))"
private const val LIBRARY_PAGE =
    "SELECT n.*" +
        LIBRARY_FILTER +
        " ORDER BY n.createdAt DESC,n.id DESC LIMIT :limit OFFSET :offset"

@Entity(
    tableName = "decks",
    foreignKeys =
        [
            ForeignKey(
                entity = Deck::class,
                parentColumns = ["id"],
                childColumns = ["parentId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("parentId")],
)
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
)

@Entity(
    tableName = "notes",
    foreignKeys =
        [
            ForeignKey(
                entity = Deck::class,
                parentColumns = ["id"],
                childColumns = ["deckId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("deckId"), Index(value = ["signature"], unique = true)],
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val title: String,
    val front: String,
    val back: String,
    val tags: List<String>,
    val signature: String,
    val createdAt: Long,
)

@Entity(
    tableName = "cards",
    foreignKeys =
        [
            ForeignKey(
                entity = Note::class,
                parentColumns = ["id"],
                childColumns = ["noteId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("noteId"), Index("due"), Index(value = ["noteId", "cloze"], unique = true)],
)
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val cloze: Int = 0,
    val schedule: Schedule = Schedule(),
    val due: Long = 0,
    val buriedUntil: Long = 0,
    val revision: Int = 0,
)

@Entity(
    tableName = "reviews",
    foreignKeys =
        [
            ForeignKey(
                entity = Card::class,
                parentColumns = ["id"],
                childColumns = ["cardId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("cardId"), Index("timestamp")],
)
data class Review(
    @PrimaryKey val id: String,
    val cardId: Long,
    val rating: Int,
    val timestamp: Long,
    val wasNew: Boolean,
    val previous: Schedule,
    val next: Schedule,
)

@Entity(tableName = "preferences")
data class Preference(@PrimaryKey val id: Int = 1, val value: Settings = Settings())

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "note_tags",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys =
        [
            ForeignKey(
                entity = Note::class,
                parentColumns = ["id"],
                childColumns = ["noteId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = Tag::class,
                parentColumns = ["id"],
                childColumns = ["tagId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("tagId")],
)
data class NoteTag(val noteId: Long, val tagId: Long)

class Converters {
    @TypeConverter fun scheduleToJson(v: Schedule) = BackupCodec.json.encodeToString(v)

    @TypeConverter fun jsonToSchedule(v: String) = BackupCodec.json.decodeFromString<Schedule>(v)

    @TypeConverter fun tagsToJson(v: List<String>) = BackupCodec.json.encodeToString(v)

    @TypeConverter fun jsonToTags(v: String) = BackupCodec.json.decodeFromString<List<String>>(v)

    @TypeConverter fun settingsToJson(v: Settings) = BackupCodec.json.encodeToString(v)

    @TypeConverter fun jsonToSettings(v: String) = BackupCodec.json.decodeFromString<Settings>(v)
}

@Dao
interface FlashDao {
    @Query(LIBRARY_PAGE)
    suspend fun notesPage(
        deckIds: List<Long>,
        search: String,
        tag: String,
        limit: Int,
        offset: Int,
    ): List<Note>

    @Query(LIBRARY_PAGE)
    fun notesPageFlow(
        deckIds: List<Long>,
        search: String,
        tag: String,
        limit: Int,
        offset: Int,
    ): Flow<List<Note>>

    @Query("SELECT COUNT(*)" + LIBRARY_FILTER)
    suspend fun notesCount(deckIds: List<Long>, search: String, tag: String): Int

    @Query("SELECT COUNT(*)" + LIBRARY_FILTER)
    fun notesCountFlow(deckIds: List<Long>, search: String, tag: String): Flow<Int>

    @Query("SELECT * FROM decks ORDER BY id") fun decksFlow(): Flow<List<Deck>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC") fun notesFlow(): Flow<List<Note>>

    @Query("SELECT * FROM cards ORDER BY due,id") fun cardsFlow(): Flow<List<Card>>

    @Query("SELECT * FROM reviews ORDER BY timestamp") fun reviewsFlow(): Flow<List<Review>>

    @Query("SELECT * FROM preferences WHERE id=1") fun preferenceFlow(): Flow<Preference?>

    @Query("SELECT * FROM decks ORDER BY id") suspend fun decks(): List<Deck>

    @Query("SELECT * FROM notes") suspend fun notes(): List<Note>

    @Query("SELECT * FROM cards") suspend fun cards(): List<Card>

    @Query("SELECT * FROM reviews") suspend fun reviews(): List<Review>

    @Query("SELECT * FROM preferences WHERE id=1") suspend fun preference(): Preference?

    @Query("SELECT * FROM cards WHERE id=:id") suspend fun card(id: Long): Card?

    @Query("SELECT signature FROM notes") suspend fun signatures(): List<String>

    @Insert suspend fun addDeck(deck: Deck): Long

    @Insert suspend fun addNote(note: Note): Long

    @Insert suspend fun addCard(card: Card): Long

    @Insert suspend fun addReview(review: Review)

    @Insert suspend fun addTag(tag: Tag): Long

    @Insert suspend fun addNoteTag(noteTag: NoteTag)

    @Query("SELECT * FROM tags") suspend fun tags(): List<Tag>

    @Query("SELECT * FROM note_tags") suspend fun noteTags(): List<NoteTag>

    @Query("DELETE FROM note_tags WHERE noteId=:noteId") suspend fun clearNoteTags(noteId: Long)

    @Query("DELETE FROM tags") suspend fun clearTags()

    @Upsert suspend fun savePreference(preference: Preference)

    @Update suspend fun updateCard(card: Card)

    @Update suspend fun updateNote(note: Note)

    @Update suspend fun updateDeck(deck: Deck)

    @Query("DELETE FROM notes WHERE id=:id") suspend fun deleteNote(id: Long)

    @Query("DELETE FROM decks WHERE id=:id") suspend fun deleteDeck(id: Long)

    @Query("DELETE FROM cards WHERE noteId=:noteId AND cloze NOT IN (:indices)")
    suspend fun removeObsoleteCards(noteId: Long, indices: List<Int>)

    @Query("DELETE FROM reviews") suspend fun clearReviews()

    @Query("DELETE FROM cards") suspend fun clearCards()

    @Query("DELETE FROM notes") suspend fun clearNotes()

    @Query("DELETE FROM decks WHERE parentId IS NOT NULL") suspend fun clearChildren()

    @Query("DELETE FROM decks") suspend fun clearDecks()
}

@Database(
    entities =
        [
            Deck::class,
            Note::class,
            Card::class,
            Review::class,
            Preference::class,
            Tag::class,
            NoteTag::class,
        ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FlashDatabase : RoomDatabase() {
    abstract fun dao(): FlashDao
}
