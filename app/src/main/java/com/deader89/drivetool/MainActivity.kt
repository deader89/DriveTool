package com.deader89.drivetool

import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
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
        Log.d("MainActivity", "onDestroy: App wird geschlossen, beginne Hardware-Cleanup...")
        super.onDestroy()
        val stopHosting = ImageStorage.getStopOnClose(this)
        val stopWebdav = ImageStorage.getStopWebdavOnClose(this)
        
        if (stopWebdav) {
            WebDavService.stop(this)
        }

        if (stopHosting) {
            Log.d("MainActivity", "Automatischer Teardown gestartet...")
            HidManager.teardown()
            Log.d("MainActivity", "Automatischer Teardown abgeschlossen.")
        } else {
            Log.d("MainActivity", "Teardown übersprungen (Einstellung), schließe nur Streams.")
            HidManager.closeStreams()
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
    
    // Status states
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var isHosting by remember { mutableStateOf(false) }
    var lunPath by remember { mutableStateOf<String?>(null) }
    var activeImage by remember { mutableStateOf<String?>(null) }

    // Management states
    val baseDir = ImageStorage.getBaseDir(context) ?: ""
    var images by remember { mutableStateOf(ImageStorage.getImagesInBaseDir(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("new_disk") }
    var selectedSizeGb by remember { mutableIntStateOf(4) }
    var selectedFs by remember { mutableStateOf(DriveManager.FileSystem.FAT32) }

    // Initialization
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val available = RootUtils.isRootAvailable()
            rootAvailable = available
            if (available) {
                lunPath = DriveManager.findLunPath()
                isHosting = DriveManager.isHosting()
                if (isHosting) {
                    activeImage = RootUtils.execute("cat '$lunPath'").getOrNull()?.trim()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ISO Mount & Manage", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { 
                images = ImageStorage.getImagesInBaseDir(context)
                scope.launch(Dispatchers.IO) {
                    isHosting = DriveManager.isHosting()
                    if (isHosting) {
                        activeImage = RootUtils.execute("cat '${DriveManager.findLunPath()}'").getOrNull()?.trim()
                    }
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
        
        // Root & LUN Status
        if (rootAvailable == false) {
            Text("Root access denied!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        } else if (lunPath == null && rootAvailable == true) {
            Text("No UMS support detected!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        } else if (isHosting) {
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

        Spacer(modifier = Modifier.height(8.dp))
        Text("Directory: $baseDir", style = MaterialTheme.typography.labelSmall)
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create New Empty ISO")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(images) { path ->
                ImageItem(
                    path = path, 
                    isCurrentlyHosting = isHosting && activeImage == path,
                    onMountChange = { newState, imgPath ->
                        isHosting = newState
                        activeImage = if (newState) imgPath else null
                    },
                    onDelete = {
                        images = ImageStorage.getImagesInBaseDir(context)
                    }
                )
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
                    onClick = { /* Die Action wird primär über den IconButton gesteuert, aber wir lassen den Klick hier für UX zu */ 
                        if (!isCurrentlyHosting) {
                            scope.launch(Dispatchers.IO) {
                                val res = DriveManager.hostImage(path, asReadonly = false)
                                withContext(Dispatchers.Main) {
                                    if (res.isSuccess) {
                                        onMountChange(true, path)
                                        Toast.makeText(context, "Mounted (RW)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    onLongClick = {
                        if (!isCurrentlyHosting) {
                            scope.launch(Dispatchers.IO) {
                                val res = DriveManager.hostImage(path, asReadonly = true)
                                withContext(Dispatchers.Main) {
                                    if (res.isSuccess) {
                                        onMountChange(true, path)
                                        Toast.makeText(context, "Mounted (CD-ROM)", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
            
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (isCurrentlyHosting) {
                            DriveManager.stopHosting()
                            withContext(Dispatchers.Main) { onMountChange(false, path) }
                        } else {
                            val res = DriveManager.hostImage(path, asReadonly = false)
                            withContext(Dispatchers.Main) {
                                if (res.isSuccess) {
                                    onMountChange(true, path)
                                    Toast.makeText(context, "Mounted (RW)", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            ) {
                Icon(
                    if (isCurrentlyHosting) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Mount",
                    tint = if (isCurrentlyHosting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                                val rootDoc = DocumentFile.fromTreeUri(context, webdavDir.toUri())
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
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // Fallback
                }
            }) {
                Text("Open Battery Settings")
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
    var kbWritable by remember { mutableStateOf(false) }
    var mouseWritable by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    var keyboardTextState by remember { mutableStateOf(TextFieldValue(" ")) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kbAvailable = HidManager.isKeyboardAvailable()
            mouseAvailable = HidManager.isMouseAvailable()
            kbWritable = HidManager.isKeyboardWritable()
            mouseWritable = HidManager.isMouseWritable()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Fullscreen Touchpad
        if (mouseAvailable) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                scope.launch(Dispatchers.IO) {
                                    HidManager.sendMouseReport(1, 0, 0)
                                    delay(50)
                                    HidManager.sendMouseReport(0, 0, 0)
                                }
                            },
                            onLongPress = {
                                scope.launch(Dispatchers.IO) {
                                    HidManager.sendMouseReport(2, 0, 0)
                                    delay(50)
                                    HidManager.sendMouseReport(0, 0, 0)
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.changes.size >= 3) {
                                    focusRequester.requestFocus()
                                }
                            }
                        }
                    }
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(0.3f)) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(64.dp))
                    Text("HID Mode Active")
                    Text("Tap: Left | Long: Right | 3-Finger: Keyboard", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Mode Switch / Activation Card
        if (!kbAvailable && !mouseAvailable) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .align(Alignment.Center)
                    .zIndex(2f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Switch to HID Mode", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Activate HID to use your phone as a keyboard and mouse. This will temporarily stop any active USB hosting.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                // 1. Hosting beenden um HW-Konflikte zu vermeiden
                                DriveManager.stopHosting()
                                delay(500)
                                // 2. HID starten
                                val result = HidManager.setupHidNodes()
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        kbAvailable = HidManager.isKeyboardAvailable()
                                        mouseAvailable = HidManager.isMouseAvailable()
                                        kbWritable = HidManager.isKeyboardWritable()
                                        mouseWritable = HidManager.isMouseWritable()
                                    } else {
                                        Toast.makeText(context, "Mode switch failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Activate HID Mode")
                    }
                }
            }
        }

        // Invisible Keyboard Input Trigger
        BasicTextField(
            value = keyboardTextState,
            onValueChange = { newValue: TextFieldValue ->
                val newText = newValue.text
                val oldText = keyboardTextState.text

                if (newText.length > oldText.length) {
                    // Zeichen hinzugefügt
                    val addedChar = newText.last()
                    // Nur senden, wenn es nicht das initiale Buffer-Space ist
                    scope.launch(Dispatchers.IO) { HidManager.typeChar(addedChar) }
                } else if (newText.length < oldText.length) {
                    // Backspace gedrückt
                    scope.launch(Dispatchers.IO) { HidManager.typeKey(0x2A.toByte()) }
                }
                
                // Reset auf Buffer-Space (" "), damit Backspace immer aktiv bleibt
                keyboardTextState = TextFieldValue(" ", selection = androidx.compose.ui.text.TextRange(1))
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (keyEvent.key) {
                            Key.Enter -> { scope.launch(Dispatchers.IO) { HidManager.typeKey(0x28.toByte()) }; true }
                            else -> false
                        }
                    } else false
                }
        )

        // Overlay Status & Standard Mode Toggle
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .zIndex(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    StatusChip("KB", kbAvailable, kbWritable)
                    Spacer(modifier = Modifier.width(4.dp))
                    StatusChip("Mouse", mouseAvailable, mouseWritable)
                }
                
                if (kbAvailable || mouseAvailable) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                HidManager.teardown()
                                withContext(Dispatchers.Main) {
                                    kbAvailable = false
                                    mouseAvailable = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("To Standard Mode")
                    }
                }
            }

            if (kbAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                // Essential Win Shortcuts
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    ShortcutButton("WIN", 0x08.toByte(), 0x00.toByte(), scope)
                    ShortcutButton("TaskMgr", 0x05.toByte(), 0x29.toByte(), scope)
                    ShortcutButton("Alt+Tab", 0x04.toByte(), 0x2B.toByte(), scope)
                    ShortcutButton("Enter", 0x00.toByte(), 0x28.toByte(), scope)
                }
            }
        }
    }
}

@Composable
fun ShortcutButton(label: String, modifiers: Byte, key: Byte, scope: kotlinx.coroutines.CoroutineScope) {
    Button(
        onClick = {
            scope.launch(Dispatchers.IO) {
                if (modifiers == 0.toByte()) {
                    HidManager.typeKey(key)
                } else {
                    // Specific complex shortcut logic if needed
                    if (label == "TaskMgr") {
                         HidManager.sendKeyboardReport(0x05.toByte(), byteArrayOf(0x29.toByte())) // Ctrl+Shift+Esc
                         delay(50)
                         HidManager.releaseAllKeys()
                    } else {
                        HidManager.sendKeyboardReport(modifiers, byteArrayOf(key))
                        delay(50)
                        HidManager.releaseAllKeys()
                    }
                }
            }
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(36.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun StatusChip(label: String, exists: Boolean, writable: Boolean) {
    val color = when {
        !exists -> MaterialTheme.colorScheme.errorContainer
        !writable -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val text = when {
        !exists -> "Missing"
        !writable -> "No Perm"
        else -> "OK"
    }
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "$label: $text",
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
                Text("USB Emulation", style = MaterialTheme.typography.titleMedium)
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
