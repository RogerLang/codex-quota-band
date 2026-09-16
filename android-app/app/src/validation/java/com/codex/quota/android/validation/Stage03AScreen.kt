package com.codex.quota.android.validation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.quota.android.ui.CodexTokens
import kotlinx.coroutines.launch

@Composable
internal fun Stage03AScreen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Stage03ARunner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var result42 by remember { mutableStateOf<Stage03ASendResult?>(null) }
  var result43 by remember { mutableStateOf<Stage03ASendResult?>(null) }
  var sending by remember { mutableStateOf<Int?>(null) }
  val background = Brush.verticalGradient(
    listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.background),
  )

  fun send(sequence: Int) {
    sending = sequence
    scope.launch {
      val result = runner.sendState(sequence)
      if (sequence == 42) result42 = result else result43 = result
      sending = null
    }
  }

  fun report(): String = buildString {
    appendLine("CodexQuota Stage 03A")
    appendLine(result42?.reportLine() ?: "Send42: NOT_RUN")
    appendLine(result43?.reportLine() ?: "Send43: NOT_RUN")
    appendLine("Watchface42: USER_OBSERVATION")
    append("Watchface43: USER_OBSERVATION")
  }

  Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text("Stage 03A", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold)
      Text("真表盘数据桥探针", fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(CodexTokens.Space.Xl))
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(Modifier.padding(CodexTokens.Space.Lg)) {
          Text("测试顺序", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
          Text(
            "① 先打开 Stage 03A Probe 一次。\n② 回到 Stage 03A Lua 表盘。\n③ 发送 42，确认表盘显示 SEQ 42。\n④ 不再打开 Probe，直接发送 43。\n⑤ 抬腕看表盘是否变为 SEQ 43。",
            modifier = Modifier.padding(top = CodexTokens.Space.Md),
            fontSize = CodexTokens.Type.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = { send(42) }, enabled = sending == null,
            modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl),
            shape = RoundedCornerShape(CodexTokens.Radius.Button),
          ) { Text(if (sending == 42) "发送 42…" else "发送 42") }
          Text("42: ${result42?.result ?: "NOT_RUN"}", modifier = Modifier.padding(top = CodexTokens.Space.Sm))
          Button(
            onClick = { send(43) }, enabled = sending == null,
            modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg),
            shape = RoundedCornerShape(CodexTokens.Radius.Button),
          ) { Text(if (sending == 43) "发送 43…" else "发送 43") }
          Text("43: ${result43?.result ?: "NOT_RUN"}", modifier = Modifier.padding(top = CodexTokens.Space.Sm))
        }
      }
      Text(
        "ACK_PERSISTED 只证明 RPK 已写文件；最终是否成功以 Lua 表盘实际从 42 变成 43 为准。",
        modifier = Modifier.padding(top = CodexTokens.Space.Lg),
        fontSize = CodexTokens.Type.Supporting,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedButton(
        onClick = { copyReport(report()) }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg),
      ) { Text("复制脱敏报告") }
    }
  }
}
