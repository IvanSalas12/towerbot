package com.arisa.towerbot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arisa.towerbot.android.BitmapShot
import com.arisa.towerbot.android.TextReader
import com.arisa.towerbot.android.TowerBotApp
import com.arisa.towerbot.core.Box as ScreenBox
import com.arisa.towerbot.core.ListPos
import com.arisa.towerbot.core.NumberParser
import com.arisa.towerbot.core.Pt
import com.arisa.towerbot.core.ScreenDef
import com.arisa.towerbot.core.ScreenRole
import com.arisa.towerbot.core.Tab
import com.arisa.towerbot.core.Template
import com.arisa.towerbot.core.Upgrade
import com.arisa.towerbot.core.UpgradeSlot
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private enum class Tool(val label: String, val isRect: Boolean, val hint: String) {
    ANCHOR("Ancla", true, "Arrastra un rectángulo sobre algo que SÓLO salga en esta pantalla y no se mueva."),
    TAP("Toque", false, "Toca el botón que el bot debe pulsar aquí (BATALLA, REINTENTAR, la X…)."),
    WAVE("Oleada", true, "Arrastra un rectángulo justo sobre el número de oleada."),
    COINS("Monedas", true, "Fin de partida: las monedas ganadas. En partida: el contador de monedas de arriba, sin el icono."),
    TIER("Nivel", true, "Arrastra un rectángulo sobre «Nivel N» (en el inicio, en partida o en el fin de partida)."),
    HOME("INICIO", false, "Fin de partida: toca el botón INICIO. Lo usa para ir a cambiar de nivel."),
    TIER_PREV("Nivel ‹", false, "Inicio: toca la flecha que baja de nivel."),
    TIER_NEXT("Nivel ›", false, "Inicio: toca la flecha que sube de nivel."),
    TAB("Pestaña", false, "Elige la pestaña y toca su botón."),
    HEADER("Título", true, "Con esa pestaña abierta, arrastra un rectángulo sobre su título («MEJORAS DE ATAQUE»…)."),
    UPGRADE("Mejora", true, "Elige pestaña y posición de la lista, y arrastra un rectángulo sobre el botón de compra."),
}

private data class SlotDraft(val upgrade: Upgrade, val tab: Tab, val pos: ListPos, val box: ScreenBox)

/** Escala y desplazamiento para encajar la captura en el lienzo. */
private data class Fit(val scale: Float, val ox: Float, val oy: Float) {
    fun toImage(o: Offset, w: Int, h: Int) = Pt(
        ((o.x - ox) / scale).toInt().coerceIn(0, w),
        ((o.y - oy) / scale).toInt().coerceIn(0, h),
    )

    fun toCanvas(p: Pt) = Offset(ox + p.x * scale, oy + p.y * scale)

    companion object {
        fun of(cw: Float, ch: Float, iw: Int, ih: Int): Fit {
            val s = minOf(cw / iw, ch / ih)
            return Fit(s, (cw - iw * s) / 2, (ch - ih * s) / 2)
        }
    }
}

