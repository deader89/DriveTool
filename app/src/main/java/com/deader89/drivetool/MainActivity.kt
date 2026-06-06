package com.deader89.drivetool

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deader89.drivetool.ui.theme.DrivetoolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveManager.initialize(this)
        
        // Initialize HID streams if nodes exist
        if (HidManager.isKeyboardAvailable() || HidManager.isMouseAvailable()) {
            HidManager.setupHidNodes()
        }
        
        // Request notification permission for Android 13+
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
        super.onDestroy()
        HidManager.closeStreams()
        val stopHosting = ImageStorage.getStopOnClose(this)
        val stopWebdav = ImageStorage.getStopWebdavOnClose(this)
        
        if (stopWebdav) {
            WebDavService.stop(this)
        }

        if (stopHosting) {
            try {
                val lunPath = DriveManager.findLunPath() ?: ""
                val hostCmd = "echo '' > $lunPath && setprop sys.usb.config none && sleep 1 && setprop sys.usb.config mtp,adb"
                Runtime.getRuntime().exec(arrayOf("su", "-c", hostCmd))
            } catch (_: Exception) {
                // Ignore errors on destroy
            }
        }
    }
}

enum class Screen(val title: String, val icon: ImageVector) {
    Hosting("Hosting", Icons.Default.Usb),
    Manage("Manage", Icons.Default.Build),
    Network("Network", Icons.Default.Share),
    HID("HID", Icons.Default.Keyboard),
    Downloads("Downloads", Icons.Default.Download),
    Settings("Settings", Icons.Default.Settings)
}

