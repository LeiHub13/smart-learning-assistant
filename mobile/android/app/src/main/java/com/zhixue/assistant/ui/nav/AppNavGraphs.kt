package com.zhixue.assistant.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.ui.screens.auth.BillingScreen
import com.zhixue.assistant.ui.screens.auth.LoginScreen
import com.zhixue.assistant.ui.screens.auth.ProfileScreen
import com.zhixue.assistant.ui.screens.auth.ServerSettingsScreen
import com.zhixue.assistant.ui.screens.auth.WelcomeScreen
import com.zhixue.assistant.ui.screens.chat.ChatScreen
import com.zhixue.assistant.ui.screens.course.CourseDetailScreen
import com.zhixue.assistant.ui.screens.course.DocPreviewScreen
import com.zhixue.assistant.ui.screens.course.DocsScreen
import com.zhixue.assistant.ui.screens.course.GenerateScreen
import com.zhixue.assistant.ui.screens.course.GraphScreen
import com.zhixue.assistant.ui.screens.course.HubScreen
import com.zhixue.assistant.ui.screens.course.ManageScreen
import com.zhixue.assistant.ui.screens.hall.HallScreen
import com.zhixue.assistant.ui.screens.insight.AdminScreen
import com.zhixue.assistant.ui.screens.insight.DashboardScreen
import com.zhixue.assistant.ui.screens.insight.NoteEditorScreen
import com.zhixue.assistant.ui.screens.insight.NotesScreen
import com.zhixue.assistant.ui.screens.insight.PlanScreen
import com.zhixue.assistant.ui.screens.insight.ProgressScreen
import com.zhixue.assistant.ui.screens.insight.ReportDetailScreen
import com.zhixue.assistant.ui.screens.insight.ReportScreen
import com.zhixue.assistant.ui.screens.insight.SearchScreen
import com.zhixue.assistant.ui.screens.learn.ExamReportScreen
import com.zhixue.assistant.ui.screens.learn.ExamScreen
import com.zhixue.assistant.ui.screens.learn.ExamTakeScreen
import com.zhixue.assistant.ui.screens.learn.FavoritesScreen
import com.zhixue.assistant.ui.screens.learn.FlashcardsScreen
import com.zhixue.assistant.ui.screens.learn.MistakeBookScreen
import com.zhixue.assistant.ui.screens.learn.PracticeReportScreen
import com.zhixue.assistant.ui.screens.learn.PracticeScreen
import com.zhixue.assistant.ui.screens.learn.QuestionBankScreen
import com.zhixue.assistant.ui.screens.learn.QuestionEditorScreen
import com.zhixue.assistant.ui.screens.notify.NotificationsScreen

@Composable
fun GuestNavGraph(navController: NavHostController, container: AppContainer) {
    NavHost(navController = navController, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) { WelcomeScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
    }
}

