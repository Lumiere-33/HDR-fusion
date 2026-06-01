package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme

// Dynamic high contrast Photography color scheme
val DeepGrey = Color(0xFF0F0F11)
val SlateCard = Color(0xFF1B1B1F)
val BorderSlate = Color(0xFF2B2B33)
val BrightAmber = Color(0xFFFFA000)
val HighlightSg = Color(0xFFFFC107)
val GlowAmber = Color(0x33FFA000)
val CleanWhite = Color(0xFFF0F0F2)
val SoftGrey = Color(0xFF9EA3AC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) { // Explicit dark mode for consistent premium photo grading UI
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DeepGrey
                ) { innerPadding ->
                    HdrFusionApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HdrFusionApp(
    modifier: Modifier = Modifier,
    viewModel: HdrViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Setup multiple image picker launcher
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            if (uris.size in 2..3) {
                viewModel.loadImagesFromUris(context, uris)
            } else {
                Toast.makeText(context, "Please select exactly 2 or 3 exposures to fuse.", Toast.LENGTH_LONG).show()
                viewModel.loadImagesFromUris(context, uris) // Will trigger error in model
            }
        }
    }

    // Monitor success or error toast messages
    LaunchedEffect(uiState.processState) {
        if (uiState.processState == ProcessState.SUCCESS) {
            Toast.makeText(context, "HDR Exposure Saved to Gallery!", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepGrey)
            .verticalScroll(rememberScrollState())
    ) {
        // Headers area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        brush = Brush.radialGradient(listOf(HighlightSg, BrightAmber)),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = "HDR Lens Icon",
                    tint = DeepGrey,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "HDR FUSION",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = CleanWhite
                )
                Text(
                    text = "Exposure Alignment & Blending Engine",
                    fontSize = 11.sp,
                    color = SoftGrey,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Divider(color = BorderSlate, thickness = 1.dp)

        if (uiState.selectedImages.isEmpty()) {
            // Unselected / Empty view
            HdrEmptyState(
                onSelectClick = { pickerLauncher.launch("image/*") }
            )
        } else {
            // Real workspace content
            HdrWorkspace(
                uiState = uiState,
                viewModel = viewModel,
                onSelectNewClick = { pickerLauncher.launch("image/*") },
                onSaveClick = { viewModel.saveHighResExposure(context) }
            )
        }
    }
}

