package com.deader89.drivetool

import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.deader89.drivetool.ui.theme.DrivetoolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveManager.initialize(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (!isGranted) {
                    Toast.makeText(this, "Notification permission required for background server", Toast.LENGTH_LONG).show()
                }
            }
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()
        setContent {
            DrivetoolTheme {
                AppNavigation()
            }
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy: App wird geschlossen, beginne Hardware-Cleanup...")
        super.onDestroy()
        if (ImageStorage.getStopOnClose(this)) {
            Log.d("MainActivity", "Automatischer Teardown gestartet...")
            HidManager.teardown()
            Log.d("MainActivity", "Automatischer Teardown abgeschlossen.")
        } else {
            HidManager.closeStreams()
        }
        
        if (ImageStorage.getStopWebdavOnClose(this)) {
            WebDavService.stop(this)
        }
    }
}

enum class Screen(val title: String, val icon: ImageVector) {
    IsoMnt("ISO-MNT", Icons.Default.Usb),
    Network("Network", Icons.Default.Share),
    HID("HID", Icons.Default.Keyboard),
    Downloads("Os-DL", Icons.Default.Download),
    Settings("Settings", Icons.Default.Settings)
}

fun getRealPathFromTreeUri(uri: Uri): String {
    return try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        if (split.size < 2) return uri.path ?: ""
        val type = split[0]
        if ("primary".equals(type, ignoreCase = true)) {
            "/storage/emulated/0/${split[1]}"
        } else {
            "/storage/$type/${split[1]}"
        }
    } catch (e: Exception) {
        uri.path ?: ""
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(Screen.IsoMnt) }
    var baseDir by remember { mutableStateOf(ImageStorage.getBaseDir(context)) }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val path = getRealPathFromTreeUri(it)
            ImageStorage.setBaseDir(context, path, it.toString())
            baseDir = path
        }
    }

    if (baseDir == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Welcome to DriveTool", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Please select a directory where your ISO images are stored.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { dirPicker.launch(null) }) {
                Text("Select ISO Directory")
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    Screen.IsoMnt -> IsoMntScreen()
                    Screen.Network -> NetworkScreen()
                    Screen.HID -> HidScreen()
                    Screen.Downloads -> DownloadScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun IsoMntScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isHosting by remember { mutableStateOf(false) }
    var activeImage by remember { mutableStateOf<String?>(null) }
    val baseDir = ImageStorage.getBaseDir(context) ?: ""
    var images by remember { mutableStateOf(ImageStorage.getImagesInBaseDir(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isHosting = DriveManager.isHosting()
            if (isHosting) {
                val path = DriveManager.findLunPath()
                if (path != null) activeImage = RootUtils.execute("cat '$path'").getOrNull()?.trim()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ISO Mount & Manage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { 
                images = ImageStorage.getImagesInBaseDir(context)
                scope.launch(Dispatchers.IO) { isHosting = DriveManager.isHosting() }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        
        if (isHosting) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ACTIVE HOSTING", style = MaterialTheme.typography.labelLarge)
                        Text(activeImage?.substringAfterLast("/") ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                DriveManager.stopHosting()
                                isHosting = false
                                activeImage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Stop")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create New Empty ISO")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(images, key = { it }) { path ->
                ImageItem(
                    path = path, 
                    isCurrentlyHosting = isHosting && activeImage == path,
                    onMountChange = { newState, imgPath ->
                        isHosting = newState
                        activeImage = if (newState) imgPath else null
                    },
                    onDelete = { images = ImageStorage.getImagesInBaseDir(context) }
                )
            }
        }
    }

    if (showCreateDialog) {
        var newFileName by remember { mutableStateOf("new_disk") }
        var selectedSizeGb by remember { mutableIntStateOf(4) }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create ISO") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Filename") },
                        suffix = { Text(".iso") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Size: ${selectedSizeGb}GB")
                    Slider(
                        value = selectedSizeGb.toFloat(),
                        onValueChange = { selectedSizeGb = it.toInt() },
                        valueRange = 1f..32f,
                        steps = 31
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCreateDialog = false
                    val finalName = if (newFileName.lowercase().endsWith(".iso")) newFileName else "$newFileName.iso"
                    val path = "$baseDir/$finalName"
                    scope.launch {
                        withContext(Dispatchers.IO) { DriveManager.createEmptyIso(path, selectedSizeGb, DriveManager.FileSystem.FAT32) }
                        images = ImageStorage.getImagesInBaseDir(context)
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageItem(path: String, isCurrentlyHosting: Boolean, onMountChange: (Boolean, String) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyHosting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (!isCurrentlyHosting) {
                            scope.launch(Dispatchers.IO) {
                                val res = DriveManager.hostImage(path, false)
                                if (res.isSuccess) withContext(Dispatchers.Main) { onMountChange(true, path) }
                            }
                        }
                    },
                    onLongClick = {
                        if (!isCurrentlyHosting) {
                            scope.launch(Dispatchers.IO) {
                                val res = DriveManager.hostImage(path, true)
                                if (res.isSuccess) withContext(Dispatchers.Main) { onMountChange(true, path) }
                            }
                        }
                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(path.substringAfterLast("/"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isCurrentlyHosting) "MOUNTED" else path,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrentlyHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isCurrentlyHosting) {
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        DriveManager.stopHosting()
                        withContext(Dispatchers.Main) { onMountChange(false, path) }
                    }
                }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                }
            }

            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Image?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch(Dispatchers.IO) {
                            val baseUri = ImageStorage.getBaseUri(context) ?: return@launch
                            val rootDoc = DocumentFile.fromTreeUri(context, baseUri.toUri())
                            val targetFile = rootDoc?.findFile(path.substringAfterLast("/"))
                            if (targetFile?.delete() == true) withContext(Dispatchers.Main) { onDelete() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DownloadScreen() {
    val distributions = listOf(
        "Windows 11" to "https://www.microsoft.com/software-download/windows11",
        "Ghost Spectre" to "https://ghostclouds.win/wp/g-w11-p-25h2/",
        "Ubuntu" to "https://ubuntu.com/download/desktop",
        "Kali Linux" to "https://www.kali.org/get-kali/",
        "Arch Linux" to "https://archlinux.org/download/",
        "TrueNAS" to "https://www.truenas.com/download-truenas-core/"
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("OS Downloads", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(distributions) { (name, url) ->
                val context = LocalContext.current
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                            Text("Visit")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareUri by remember { mutableStateOf(ImageStorage.getWebdavDir(context)?.let { Uri.parse(it) }) }
    var isSharing by remember { mutableStateOf(WebDavServer.isActive()) }
    var username by remember { mutableStateOf(ImageStorage.getWebdavUser(context)) }
    var password by remember { mutableStateOf(ImageStorage.getWebdavPass(context)) }
    var ip by remember { mutableStateOf("Checking...") }

    LaunchedEffect(isSharing) {
        withContext(Dispatchers.IO) { ip = RootUtils.getIpAddress() }
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { 
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            shareUri = it
            ImageStorage.setWebdavDir(context, it.toString())
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Network Share (WebDAV)", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                Text("URI: ${shareUri?.path ?: "Not selected"}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { dirPicker.launch(null) }, enabled = !isSharing) { Text("Select Folder") }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("Login: $username / ${"*".repeat(password.length)}", style = MaterialTheme.typography.bodySmall)
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val currentUri = shareUri ?: return@Button
                        scope.launch {
                            if (isSharing) WebDavService.stop(context) else WebDavService.start(context, currentUri)
                            isSharing = !isSharing
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isSharing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (isSharing) "Stop Share" else "Start Share")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Connect to: http://$ip:8081", style = MaterialTheme.typography.titleSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HidScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kbAvailable by remember { mutableStateOf(false) }
    var mouseAvailable by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var keyboardTextState by remember { mutableStateOf(TextFieldValue(" ")) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kbAvailable = HidManager.isKeyboardAvailable()
            mouseAvailable = HidManager.isMouseAvailable()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (mouseAvailable) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).pointerInput(Unit) {
                detectTapGestures(
                    onTap = { scope.launch(Dispatchers.IO) { HidManager.sendMouseReport(1, 0, 0); delay(50); HidManager.sendMouseReport(0, 0, 0) } },
                    onLongPress = { scope.launch(Dispatchers.IO) { HidManager.sendMouseReport(2, 0, 0); delay(50); HidManager.sendMouseReport(0, 0, 0) } }
                )
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.changes.size >= 3) focusRequester.requestFocus()
                    }
                }
            }.pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    scope.launch(Dispatchers.IO) { HidManager.sendMouseReport(0, drag.x.toInt(), drag.y.toInt()) }
                }
            }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.3f)) {
                    Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(64.dp))
                    Text("HID Active")
                    Text("3-Finger: Keyboard", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (!kbAvailable && !mouseAvailable) {
            Card(modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.Center).zIndex(2f)) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Switch to HID", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { scope.launch(Dispatchers.IO) {
                        DriveManager.stopHosting()
                        delay(100)
                        val res = HidManager.setupHidNodes()
                        withContext(Dispatchers.Main) {
                            if (res.isSuccess) {
                                kbAvailable = true
                                mouseAvailable = true
                            } else {
                                Toast.makeText(context, "HID failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Activate HID") }
                }
            }
        }

        BasicTextField(
            value = keyboardTextState,
            onValueChange = { newValue ->
                if (newValue.text.length > keyboardTextState.text.length) {
                    scope.launch(Dispatchers.IO) { HidManager.typeChar(newValue.text.last()) }
                } else if (newValue.text.length < keyboardTextState.text.length) {
                    scope.launch(Dispatchers.IO) { HidManager.typeKey(0x2A.toByte()) }
                }
                keyboardTextState = TextFieldValue(" ", selection = androidx.compose.ui.text.TextRange(1))
            },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focusRequester).onKeyEvent { 
                if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    scope.launch(Dispatchers.IO) { HidManager.typeKey(0x28.toByte()) }; true
                } else false
            }
        )

        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).zIndex(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusChip("HID", kbAvailable, kbAvailable)
            if (kbAvailable || mouseAvailable) {
                Button(onClick = { scope.launch(Dispatchers.IO) { 
                    HidManager.teardown()
                    withContext(Dispatchers.Main) { kbAvailable = false; mouseAvailable = false }
                } }) { Text("Standard Mode") }
            }
        }
    }
}

