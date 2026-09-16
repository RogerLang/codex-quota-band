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
internal fun Stage03ENotifyScreen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Stage03ENotifyRunner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var result by remember { mutableStateOf<Stage03ENotifyResult?>(null) }
  var running by remember { mutableStateOf(false) }
  val background = Brush.verticalGradient(
    listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.background),
  )

  Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text("Stage 03E", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold)
      Text("系统通知存储探针", fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(CodexTokens.Space.Xl))

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(Modifier.padding(CodexTokens.Space.Lg)) {
          Text("测试说明", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold)
          Text(
            "① 保持 Stage 03E Lua 表盘。\n" +
              "② 不打开任何 Probe RPK。\n" +
              "③ 点击发送测试通知。\n" +
              "④ 手环收到通知后返回表盘，观察 MARKER 是否变为 FOUND。",
            modifier = Modifier.padding(top = CodexTokens.Space.Md),
            fontSize = CodexTokens.Type.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            "Title: ${Stage03ENotifyProbe.TITLE}\nBody: ${Stage03ENotifyProbe.BODY}",
            modifier = Modifier.padding(top = CodexTokens.Space.Lg),
            fontSize = CodexTokens.Type.Supporting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(
            onClick = {
              running = true
              result = null
              scope.launch {
                result = runner.sendProbe()
                running = false
              }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl),
            shape = RoundedCornerShape(CodexTokens.Radius.Button),
          ) {
            Text(if (running) "正在发送…" else "发送测试通知")
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
            Text("Node: ${value.nodeResult}", fontSize = CodexTokens.Type.Body)
            Text("NOTIFY permission: ${value.notifyPermission}", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Body)
            Text("Notify request: ${value.notifyRequest}", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Body)
            Text("Node attempt: ${value.nodeAttempt}", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Supporting)
          }
        }
        Text(
          "REQUESTED_CALLBACK_TIMEOUT 不等于通知失败；最终以手环是否收到该 marker 通知为准。",
          modifier = Modifier.padding(top = CodexTokens.Space.Lg),
          fontSize = CodexTokens.Type.Supporting,
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
