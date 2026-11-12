package com.villamediana.fio.mind

import com.villamediana.fio.R
import com.villamediana.fio.data.Kind
import com.villamediana.fio.local.LocalEngine
import com.villamediana.fio.local.LocalModel
import com.villamediana.fio.settings.Lang
import com.villamediana.fio.settings.t

/**
 * Organizing on the phone ([LocalBrain]): Omni looks at the photo (what can be seen, for the
 * search), d1 answers the questions about what she said, LFM3-1.7B writes only what d1 decided.
 * One model in memory at a time; they come in this order.
 */
class LocalOrganizer(private val engine: LocalEngine, private val model: LocalModel) {
    suspend fun plan(talk: Talk): Plan {
        val tasks = (listOfNotNull(talk.focus?.takeIf { it.kindEnum == Kind.TASK }) + talk.openTasks)
            .distinctBy { it.id }
            .take(LocalBrain.MAX_TASKS)
            .map { LocalBrain.taskLine(it, talk.now, talk.zone) }

        // What can be seen in the photo, in a sentence or two: what the search finds it by.
        val seen = talk.image?.let { image ->
            runCatching {
                engine.generate(
                    model.files,
                    Brain.chatml(LocalBrain.describe(Lang.current.label), LocalEngine.MEDIA),
                    grammar = null,
                    image = image,
                    maxTokens = 160,
                ).let(LocalEngine::clean).trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        // A photo with nothing written in it: it is kept in the day's diary, nothing else to do.
        if (talk.photo && talk.said.isBlank()) return Plan(reply = t(R.string.local_photo_kept), photoDescription = seen)

        val state = LocalBrain.state(talk.said, tasks)
        val questions = LocalBrain.questions(tasks)
        val answers = HashMap<String, DoubleArray>()
        suspend fun ask(q: LocalBrain.Question) {
            answers[q.id] = engine.decide(model.decider, q.type.key, maxOf(2, q.options.size), LocalBrain.labels(q)) { spellings ->
                LocalBrain.prompt(state, q, spellings)
            }
        }
        questions.forEach { ask(it) }
        var decision = LocalBrain.decide(questions, answers)
        if (decision.note) {
            ask(LocalBrain.SENSITIVE)
            decision = LocalBrain.decide(questions + LocalBrain.SENSITIVE, answers)
        }
        // What is written in a photo is neither her day (the photo is in the diary by itself) nor a question.
        if (talk.photo) decision = decision.copy(question = false, diary = false)

        val related = if (decision.question) talk.related.filter { it.kindEnum != Kind.TASK }.take(MAX_RELATED).map { Brain.line(it, talk.zone, talk.now, long = true) } else emptyList()
        val system = LocalBrain.writerSystem(decision, talk.now, talk.zone, tasks, related)
        val written = engine.generate(model.writer, Brain.chatml(system, talk.said.trim()), LocalBrain.grammar(decision), maxTokens = 900)
        val plan = LocalBrain.plan(
            decision,
            LocalBrain.filled(written),
            talk.now,
            talk.zone,
            diaryTitle = DayDiary.title(talk.now, talk.zone),
            photos = talk.photos,
            photoDescription = seen,
        )
        return if (talk.photo && plan.reply.isBlank()) plan.copy(reply = t(R.string.local_photo_kept)) else plan
    }

    private companion object {
        const val MAX_RELATED = 8
    }
}
