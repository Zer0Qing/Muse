package io.zer0.muse.conversation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import io.mockk.mockk

class ConversationKoinAssemblyTest {
    @Test
    fun `conversation production dependencies are registered in Koin`() {
        val source = read("AppChatModule.kt")

        assertTrue(source.contains("RoomConversationStore(get())"))
        assertTrue(source.contains("RoomConversationWindowSource(get(), get())"))
        assertTrue(source.contains("RoomConversationMessageSource(get())"))
        assertTrue(source.contains("RoomSubmitCommandPort(get(), get())"))
        assertTrue(source.contains("LegacyConversationMigrator(get(), get())"))
        assertTrue(source.contains("SelectionSnapshotResolver(get(), get())"))
    }

    @Test
    fun `conversation view model receives a session scoped controller factory`() {
        val source = read("AppChatModule.kt")

        assertTrue(source.contains("ConversationViewModel("))
        assertTrue(source.contains("controllerFactory ="))
        assertTrue(source.contains("pageModel: ConversationPageModel"))
        assertTrue(source.contains("selectionResolver ="))
    }

    @Test
    fun `minimal production graph resolves conversation view model`() {
        val database = mockk<io.zer0.muse.data.session.MuseDb>(relaxed = true)
        val sessionRepository = mockk<io.zer0.muse.data.session.SessionRepository>(relaxed = true)
        val koinApp =
            koinApplication {
                modules(
                    module {
                        single { database }
                        single { sessionRepository }
                    },
                    io.zer0.muse.appChatModule,
                )
            }

        try {
            koinApp.koin.get<io.zer0.muse.conversation.ui.ConversationViewModel>()
        } finally {
            koinApp.close()
        }
    }

    private fun read(file: String): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/$file"),
                Path.of("app/src/main/java/io/zer0/muse/$file"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $file from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