@Composable
fun StatusChip(label: String, exists: Boolean, writable: Boolean) {
    val color = if (!exists) MaterialTheme.colorScheme.errorContainer else if (!writable) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text = "$label: ${if(exists && writable) "OK" else if(exists) "No Perm" else "Missing"}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var stopOnClose by remember { mutableStateOf(ImageStorage.getStopOnClose(context)) }
    var baseDir by remember { mutableStateOf(ImageStorage.getBaseDir(context) ?: "Not set") }
    
    var webdavUser by remember { mutableStateOf(ImageStorage.getWebdavUser(context)) }
    var webdavPass by remember { mutableStateOf(ImageStorage.getWebdavPass(context)) }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val path = getRealPathFromTreeUri(it)
            ImageStorage.setBaseDir(context, path, it.toString())
            baseDir = path
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("General", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop on close", modifier = Modifier.weight(1f))
                    Switch(checked = stopOnClose, onCheckedChange = { stopOnClose = it; ImageStorage.setStopOnClose(context, it) })
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("WebDAV Authentication", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webdavUser,
                    onValueChange = { 
                        webdavUser = it
                        ImageStorage.setWebdavAuth(context, it, webdavPass)
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = webdavPass,
                    onValueChange = { 
                        webdavPass = it
                        ImageStorage.setWebdavAuth(context, webdavUser, it)
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { dirPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Change ISO Directory") }
    }
}
