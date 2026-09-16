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
import com.codex.quota.android.ui.CodexTokens
import kotlinx.coroutines.launch

@Composable
internal fun Stage03BScreen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Stage03BRunner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var result by remember { mutableStateOf<Stage03BWakeResult?>(null) }
  var running by remember { mutableStateOf(false) }
  val background = Brush.verticalGradient(
    listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.background),
  )

  Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text("Stage 03B", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold)
      Text("RPK 显式唤醒探针", fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(CodexTokens.Space.Xl))
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        border = BorderStroke(CodexTokens.Stroke.Hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(Modifier.padding(CodexTokens.Space.Lg)) {
          Text("测试前", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
          Text(
            "① 保持 Stage 03A2 Lua 真表盘在屏幕上。\n" +
              "② 不要手动打开 Stage 03A Probe。\n" +
              "③ 点击下面按钮。\n" +
              "④ 观察手环是否被切换到 Probe；等待测试结束后回到表盘，看是否显示 SEQ 44。",
            modifier = Modifier.padding(top = CodexTokens.Space.Md),
            fontSize = CodexTokens.Type.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = {
              running = true
              result = null
              scope.launch {
                result = runner.wakeAndSend(44)
                running = false
              }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl),
            shape = RoundedCornerShape(CodexTokens.Radius.Button),
          ) {
            Text(if (running) "正在唤醒并发送…" else "唤醒 RPK 并发送 44")
          }
        }
      }

      result?.let { value ->
        Card(
          modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg),
          shape = RoundedCornerShape(CodexTokens.Radius.Card),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        ) {
          Column(Modifier.padding(CodexTokens.Space.Lg)) {
            Text("LaunchWearApp: ${value.launchResult}", fontSize = CodexTokens.Type.Body)
            Text("Message: ${value.messageResult}", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Body)
            Text("Send attempts: ${value.sendAttempts}", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Supporting)
            Text(
              "Overall: ${value.overall}",
              modifier = Modifier.padding(top = CodexTokens.Space.Md),
              fontSize = CodexTokens.Type.SectionTitle,
              fontWeight = FontWeight.SemiBold,
              color = if (value.messageResult == "ACK_PERSISTED") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
          }
        }
        Text(
          "请另外记录两件事：① 手环是否自动跳进 Probe；② 回到 Lua 表盘后是否显示 SEQ 44。",
          modifier = Modifier.padding(top = CodexTokens.Space.Lg),
          fontSize = CodexTokens.Type.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = { copyReport(value.report()) },
          modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg),
        ) { Text("复制脱敏报告") }
      }
    }
  }
}