fun getRealPath(uri: Uri): String {
    val path = uri.path ?: return ""
    return if (path.contains(":")) {
        "/storage/emulated/0/${path.split(":").last()}"
    } else {
        path
    }
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
    var currentScreen by remember { mutableStateOf(Screen.Hosting) }
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
            Text("Please select a directory where your ISO images are stored or should be created.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                    Screen.Hosting -> MainScreen()
                    Screen.Manage -> ManageScreen()
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
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isHosting by remember { mutableStateOf(false) }
    var asReadonly by remember { mutableStateOf(true) }
    var lunPath by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val statusMessage = when {
        rootAvailable == null -> "Checking root access..."
        rootAvailable == false -> "Root access denied! (Required)"
        lastError != null -> "Error: $lastError"
        isHosting -> "Status: HOSTING ACTIVE"
        lunPath == null -> "No UMS support detected!"
        else -> "Status: Ready"
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val available = RootUtils.isRootAvailable()
            rootAvailable = available
            if (available) {
                lunPath = DriveManager.findLunPath()
                isHosting = DriveManager.isHosting()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "DriveTool", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = statusMessage, color = if (rootAvailable == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        
        if (lunPath != null) {
            Text(text = "LUN Path: $lunPath", style = MaterialTheme.typography.labelSmall)
        } else if (rootAvailable == true) {
            Text(text = "No UMS support detected!", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (rootAvailable == true) {
            Button(onClick = { launcher.launch("*/*") }) {
                Text(text = if (selectedImageUri == null) "Select ISO/IMG" else "Change Image")
            }

            selectedImageUri?.let { uri ->
                val realPath = getRealPath(uri)
                Text(text = "Real Path: $realPath", style = MaterialTheme.typography.bodySmall)
                
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CD-ROM Mode (Read-Only)")
                    Checkbox(checked = asReadonly, onCheckedChange = { asReadonly = it })
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val result = if (isHosting) {
                                withContext(Dispatchers.IO) { DriveManager.stopHosting() }
                            } else {
                                withContext(Dispatchers.IO) { DriveManager.hostImage(realPath, asReadonly) }
                            }

                            if (result.isSuccess) {
                                isHosting = !isHosting
                                lastError = null
                                Toast.makeText(context, if (isHosting) "Hosting active" else "Hosting stopped", Toast.LENGTH_SHORT).show()
                            } else {
                                lastError = result.exceptionOrNull()?.message
                                Toast.makeText(context, "Error: $lastError", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isHosting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(text = if (isHosting) "Stop Hosting" else "Start Hosting")
                }
            }
        }
    }
}

@Composable
fun ManageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseDir = ImageStorage.getBaseDir(context) ?: ""
    var images by remember { mutableStateOf(ImageStorage.getImagesInBaseDir(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("new_disk") }
    var selectedSizeGb by remember { mutableIntStateOf(4) }
    var selectedFs by remember { mutableStateOf(DriveManager.FileSystem.FAT32) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Manage Images", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { images = ImageStorage.getImagesInBaseDir(context) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        Text("Directory: $baseDir", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Create New Empty ISO")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(images) { path ->
                ImageItem(path = path) {
                    images = ImageStorage.getImagesInBaseDir(context)
                }
            }
        }
    }

    if (showCreateDialog) {
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
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Filesystem:")
                    DriveManager.FileSystem.entries.forEach { fs ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedFs == fs,
                                onClick = { selectedFs = fs }
                            )
                            Text(fs.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCreateDialog = false
                    val finalName = if (newFileName.lowercase().endsWith(".iso")) newFileName else "$newFileName.iso"
                    val path = "$baseDir/$finalName"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            DriveManager.createEmptyIso(path, selectedSizeGb, selectedFs)
                        }
                        if (result.isSuccess) {
                            images = ImageStorage.getImagesInBaseDir(context)
                            Toast.makeText(context, "ISO created: $finalName", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Create in Base Directory") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageItem(path: String, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isHostingLocal by remember { mutableStateOf(false) }
    var isCdRomMode by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(path.substringAfterLast("/"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isHostingLocal) (if (isCdRomMode) "HOSTING (CD-ROM)" else "HOSTING (USB-RW)") else path,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHostingLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Box(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            scope.launch {
                                val result = if (isHostingLocal) {
                                    withContext(Dispatchers.IO) { DriveManager.stopHosting() }
                                } else {
                                    isCdRomMode = false
                                    withContext(Dispatchers.IO) { DriveManager.hostImage(path, asReadonly = false) }
                                }
                                if (result.isSuccess) {
                                    isHostingLocal = !isHostingLocal
                                    if (isHostingLocal) Toast.makeText(context, "Hosted as USB (RW)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, result.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onLongClick = {
                            if (!isHostingLocal) {
                                scope.launch {
                                    isCdRomMode = true
                                    val result = withContext(Dispatchers.IO) { DriveManager.hostImage(path, asReadonly = true) }
                                    if (result.isSuccess) {
                                        isHostingLocal = true
                                        Toast.makeText(context, "Hosted as CD-ROM (RO)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, result.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    )
                    .padding(8.dp)
            ) {
                Icon(
                    if (isHostingLocal) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Host",
                    tint = if (isHostingLocal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
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
            text = { Text("Do you really want to delete this file from storage?\n\n$path") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch(Dispatchers.IO) {
                            val webdavDir = ImageStorage.getWebdavDir(context)
                            val fileName = path.substringAfterLast("/")
                            var success = false
                            
                            if (webdavDir != null) {
                                val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(webdavDir))
                                val targetFile = rootDoc?.findFile(fileName)
                                if ((targetFile != null) && targetFile.delete()) {
                                    success = true
                                }
                            }
                            
                            if (!success) {
                                // Fallback falls SAF fehlschlägt oder kein URI vorhanden
                                success = RootUtils.execute("rm '$path'").isSuccess
                            }

                            withContext(Dispatchers.Main) {
                                if (success) {
                                    onDelete()
                                    Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not delete file", Toast.LENGTH_LONG).show()
                                }
                            }
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
        "Ghost Spectre (Win11 25H2)" to "https://ghostclouds.win/wp/g-w11-p-25h2/",
        "Ubuntu" to "https://ubuntu.com/download/desktop",
        "Debian" to "https://www.debian.org/distrib/",
        "Kali Linux" to "https://www.kali.org/get-kali/",
        "Fedora" to "https://fedoraproject.org/workstation/download/",
        "Arch Linux" to "https://archlinux.org/download/",
        "Linux Mint" to "https://linuxmint.com/download.php",
        "OpenMediaVault (NAS)" to "https://www.openmediavault.org/?page_id=77",
        "Proxmox (VE)" to "https://www.proxmox.com/en/downloads",
        "RetroPie (Gaming)" to "https://retropie.org.uk/download/",
        "Lakka (RetroArch)" to "https://www.lakka.tv/get/",
        "TrueNAS" to "https://www.truenas.com/download-truenas-core/"
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("OS Downloads", style = MaterialTheme.typography.headlineMedium)
        Text("Download ISOs from official/community websites:", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(distributions) { pair ->
                val (name, url) = pair
                DownloadItem(name = name, url = url)
            }
        }
    }
}

@Composable
fun DownloadItem(name: String, url: String) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }) {
                Text("Visit Website")
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
    var isProcessing by remember { mutableStateOf(false) }
    var serverStatus by remember { mutableStateOf(if (isSharing) "RUNNING" else "Ready") }

    var username by remember { mutableStateOf(ImageStorage.getWebdavUser(context)) }
    var password by remember { mutableStateOf(ImageStorage.getWebdavPass(context)) }
    var showAuthDialog by remember { mutableStateOf(false) }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { 
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
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
                Spacer(modifier = Modifier.height(16.dp))
                
                val isUriValid = shareUri != null && shareUri?.scheme == "content"
                Text(
                    "Folder URI: ${if (isUriValid) shareUri?.path else if (shareUri == null) "Not selected" else "Invalid (Re-select required)"}", 
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (shareUri != null && !isUriValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { dirPicker.launch(null) }, enabled = !isSharing && !isProcessing) {
                    Text("Select Folder (SAF)")
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Authentication", style = MaterialTheme.typography.titleMedium)
                Text("Login: $username", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { showAuthDialog = true },
                    enabled = !isSharing && !isProcessing,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Change Login")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Status: ${if (isProcessing) "Processing..." else serverStatus}")
                Button(
                    onClick = {
                        val currentUri = shareUri
                        if (currentUri == null || !isUriValid) {
                            Toast.makeText(context, "Please select a valid folder first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            isProcessing = true
                            if (isSharing) {
                                WebDavService.stop(context)
                                isSharing = false
                                serverStatus = "Stopped"
                            } else {
                                WebDavService.start(context, currentUri)
                                isSharing = true
                                serverStatus = "RUNNING (Port 8081)"
                            }
                            isProcessing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSharing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isSharing) "Stop Share" else "Start Share")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        var ip by remember { mutableStateOf("Checking...") }
        LaunchedEffect(isSharing) {
            withContext(Dispatchers.IO) {
                ip = RootUtils.getIpAddress()
            }
        }

        Text("Connection Guide:", style = MaterialTheme.typography.titleSmall)
        Text("1. Open your WebDAV client (e.g. WinSCP, Cyberduck or Browser)", style = MaterialTheme.typography.bodySmall)
        Text("2. URL: http://$ip:8081", style = MaterialTheme.typography.bodySmall)
        Text("3. Login: $username / ${"*".repeat(password.length)}", style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Windows Map Network Drive (HTTP):", style = MaterialTheme.typography.titleSmall)
        Text("Windows forbids Basic Auth over HTTP by default. To fix this:", style = MaterialTheme.typography.bodySmall)
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Registry Path:", style = MaterialTheme.typography.labelSmall)
                Text("HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\WebClient\\Parameters", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Action:", style = MaterialTheme.typography.labelSmall)
                Text("Set 'BasicAuthLevel' to 2 (Decimal)", style = MaterialTheme.typography.bodySmall)
                Text("Then restart 'WebClient' service in services.msc", style = MaterialTheme.typography.bodySmall)
            }
        }
        
        if (ip == "Unknown") {
            Text("(Please check Wi-Fi connection!)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("Stability Settings:", style = MaterialTheme.typography.titleSmall)
        
        val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
        val isIgnoringBatteryOptimizations = remember {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }

        if (!isIgnoringBatteryOptimizations) {
            Text("Android may stop the server to save battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                }
            }) {
                Text("Disable Battery Optimization")
            }
        } else {
            Text("Battery optimization is disabled (Good).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Tip: WebDAV is more compatible with modern Android versions and less restricted by SELinux than SMB.", 
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showAuthDialog) {
        var tempUser by remember { mutableStateOf(username) }
        var tempPass by remember { mutableStateOf(password) }

        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text("WebDAV Login") },
            text = {
                Column {
                    OutlinedTextField(
                        value = tempUser,
                        onValueChange = { tempUser = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPass,
                        onValueChange = { tempPass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (tempUser.isNotBlank() && tempPass.isNotBlank()) {
                        username = tempUser
                        password = tempPass
                        ImageStorage.setWebdavAuth(context, tempUser, tempPass)
                        showAuthDialog = false
                    } else {
                        Toast.makeText(context, "Username and password cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HidScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kbAvailable by remember { mutableStateOf(false) }
    var mouseAvailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kbAvailable = HidManager.isKeyboardAvailable()
            mouseAvailable = HidManager.isMouseAvailable()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("USB HID Controls", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip("Keyboard", kbAvailable)
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip("Mouse", mouseAvailable)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    kbAvailable = HidManager.isKeyboardAvailable()
                    mouseAvailable = HidManager.isMouseAvailable()
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (!kbAvailable && !mouseAvailable) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "HID device nodes (/dev/hidg0, /dev/hidg1) not found.",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "To use this feature, your kernel must support USB Gadget HID. Many stock kernels have this disabled.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("How to fix:", style = MaterialTheme.typography.labelLarge)
                    Text("1. Install a Magisk module like 'USB HID Enabler'.", style = MaterialTheme.typography.bodySmall)
                    Text("2. Or use a custom kernel that has HID gadget support enabled.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Some devices support it via 'setprop sys.usb.config hid,adb' (Root required).", style = MaterialTheme.typography.bodySmall)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = HidManager.setupHidNodes()
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        kbAvailable = HidManager.isKeyboardAvailable()
                                        mouseAvailable = HidManager.isMouseAvailable()
                                        Toast.makeText(context, "HID Nodes initialized!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Fix failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try to Fix (ConfigFS)")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (mouseAvailable) {
            Text("Touchpad", style = MaterialTheme.typography.titleMedium)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            scope.launch(Dispatchers.IO) {
                                HidManager.sendMouseReport(0, dragAmount.x.toInt(), dragAmount.y.toInt())
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Drag here to move mouse", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { scope.launch(Dispatchers.IO) { HidManager.sendMouseReport(1, 0, 0); Thread.sleep(50); HidManager.sendMouseReport(0, 0, 0) } }) {
                    Text("Left Click")
                }
                Button(onClick = { scope.launch(Dispatchers.IO) { HidManager.sendMouseReport(2, 0, 0); Thread.sleep(50); HidManager.sendMouseReport(0, 0, 0) } }) {
                    Text("Right Click")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (kbAvailable) {
            Text("Keyboard Shortcuts", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                KeyButton("Enter", 0x28.toByte(), scope)
                KeyButton("Esc", 0x29.toByte(), scope)
                KeyButton("Tab", 0x2B.toByte(), scope)
                KeyButton("Space", 0x2C.toByte(), scope)
                KeyButton("BS", 0x2A.toByte(), scope)
                KeyButton("Up", 0x52.toByte(), scope)
                KeyButton("Down", 0x51.toByte(), scope)
                KeyButton("Left", 0x50.toByte(), scope)
                KeyButton("Right", 0x4F.toByte(), scope)
            }
        }
    }
}

@Composable
fun StatusChip(label: String, available: Boolean) {
    Surface(
        color = if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "$label: ${if (available) "OK" else "Missing"}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun KeyButton(label: String, hidCode: Byte, scope: kotlinx.coroutines.CoroutineScope) {
    Button(
        onClick = { scope.launch(Dispatchers.IO) { HidManager.typeKey(hidCode) } },
        modifier = Modifier.padding(4.dp)
    ) {
        Text(label)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var stopOnClose by remember { mutableStateOf(ImageStorage.getStopOnClose(context)) }
    var baseDir by remember { mutableStateOf(ImageStorage.getBaseDir(context) ?: "Not set") }

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
            Toast.makeText(context, "ISO Directory updated", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Hosting Behavior", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop hosting when app closes", modifier = Modifier.weight(1f))
                    Switch(
                        checked = stopOnClose,
                        onCheckedChange = {
                            stopOnClose = it
                            ImageStorage.setStopOnClose(context, it)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Network Share (WebDAV)", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                var stopWebdavOnClose by remember { mutableStateOf(ImageStorage.getStopWebdavOnClose(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop WebDAV when app closes", modifier = Modifier.weight(1f))
                    Switch(
                        checked = stopWebdavOnClose,
                        onCheckedChange = {
                            stopWebdavOnClose = it
                            ImageStorage.setStopWebdavOnClose(context, it)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Current ISO Directory:", style = MaterialTheme.typography.labelSmall)
                Text(baseDir, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { dirPicker.launch(null) }) {
                    Text("Change ISO Directory")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Changing the directory will scan for ISO/IMG files in the new location.",
                    style = MaterialTheme.typography.labelSmall, 
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
