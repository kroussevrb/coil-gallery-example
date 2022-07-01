package com.asd.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale.Companion.Crop
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.asd.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.lang.RuntimeException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        class NamingThreadFactory(private val prefix: String) : ThreadFactory {
            private val threadNumber = AtomicInteger(0)
            override fun newThread(r: Runnable) = Executors.defaultThreadFactory().newThread(r)
                .apply {
                    name = prefix + threadNumber.addAndGet(1)
                    //priority = 9
                }
            }

        val fetcher = Executors.newFixedThreadPool(5, NamingThreadFactory(prefix = "Coil-Fetcher-")).asCoroutineDispatcher()
        val decoder = Executors.newFixedThreadPool(5, NamingThreadFactory(prefix = "Coil-Decoder-")).asCoroutineDispatcher()
        val interceptor = Executors.newFixedThreadPool(5, NamingThreadFactory(prefix = "Coil-Interceptor-")).asCoroutineDispatcher()

        super.onCreate(savedInstanceState)
        setContent {
            val permissions = remember { Permissions(this) }
            CompositionLocalProvider(
                LocalPermissions provides permissions,
            ) {
                MyApplicationTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                        Gallery(
                            this.lifecycle,
                            ImageLoader.Builder(this)
                                //.logger(DebugLogger())
                                .memoryCache {
                                    MemoryCache.Builder(this)
                                        .maxSizePercent(0.9)
                                        .build()
                                }
                                .diskCache {
                                    DiskCache.Builder()
                                        .directory(this.cacheDir.resolve("coil_disk_cache"))
                                        .maxSizePercent(0.8)
                                        .build()
                                }
                                .precision(Precision.INEXACT)
                                .allowHardware(true)
//                                .okHttpClient { OkHttpClient() }
                                .networkCachePolicy(CachePolicy.DISABLED)
                                //.networkObserverEnabled(false)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
//                                .fetcherDispatcher(fetcher)
//                                .decoderDispatcher(decoder)
//                                .interceptorDispatcher(interceptor)
//                                .fetcherDispatcher(Dispatchers.IO)
//                                .decoderDispatcher(Dispatchers.IO)
//                                .interceptorDispatcher(Dispatchers.IO)
                                .build()
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("UnusedMaterialScaffoldPaddingParameter")
@Composable
fun Gallery(lifecycle: Lifecycle, imageLoader: ImageLoader) {
    PermissionAccess(
        permission = Manifest.permission.READ_EXTERNAL_STORAGE,
        rationaleTitle = "Rational title",
        rationaleMessage = "THis is rationale",
        onCancel = { }
    ) {
        val context = LocalContext.current
        val numberOfColumns = 3
        val (selectedImages, setSelectedImages) = rememberSaveable { mutableStateOf(listOf<Uri>()) }
        val maxAllowedImages = remember { 50 }
        val images by remember {
            mutableStateOf(
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    null,
                    null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    if (columnIndex >= 0) {
                        (0 until cursor.count).map {
                            cursor.moveToNext()
                            Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getInt(columnIndex).toString())
                        }
                    } else {
                        emptyList()
                    }

                } ?: emptyList()
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    backgroundColor = Color.White,
                    title = {
                        Text("Gallery")
                    }
                )
            }
        ) {
            Box {
                if (images.isEmpty()) {
                    Text("Niente qui")
                } else {
                    LazyColumn {
                        items(images.chunked(3)) { chunk ->
                            Row {
                                chunk.forEach { image ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(image)
                                            .lifecycle(lifecycle).size(360).build(),
                                        imageLoader = imageLoader,
                                        contentDescription = "",
                                        contentScale = Crop,
                                        modifier = Modifier.size(120.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PermissionState {
    NOT_DETERMINED,
    GRANTED,
    DENIED,
    DENIED_DONT_ASK_AGAIN
}
val LocalPermissions = staticCompositionLocalOf<Permissions> {
    throw RuntimeException("asd")
}

class Permissions constructor(private val activity: MainActivity) {
    fun shouldShowPermissionRationale(permission: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.shouldShowRequestPermissionRationale(permission)
        } else {
            false
        }
}

@Composable
fun PermissionAccess(
    permission: String,
    rationaleTitle: String,
    rationaleMessage: String,
    onCancel: () -> Unit,
    onGranted: @Composable () -> Unit
) {
    val permissions = LocalPermissions.current
    val context = LocalContext.current
    val (state, setState) = remember { mutableStateOf(PermissionState.NOT_DETERMINED) }

    fun mapToState(granted: Boolean, showRationale: Boolean) = when {
        granted -> PermissionState.GRANTED
        showRationale -> PermissionState.DENIED
        else -> PermissionState.DENIED_DONT_ASK_AGAIN
    }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        setState(mapToState(granted, permissions.shouldShowPermissionRationale(permission)))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setState(mapToState(granted, permissions.shouldShowPermissionRationale(permission)))
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionLauncher.launch(permission)
        } else {
            setState(PermissionState.GRANTED)
        }
    }

    when (state) {
        PermissionState.GRANTED -> onGranted()
        PermissionState.DENIED -> RationaleDialog(
            rationaleTitle = rationaleTitle,
            rationaleMessage = rationaleMessage,
            positiveText = "OK",
            onNegativeClick = onCancel,
            onPositiveClick = { permissionLauncher.launch(permission) }
        )
        PermissionState.DENIED_DONT_ASK_AGAIN -> RationaleDialog(
            rationaleTitle = rationaleTitle,
            rationaleMessage = rationaleMessage,
            positiveText = "Open settings",
            onNegativeClick = onCancel,
            onPositiveClick = {
                settingsLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        )
        PermissionState.NOT_DETERMINED -> {}
    }
}

@Composable
private fun RationaleDialog(
    rationaleTitle: String,
    rationaleMessage: String,
    positiveText: String,
    onNegativeClick: () -> Unit,
    onPositiveClick: () -> Unit
) {
    var isDialogDisplayed by remember { mutableStateOf(true)  }

    if (isDialogDisplayed) {
        AlertDialog(
            onDismissRequest = {},
            backgroundColor = Color.White,
            title = { Text("Gallery", maxLines = 2) },
            text = {
                Text(
                    text = rationaleMessage,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Black
                )
            },
            buttons = {
                Row(
                    modifier = Modifier
                        .padding(end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onNegativeClick(); isDialogDisplayed = false },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color.White,
                            contentColor = Color.Black,
                        )
                    ) {
                        Text(text = "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onPositiveClick,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color.Blue,
                            contentColor = Color.Red,
                        )
                    ) {
                        Text(text = positiveText)
                    }
                }
            }
        )
    }
}
