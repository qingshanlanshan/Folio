package com.qshlab.folio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The same files and fields as Folio on the Mac (projects/<p>/..., people/<id>.json), so the two can
// share data later.

@Serializable
data class StateUpdated(val at: Double = 0.0, val reason: String = "")

@Serializable
data class ProjectMeta(
    val name: String,
    val created: Double = 0.0,
    val stateUpdated: StateUpdated? = null,
    val stateError: String? = null,
    val builtin: Boolean = false,           // the Mac's "Unsorted" inbox
)

@Serializable
data class MeetingMeta(
    val title: String,
    val date: Double,
    val source: String = "import",          // "import" | "recording"
    val original: String? = null,
    val status: String = "queued",          // recording | queued | processing | ready | error | stopped
    val created: Double = 0.0,
    val error: String = "",
    val duration: Double = 0.0,
    val processedIn: Double = 0.0,
    val jobError: String = "",
    val tracks: List<String> = emptyList(),
    val titleSource: String? = null,        // Mac: "user" once you rename it (the summary no longer renames it)
)

@Serializable
data class Word(
    val text: String,
    val start: Double,
    val end: Double,
    val conf: Double = 1.0,
    val speaker: Int? = null,
    val edited: Boolean = false,
)

@Serializable
data class Suggestion(val person: String, val score: Double = 0.0, val name: String? = null)

@Serializable
data class Speaker(
    val name: String? = null,
    val person: String? = null,
    val auto: Boolean = false,
    val suggested: Suggestion? = null,
)

@Serializable
data class Transcript(
    val version: Int = 1,
    val language: String = "en",
    val asr: String = "parakeet",
    val edited: Boolean = false,
    val words: List<Word> = emptyList(),
    val speakers: Map<String, Speaker> = emptyMap(),
)

@Serializable
data class VoiceSource(val project: String, val meeting: String, val speaker: String, val added: Double = 0.0)

@Serializable
data class Person(
    val id: String = "",
    val name: String,
    val aliases: List<String> = emptyList(),
    val notes: String = "",
    val projects: List<String> = emptyList(),
    val me: Boolean = false,
    val voices: List<VoiceSource> = emptyList(),
    val created: Double = 0.0,
)

@Serializable
data class Fix(
    val id: String,
    val first: Int,
    val last: Int,
    val old: String,
    val new: String,
    val why: String = "",
    val source: String,                     // glossary | audio | text
    val status: String = "pending",         // pending | accepted | rejected | stale
)

@Serializable
data class FixRun(val mode: String, val at: Double, val found: Int)

@Serializable
data class Corrections(val runs: List<FixRun> = emptyList(), val items: List<Fix> = emptyList())

@Serializable
data class Topic(val title: String = "", val summary: String = "", val start: String = "")

@Serializable
data class ActionItem(val task: String = "", val owner: String = "", val due: String = "")

@Serializable
data class TodoUpdate(val id: String = "", val status: String = "", val note: String = "")

@Serializable
data class ProgressItem(val area: String = "", val update: String = "")

@Serializable
data class Summary(
    val title: String = "",
    val overview: String = "",
    val topics: List<Topic> = emptyList(),
    val decisions: List<String> = emptyList(),
    @SerialName("action_items") val actionItems: List<ActionItem> = emptyList(),
    @SerialName("todo_updates") val todoUpdates: List<TodoUpdate> = emptyList(),
    val progress: List<ProgressItem> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
    val at: Double = 0.0,
    val writer: String = "",
    val updatedState: Boolean = true,
    val edited: Boolean = false,            // changed by hand
)

@Serializable
data class TodoFrom(val meeting: String = "")

@Serializable
data class TodoClosed(val meeting: String = "", val note: String = "")

@Serializable
data class Todo(
    val id: String,
    val text: String,
    val owner: String = "",
    val person: String? = null,
    val due: String = "",
    val status: String = "open",
    val from: TodoFrom? = null,
    val closedBy: TodoClosed? = null,
    val created: Double = 0.0,
    val updated: Double = 0.0,
    val touched: Boolean = false,
)

@Serializable
data class Todos(val items: List<Todo> = emptyList())

@Serializable
data class StateVersion(val file: String, val at: Double, val reason: String = "")

@Serializable
data class StateIndex(val versions: List<StateVersion> = emptyList())
