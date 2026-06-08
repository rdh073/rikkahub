package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.contextTokens
import me.rerere.ai.core.resolveReserveOutput
import me.rerere.ai.core.tokenPressure
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelRegistry.getContextWindowForModel
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation

// 消息节点数量警告阈值（次级条件，保持告警罕见）。
const val MESSAGE_NODE_WARNING_THRESHOLD = 768

// 告警触发的上下文窗口占用比例（design #193 R3）。取代旧的硬编码 300k 常量：那对窗口非 ~300k 的每个模型
// 都是错的。这里用模型相对比例，且 >= 自动压缩的软阈值默认（0.8），即“压缩本应早已介入，现在确实危险”。
const val CONVERSATION_SIZE_WARNING_FRACTION = 0.9f

data class ConversationSizeInfo(
    val nodeCount: Int,
    val contextTokens: Int,
    val contextWindow: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedTokenThreshold: Boolean,
    val showWarning: Boolean
)

/**
 * 纯函数：会话体量告警的判定核心（design #193 Stage 1，R3）。抽出以便 JVM 单测（无 Compose/Android 依赖），
 * 使 P5（告警与触发器共用同一 [tokenPressure]、对相同输入在 over-threshold 上不可能分歧）测的是生产真正
 * 使用的接线，而非手抄镜像。
 *
 * 旧实现用最后一条 assistant 消息的 promptTokens 对照硬编码 300k 触发——token 无关于模型、且 300k 对窗口
 * 非 ~300k 的模型都是错的。改为：以与触发器完全相同的 [contextTokens]（真实 totalTokens 锚 + 待发送轮估算）
 * 对照模型相对窗口（[getContextWindowForModel]）经 [tokenPressure] 求得占用比例。
 *
 * 告警仍是“危险且罕见”的最后手段，故保留与节点数的合取：占用越过 [CONVERSATION_SIZE_WARNING_FRACTION]
 * **且**节点数越过 [MESSAGE_NODE_WARNING_THRESHOLD] 才弹窗。[model] 为 null（未配置对话模型）时退回保守
 * 默认窗口。
 */
internal fun computeConversationSizeInfo(
    nodeCount: Int,
    messages: List<UIMessage>,
    model: Model?,
    assistantMaxTokens: Int?,
): ConversationSizeInfo {
    val window = model?.let { getContextWindowForModel(it) } ?: ModelRegistry.DEFAULT_CONTEXT_WINDOW
    val pressure = tokenPressure(
        contextTokens = contextTokens(messages),
        window = window,
        thresholdFraction = CONVERSATION_SIZE_WARNING_FRACTION,
        // 必须与触发器（ChatService.maybeAutoCompact）用同一 reserve，否则 hardOver 的 allowedTokens 不同，
        // 两条路径在“小窗口 + 大 maxTokens”下会对 hardOver 分歧——破坏单一事实源（P5）。
        reserveOutput = resolveReserveOutput(assistantMaxTokens),
    )
    val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
    // 与自动压缩触发器完全相同的越界谓词：soft（占用比例）或 hard（绝对安全护栏）任一越过即视为危险。
    // 不仅用 softOver——否则在“小窗口 + 大 reserve”下 hard 护栏会先于 soft 触发（allowedTokens < 软线），
    // 形成介于二者之间的区间：触发器会压缩而告警却沉默。共用 soft||hard 才是真正的单一事实源（P5）。
    val exceedTokenThreshold = pressure.softOver || pressure.hardOver
    return ConversationSizeInfo(
        nodeCount = nodeCount,
        contextTokens = pressure.contextTokens,
        contextWindow = pressure.window,
        exceedNodeCountThreshold = exceedNodeCountThreshold,
        exceedTokenThreshold = exceedTokenThreshold,
        showWarning = exceedNodeCountThreshold && exceedTokenThreshold
    )
}

@Composable
fun rememberConversationSizeInfo(
    conversation: Conversation,
    model: Model?,
    assistantMaxTokens: Int?,
): ConversationSizeInfo {
    return remember(conversation.messageNodes, model, assistantMaxTokens) {
        computeConversationSizeInfo(
            nodeCount = conversation.messageNodes.size,
            messages = conversation.currentMessages,
            model = model,
            assistantMaxTokens = assistantMaxTokens,
        )
    }
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
