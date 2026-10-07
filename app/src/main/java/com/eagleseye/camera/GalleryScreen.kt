package com.eagleseye.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

sealed class GalleryItem {
    abstract val uri: Uri
    abstract val name: String
    abstract val size: Long
    abstract val dateAdded: Long

    data class Photo(override val uri: Uri, override val name: String, override val size: Long, val width: Int, val height: Int, override val dateAdded: Long) : GalleryItem()
    data class Video(override val uri: Uri, override val name: String, override val size: Long, val durationMs: Long, override val dateAdded: Long) : GalleryItem()
}

fun loadEaglesEyeMedia(context: Context, newestFirst: Boolean = true): List<GalleryItem> {
    val photos = mutableListOf<GalleryItem.Photo>()
    val videos = mutableListOf<GalleryItem.Video>()
    val sort = if (newestFirst) " DESC" else " ASC"
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_ADDED
            ),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_TRASHED}!=1", arrayOf("EaglesEye_%"),
            "${MediaStore.Images.Media.DATE_ADDED}$sort"
        )?.use { cursor ->
            val idC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val wC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val dC = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idC)
                photos.add(
                    GalleryItem.Photo(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        cursor.getString(nC) ?: "", cursor.getLong(sC), cursor.getInt(wC), cursor.getInt(hC), cursor.getLong(dC)
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            ),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_TRASHED}!=1", arrayOf("EaglesEye_%"),
            "${MediaStore.Video.Media.DATE_ADDED}$sort"
        )?.use { cursor ->
            val idC = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nC = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sC = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dC = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val aC = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idC)
                videos.add(
                    GalleryItem.Video(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        cursor.getString(nC) ?: "", cursor.getLong(sC), cursor.getLong(dC), cursor.getLong(aC)
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return (photos + videos).sortedByDescending { it.dateAdded }.let { if (newestFirst) it else it.reversed() }
}

fun loadTrashedMedia(context: Context): List<GalleryItem> {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return emptyList()
    val out = mutableListOf<GalleryItem>()
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_ADDED),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_TRASHED}=1", arrayOf("EaglesEye_%"), null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val nC = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sC = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE); val wC = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hC = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT); val dC = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) out.add(GalleryItem.Photo(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idC)),
                c.getString(nC) ?: "", c.getLong(sC), c.getInt(wC), c.getInt(hC), c.getLong(dC)))
        }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED),
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_TRASHED}=1", arrayOf("EaglesEye_%"), null
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID); val nC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sC = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE); val dC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val aC = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) out.add(GalleryItem.Video(
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idC)),
                c.getString(nC) ?: "", c.getLong(sC), c.getLong(dC), c.getLong(aC)))
        }
    } catch (e: Exception) { e.printStackTrace() }
    return out.sortedByDescending { it.dateAdded }
}

fun setMediaTrashed(context: Context, uris: List<Uri>, trashed: Boolean) {
    if (uris.isEmpty()) return
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
        if (trashed) uris.forEach { uri -> try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) {} }
        return
    }
    uris.forEach { uri ->
        try {
            val cv = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
            context.contentResolver.update(uri, cv, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }
}

fun formatFileSize(bytes: Long): String {    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1.0) "${(bytes / 1024.0).toInt()} KB" else String.format("%.1f MB", mb)
}

fun formatPhotoDate(epochSeconds: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000))

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60; val s = total % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

private fun dayKey(epochSeconds: Long): Long = epochSeconds / 86400L

private fun sectionLabel(epochSeconds: Long): String {
    val cal = Calendar.getInstance()
    val todayKey = dayKey(cal.timeInMillis / 1000L)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yestKey = dayKey(cal.timeInMillis / 1000L)
    val k = dayKey(epochSeconds)
    return when (k) {
        todayKey -> "Today"
        yestKey -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochSeconds * 1000))
    }
}

private val G_BG = Color(0xFF08080A)
private val G_GOLD: Color get() = AppAccent.color