@Composable
fun HdrEmptyState(
    onSelectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(30.dp))

        // Large graphics representing bracketed exposures
        Box(
            modifier = Modifier
                .size(240.dp)
                .drawBehind {
                    // Draw exposure brackets illustration
                    val cardW = 120.dp.toPx()
                    val cardH = 80.dp.toPx()

                    // Back overexposed bracket
                    drawRoundRect(
                        color = Color(0x33FFA000),
                        topLeft = Offset(center.x - cardW / 2 + 30.dp.toPx(), center.y - cardH / 2 - 25.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(cardW, cardH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )

                    // Front underexposed bracket
                    drawRoundRect(
                        color = Color(0x66FFA000),
                        topLeft = Offset(center.x - cardW / 2 - 30.dp.toPx(), center.y - cardH / 2 + 15.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(cardW, cardH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )

                    // Center normal exposure bracket
                    drawRoundRect(
                        color = BrightAmber,
                        topLeft = Offset(center.x - cardW / 2, center.y - cardH / 2 - 5.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(cardW, cardH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Decor",
                tint = BrightAmber,
                modifier = Modifier
                    .size(48.dp)
                    .offset(y = (-5).dp)
            )
        }

        Text(
            text = "No Exposure Brackets Loaded",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = CleanWhite,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Fusing exposures expands your camera's dynamic range. Load 2 or 3 photos with varying brightness from your gallery to construct a high-fidelity rendering.",
            fontSize = 13.sp,
            color = SoftGrey,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Golden action button
        Button(
            onClick = onSelectClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrightAmber,
                contentColor = DeepGrey
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("select_photos_button"),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = "Select photos",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "SELECT 2 OR 3 PHOTOS",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Small instructional cards
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, BorderSlate),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "HDR WORKFLOW GUIDE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrightAmber,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                InstructionRow(index = "1", text = "For optimal outcome, choose photos of the same framing with slight shifts (e.g. handheld bursts).")
                InstructionRow(index = "2", text = "Select an underexposed (dark Highlights), normal (Midtones) and overexposed (Bright shadows) shot.")
                InstructionRow(index = "3", text = "Our auto-registration engine will pixel-align background features automatically to remove ghosting!")
            }
        }
    }
}

@Composable
fun InstructionRow(index: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(BorderSlate, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(index, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrightAmber)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = SoftGrey, lineHeight = 16.sp)
    }
}

@Composable
fun HdrWorkspace(
    uiState: HdrUiState,
    viewModel: HdrViewModel,
    onSelectNewClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    var expandedConsole by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Selected Bracket Photos Row Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BRACKET FRAMES (${uiState.selectedImages.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = BrightAmber,
                letterSpacing = 1.sp
            )

            TextButton(
                onClick = onSelectNewClick,
                colors = ButtonDefaults.textButtonColors(contentColor = HighlightSg)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select Other", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Horizontal items layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            uiState.selectedImages.forEachIndexed { index, image ->
                BracketImageCard(
                    index = index,
                    image = image,
                    isManualAlign = uiState.alignmentMode == AlignmentMode.MANUAL,
                    onNudge = { dx, dy -> viewModel.adjustManualOffset(image.id, dx, dy) },
                    onReset = { viewModel.resetOffsets(image.id) },
                    onDelete = { viewModel.removeImage(image.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // High visual preview section
        PrimaryPreviewContainer(uiState = uiState, viewModel = viewModel)

        Spacer(modifier = Modifier.height(16.dp))

        // Main controls card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderSlate)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // INTENSITY SECTION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = BrightAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "HDR Effect Intensity",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CleanWhite
                        )
                    }
                    Text(
                        "${(uiState.intensity * 100).toInt()}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = BrightAmber,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = uiState.intensity,
                    onValueChange = { viewModel.setIntensity(it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = BrightAmber,
                        thumbColor = BrightAmber,
                        inactiveTrackColor = BorderSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("intensity_slider")
                )

                // SHADOWS SECTION
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Contrast,
                            contentDescription = null,
                            tint = SoftGrey,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Shadows (Contrast/Boost)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CleanWhite
                        )
                    }
                    val shadowsPercent = (uiState.shadows * 100).toInt()
                    val sign = if (shadowsPercent > 0) "+" else ""
                    Text(
                        "$sign$shadowsPercent%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = BrightAmber,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Slider(
                    value = uiState.shadows,
                    onValueChange = { viewModel.setShadows(it) },
                    valueRange = -1f..1f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = BrightAmber,
                        thumbColor = BrightAmber,
                        inactiveTrackColor = BorderSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shadows_slider")
                )

                // HIGHLIGHTS SECTION
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.WbSunny,
                            contentDescription = null,
                            tint = SoftGrey,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Highlights (Recover/Brighten)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CleanWhite
                        )
                    }
                    val highlightsPercent = (uiState.highlights * 100).toInt()
                    val sign = if (highlightsPercent > 0) "+" else ""
                    Text(
                        "$sign$highlightsPercent%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = BrightAmber,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Slider(
                    value = uiState.highlights,
                    onValueChange = { viewModel.setHighlights(it) },
                    valueRange = -1f..1f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = BrightAmber,
                        thumbColor = BrightAmber,
                        inactiveTrackColor = BorderSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("highlights_slider")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ALIGNMENT MODE SECTIONS
                Text("Alignment Registration", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGrey, letterSpacing = 0.5.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AlignmentChip(
                        label = "Auto-Register",
                        icon = Icons.Outlined.AutoMode,
                        isSelected = uiState.alignmentMode == AlignmentMode.AUTO,
                        onClick = { viewModel.setAlignmentMode(AlignmentMode.AUTO) },
                        modifier = Modifier.weight(1f)
                    )
                    AlignmentChip(
                        label = "Manual Nudge",
                        icon = Icons.Outlined.GridOn,
                        isSelected = uiState.alignmentMode == AlignmentMode.MANUAL,
                        onClick = { viewModel.setAlignmentMode(AlignmentMode.MANUAL) },
                        modifier = Modifier.weight(1f)
                    )
                    AlignmentChip(
                        label = "Tripod / None",
                        icon = Icons.Outlined.LayersClear,
                        isSelected = uiState.alignmentMode == AlignmentMode.NONE,
                        onClick = { viewModel.setAlignmentMode(AlignmentMode.NONE) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // BLENDING STYLES SECTION
                Text("Exposure Fusion Formula", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoftGrey, letterSpacing = 0.5.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AlignmentChip(
                        label = "Balanced HDR",
                        icon = Icons.Outlined.PhotoFilter,
                        isSelected = uiState.blendStyle == BlendStyle.BALANCED_HDR,
                        onClick = { viewModel.setBlendStyle(BlendStyle.BALANCED_HDR) },
                        modifier = Modifier.weight(1f)
                    )
                    AlignmentChip(
                        label = "Vibrant Pop",
                        icon = Icons.Filled.AutoAwesome,
                        isSelected = uiState.blendStyle == BlendStyle.VIBRANT,
                        onClick = { viewModel.setBlendStyle(BlendStyle.VIBRANT) },
                        modifier = Modifier.weight(1f)
                    )
                    AlignmentChip(
                        label = "Night Sight",
                        icon = Icons.Outlined.Brightness3,
                        isSelected = uiState.blendStyle == BlendStyle.NIGHT_SIGHT,
                        onClick = { viewModel.setBlendStyle(BlendStyle.NIGHT_SIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // CONSOLE LOGGER BOX
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (expandedConsole) DeepGrey else SlateCard),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderSlate),
            onClick = { expandedConsole = !expandedConsole }
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Analytics,
                            contentDescription = null,
                            tint = SoftGrey,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Engine Registration Output",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SoftGrey
                        )
                    }
                    Icon(
                        imageVector = if (expandedConsole) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = SoftGrey,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (expandedConsole || uiState.displayLog.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .background(Color(0xFF09090B), RoundedCornerShape(8.dp))
                            .border(BorderStroke(0.5.dp, BorderSlate), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (uiState.displayLog.isEmpty()) {
                                Text(
                                    text = "> Engine ready. Waiting for tasks...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color.DarkGray
                                )
                            } else {
                                uiState.displayLog.forEach { log ->
                                    Text(
                                        text = "> $log",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = if (log.contains("Saved") || log.contains("complete")) BrightAmber else SoftGrey,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (uiState.displayLog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last logs: ${uiState.displayLog.lastOrNull()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = BrightAmber,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // EXPORT CORE BUTTON
        Button(
            onClick = onSaveClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrightAmber,
                contentColor = DeepGrey,
                disabledContainerColor = BorderSlate,
                disabledContentColor = SoftGrey
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .testTag("save_button"),
            enabled = uiState.selectedImages.size >= 2 && uiState.processState != ProcessState.SAVING,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Save HDR Result"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SAVE HIGH-RES HDR EXPOSURE",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun BracketImageCard(
    index: Int,
    image: LoadedImage,
    isManualAlign: Boolean,
    onNudge: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(180.dp)
            .border(BorderStroke(1.dp, if (index == 0) BrightAmber else BorderSlate), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            ) {
                if (image.bitmap != null) {
                    Image(
                        bitmap = image.bitmap.asImageBitmap(),
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DeepGrey),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = BrightAmber, strokeWidth = 2.dp)
                    }
                }

                // Exposure indicator badge overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            brush = Brush.horizontalGradient(listOf(Color(0xFF1E1E24), DeepGrey)),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .border(BorderStroke(0.5.dp, BorderSlate), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (index == 0) BrightAmber else HighlightSg,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (index == 0) "Frame #1 (Base)" else "Frame #${index + 1}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = CleanWhite
                        )
                    }
                }

                // Delete frame overlay button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(28.dp)
                        .background(Color(0xAA000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete Frame",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = image.exposureLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (index == 0) BrightAmber else CleanWhite
                )
                Text(
                    text = "Luminance: ${image.averageLuminance.toInt()}/255",
                    fontSize = 10.sp,
                    color = SoftGrey
                )
                Text(
                    text = "${image.width} x ${image.height} px",
                    fontSize = 9.sp,
                    color = SoftGrey,
                    fontFamily = FontFamily.Monospace
                )

                // Sub alignment details
                if (index > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto Shift: (${image.autoDx}px, ${image.autoDy}px)",
                        fontSize = 9.sp,
                        color = HighlightSg,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (isManualAlign) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Divider(color = BorderSlate, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Manual Nudge",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = CleanWhite
                        )
                        Text(
                            text = "Offset: (${image.manualDx}px, ${image.manualDy}px)",
                            fontSize = 9.sp,
                            color = SoftGrey,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Nudger row x
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("dx", fontSize = 10.sp, color = SoftGrey, modifier = Modifier.width(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                NudgeButton(label = "-1", onClick = { onNudge(-1, 0) })
                                NudgeButton(label = "+1", onClick = { onNudge(1, 0) })
                                NudgeButton(label = "+5", onClick = { onNudge(5, 0) })
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Nudger row y
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("dy", fontSize = 10.sp, color = SoftGrey, modifier = Modifier.width(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                NudgeButton(label = "-1", onClick = { onNudge(0, -1) })
                                NudgeButton(label = "+1", onClick = { onNudge(0, 1) })
                                NudgeButton(label = "+5", onClick = { onNudge(0, 5) })
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = onReset,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Reset Offset", fontSize = 10.sp, color = HighlightSg, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NudgeButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 20.dp)
            .background(BorderSlate, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = CleanWhite)
    }
}

@Composable
fun PrimaryPreviewContainer(
    uiState: HdrUiState,
    viewModel: HdrViewModel
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepGrey)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val currentBitmap = if (uiState.showCompareOriginal) {
                    uiState.selectedImages.firstOrNull()?.bitmap
                } else {
                    uiState.blendedPreviewBitmap
                }

                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = "HDR Result Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay showing current visual mode
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color(0xDD0C0C0F), RoundedCornerShape(6.dp))
                            .border(BorderStroke(0.5.dp, BorderSlate), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (uiState.showCompareOriginal) "ORIGINAL BASE EXPOSURE" else "HDR SYNTHETIC GRADIENT MASTER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.showCompareOriginal) HighlightSg else BrightAmber,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // HOLD TO COMPARE button overlay in bottom right corner
                    Button(
                        onClick = {},
                        interactionSource = interactionSource,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xCC000000),
                            contentColor = CleanWhite
                        ),
                        border = BorderStroke(1.dp, BorderSlate),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .height(34.dp)
                            .testTag("compare_button")
                    ) {
                        Icon(imageVector = Icons.Default.Flip, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Hold to Compare", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // Track press state of comparison button to trigger temporary preview swap
                    val isPressed by interactionSource.collectIsPressedAsState()
                    LaunchedEffect(isPressed) {
                        viewModel.setShowCompare(isPressed)
                    }

                } else {
                    // Placeholder when no render output is available yet
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = BrightAmber, strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                "Aligning & blending exposure brackets...",
                                fontSize = 12.sp,
                                color = SoftGrey,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Process blocker loader overlays
                if (uiState.processState == ProcessState.SAVING || uiState.processState == ProcessState.LOADING_IMAGES) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xEE0F0F11)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            CircularProgressIndicator(
                                color = BrightAmber,
                                strokeWidth = 4.dp,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = uiState.statusText,
                                color = CleanWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Running graphics pipelines on workers...",
                                color = SoftGrey,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlignmentChip(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .border(BorderStroke(1.dp, if (isSelected) BrightAmber else BorderSlate), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0x18FFA000) else DeepGrey
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BrightAmber else SoftGrey,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) BrightAmber else SoftGrey,
                textAlign = TextAlign.Center
            )
        }
    }
}
