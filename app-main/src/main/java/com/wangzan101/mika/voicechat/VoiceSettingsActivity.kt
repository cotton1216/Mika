package com.wangzan101.mika.voicechat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.calls.CallVoiceProfile
import com.wangzan101.mika.calls.VolcRealtimeClient
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch

/**
 * 实时语音的凭据配置:对应豆包端到端实时语音(火山引擎 · 实时对话)。
 * 填完保存就能直接用听筒开始对话;这里只管语音,和聊天的模型配置无关。
 */
class VoiceSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as MikaApp).calls.voiceProfiles
        val existing = store.get(CallVoiceProfile.DEFAULT_ID)
        val saved = store.credentials(CallVoiceProfile.DEFAULT_ID)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFEDEFF4)) {
                    VoiceSettingsScreen(
                        initialProfile = existing,
                        initialCredentials = saved,
                        onSave = { apiKey, appId, accessKey, speaker ->
                            save(apiKey, appId, accessKey, speaker)
                        },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun save(apiKey: String, appId: String, accessKey: String, speaker: String) {
        val store = (application as MikaApp).calls.voiceProfiles
        val credentials = VolcRealtimeCredentials(apiKey, appId, accessKey)
        if (!credentials.valid) {
            Toast.makeText(this, "凭据不完整:填 API Key,或同时填 App ID 和 Access Key", Toast.LENGTH_LONG).show()
            return
        }
        val profile = (store.get(CallVoiceProfile.DEFAULT_ID) ?: CallVoiceProfile(
            id = CallVoiceProfile.DEFAULT_ID,
            name = "默认实时语音",
        )).copy(name = "默认实时语音", speaker = speaker)
        val result = runCatching { store.save(profile, credentials) }
        result.onSuccess {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }.onFailure {
            Toast.makeText(this, it.message ?: "没能保存", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, VoiceSettingsActivity::class.java)
    }
}

@Composable
private fun VoiceSettingsScreen(
    initialProfile: CallVoiceProfile?,
    initialCredentials: VolcRealtimeCredentials?,
    onSave: (apiKey: String, appId: String, accessKey: String, speaker: String) -> Unit,
    onClose: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var accessKey by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf(initialProfile?.speaker.orEmpty()) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.ime)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                MikaIcons.Back, "返回",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(8.dp),
                tint = Color(0xFF374151),
            )
            Spacer(Modifier.width(6.dp))
            Text("语音对话（实时）", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111827))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "填豆包端到端实时语音的凭据。填完在对话页点听筒就能直接开口说话,说完它马上答,可以随时插话打断。",
            fontSize = 13.sp, color = Color(0xFF6B7280), lineHeight = 20.sp,
        )
        if (initialCredentials != null) {
            Text("已经配过凭据,重新填写会覆盖。", fontSize = 13.sp, color = Color(0xFF16A34A))
        }
        Field(
            value = apiKey,
            onChange = { apiKey = it },
            label = "API Key(可选,填了就不用下面两项)",
            secret = true,
            placeholder = "填新版控制台的 API Key",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Field(
                value = appId, onChange = { appId = it }, label = "App ID", secret = false,
                modifier = Modifier.weight(1f), placeholder = "语音应用 App ID",
            )
            Field(
                value = accessKey, onChange = { accessKey = it }, label = "Access Key", secret = true,
                modifier = Modifier.weight(1f), placeholder = "访问令牌",
            )
        }
        Field(
            value = speaker, onChange = { speaker = it }, label = "音色编号(可留空)", secret = false,
            placeholder = "留空用默认音色",
        )
        Text(
            "接口与模型名已内置,一般不用改。",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )
        Spacer(Modifier.height(4.dp))
        val credentials = VolcRealtimeCredentials(apiKey.trim(), appId.trim(), accessKey.trim())
        OutlinedButton(
            onClick = {
                if (checking) return@OutlinedButton
                checking = true
                checkResult = null
                scope.launch {
                    val result = VolcRealtimeClient.probe(credentials)
                    checkResult = result.fold(
                        onSuccess = { true to "能连上,鉴权通过" },
                        onFailure = { false to (it.message ?: it.javaClass.simpleName) },
                    )
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !checking && credentials.valid,
        ) {
            Text(if (checking) "检查中…" else "检查连通", fontSize = 15.sp)
        }
        checkResult?.let { (ok, text) ->
            Text(
                text,
                fontSize = 12.5.sp, lineHeight = 19.sp,
                color = if (ok) Color(0xFF16A34A) else Color(0xFFDC2626),
            )
        }
        Button(
            onClick = { onSave(apiKey.trim(), appId.trim(), accessKey.trim(), speaker.trim()) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
        ) {
            Text("保存", fontSize = 16.sp)
        }
        Text(
            "取消",
            fontSize = 15.sp,
            color = Color(0xFF6B7280),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .background(Color.Transparent)
                .clickable(onClick = onClose),
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    secret: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier,
    )
}
