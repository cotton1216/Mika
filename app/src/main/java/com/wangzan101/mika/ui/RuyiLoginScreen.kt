package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.MikaLogo

/**
 * 首屏登录页:还没登录如意时,App 打开先停在这里。点「登录」走和设置 › 模型里一样的如意授权
 * (浏览器授权 → scheme 回跳 → 换推理 Key → 列出账号下的模型 → 存成服务商、没有主模型时选一个),
 * 全部完成后 [onSignedIn] 进主界面;[onSkip] 暂不登录直接进主界面,以后可在设置 › 模型里登录。
 */
@Composable
fun RuyiLoginScreen(store: ProviderStore, onSignedIn: () -> Unit, onSkip: () -> Unit) {
    val vm = rememberModelSetupViewModel(store)
    val ui by vm.state.collectAsState()
    val (signedIn, running, failure) = ui.signInState(SignInKind.Ruyi)
    // 授权完成(Key 换到、模型列出、服务商存好)后自动进主界面
    LaunchedEffect(signedIn) { if (signedIn != null) onSignedIn() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MikaLogo(92.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.ruyi_login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            if (failure != null) {
                Text(
                    failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = { vm.signInWithAccount(AccountProvider.Ruyi) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ruyi_login_waiting))
                } else {
                    Text(stringResource(R.string.ruyi_login_cta))
                }
            }
            TextButton(onClick = onSkip, enabled = !running, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.ruyi_login_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
