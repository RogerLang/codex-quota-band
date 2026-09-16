package com.codex.quota.android.validation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.codex.quota.android.ui.CodexQuotaTheme
import com.codex.quota.android.ui.CodexTokens
import kotlinx.coroutines.launch

class ValidationActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CodexQuotaTheme {
        Stage02DScreen { report ->
          getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Stage 02D 脱敏报告", report))
          Toast.makeText(this, "脱敏报告已复制", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }
}

@Composable
private fun Stage02Screen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Stage02Runner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var progress by remember { mutableStateOf(Stage02Progress()) }
  var sending by remember { mutableStateOf(false) }
  var notificationSent by remember { mutableStateOf(false) }
  val background = Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.background))

  fun start() {
    notificationSent = false
    scope.launch { progress = runner.run { progress = it } }
  }

  Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text("Stage 02", fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
      Text("Band 9 Pro 通信验证", fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(CodexTokens.Space.Xl))
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(Modifier.padding(CodexTokens.Space.Lg)) {
          when (progress.phase) {
            Stage02Phase.Idle -> {
              Text("准备", fontSize = CodexTokens.Type.SectionTitle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
              Text("先确认小米运动健康已连接手环。\n? Probe RPK", modifier = Modifier.padding(top = CodexTokens.Space.Md), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Button(onClick = ::start, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text("开始测试") }
            }
            else -> {
              Stage02Item.entries.forEach { item -> Stage02Row(item.label, progress.states.getValue(item)) }
              if (progress.phase == Stage02Phase.Running) {
                Text("正在验证，请在手环打开 Stage 02 Probe，并保持本页打开…", modifier = Modifier.padding(top = CodexTokens.Space.Md), fontSize = CodexTokens.Type.Supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              if (progress.phase == Stage02Phase.InstallRequired) {
                Text("需要先安装 Stage 02 Probe", modifier = Modifier.padding(top = CodexTokens.Space.Lg), fontSize = CodexTokens.Type.SectionTitle, color = MaterialTheme.colorScheme.onSurface)
                Text("文件：CodexQuota-Stage02-probe.rpk\n用 AstroBox 安装完成后返回此页面。", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = ::start, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text("重新检查") }
                OutlinedButton(onClick = { progress = progress.copy(phase = Stage02Phase.Done, errorCode = "RPK_INSTALL_BLOCKED") }, modifier = Modifier.fillMaxWidth()) { Text("无法安装 RPK") }
              }
              if (progress.phase == Stage02Phase.OpenProbe) {
                Text("请在手环上打开 Stage 02 Probe。看到“手机连接 PASS”后，返回手机继续测试。", modifier = Modifier.padding(top = CodexTokens.Space.Lg), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = { runner.continueAfterOpeningProbe() }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text("手环已打开，继续") }
              }
              if (progress.phase == Stage02Phase.ConfirmNotification) {
                if (!notificationSent) {
                  Button(onClick = {
                    sending = true
                    scope.launch {
                      val error = runner.sendNotification()
                      sending = false
                      if (error == null) notificationSent = true
                      else progress = progress.update(Stage02Item.Notification, Stage02State.Fail).copy(phase = Stage02Phase.Done, errorCode = error)
                    }
                  }, enabled = !sending, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg), shape = RoundedCornerShape(CodexTokens.Radius.Button)) { Text(if (sending) "发送中…" else "发送测试通知") }
                } else {
                  Text("测试通知已发送。手环是否收到通知并发生振动？", modifier = Modifier.padding(top = CodexTokens.Space.Lg), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.onSurface)
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CodexTokens.Space.Sm)) {
                    Button(onClick = { progress = progress.update(Stage02Item.Notification, Stage02State.Pass).copy(phase = Stage02Phase.Done) }, modifier = Modifier.weight(1f)) { Text("是") }
                    OutlinedButton(onClick = { progress = progress.update(Stage02Item.Notification, Stage02State.Fail).copy(phase = Stage02Phase.Done, errorCode = "NOTIFY_NOT_RECEIVED") }, modifier = Modifier.weight(1f)) { Text("否") }
                  }
                  OutlinedButton(onClick = { progress = progress.update(Stage02Item.Notification, Stage02State.Fail).copy(phase = Stage02Phase.Done, errorCode = "NOTIFY_RECEIVED_NO_VIBRATION") }, modifier = Modifier.fillMaxWidth()) { Text("收到文字，但没有振动") }
                }
              }
            }
          }
        }
      }
      if (progress.phase == Stage02Phase.Done) {
        Text("Overall: ${if (progress.overallPass) "PASS" else "FAIL"}", modifier = Modifier.padding(top = CodexTokens.Space.Xl), fontSize = CodexTokens.Type.PageTitle, fontWeight = FontWeight.Bold, color = if (progress.overallPass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        progress.errorCode?.let { Text("错误代码：$it", modifier = Modifier.padding(top = CodexTokens.Space.Sm), fontSize = CodexTokens.Type.Body, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = ::start, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg)) { Text("重新测试") }
      }
      if (progress.phase == Stage02Phase.Done || progress.phase == Stage02Phase.InstallRequired) {
        OutlinedButton(onClick = { copyReport(progress.report()) }, modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Md)) { Text("复制脱敏报告") }
      }
    }
  }
}

@Composable
private fun Stage02Row(label: String, state: Stage02State) {
  val color = when (state) {
    Stage02State.Pass -> MaterialTheme.colorScheme.tertiary
    Stage02State.Fail -> MaterialTheme.colorScheme.error
    Stage02State.Running -> MaterialTheme.colorScheme.primary
    Stage02State.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  val marker = when (state) { Stage02State.Pass -> "✓"; Stage02State.Fail -> "×"; Stage02State.Running -> "…"; Stage02State.Waiting -> "?" }
  Row(Modifier.fillMaxWidth().padding(vertical = CodexTokens.Space.Sm)) {
    Text(marker, color = color, fontSize = CodexTokens.Type.Body)
    Text(label, modifier = Modifier.weight(1f).padding(start = CodexTokens.Space.Md), color = MaterialTheme.colorScheme.onSurface, fontSize = CodexTokens.Type.Body)
    Text(state.name.uppercase(), color = color, fontSize = CodexTokens.Type.Supporting)
  }
}