@Composable
fun GalleryScreen(onClose: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    var media by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var edits by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var trashed by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var section by remember { mutableStateOf("photos") } // photos | edits | live
    var showTrash by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(true) }
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var cols by remember { mutableStateOf(3) }
    var refreshKey by remember { mutableStateOf(0) }
    var previewItem by remember { mutableStateOf<GalleryItem?>(null) }
    var deleteConfirm by remember { mutableStateOf<Set<Uri>?>(null) }
    val favPrefs = remember { context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE) }
    var favorites by remember {
        mutableStateOf(
            (favPrefs.getStringSet("favorites", emptySet()) ?: emptySet()).map { Uri.parse(it) }.toSet()
        )
    }
    fun toggleFavorite(uri: Uri) {
        favorites = if (uri in favorites) favorites - uri else favorites + uri
        favPrefs.edit().putStringSet("favorites", favorites.map { it.toString() }.toSet()).apply()
    }
    var editUri by remember { mutableStateOf<Uri?>(null) }
    // Custom (non-EaglesEye) photo opened in the live analogue engine sheet.
    var customPhoto by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) customPhoto = picked
    }
    LaunchedEffect(refreshKey, newestFirst) {
        val all = loadEaglesEyeMedia(context, newestFirst)
        media = all.filter { !it.name.contains("EaglesEye_edit_") }
        edits = all.filter { it.name.contains("EaglesEye_edit_") }
        trashed = loadTrashedMedia(context)
    }

    val videoList = media.filterIsInstance<GalleryItem.Video>()
    val currentList: List<GalleryItem> = when {
        showTrash -> trashed
        section == "edits" -> edits
        section == "live" -> videoList
        else -> media
    }
    val qs = query.trim()
    val gridItems = currentList.filter { qs.isEmpty() || it.name.contains(qs, ignoreCase = true) }

    val dayStarts = remember(currentList) {
        val set = mutableSetOf<Int>()
        var last = -1L
        currentList.forEachIndexed { i, p ->
            val k = dayKey(p.dateAdded)
            if (k != last) { set.add(i); last = k }
        }
        set
    }

    fun doDelete(uris: Set<Uri>) {
        if (showTrash) {
            uris.forEach { uri -> try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) {} }
        } else {
            setMediaTrashed(context, uris.toList(), trashed = true)
        }
        selected = emptySet(); selectMode = false; deleteConfirm = null; refreshKey++
    }

    val gridState = rememberLazyGridState()
    val dayLabel = remember(media, gridState.firstVisibleItemIndex) {
        media.getOrNull(gridState.firstVisibleItemIndex)?.let { sectionLabel(it.dateAdded) }
    }

    BackHandler {
        when {
            customPhoto != null -> customPhoto = null
            editUri != null -> editUri = null
            previewItem != null -> previewItem = null
            selectMode -> { selectMode = false; selected = emptySet() }
            showSortMenu -> showSortMenu = false
            showSearch -> showSearch = false
            showTrash -> showTrash = false
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(G_BG)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ── Top bar (floating glass) ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .then(Modifier.uiGlass(RoundedCornerShape(22.dp)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF15151A))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (selectMode) { selectMode = false; selected = emptySet() } else onClose()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (selectMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        if (selectMode) "Cancel selection" else "Back",
                        tint = Color.White, modifier = Modifier.size(17.dp)
                    )
                }
                if (selectMode) {
                    Text(
                        "${selected.size} selected",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("EAGLESEYE", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                        Text(
                            "LIBRARY  ·  ${media.size} ITEM${if (media.size == 1) "" else "S"}",
                            color = G_GOLD, fontSize = 9.sp, letterSpacing = 1.8.sp
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (showSortMenu) G_GOLD else Color(0xFF15151A))
                            .clickable(remember { MutableInteractionSource() }, null) { showSortMenu = !showSortMenu },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SwapVert, "Sort order",
                            tint = if (showSortMenu) Color.Black else Color.White, modifier = Modifier.size(16.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (showSearch) G_GOLD else Color(0xFF15151A))
                            .clickable(remember { MutableInteractionSource() }, null) { showSearch = !showSearch },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Search, "Search library",
                            tint = if (showSearch) Color.Black else Color.White, modifier = Modifier.size(16.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (cols == 2) G_GOLD else Color(0xFF15151A))
                            .clickable(remember { MutableInteractionSource() }, null) { cols = if (cols == 2) 3 else 2 },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (cols == 2) Icons.Default.TableRows else Icons.Default.GridView, "Grid density",
                            tint = if (cols == 2) Color.Black else Color.White, modifier = Modifier.size(16.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF15151A))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add, "Edit a custom photo",
                            tint = Color.White, modifier = Modifier.size(18.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF15151A))
                            .clickable(remember { MutableInteractionSource() }, null) { selectMode = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SELECT",
                            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp
                        )
                    }
                }
                AnimatedVisibility(
                    visible = selectMode,
                    enter = fadeIn(tween(150)) + expandHorizontally(),
                    exit = fadeOut(tween(120)) + shrinkHorizontally()
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected.size == gridItems.size && gridItems.isNotEmpty()) G_GOLD else Color(0xFF15151A))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    selected = if (selected.size == gridItems.size) emptySet() else gridItems.map { it.uri }.toSet()
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (selected.size == gridItems.size) "CLEAR" else "SELECT ALL",
                                color = if (selected.size == gridItems.size) Color.Black else Color.White,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp
                            )
                        }
                    }
                }
            }

            // ── Sort dropdown ──
            AnimatedVisibility(
                showSortMenu,
                enter = fadeIn(tween(160)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(140))
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .shadow(18.dp, RoundedCornerShape(18.dp), true, Color(0x88000000), Color.Transparent)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xE61A1A20))
                        .border(1.dp, Color(0xFF26262F), RoundedCornerShape(18.dp))
                        .padding(vertical = 6.dp)
                ) {
                    listOf(true to "Newest first", false to "Oldest first").forEach { (n, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    newestFirst = n; showSortMenu = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(if (newestFirst == n) G_GOLD else Color.Transparent)
                                    .border(1.5.dp, if (newestFirst == n) G_GOLD else Color(0xFF3A3A46), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (newestFirst == n) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.Black))
                            }
                            Text(
                                label,
                                color = if (newestFirst == n) Color.White else Color.White.copy(0.55f),
                                fontSize = 13.sp, fontWeight = if (newestFirst == n) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // ── Search bar ──
            AnimatedVisibility(
                visible = showSearch && !selectMode,
                enter = fadeIn(tween(150)) + expandVertically(tween(170)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(140))
            ) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .shadow(14.dp, RoundedCornerShape(50), true, Color(0x88000000), Color.Transparent)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF15151A))
                        .border(1.dp, if (query.isNotEmpty()) G_GOLD.copy(0.5f) else Color(0xFF26262F), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, "Search", tint = Color.White.copy(0.5f), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(G_GOLD),
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close, "Clear search", tint = Color.White.copy(0.5f),
                            modifier = Modifier.size(14.dp).clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { query = "" }
                        )
                    }
                }
            }

            // ── Seamless grid ──
            if (gridItems.isEmpty() && !(section == "live" && !showTrash)) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(92.dp).clip(CircleShape).background(Brush.radialGradient(listOf(G_GOLD.copy(0.18f), Color(0xFF141419)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (showTrash) Icons.Default.DeleteSweep else Icons.Default.PhotoLibrary, null, tint = G_GOLD.copy(alpha = 0.6f), modifier = Modifier.size(36.dp))
                        }
                        Text(
                            if (qs.isNotEmpty()) "No matches for \"$qs\""
                            else when {
                                showTrash -> "Trash is empty"
                                section == "edits" -> "No edited copies yet — open a photo and tap Edit"
                                else -> "Nothing here yet"
                            },
                            color = Color.White.copy(0.4f), fontSize = 16.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (showTrash) "Deleted items appear here and can be restored"
                            else "Your EaglesEye photos and videos will show up here",
                            color = Color.White.copy(0.25f), fontSize = 12.sp
                        )
                    }
                }
            } else if (section == "live" && !showTrash) {
                Column(Modifier.fillMaxSize()) {
                    LiveCameraTile(onOpenCamera = onClose)
                    LiveGLTile(onOpen = {
                        context.startActivity(Intent(context, LiveGLPreviewActivity::class.java))
                    })
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        state = gridState,
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(gridItems, key = { _, p -> p.uri.toString() }) { idx, item ->
                            MediaCell(
                                item = item,
                                selectMode = selectMode,
                                selected = item.uri in selected,
                                favorite = item.uri in favorites,
                                dayLabel = null,
                                topGap = 0.dp,
                                onTap = {
                                    when {
                                        selectMode -> selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                                        previewItem != null -> previewItem = null
                                        else -> previewItem = item
                                    }
                                },
                                onLongPress = {
                                    selectMode = true
                                    selected = selected + item.uri
                                }
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    state = gridState,
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(gridItems, key = { _, p -> p.uri.toString() }) { idx, item ->
                        MediaCell(
                            item = item,
                            selectMode = selectMode,
                            selected = item.uri in selected,
                            favorite = item.uri in favorites,
                            dayLabel = if (idx in dayStarts) sectionLabel(item.dateAdded) else null,
                            topGap = if (idx in dayStarts && idx > 0) 14.dp else 0.dp,
                            onTap = {
                                when {
                                    selectMode -> selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                                    previewItem != null -> previewItem = null
                                    else -> previewItem = item
                                }
                            },
                            onLongPress = {
                                selectMode = true
                                selected = selected + item.uri
                            }
                        )
                    }
                }
            }
        }

        // ── Floating day pill ──
        AnimatedVisibility(
            visible = dayLabel != null && !selectMode && section == "photos" && gridState.firstVisibleItemIndex > 0,
            enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
        ) {
            dayLabel?.let { label ->
                Box(
                    Modifier
                        .shadow(12.dp, RoundedCornerShape(50), true, Color(0x88000000), Color.Transparent)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xE622222B))
                        .border(1.dp, Color(0xFF33333F), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
                }
            }
        }

        // ── Floating selection pill ──
        AnimatedVisibility(
            visible = selectMode && selected.isNotEmpty(),
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (showTrash) 86.dp else 0.dp)
        ) {
            Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .shadow(18.dp, RoundedCornerShape(24.dp), true, Color(0xCC000000), Color.Transparent)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xED1C1C24))
                    .border(1.dp, Color(0xFF2E2E3A), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${selected.size}",
                    color = G_GOLD, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            selected.firstOrNull()?.let { u ->
                                (gridItems + currentList + media + edits + trashed).firstOrNull { it.uri == u }?.let { m -> shareItem(context, m) }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White.copy(0.85f), modifier = Modifier.size(18.dp))
                }
                Box(Modifier.width(1.dp).height(24.dp).background(Color(0xFF2E2E3A)))
                if (showTrash) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF2A2A35))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                setMediaTrashed(context, selected.toList(), trashed = false)
                                selected = emptySet(); selectMode = false; refreshKey++
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Default.Restore, "Restore", tint = Color.White, modifier = Modifier.size(15.dp))
                            Text("RESTORE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(RecordRed.copy(alpha = 0.9f))
                            .clickable(remember { MutableInteractionSource() }, null) { deleteConfirm = selected }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Default.Delete, "Delete forever", tint = Color.White, modifier = Modifier.size(15.dp))
                            Text("DELETE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(RecordRed.copy(alpha = 0.9f))
                            .clickable(remember { MutableInteractionSource() }, null) { deleteConfirm = selected }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color.White, modifier = Modifier.size(15.dp))
                            Text("DELETE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        }
                    }
                }
            }
        }

        // ── Floating bottom pill (Photos · Edits · Live · Trash · Settings) ──
        AnimatedVisibility(
            visible = !selectMode,
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(280)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            GalleryPill(
                section = section,
                showTrash = showTrash,
                onSection = { s -> section = s; showTrash = false; refreshKey++ },
                onTrash = { showTrash = !showTrash; refreshKey++ },
                onSettings = onOpenSettings
            )
        }
    }

    // ── Preview overlay ──
    AnimatedVisibility(
        visible = previewItem != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180))
    ) {
        previewItem?.let { initial ->
            MediaPreviewOverlay(
                media = gridItems.ifEmpty { currentList }, initialItem = initial,
                onDismiss = { previewItem = null },
                context = context,
                favorites = favorites,
                onToggleFavorite = { uri -> toggleFavorite(uri) },
                onDelete = { p -> deleteConfirm = setOf(p.uri); previewItem = null },
                onEdit = { editUri = it }
            )
        }
    }

    // ── Editor overlay ──
    editUri?.let { u ->
        EditorScreen(
            uri = u,
            onClose = { editUri = null },
            onSaved = { editUri = null; previewItem = null; refreshKey++ },
            editMode = true
        )
    }

    // ── Custom photo analogue editor ──
    customPhoto?.let { u ->
        PhotoAnalogSheet(
            uri = u,
            onClose = { customPhoto = null },
            onSaved = { customPhoto = null; refreshKey++ }
        )
    }

    // ── Delete confirmation ──
    deleteConfirm?.let { uris ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            confirmButton = { TextButton(onClick = { doDelete(uris) }) { Text("Delete", color = RecordRed, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") } },
            title = { Text("Delete ${uris.size} item${if (uris.size > 1) "s" else ""}?") },
            text = { Text(if (showTrash) "This cannot be undone." else "Items are moved to trash and can be restored.") },
            containerColor = Color(0xFF1A1A20),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(0.7f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: GalleryItem,
    selectMode: Boolean,
    selected: Boolean,
    favorite: Boolean,
    topGap: Dp,
    dayLabel: String? = null,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "cellScale"
    )
    val dimAlpha by animateFloatAsState(if (selected) 0.35f else 0f, tween(130), label = "cellDim")
    val checkScale by animateFloatAsState(
        if (selected) 1f else 0.4f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "checkScale"
    )
    val checkAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(120), label = "checkAlpha")
    val ph = remember { ColorPainter(Color(0xFF121318)) }
    Column(Modifier.padding(top = topGap)) {
        if (dayLabel != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(G_GOLD))
                Text(
                    dayLabel.uppercase(),
                    color = Color.White.copy(0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.3.sp
                )
            }
        }
        Box(
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF121318))
                .border(1.5.dp, if (selected) G_GOLD else Color.White.copy(0.06f), RoundedCornerShape(16.dp))
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onTap,
                    onLongClick = onLongPress
                )
        ) {
            when (item) {
                is GalleryItem.Photo -> AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = ph,
                    error = ph,
                    modifier = Modifier.fillMaxSize()
                )
                is GalleryItem.Video -> VideoThumb(item.uri, Modifier.fillMaxSize())
            }
            if (item is GalleryItem.Video) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(0.68f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        formatDuration(item.durationMs),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.55f))
                        .border(1.dp, Color.White.copy(0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, "Video", tint = Color.White.copy(0.95f), modifier = Modifier.size(18.dp))
                }
            }
            if (dimAlpha > 0f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
            }
            if (favorite && !selectMode) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Star, "Favorite", tint = G_GOLD, modifier = Modifier.size(13.dp))
                }
            }
            if (selectMode || selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkAlpha }
                        .clip(CircleShape)
                        .background(if (selected) G_GOLD else Color.Black.copy(0.55f))
                        .border(if (selected) 2.dp else 1.5.dp, if (selected) G_GOLD else Color.White.copy(0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(Icons.Default.Check, "Selected", tint = Color.Black, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoThumb(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(512, 512), null)
            } catch (e: Exception) { null }
        }
    }
    val bm = bmp
    if (bm != null) {
        Image(bitmap = bm.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Color(0xFF14141A)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun MediaPreviewOverlay(
    media: List<GalleryItem>,
    initialItem: GalleryItem,
    onDismiss: () -> Unit,
    context: Context = LocalContext.current,
    favorites: Set<Uri> = emptySet(),
    onToggleFavorite: (Uri) -> Unit = {},
    onDelete: (GalleryItem) -> Unit = {},
    onEdit: (Uri) -> Unit = {}
) {
    val initialIndex = media.indexOfFirst { it.uri == initialItem.uri }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex) { media.size }
    val current = media.getOrNull(pagerState.currentPage)
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    var isDismissing by remember { mutableStateOf(false) }
    var currentZoom by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(pagerState.currentPage) {
        currentZoom = 1f
        playing = current is GalleryItem.Video
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(currentZoom) {
                if (currentZoom > 1f) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(dismissOffset) > 300f) onDismiss()
                        else { dismissOffset = 0f; isDismissing = false }
                    },
                    onVerticalDrag = { _, drag ->
                        dismissOffset += drag
                        isDismissing = true
                    }
                )
            }
            .graphicsLayer {
                translationY = if (isDismissing) dismissOffset else 0f
                alpha = if (isDismissing) (1f - kotlin.math.abs(dismissOffset) / 600f).coerceIn(0f, 1f) else 1f
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = currentZoom <= 1f
        ) { page ->
            when (val item = media[page]) {
                is GalleryItem.Photo -> ZoomablePhotoPage(item, onZoomChanged = { currentZoom = it })
                is GalleryItem.Video -> VideoPlayerPage(
                    uri = item.uri,
                    playing = playing && page == pagerState.currentPage,
                    speed = speed
                )
            }
        }

        // Top bar
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.75f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.14f))
                    .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Close preview", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${pagerState.currentPage + 1} / ${media.size}",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                current?.let {
                    Text(
                        sectionLabel(it.dateAdded),
                        color = Color.White.copy(0.45f), fontSize = 9.sp, letterSpacing = 1.4.sp
                    )
                }
            }
            current?.let { itm ->
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (itm.uri in favorites) G_GOLD else Color.White.copy(0.14f))
                        .clickable(remember { MutableInteractionSource() }, null) { onToggleFavorite(itm.uri) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (itm.uri in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                        if (itm.uri in favorites) "Remove from favorites" else "Add to favorites",
                        tint = if (itm.uri in favorites) Color.Black else Color.White, modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Bottom bar
        current?.let { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                if (item is GalleryItem.Video) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed selector
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(0.1f))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            listOf(1f, 0.5f, 0.25f).forEach { s ->
                                val active = kotlin.math.abs(speed - s) < 0.001f
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (active) G_GOLD else Color.Transparent)
                                        .clickable(remember { MutableInteractionSource() }, null) { speed = s }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (s == 1f) "1x" else if (s == 0.5f) "0.5x" else "0.25x",
                                        color = if (active) Color.Black else Color.White.copy(0.7f),
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        // Play / pause
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(0.14f))
                                .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                .clickable(remember { MutableInteractionSource() }, null) { playing = !playing },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playing) "Pause" else "Play",
                                tint = Color.White, modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        when (item) {
                            is GalleryItem.Photo -> {
                                if (item.width > 0 && item.height > 0)
                                    Text("${item.width} × ${item.height}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (item.size > 0)
                                    Text(formatFileSize(item.size), color = Color.White.copy(0.6f), fontSize = 12.sp)
                            }
                            is GalleryItem.Video -> {
                                Text("Video · ${formatDuration(item.durationMs)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                if (item.size > 0)
                                    Text(formatFileSize(item.size), color = Color.White.copy(0.6f), fontSize = 12.sp)
                            }
                        }
                        Text(formatPhotoDate(item.dateAdded), color = Color.White.copy(0.45f), fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (item is GalleryItem.Photo) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(0.13f))
                                    .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                    .clickable(remember { MutableInteractionSource() }, null) { onEdit(item.uri) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Edit, "Edit", tint = Color.White, modifier = Modifier.size(17.dp))
                            }
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(G_GOLD.copy(0.18f))
                                    .border(1.dp, G_GOLD.copy(0.4f), CircleShape)
                                    .clickable(remember { MutableInteractionSource() }, null) {
                                        context.startActivity(
                                            Intent(context, ClassicEditorActivity::class.java)
                                                .putExtra("EXTRA_URI", item.uri)
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AutoFixHigh, "Tools", tint = G_GOLD, modifier = Modifier.size(19.dp))
                            }
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(0.13f))
                                .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                .clickable(remember { MutableInteractionSource() }, null) { shareItem(context, item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Share, "Share", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(0.13f))
                                .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                .clickable(remember { MutableInteractionSource() }, null) {
                                    openDetails(context, item.uri, if (item is GalleryItem.Video) "video/mp4" else "image/jpeg")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Info, "Details", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF3B30).copy(0.25f))
                                .border(1.dp, Color.White.copy(0.1f), CircleShape)
                                .clickable(remember { MutableInteractionSource() }, null) { onDelete(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6B60), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// Video playback page (MediaPlayer + SurfaceView, speed control, seek bar)
@Composable
private fun VideoPlayerPage(uri: Uri, playing: Boolean, speed: Float) {
    val context = LocalContext.current
    var ready by remember(uri) { mutableStateOf(false) }
    var ended by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf(false) }
    var positionMs by remember(uri) { mutableStateOf(0L) }
    var durationMs by remember(uri) { mutableStateOf(0L) }
    var seeking by remember(uri) { mutableStateOf(false) }
    var seekTarget by remember(uri) { mutableStateOf(0L) }
    val player = remember(uri) { MediaPlayer() }
    DisposableEffect(uri) {
        var released = false
        runCatching {
            player.setDataSource(context, uri)
            player.setOnPreparedListener {
                if (!released) {
                    ready = true
                    durationMs = runCatching { player.duration }.getOrDefault(0).toLong()
                    runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
                    runCatching { player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                    if (playing) player.start()
                }
            }
            player.setOnCompletionListener { if (!released) { ended = true } }
            player.setOnErrorListener { _, _, _ -> if (!released) error = true; true }
            player.prepareAsync()
        }.onFailure { if (!released) error = true }
        onDispose {
            released = true
            runCatching { player.release() }
        }
    }
    LaunchedEffect(speed, ready) {
        if (ready) runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
    }
    LaunchedEffect(playing, ready) {
        if (!ready) return@LaunchedEffect
        if (playing && ended) { ended = false; runCatching { player.seekTo(0) } }
        if (playing) runCatching { if (!player.isPlaying) player.start() }
        else runCatching { if (player.isPlaying) player.pause() }
    }
    LaunchedEffect(uri, ready) {
        while (true) {
            if (ready) {
                if (!seeking) positionMs = runCatching { player.currentPosition }.getOrDefault(0).toLong()
                if (durationMs <= 0L) durationMs = runCatching { player.duration }.getOrDefault(0).toLong()
            }
            delay(400)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(surface: SurfaceHolder) { runCatching { player.setDisplay(surface) } }
                        override fun surfaceChanged(surface: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(surface: SurfaceHolder) {}
                    })
                }
            }
        )
        if (!ready && !error) {
            CircularProgressIndicator(color = G_GOLD, modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp)
        }
        if (error) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cannot play this video", color = Color.White.copy(0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("The file may be unsupported or damaged.", color = Color.White.copy(0.45f), fontSize = 11.sp)
            }
        }
        if (ready && !error && durationMs > 0L) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Slider(
                    value = (if (seeking) seekTarget else positionMs).coerceIn(0L, durationMs).toFloat(),
                    onValueChange = { seeking = true; seekTarget = it.toLong() },
                    onValueChangeFinished = {
                        val target = seekTarget.coerceIn(0L, durationMs)
                        runCatching { player.seekTo(target.toInt()) }
                        positionMs = target
                        seeking = false
                        if (ended) ended = false
                        if (playing) runCatching { if (!player.isPlaying) player.start() }
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = G_GOLD,
                        activeTrackColor = G_GOLD,
                        inactiveTrackColor = Color.White.copy(0.25f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatDuration(if (seeking) seekTarget else positionMs),
                        color = Color.White.copy(0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "-" + formatDuration((durationMs - (if (seeking) seekTarget else positionMs)).coerceAtLeast(0L)),
                        color = Color.White.copy(0.45f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// Per-page zoomable photo: pinch-zoom, double-tap 3x, dismiss drag when unzoomed

/** Max decode edge for the zoomable preview — high enough that 8x zoom keeps
 *  real detail (no blocky upscale of a screen-res bitmap), low enough to fit
 *  a few paged bitmaps in memory. */
private const val ZOOM_DECODE_MAX = 2400

/**
 * Full-screen photo pager page with pinch/double-tap zoom.
 *
 * Decodes the photo with the SAME EXIF-aware decoder the editor uses
 * (`decodeForUri`), so the preview is pixel-identical to the editor's canvas
 * (right orientation, same color/geometry) — no more stale, rotated, or
 * differently-scaled preview vs editor.
 *
 * The zoom transform is applied to a layer that is EXACTLY the size of the
 * fitted image (not the letterboxed viewport), so scaling and panning pivot on
 * the image itself: no warping, no scaling of empty bars, no texture
 * destruction while zooming.
 */
@Composable
private fun ZoomablePhotoPage(photo: GalleryItem.Photo, onZoomChanged: (Float) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scaleAnim = remember(photo.uri) { Animatable(1f) }
    val offsetAnim = remember(photo.uri) { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(scaleAnim.value) { onZoomChanged(scaleAnim.value) }

    val bmp by produceState<Bitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeForUri(context, photo.uri, ZOOM_DECODE_MAX) }.getOrNull()
        }
    }
    val bm = bmp
    if (bm == null) {
        AsyncImage(
            model = photo.uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clipToBounds()
        )
        return
    }

    val imgW = bm.width.toFloat().coerceAtLeast(1f)
    val imgH = bm.height.toFloat().coerceAtLeast(1f)
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val vw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val vh = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fit = kotlin.math.min(vw / imgW, vh / imgH)
        val drawnW = imgW * fit
        val drawnH = imgH * fit
        val sizeMod = with(density) {
            Modifier.size(drawnW.toDp(), drawnH.toDp())
        }

        Image(
            bitmap = bm.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = sizeMod
                .graphicsLayer {
                    scaleX = scaleAnim.value; scaleY = scaleAnim.value
                    translationX = offsetAnim.value.x; translationY = offsetAnim.value.y
                }
        )

        // Full-viewport gesture layer so pinch/taps work over the whole pager
        // surface (including letterbox bars); the transform above only touches
        // the image-sized layer, so clamps below are exact image bounds.
        Box(
            Modifier.fillMaxSize().pointerInput(photo.uri, imgW, imgH) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        if (scaleAnim.value > 1.001f) {
                            scope.launch {
                                scaleAnim.animateTo(1f, spring(0.55f, 350f))
                                offsetAnim.animateTo(Offset.Zero, spring(0.55f, 350f))
                            }
                        } else {
                            val k = 3f
                            var target = pos * (1f - k)
                            val maxX = ((drawnW * k - vw) * 0.5f).coerceAtLeast(0f)
                            val maxY = ((drawnH * k - vh) * 0.5f).coerceAtLeast(0f)
                            target = Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
                            scope.launch {
                                scaleAnim.animateTo(k, spring(0.55f, 350f))
                                offsetAnim.animateTo(target, spring(0.55f, 350f))
                            }
                        }
                    }
                )
            }
        )
        Box(
            Modifier.fillMaxSize().pointerInput(photo.uri, drawnW, drawnH) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cur = scaleAnim.value
                    val newScale = (cur * zoom).coerceIn(1f, 8f)
                    val k = newScale / cur
                    val off = offsetAnim.value
                    var nx = centroid.x + k * (off.x - centroid.x) + pan.x
                    var ny = centroid.y + k * (off.y - centroid.y) + pan.y
                    val maxX = ((drawnW * newScale - vw) * 0.5f).coerceAtLeast(0f)
                    val maxY = ((drawnH * newScale - vh) * 0.5f).coerceAtLeast(0f)
                    nx = nx.coerceIn(-maxX, maxX)
                    ny = ny.coerceIn(-maxY, maxY)
                    scope.launch {
                        scaleAnim.snapTo(newScale)
                        offsetAnim.snapTo(Offset(nx, ny))
                    }
                }
            }
        )
    }
}

