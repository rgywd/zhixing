package me.rerere.rikkahub.ui.pages.workflow

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import me.rerere.rikkahub.data.workflow.WorkNotConnectedException
import me.rerere.rikkahub.data.workflow.WorkSessionNotSyncedException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyDecryptionException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyRpcException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncException

internal fun Throwable.toWorkflowMessage(): String = when (this) {
    is WorkNotConnectedException -> "Happy 登录已失效，请到设置中重新连接"
    is WorkSessionNotSyncedException -> "该会话尚未同步，请刷新后重试"
    is HappyDecryptionException -> "此会话无法解密，其他会话不受影响"
    is HappySyncException -> when (statusCode) {
        401, 403 -> "Happy 登录已失效，请重新连接"
        409 -> "会话状态已变化，已重新同步"
        else -> "同步失败（$statusCode）"
    }
    is HappyRpcException.Offline -> "开发机或 Happy 中继当前离线"
    is HappyRpcException.Rejected -> "远程操作被拒绝：${message.orEmpty().substringAfterLast(':').trim()}"
    is TimeoutCancellationException -> "远程操作超时，请确认开发机在线"
    is IOException -> "网络连接失败，请稍后重试"
    else -> "操作失败，请稍后重试"
}
