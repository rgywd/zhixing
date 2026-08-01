package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.agenda.AgendaPlanReminderGateway
import me.rerere.rikkahub.data.agenda.AgendaPlanReminderScheduler
import me.rerere.rikkahub.data.agenda.AgendaReminderScheduler
import me.rerere.rikkahub.data.agenda.AgendaTaskReminderGateway
import me.rerere.rikkahub.data.agenda.DeviceCalendarRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MonthlyLedgerRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.knowledge.KnowledgeSpaceService
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single<AgendaTaskReminderGateway> { AgendaReminderScheduler(get()) }

    single { AgendaTaskRepository(get(), get()) }

    single<AgendaPlanReminderGateway> { AgendaPlanReminderScheduler(get()) }

    single { AgendaPlanRepository(get(), get()) }

    single { MonthlyLedgerRepository(get()) }

    single { DeviceCalendarRepository(get()) }

    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                        target = "/skills",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                        target = "/tool_outputs",
                    ),
                    WorkspaceBindMount(
                        source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                        target = "/upload",
                    ),
                ),
            )
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        KnowledgeSpaceService(get(), get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}