private fun shareItem(context: Context, item: GalleryItem) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = if (item is GalleryItem.Video) "video/mp4" else "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share"))
}

private fun openDetails(context: Context, uri: Uri, mime: String = "image/jpeg") {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

// ── Bottom section pill ────────────────────────────────────────────────────
@Composable
private fun GalleryPill(
    section: String,
    showTrash: Boolean,
    onSection: (String) -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .shadow(20.dp, RoundedCornerShape(30.dp), true, Color(0xCC000000), Color.Transparent)
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xF2111218))
            .border(1.dp, Color(0xFF242430), RoundedCornerShape(30.dp))
            .padding(horizontal = 7.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PillItem("Photos", Icons.Default.PhotoLibrary, active = section == "photos" && !showTrash) { onSection("photos") }
        PillItem("Edits", Icons.Default.Edit, active = section == "edits" && !showTrash) { onSection("edits") }
        PillItem("Live", Icons.Default.VideoLibrary, active = section == "live" && !showTrash) { onSection("live") }
        PillItem("Trash", Icons.Default.DeleteSweep, active = showTrash) { onTrash() }
        PillItem("Settings", Icons.Default.Settings, active = false) { onSettings() }
    }
}

@Composable
private fun PillItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val iconScale by animateFloatAsState(if (active) 1.15f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "ps")
    val labelAlpha by animateFloatAsState(if (active) 1f else 0.55f, tween(160), label = "pl")
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .then(if (active) Modifier.background(G_GOLD) else Modifier.background(Color.Transparent))
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, null, tint = if (active) Color.Black else Color.White.copy(0.55f),
            modifier = Modifier.size(18.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
        Text(label, color = if (active) Color.Black else Color.White.copy(0.45f),
            fontSize = 7.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.2.sp, modifier = Modifier.alpha(labelAlpha))
    }
}

// ── Live: prominent "open camera" tile (jumps back to capture) ─────────────
@Composable
private fun LiveCameraTile(onOpenCamera: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(G_GOLD.copy(0.16f), Color(0xFF141419))))
            .border(1.dp, G_GOLD.copy(0.4f), RoundedCornerShape(18.dp))
            .clickable(remember { MutableInteractionSource() }, null) { onOpenCamera() }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(G_GOLD), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VideoLibrary, "Open camera", tint = Color.Black, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Open Camera", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Jump to live capture", color = Color.White.copy(0.5f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LiveGLTile(onOpen: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A2A55), Color(0xFF141419))))
            .border(1.dp, Color(0xFF6A4FB0).copy(0.5f), RoundedCornerShape(18.dp))
            .clickable(remember { MutableInteractionSource() }, null) { onOpen() }
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF6A4FB0)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, "Live GL preview", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column {
                Text("Live GL Preview", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("OpenGL live capture (MIT: CameraRecorder-android)", color = Color.White.copy(0.5f), fontSize = 11.sp)
            }
        }
    }
}