@Composable
fun EditorScreen(request: CaptureRequest, onDone: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(request.path) { BitmapFactory.decodeFile(request.path) }
    if (bitmap == null) {
        Column(Modifier.padding(16.dp)) {
            Text("No pude abrir la captura.")
            Button(onClick = onDone) { Text("Volver") }
        }
        return
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val iw = bitmap.width
    val ih = bitmap.height
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    var role by remember { mutableStateOf<ScreenRole?>(null) }
    var name by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf(Tool.ANCHOR) }
    val anchors = remember { mutableStateListOf<ScreenBox>() }
    var tap by remember { mutableStateOf<Pt?>(null) }
    var waveBox by remember { mutableStateOf<ScreenBox?>(null) }
    var coinsBox by remember { mutableStateOf<ScreenBox?>(null) }
    var tierBox by remember { mutableStateOf<ScreenBox?>(null) }
    var homeTap by remember { mutableStateOf<Pt?>(null) }
    var tierPrev by remember { mutableStateOf<Pt?>(null) }
    var tierNext by remember { mutableStateOf<Pt?>(null) }
    val tabs = remember { mutableStateMapOf<Tab, Pt>() }
    val headers = remember { mutableStateMapOf<Tab, ScreenBox>() }
    val slots = remember { mutableStateListOf<SlotDraft>() }
    var currentTab by remember { mutableStateOf(Tab.ATTACK) }
    var currentPos by remember { mutableStateOf(ListPos.TOP) }
    var pendingSlot by remember { mutableStateOf<ScreenBox?>(null) }
    var dragFrom by remember { mutableStateOf<Pt?>(null) }
    var dragTo by remember { mutableStateOf<Pt?>(null) }
    var readout by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun readBox(box: ScreenBox?, label: String) {
        box ?: return
        scope.launch {
            val text = TextReader.read(bitmap, box)
            val number = text?.let(NumberParser::parse)
            readout = "$label: «${text ?: "nada"}» → ${number?.let(NumberParser::format) ?: "no es un número"}"
        }
    }

    fun commitRect(box: ScreenBox) {
        if (box.width < 8 || box.height < 8) return
        when (tool) {
            Tool.ANCHOR -> anchors += box
            Tool.WAVE -> { waveBox = box; readBox(box, "Oleada") }
            Tool.COINS -> { coinsBox = box; readBox(box, "Monedas") }
            Tool.TIER -> {
                tierBox = box
                scope.launch {
                    val text = TextReader.read(bitmap, box)
                    readout = "Nivel: «${text ?: "nada"}» → ${text?.let(NumberParser::parseTier) ?: "no lo entiendo"}"
                }
            }
            Tool.UPGRADE -> pendingSlot = box
            Tool.HEADER -> headers[currentTab] = box
            else -> {}
        }
    }

    fun commitPoint(p: Pt) {
        when (tool) {
            Tool.TAP -> tap = p
            Tool.TAB -> tabs[currentTab] = p
            Tool.HOME -> homeTap = p
            Tool.TIER_PREV -> tierPrev = p
            Tool.TIER_NEXT -> tierNext = p
            else -> {}
        }
    }

    fun undo() {
        when (tool) {
            Tool.ANCHOR -> anchors.removeLastOrNull()
            Tool.TAP -> tap = null
            Tool.WAVE -> waveBox = null
            Tool.COINS -> coinsBox = null
            Tool.TIER -> tierBox = null
            Tool.HOME -> homeTap = null
            Tool.TIER_PREV -> tierPrev = null
            Tool.TIER_NEXT -> tierNext = null
            Tool.TAB -> tabs.remove(currentTab)
            Tool.HEADER -> headers.remove(currentTab)
            Tool.UPGRADE -> slots.removeLastOrNull()
        }
    }

    fun discard() {
        File(request.path).delete()
        onDone()
    }

    fun save() {
        val r = role
        if (r != null && anchors.isEmpty()) {
            error = "Marca al menos un Ancla para que el bot reconozca esta pantalla."
            return
        }
        if (r == null && slots.isEmpty() && tabs.isEmpty() && headers.isEmpty()) {
            error = "No has marcado nada. Elige qué pantalla es o marca pestañas y mejoras."
            return
        }
        val frame = BitmapShot(bitmap).frame
        TowerBotApp.store.updateCalibration { c ->
            var next = c.copy(screenWidth = iw, screenHeight = ih)
            request.gamePackage
                ?.takeIf { it != context.packageName && it != "com.android.systemui" }
                ?.let { next = next.copy(gamePackage = it) }
            if (r != null) {
                next = next.copy(
                    screens = next.screens + ScreenDef(
                        id = UUID.randomUUID().toString(),
                        name = name.ifBlank { r.label },
                        role = r,
                        anchors = anchors.map { Template.sample(frame, it) },
                        tap = tap,
                        waveBox = waveBox,
                        coinsBox = coinsBox,
                        tierBox = tierBox,
                        homeTap = homeTap,
                        tierPrev = tierPrev,
                        tierNext = tierNext,
                    ),
                )
            }
            val newSlots = slots.map { UpgradeSlot(it.upgrade, it.tab, it.pos, it.box, Template.sample(frame, it.box, 32)) }
            next.copy(
                tabs = next.tabs + tabs,
                tabHeaders = next.tabHeaders + headers.mapValues { (_, box) -> Template.sample(frame, box) },
                slots = next.slots.filter { old -> newSlots.none { it.upgrade == old.upgrade } } + newSlots,
            )
        }
        discard()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::discard) { Text("Descartar") }
            Text("Marcar captura", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Button(onClick = ::save) { Text("Guardar") }
        }

        ChipRow {
            FilterChip(role == null, onClick = { role = null }, label = { Text("Sólo mejoras") })
            ScreenRole.entries.forEach { r ->
                FilterChip(role == r, onClick = { role = r }, label = { Text(r.label) })
            }
        }
        if (role == ScreenRole.POPUP) {
            OutlinedTextField(
                name, { name = it }, label = { Text("Nombre (p. ej. «Oferta»)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        ChipRow {
            Tool.entries.forEach { t -> FilterChip(tool == t, onClick = { tool = t }, label = { Text(t.label) }) }
        }
        if (tool == Tool.TAB || tool == Tool.HEADER || tool == Tool.UPGRADE) {
            ChipRow {
                Tab.entries.forEach { t -> FilterChip(currentTab == t, onClick = { currentTab = t }, label = { Text(t.label) }) }
                if (tool == Tool.UPGRADE) {
                    ListPos.entries.forEach { p ->
                        FilterChip(currentPos == p, onClick = { currentPos = p }, label = { Text(if (p == ListPos.TOP) "Arriba" else "Abajo") })
                    }
                }
            }
        }
        Text(tool.hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(tool) {
                        val fit = Fit.of(size.width.toFloat(), size.height.toFloat(), iw, ih)
                        if (tool.isRect) {
                            detectDragGestures(
                                onDragStart = { dragFrom = fit.toImage(it, iw, ih); dragTo = dragFrom },
                                onDrag = { change, _ -> dragTo = fit.toImage(change.position, iw, ih) },
                                onDragEnd = {
                                    val a = dragFrom
                                    val b = dragTo
                                    if (a != null && b != null) commitRect(ScreenBox.of(a, b))
                                    dragFrom = null
                                    dragTo = null
                                },
                                onDragCancel = { dragFrom = null; dragTo = null },
                            )
                        } else {
                            detectTapGestures(onTap = { commitPoint(fit.toImage(it, iw, ih)) })
                        }
                    },
            ) {
                val fit = Fit.of(size.width, size.height, iw, ih)
                drawImage(
                    image,
                    dstOffset = IntOffset(fit.ox.toInt(), fit.oy.toInt()),
                    dstSize = IntSize((iw * fit.scale).toInt(), (ih * fit.scale).toInt()),
                )
                anchors.forEach { mark(fit, measurer, it, Color.Green, "ancla") }
                waveBox?.let { mark(fit, measurer, it, Color.Cyan, "oleada") }
                coinsBox?.let { mark(fit, measurer, it, Color.Yellow, "monedas") }
                tierBox?.let { mark(fit, measurer, it, Color.Cyan, "nivel") }
                homeTap?.let { dot(fit, measurer, it, Color.Red, "INICIO") }
                tierPrev?.let { dot(fit, measurer, it, Color.Red, "‹") }
                tierNext?.let { dot(fit, measurer, it, Color.Red, "›") }
                slots.forEach { mark(fit, measurer, it.box, Color(0xFFFF9800), it.upgrade.label) }
                tap?.let { dot(fit, measurer, it, Color.Red, "toque") }
                tabs.forEach { (t, p) -> dot(fit, measurer, p, Color.Magenta, t.label) }
                headers.forEach { (t, b) -> mark(fit, measurer, b, Color.Magenta, "título ${t.label}") }
                val a = dragFrom
                val b = dragTo
                if (a != null && b != null) mark(fit, measurer, ScreenBox.of(a, b), Color.White, "")
            }
        }

        readout?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::undo) { Text("Deshacer «${tool.label}»") }
            Text(
                "${anchors.size} anclas · ${tabs.size} pestañas · ${slots.size} mejoras",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    pendingSlot?.let { box ->
        AlertDialog(
            onDismissRequest = { pendingSlot = null },
            title = { Text("¿Qué mejora es? (${currentTab.label})") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(Upgrade.entries.sortedBy { if (it.tab == currentTab) 0 else 1 }) { u ->
                        Text(
                            u.label,
                            Modifier.fillMaxWidth()
                                .clickable {
                                    slots.removeAll { it.upgrade == u }
                                    slots += SlotDraft(u, currentTab, currentPos, box)
                                    pendingSlot = null
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingSlot = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

private fun DrawScope.mark(fit: Fit, measurer: TextMeasurer, box: ScreenBox, color: Color, label: String) {
    drawRect(
        color,
        topLeft = fit.toCanvas(Pt(box.left, box.top)),
        size = Size(box.width * fit.scale, box.height * fit.scale),
        style = Stroke(width = 3f),
    )
    if (label.isNotEmpty()) tag(measurer, fit.toCanvas(Pt(box.left, box.top)), color, label)
}

private fun DrawScope.dot(fit: Fit, measurer: TextMeasurer, p: Pt, color: Color, label: String) {
    val c = fit.toCanvas(p)
    drawCircle(color, radius = 10f, center = c, style = Stroke(width = 4f))
    tag(measurer, c + Offset(12f, -12f), color, label)
}

private fun DrawScope.tag(measurer: TextMeasurer, at: Offset, color: Color, label: String) {
    val x = at.x.coerceIn(0f, (size.width - 40f).coerceAtLeast(0f))
    val y = at.y.coerceIn(0f, (size.height - 20f).coerceAtLeast(0f))
    drawText(
        measurer, label, topLeft = Offset(x, y),
        style = TextStyle(color = color, fontSize = 10.sp, background = Color.Black.copy(alpha = 0.6f)),
    )
}
