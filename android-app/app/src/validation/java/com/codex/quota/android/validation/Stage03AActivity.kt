package com.codex.quota.android.validation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.codex.quota.android.ui.CodexQuotaTheme

class Stage03AActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CodexQuotaTheme {
        Stage03BScreen { report ->
          getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Stage 03B 脱敏报告", report))
          Toast.makeText(this, "脱敏报告已复制", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }
}
