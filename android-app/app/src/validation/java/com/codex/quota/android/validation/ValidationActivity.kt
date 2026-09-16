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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
        ValidationScreen(
          copyReport = { report ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Foundation 01V 脱敏报告", report))
            Toast.makeText(this, "脱敏报告已复制", Toast.LENGTH_SHORT).show()
          },
        )
      }
    }
  }
}

@Composable
private fun ValidationScreen(copyReport: (String) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val runner = remember { Foundation01ValidationRunner(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var progress by remember { mutableStateOf(ValidationProgress()) }
  val background =
    Brush.verticalGradient(
      listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = .10f),
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.background,
      ),
    )

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(background)
        .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = CodexTokens.Space.Page, vertical = CodexTokens.Space.Xl),
    ) {
      Text(
        text = "Foundation 01V",
        fontSize = CodexTokens.Type.PageTitle,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        text = "验证 Windows → ntfy → Android 基础链路",
        modifier = Modifier.padding(top = CodexTokens.Space.Xs),
        fontSize = CodexTokens.Type.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(CodexTokens.Space.Xl))

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CodexTokens.Radius.Card),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
          ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
      ) {
        Column(modifier = Modifier.padding(CodexTokens.Space.Lg)) {
          if (!progress.running && !progress.finished) {
            Text(
              text = "一次点击即可完成全部验证",
              fontSize = CodexTokens.Type.SectionTitle,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "仅使用一次性随机凭据与虚构数据，测试结束后自动清理。",
              modifier = Modifier.padding(top = CodexTokens.Space.Sm),
              fontSize = CodexTokens.Type.Supporting,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
              onClick = {
                if (!progress.running) {
                  scope.launch { progress = runner.run { progress = it } }
                }
              },
              modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl),
              shape = RoundedCornerShape(CodexTokens.Radius.Button),
            ) {
              Text("开始测试")
            }
          } else {
            ValidationItem.entries.forEach { item ->
              ValidationRow(item.label, progress.statuses.getValue(item))
            }
          }
        }
      }

      if (progress.running) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Lg),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Text(
            text = "正在验证，请保持页面打开…",
            modifier = Modifier.padding(start = CodexTokens.Space.Sm),
            fontSize = CodexTokens.Type.Supporting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (progress.finished) {
        val resultColor =
          if (progress.overallPass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
        Text(
          text = if (progress.overallPass) "Overall: PASS" else "Overall: FAIL",
          modifier = Modifier.padding(top = CodexTokens.Space.Xl),
          fontSize = CodexTokens.Type.PageTitle,
          fontWeight = FontWeight.Bold,
          color = resultColor,
        )
        if (!progress.overallPass) {
          val failures =
            ValidationItem.entries
              .filter { progress.statuses[it] == ValidationStatus.Fail }
              .joinToString("、") { it.label }
          Text(
            text = "失败项目：$failures",
            modifier = Modifier.padding(top = CodexTokens.Space.Md),
            fontSize = CodexTokens.Type.Body,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "错误代码：${progress.errorCode ?: "VALIDATION_FAILED"}",
            modifier = Modifier.padding(top = CodexTokens.Space.Sm),
            fontSize = CodexTokens.Type.Supporting,
            color = MaterialTheme.colorScheme.error,
          )
        }
        OutlinedButton(
          onClick = { copyReport(progress.sanitizedReport(context)) },
          modifier = Modifier.fillMaxWidth().padding(top = CodexTokens.Space.Xl),
          shape = RoundedCornerShape(CodexTokens.Radius.Button),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
          Text("复制脱敏报告")
        }
      }
    }
  }
}

@Composable
private fun ValidationRow(label: String, status: ValidationStatus) {
  val color =
    when (status) {
      ValidationStatus.Pass -> MaterialTheme.colorScheme.tertiary
      ValidationStatus.Fail -> MaterialTheme.colorScheme.error
      ValidationStatus.Running -> MaterialTheme.colorScheme.primary
      ValidationStatus.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val icon =
    when (status) {
      ValidationStatus.Pass -> Icons.Outlined.CheckCircle
      ValidationStatus.Fail -> Icons.Outlined.Cancel
      ValidationStatus.Running -> Icons.Outlined.Sync
      ValidationStatus.Waiting -> Icons.Outlined.HourglassEmpty
    }
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = CodexTokens.Space.Sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    Text(
      text = label,
      modifier = Modifier.weight(1f).padding(start = CodexTokens.Space.Md),
      fontSize = CodexTokens.Type.Body,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = status.label(),
      fontSize = CodexTokens.Type.Supporting,
      fontWeight = FontWeight.SemiBold,
      color = color,
    )
  }
}

private fun ValidationStatus.label(): String =
  when (this) {
    ValidationStatus.Waiting -> "等待"
    ValidationStatus.Running -> "测试中"
    ValidationStatus.Pass -> "PASS"
    ValidationStatus.Fail -> "FAIL"
  }
