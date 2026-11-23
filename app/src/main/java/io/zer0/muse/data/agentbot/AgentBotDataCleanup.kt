package io.zer0.muse.data.agentbot

import androidx.room.withTransaction
import io.zer0.muse.data.session.MuseDb

class AgentBotDataCleanup(private val database: MuseDb) {

    suspend fun deleteForSession(sessionId: String) {
        database.withTransaction {
            deleteSessionRows(sessionId)
        }
    }

    suspend fun deleteForAssistant(assistantId: String) {
        database.withTransaction {
            deleteAssistantRows(assistantId)
        }
    }

    suspend fun deleteAssistantRowsInCurrentTransaction(assistantId: String) {
        deleteAssistantRows(assistantId)
    }

    suspend fun deleteSessionRowsInCurrentTransaction(sessionId: String) {
        val tasks = database.botTaskDao().listByOriginSession(sessionId)
        deleteTasks(tasks)
        database.botEventDao().deleteBySession(sessionId)
        database.approvalRequestDao().deleteBySession(sessionId)
        database.publicationOutboxDao().deleteBySession(sessionId)
        database.botAnnotationDao().deleteBySession(sessionId)
        database.botMessageCardDao().deleteBySession(sessionId)
        database.botBindingDao().deleteBySession(sessionId)
    }

    private suspend fun deleteSessionRows(sessionId: String) {
        deleteSessionRowsInCurrentTransaction(sessionId)
    }

    private suspend fun deleteAssistantRows(assistantId: String) {
        val tasks = database.botTaskDao().listByAssistant(assistantId)
        deleteTasks(tasks)
        database.botBindingDao().listByAssistant(assistantId)
            .map(BotBindingEntity::canonicalAgentSessionId)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { sessionId -> deleteSessionRowsInCurrentTransaction(sessionId) }
        database.botRoutineDao().deleteByBot(assistantId)
        database.botBindingDao().deleteByAssistant(assistantId)
    }

    private suspend fun deleteTasks(tasks: List<BotTaskEntity>) {
        if (tasks.isEmpty()) return
        val taskIds = tasks.map(BotTaskEntity::taskId)
        val runIds = taskIds.flatMap { taskId ->
            database.taskRunDao().listByTask(taskId).map(TaskRunEntity::runId)
        }
        if (runIds.isNotEmpty()) database.taskStepDao().deleteByRuns(runIds)
        database.taskRunDao().deleteByTasks(taskIds)
        database.botEventDao().deleteByTasks(taskIds)
        database.approvalRequestDao().deleteByTasks(taskIds)
        database.botMessageCardDao().deleteByTasks(taskIds)
        database.botTaskDao().deleteByIds(taskIds)
    }
}
