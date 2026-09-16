package com.codex.quota.android.validation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
internal fun Stage02DScreen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Stage02DRunner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var progress by remember { mutableStateOf(Stage02DProgress()) }
  var diagnosis by remember { mutableStateOf("NOT_REPORTED") }
  var sending by remember { mutableStateOf(false) }
  var notificationSent by remember { mutableStateOf(false) }
  val background = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.background))

  fun start() {
    diagnosis = "NOT_REPORTED"
    notificationSent = false
    progress = Stage02DProgress(phase = Stage02DPhase.Running)
    scope.launch { progress = runner.run { progress = it.copy(bandDiagnosis = diagnosis) }.copy(bandDiagnosis = diagnosis) }
  }

  Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text("Stage 02D", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
      Text("Band 9 Pro XMS 诊断", fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(CodexTokens.Space.Xl))
      Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(Modifier.padding(CodexTokens.Space.Lg)) {
          if (progress.phase == Stage02DPhase.Idle) {
            Text("准备", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("确认小米运动健康已连接手环，Stage 02D Probe 已安装。", modifier = Modifier.padding(top = CodexTokens.Space.Md), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = ::start, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text("开始测试") }
          } else {
            Stage02DItem.entries.forEach { item -> DiagnosticRow(item.label, progress.result(item)) }
            Text("Node attempt: ${progress.nodeAttempt}/3", modifier = Modifier.padding(top = CodexTokens.Space.Sm), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = CodexTokens.Type.Supporting)
            DiagnosticRow("手环互联诊断", diagnosis)

            if (progress.phase == Stage02DPhase.Running) {
              Text("正在验证，请保持手机页面打开。", modifier = Modifier.padding(top = CodexTokens.Space.Lg), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = CodexTokens.Type.Supporting)
            }
            if (progress.phase == Stage02DPhase.OpenProbe) {
              Text("打开手环 Stage 02D Probe，读取“互联诊断”，再选择对应结果。", modifier = Modifier.padding(top = CodexTokens.Space.Lg), color = MaterialTheme.colorScheme.onSurface, fontSize = CodexTokens.Type.Body)
              listOf(listOf("OK", "TIMEOUT"), listOf("APP_UNINSTALLED", "OTHER")).forEach { options ->
                Row(Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Sm), horizontalArrangement = Arrangement.spacedBy(CodexTokens.Space.Sm)) {
                  options.forEach { option ->
                    OutlinedButton(onClick = { diagnosis = option; progress = progress.copy(bandDiagnosis = option) }, modifier = Modifier.weight(1f)) {
                      Text(option, fontSize = CodexTokens.Type.Supporting)
                    }
                  }
                }
              }
              Button(onClick = { runner.continueAfterOpeningProbe() }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text("手环已打开，继续") }
            }
            if (progress.phase == Stage02DPhase.TapBandButton) {
              Text("请在手环 Probe 中点击「测试手环→手机」。等待约 30 秒。", modifier = Modifier.padding(top = CodexTokens.Space.Lg), color = MaterialTheme.colorScheme.onSurface, fontSize = CodexTokens.Type.Body)
            }
            if (progress.phase == Stage02DPhase.ConfirmNotification) {
              if (!notificationSent) {
                Button(onClick = {
                  sending = true
                  scope.launch {
                    val apiResult = runner.sendNotification()
                    sending = false
                    progress = progress.withNotificationApi(apiResult)
                    notificationSent = true
                  }
                }, enabled = !sending, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg), shape = RoundedCornerShape(CodexTokens.Radius.Button)) {
                  Text(if (sending) "发送中…" else "发送测试通知")
                }
              } else {
                if (progress.result(Stage02DItem.NotificationApi) != "SUCCESS") {
                  Text("API 回执：${progress.result(Stage02DItem.NotificationApi)}。仍请按手环实际收到和振动情况选择。",
                    modifier = Modifier.padding(top = CodexTokens.Space.Lg), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = CodexTokens.Type.Supporting)
                }
                Text("手环是否收到通知并发生振动？", modifier = Modifier.padding(top = CodexTokens.Space.Lg), color = MaterialTheme.colorScheme.onSurface, fontSize = CodexTokens.Type.Body)
                Row(Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Sm), horizontalArrangement = Arrangement.spacedBy(CodexTokens.Space.Sm)) {
                  Button(onClick = { progress = progress.withNotificationObservation("PASS") }, modifier = Modifier.weight(1f)) { Text("是") }
                  OutlinedButton(onClick = { progress = progress.withNotificationObservation("NOT_RECEIVED") }, modifier = Modifier.weight(1f)) { Text("未收到") }
                }
                OutlinedButton(onClick = { progress = progress.withNotificationObservation("RECEIVED_NO_VIBRATION") }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Sm)) { Text("收到但未振动") }
              }
            }
          }
        }
      }
      if (progress.phase == Stage02DPhase.Done) {
        Text("Overall: ${progress.overall}", modifier = Modifier.padding(top = CodexTokens.Space.Xl), fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold,
          color = if (progress.overall.startsWith("PASS")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = ::start, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg)) { Text("重新测试") }
        OutlinedButton(onClick = { copyReport(progress.copy(bandDiagnosis = diagnosis).report()) }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Md)) { Text("复制脱敏报告") }
      }
    }
  }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
  val color = when (value) {
    "PASS", "TRUE", "NODE_FOUND", "OK", "SUCCESS" -> MaterialTheme.colorScheme.tertiary
    "WAITING", "NOT_REPORTED" -> MaterialTheme.colorScheme.onSurfaceVariant
    "RUNNING" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
  }
  Row(Modifier.fillMaxWidth().padding(vertical = CodexTokens.Space.Sm), horizontalArrangement = Arrangement.spacedBy(CodexTokens.Space.Sm)) {
    Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = CodexTokens.Type.Body)
    Text(value, color = color, fontSize = CodexTokens.Type.Supporting)
  }
}
