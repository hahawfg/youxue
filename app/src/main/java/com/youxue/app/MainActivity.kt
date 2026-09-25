package com.youxue.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.youxue.app.ocr.OcrSessionStore
import com.youxue.app.ui.Routes
import com.youxue.app.ui.dictation.DictationRunningScreen
import com.youxue.app.ui.dictation.DictationSetupScreen
import com.youxue.app.ui.dictation.DictationResultScreen
import com.youxue.app.ui.home.HomeScreen
import com.youxue.app.ui.ocr.OcrCropScreen
import com.youxue.app.ui.ocr.OcrPreviewScreen
import com.youxue.app.ui.plan.PlanDetailScreen
import com.youxue.app.ui.plan.PlanScreen
import com.youxue.app.ui.plan.PlanViewModel
import com.youxue.app.ui.task.TaskScreen
import com.youxue.app.ui.theme.YouxueTheme
import com.youxue.app.util.ImageSource

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YouxueTheme {
                YouxueApp()
            }
        }
    }
}

@Composable
private fun YouxueApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenDictation = { navController.navigate(Routes.DICTATION_SETUP) },
                onOpenTasks = { navController.navigate(Routes.TASKS) },
                onOpenPlans = { navController.navigate(Routes.PLANS) },
            )
        }

        composable(Routes.TASKS) {
            TaskScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PLANS) { entry ->
            PlanScreen(
                onBack = { navController.popBackStack() },
                onOpenDetail = { planId -> navController.navigate(Routes.planDetail(planId)) },
                viewModel = planViewModel(entry),
            )
        }

        composable(
            route = Routes.PLAN_DETAIL_ROUTE,
            arguments = listOf(navArgument(Routes.ARG_PLAN_ID) { type = NavType.StringType }),
        ) { entry ->
            // 详情页与列表页**共用同一个 ViewModel 实例**，owner 取列表页那个 entry。
            // 不这么做的话两个路由各持一份状态，在详情页打卡、返回列表页会看到
            // 过期的勾选状态（viewModel() 默认作用域就是当前 NavBackStackEntry）。
            // 计划页与详情页始终成对出现，所以这个 entry 一定在栈上。
            val parent = remember(entry) { navController.getBackStackEntry(Routes.PLANS) }
            PlanDetailScreen(
                planId = entry.arguments?.getString(Routes.ARG_PLAN_ID).orEmpty(),
                onBack = { navController.popBackStack() },
                viewModel = planViewModel(parent),
            )
        }

        composable(Routes.DICTATION_SETUP) {
            DictationSetupScreen(
                onBack = { navController.popBackStack() },
                onStarted = {
                    navController.navigate(Routes.DICTATION_RUNNING) {
                        // 听写中按返回不该回到录入页，避免状态错乱
                        popUpTo(Routes.DICTATION_SETUP) { inclusive = true }
                    }
                },
                onOcrImageReady = { source: ImageSource ->
                    // 图片来源存进交接点，裁剪页自己去解码。
                    // 位图不跟着导航走——十几 MB 的对象塞进单例是个泄漏点
                    OcrSessionStore.begin(source)
                    navController.navigate(Routes.OCR_CROP)
                },
            )
        }

        composable(Routes.OCR_CROP) {
            OcrCropScreen(
                onBack = {
                    // 从裁剪页退回录入页说明这次识字放弃了，把来源一起清掉
                    OcrSessionStore.clear()
                    navController.popBackStack()
                },
                onRecognize = { navController.navigate(Routes.OCR_PREVIEW) },
            )
        }

        composable(Routes.OCR_PREVIEW) {
            OcrPreviewScreen(
                // 退回裁剪页时**不清**：裁剪页还要用同一张图重新框一次
                onBack = { navController.popBackStack() },
                onFilled = {
                    // 回到录入页并弹掉裁剪、预览两页。
                    // 录入页的 ViewModel 一直活在栈上，所以它观察到的
                    // OcrSessionStore.filled 就是这次识别的结果
                    navController.popBackStack(Routes.DICTATION_SETUP, inclusive = false)
                },
            )
        }

        composable(Routes.DICTATION_RUNNING) {
            DictationRunningScreen(
                onFinished = {
                    navController.navigate(Routes.DICTATION_RESULT) {
                        // 结果页要留在栈上，用户可能从结果页返回再听一遍，
                        // 所以这里只弹掉听写页自己
                        popUpTo(Routes.DICTATION_RUNNING) { inclusive = true }
                    }
                },
                onExit = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DICTATION_RESULT) {
            DictationResultScreen(
                // 「再听一遍」回听写页，听写页会从草稿重新开始。
                // 这样两个页面各自持有干净的 ViewModel，不用跨路由共享实例。
                onRestart = {
                    navController.navigate(Routes.DICTATION_RUNNING) {
                        popUpTo(Routes.DICTATION_RESULT) { inclusive = true }
                    }
                },
                onNewWords = {
                    navController.navigate(Routes.DICTATION_SETUP) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}

/**
 * 计划页与详情页共用的 ViewModel。
 *
 * [owner] 由调用方指定：列表页传自己那个 entry，详情页传**列表页**的 entry，
 * 于是两个路由拿到同一个实例。工厂每次都新建一个 lambda，但 `viewModel()`
 * 是按 owner + key 缓存实例的，重复调用不会创建出第二个 ViewModel。
 */
@Composable
private fun planViewModel(owner: ViewModelStoreOwner): PlanViewModel = viewModel(
    viewModelStoreOwner = owner,
    factory = PlanViewModel.factory(YouxueApplication.get().planRepository),
)
