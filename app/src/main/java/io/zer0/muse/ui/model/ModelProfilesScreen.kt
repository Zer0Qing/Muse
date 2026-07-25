package io.zer0.muse.ui.model

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import io.zer0.muse.ui.common.IosSwitch
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.muse.data.ModelProfile
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProfilesScreen(
    onBack: () -> Unit,
    settings: SettingsRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = null)
    val profiles by settings.modelProfilesFlow.collectAsStateWithLifecycle(initialValue = null)

    // Single image picker launcher shared by all models
    var pendingModelId by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val modelId = pendingModelId ?: return@rememberLauncherForActivityResult
        pendingModelId = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                val file = java.io.File(context.filesDir, "avatars/$modelId.jpg")
                file.parentFile?.mkdirs()
                input?.use { inp -> file.outputStream().use { inp.copyTo(it) } }
                "file://${file.absolutePath}"
            }
            settings.saveModelProfile(modelId, ModelProfile(url, profiles?.get(modelId)?.showAvatar ?: false))
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = "模型头像",
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            providers == null -> {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // v1.131: 用局部非空变量捕获,替代 !!(委托属性无法 smart-cast)。
                // 此时已排除 loading/null 分支,providers 一定非空,但 requireNotNull 让契约更显式。
                val providerList = requireNotNull(providers) { "providers must be non-null in loaded state" }
                val allModels = providerList.flatMap { provider ->
                    provider.models.map { model -> provider.displayName.ifBlank { provider.type.name } to model }
                }

                if (allModels.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Outlined.Memory,
                            title = "暂无模型",
                            subtitle = "请先在设置中添加 AI 提供商",
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(allModels, key = { "${it.first}_${it.second.id}" }) { (providerName, model) ->
                            ModelProfileRow(
                                providerName = providerName,
                                modelId = model.id,
                                modelName = model.name,
                                profile = profiles?.get(model.id) ?: ModelProfile(),
                                onToggleShow = { show -> scope.launch { settings.saveModelProfile(model.id, ModelProfile(profiles?.get(model.id)?.avatarUrl ?: "", show)) } },
                                onPickImage = {
                                    pendingModelId = model.id
                                    imageLauncher.launch("image/*")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelProfileRow(
    providerName: String,
    modelId: String,
    modelName: String,
    profile: ModelProfile,
    onToggleShow: (Boolean) -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onPickImage),
                contentAlignment = Alignment.Center,
            ) {
                if (profile.avatarUrl.isNotBlank()) {
                    AsyncImage(model = profile.avatarUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape))
                } else {
                    Icon(Icons.Outlined.PhotoCamera, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(modelName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            IosSwitch(checked = profile.showAvatar, onCheckedChange = onToggleShow)
        }
    }
}