@Composable
fun MainNavGraph(navController: NavHostController, container: AppContainer) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { DashboardScreen() }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType; defaultValue = Routes.NO_SESSION },
                navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
                navArgument(Routes.ARG_KB_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
                navArgument(Routes.ARG_SCOPE) { type = NavType.StringType; defaultValue = "single" },
                navArgument(Routes.ARG_ASK) { type = NavType.StringType; defaultValue = "" },
                navArgument(Routes.ARG_REL) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ChatScreen(
                sessionId = entry.arguments?.getLong(Routes.ARG_SESSION_ID) ?: Routes.NO_SESSION,
                courseId = entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: Routes.NO_COURSE,
                kbId = entry.arguments?.getLong(Routes.ARG_KB_ID) ?: Routes.NO_COURSE,
                scope = entry.arguments?.getString(Routes.ARG_SCOPE) ?: "single",
                ask = entry.arguments?.getString(Routes.ARG_ASK).orEmpty(),
                rel = entry.arguments?.getString(Routes.ARG_REL).orEmpty(),
            )
        }

        composable(
            route = "${Routes.HALL}?${Routes.ARG_PEER_ID}={${Routes.ARG_PEER_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_PEER_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
            ),
        ) { entry ->
            HallScreen(peerId = entry.arguments?.getLong(Routes.ARG_PEER_ID) ?: Routes.NO_COURSE)
        }

        composable(Routes.GENERATE) { GenerateScreen() }

        composable(
            route = Routes.PRACTICE,
            arguments = listOf(
                navArgument(Routes.ARG_MODE) { type = NavType.StringType; defaultValue = "normal" },
                navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
                navArgument(Routes.ARG_KP) { type = NavType.StringType; defaultValue = "" },
                navArgument(Routes.ARG_AUTO) { type = NavType.StringType; defaultValue = "false" },
                navArgument(Routes.ARG_IDS) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PracticeScreen(
                mode = entry.arguments?.getString(Routes.ARG_MODE) ?: "normal",
                courseId = entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: Routes.NO_COURSE,
                kp = entry.arguments?.getString(Routes.ARG_KP).orEmpty(),
                auto = entry.arguments?.getString(Routes.ARG_AUTO) == "true",
                ids = entry.arguments?.getString(Routes.ARG_IDS).orEmpty(),
            )
        }

        composable(
            route = Routes.PRACTICE_REPORT,
            arguments = listOf(navArgument(Routes.ARG_PRACTICE_ID) { type = NavType.LongType }),
        ) { entry -> PracticeReportScreen(entry.arguments?.getLong(Routes.ARG_PRACTICE_ID) ?: 0L) }

        composable(Routes.MISTAKES) { MistakeBookScreen() }
        composable(Routes.FAVORITES) { FavoritesScreen() }
        composable(Routes.EXAM) { ExamScreen() }

        composable(
            route = Routes.EXAM_TAKE,
            arguments = listOf(navArgument(Routes.ARG_EXAM_ID) { type = NavType.LongType }),
        ) { entry -> ExamTakeScreen(entry.arguments?.getLong(Routes.ARG_EXAM_ID) ?: 0L) }

        composable(
            route = Routes.EXAM_REPORT,
            arguments = listOf(navArgument(Routes.ARG_RECORD_ID) { type = NavType.LongType }),
        ) { entry -> ExamReportScreen(entry.arguments?.getLong(Routes.ARG_RECORD_ID) ?: 0L) }

        composable(Routes.BANK) { QuestionBankScreen() }

        composable(
            route = Routes.QUESTION_EDITOR,
            arguments = listOf(navArgument(Routes.ARG_QUESTION_ID) { type = NavType.LongType; defaultValue = -1L }),
        ) { entry -> QuestionEditorScreen(entry.arguments?.getLong(Routes.ARG_QUESTION_ID) ?: -1L) }

        composable(Routes.FLASHCARDS) { FlashcardsScreen() }
        composable(Routes.PROGRESS) { ProgressScreen() }
        composable(Routes.MANAGE) { ManageScreen() }

        composable(
            route = Routes.COURSE_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType }),
        ) { entry -> CourseDetailScreen(entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: 0L) }

        composable(Routes.HUB) { HubScreen() }

        composable(
            route = "${Routes.DOCS}?${Routes.ARG_COURSE_ID}={${Routes.ARG_COURSE_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
            ),
        ) { entry -> DocsScreen(entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: Routes.NO_COURSE) }

        composable(
            route = Routes.DOC_PREVIEW,
            arguments = listOf(
                navArgument(Routes.ARG_KB_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType },
            ),
        ) { entry ->
            DocPreviewScreen(
                kbId = entry.arguments?.getLong(Routes.ARG_KB_ID) ?: 0L,
                docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: 0L,
            )
        }

        composable(
            route = Routes.GRAPH,
            arguments = listOf(
                navArgument(Routes.ARG_KB_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
            ),
        ) { entry ->
            GraphScreen(
                kbId = entry.arguments?.getLong(Routes.ARG_KB_ID) ?: 0L,
                courseId = entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: Routes.NO_COURSE,
            )
        }

        composable(Routes.PLANS) { PlanScreen() }
        composable(Routes.REPORTS) { ReportScreen() }

        composable(
            route = Routes.REPORT_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_REPORT_ID) { type = NavType.LongType }),
        ) { entry -> ReportDetailScreen(entry.arguments?.getLong(Routes.ARG_REPORT_ID) ?: 0L) }

        composable(
            route = "${Routes.NOTES}?${Routes.ARG_COURSE_ID}={${Routes.ARG_COURSE_ID}}",
            arguments = listOf(
                navArgument(Routes.ARG_COURSE_ID) { type = NavType.LongType; defaultValue = Routes.NO_COURSE },
            ),
        ) { entry -> NotesScreen(entry.arguments?.getLong(Routes.ARG_COURSE_ID) ?: Routes.NO_COURSE) }

        composable(
            route = Routes.NOTE_EDITOR,
            arguments = listOf(navArgument(Routes.ARG_NOTE_ID) { type = NavType.LongType; defaultValue = -1L }),
        ) { entry -> NoteEditorScreen(entry.arguments?.getLong(Routes.ARG_NOTE_ID) ?: -1L) }

        composable(Routes.PROFILE) { ProfileScreen() }
        composable(Routes.BILLING) { BillingScreen() }
        composable(Routes.ADMIN) { AdminScreen() }
        composable(Routes.SERVER) { ServerSettingsScreen() }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen() }

        composable(
            route = "${Routes.SEARCH}?${Routes.ARG_QUERY}={${Routes.ARG_QUERY}}",
            arguments = listOf(navArgument(Routes.ARG_QUERY) { type = NavType.StringType; defaultValue = "" }),
        ) { entry -> SearchScreen(entry.arguments?.getString(Routes.ARG_QUERY).orEmpty()) }
    }
}

/** 供 AppRoot 判断「这条目的地是否需要返回按钮」。 */
fun NavGraphBuilder.noop() = Unit
