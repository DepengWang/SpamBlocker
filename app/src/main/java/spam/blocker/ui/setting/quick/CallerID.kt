package spam.blocker.ui.setting.quick

import android.content.Context
import android.text.Html
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import spam.blocker.G
import spam.blocker.R
import spam.blocker.ui.setting.LabeledRow
import spam.blocker.ui.setting.SettingsPage
import spam.blocker.ui.setting.SettingsGroup
import spam.blocker.ui.widgets.ColorPickerButton
import spam.blocker.ui.widgets.CheckBox
import spam.blocker.ui.widgets.FloatingWindow
import spam.blocker.ui.widgets.GreyIcon16
import spam.blocker.ui.widgets.GreyText
import spam.blocker.ui.widgets.NumberInputBox
import spam.blocker.ui.widgets.PopupDialog
import spam.blocker.ui.widgets.PopupSize
import spam.blocker.ui.widgets.RowVCenterSpaced
import spam.blocker.ui.widgets.Str
import spam.blocker.ui.widgets.StrInputBox
import spam.blocker.ui.widgets.StrokeButton
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.ui.widgets.ComboBox
import spam.blocker.ui.widgets.LabelItem
import spam.blocker.util.Permission
import spam.blocker.util.CallerIdAppearance
import spam.blocker.util.PermissionWrapper
import spam.blocker.util.spf

const val DefaultCallerIdBgColor = 0x20808080
const val DefaultCallerIdTemplate =
"""<font color="#50c878">
    <big><big>
        {reason}
    </big></big>
</font>
<br>
<font color="#ffffff">
    <big>
        {number}
    </big>
</font>
<br>
<font color="#ffd166">
    {contact}
</font>
<br>
<font color="#00bfff">
    <big>
        {geolocation}
    </big>
</font>
<br>
<font color="#ea86ff">
    <big>
        {carrier}
    </big>
</font>
"""

const val FullHtmlTagList = "https://github.com/aj3423/SpamBlocker/issues/555"

fun callerIdView(ctx: Context, template: String, fontSizeSp: Int = 14) : View {
    return TextView(ctx).apply {
        setTextColor(if (spf.CallerID(ctx).theme == 2) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        textSize = fontSizeSp.coerceIn(10, 32).toFloat()
        text = Html.fromHtml(template, Html.FROM_HTML_MODE_LEGACY)
        gravity = Gravity.CENTER
    }
}

fun showCallerIdWindow(
    ctx: Context,
    reason: String? = "{reason}",
    number: String? = "{number}",
    contact: String? = "{contact}",
    geolocation: String? = "{geolocation}",
    carrier: String? = "{carrier}",
    category: String? = null,
    sources: String? = null,
    risk: String? = null,
    refreshOnly: Boolean = false,
    mark: String? = null,
    company: String? = null,
    info: String? = null,
) {
    val spf = spf.CallerID(ctx)

    // Permission.phoneState is needed to close the floating dialog when the call gets answered or rejected.
    if(!Permission.phoneState.isGranted || !Permission.showOverlay.isGranted)
        return

    val visibleContact = contact?.takeIf { spf.showContact }
    // Resolve tags: {reason} {number} {contact} {carrier} {geolocation}
    val content = if (spf.template == DefaultCallerIdTemplate) {
        defaultCallerIdContent(reason, number, visibleContact, geolocation, carrier,
            category, sources?.takeIf { spf.showSources }, risk, spf.theme, mark, company, info)
    } else {
        spf.template
            .replace("{reason}", displayValue(reason))
            .replace("{number}", displayValue(number))
            .replace("{contact}", displayValue(visibleContact))
            .replace("{geolocation}", displayValue(geolocation))
            .replace("{carrier}", displayValue(carrier))
            .replace("{category}", displayValue(category))
            .replace("{sources}", displayValue(sources?.takeIf { spf.showSources }))
            .replace("{risk}", displayValue(risk))
            .replace("{mark}", displayValue(mark))
            .replace("{company}", displayValue(company))
            .replace("{info}", displayValue(info))
    }

    FloatingWindow.apply {
        if (refreshOnly) {
            replaceContent(ctx, callerIdView(ctx, content, spf.fontSizeSp))
            return
        }
        val background = CallerIdAppearance.backgroundColor(spf.theme, spf.bgColor, spf.transparentBackground)
        update(ctx, bgColor = background, x = spf.x, y = spf.y,
            widthDp = spf.windowWidthDp, heightDp = spf.windowHeightDp)
        onDrag = { newX, newY ->
            spf.x = newX
            spf.y = newY
        }
        show(
            ctx = ctx,
            view = callerIdView(ctx, content, spf.fontSizeSp),
            durationMs = spf.durationSec.coerceIn(0, 600).takeIf { it > 0 }?.times(1000L),
            widthDp = spf.windowWidthDp,
            heightDp = spf.windowHeightDp,
        )
    }
}

private fun defaultCallerIdContent(
    reason: String?,
    number: String?,
    contact: String?,
    geolocation: String?,
    carrier: String?,
    category: String?,
    sources: String?,
    risk: String?,
    theme: Int,
    mark: String?,
    company: String?,
    info: String?,
): String {
    val light = theme == 2
    val foreground = if (light) "#202124" else "#ffffff"
    val sections = mutableListOf("<font color=\"$foreground\"><big>${displayValue(number)}</big></font>")
    reason?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"${if (light) "#176b3a" else "#50c878"}\">${displayValue(it)}</font>"
    }
    contact?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"${if (light) "#825600" else "#ffd166"}\">${displayValue(it)}</font>"
    }
    company?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"$foreground\">${displayValue(it)}</font>"
    }
    geolocation?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"${if (light) "#006887" else "#00bfff"}\"><big>${displayValue(it)}</big></font>"
    }
    carrier?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"${if (light) "#763589" else "#ea86ff"}\">${displayValue(it)}</font>"
    }
    listOf(category, risk, sources).filterNot { it.isNullOrBlank() }.forEach {
        sections += "<font color=\"$foreground\">${displayValue(it)}</font>"
    }
    mark?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"#ffb74d\">${displayValue(it)}</font>"
    }
    info?.trim()?.takeIf { it.isNotEmpty() }?.let {
        sections += "<font color=\"$foreground\">${displayValue(it)}</font>"
    }
    return sections.joinToString("<br>\n")
}

private fun displayValue(value: String?): String {
    return value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(TextUtils::htmlEncode)
        ?: "—"
}

@Composable
private fun ConfigDialog(
    trigger: MutableState<Boolean>
) {
    val ctx = LocalContext.current
    val spf = spf.CallerID(ctx)

    var bgColor by remember { mutableStateOf(Color(spf.bgColor)) }
    var transparentBackground by remember { mutableStateOf(spf.transparentBackground) }
    var template by remember { mutableStateOf(spf.template) }
    var durationSec by remember { mutableIntStateOf(spf.durationSec) }
    var showContact by remember { mutableStateOf(spf.showContact) }
    var fontSizeSp by remember { mutableIntStateOf(spf.fontSizeSp) }
    var showForContacts by remember { mutableStateOf(spf.showForContacts) }
    var keepAfterAnswer by remember { mutableStateOf(spf.keepAfterAnswer) }
    var showOutgoing by remember { mutableStateOf(spf.showOutgoing) }
    var showSources by remember { mutableStateOf(spf.showSources) }
    var theme by remember { mutableIntStateOf(spf.theme.coerceIn(0, 2)) }
    var windowWidthDp by remember { mutableIntStateOf(if (spf.windowWidthDp == 0) 0 else spf.windowWidthDp.coerceIn(220, 1000)) }
    var windowHeightDp by remember { mutableIntStateOf(if (spf.windowHeightDp == 0) 0 else spf.windowHeightDp.coerceIn(120, 500)) }
    var advanced by remember { mutableStateOf(false) }

    // Destroy the floating window when the app is killed
    DisposableEffect(true) {
        onDispose {
            FloatingWindow.hide(ctx)
        }
    }
    if (trigger.value) SettingsPage(
        Str(R.string.caller_id),
        onDismiss = {
            FloatingWindow.hide(ctx)
            trigger.value = false
        },
        actions = {
            val C = G.palette

            TextButton(onClick = {
                spam.blocker.util.CallerIdentitySession.invalidate()
                showCallerIdWindow(ctx)
            }, enabled = Permission.showOverlay.isGranted) { Text(Str(R.string.preview)) }

            val confirmTrigger = remember { mutableStateOf(false) }
            PopupDialog(
                trigger = confirmTrigger,
                buttons = {
                    // Reset
                    StrokeButton(Str(R.string.reset), color = C.error) {
                        spf.bgColor = DefaultCallerIdBgColor
                        spf.transparentBackground = false
                        spf.template = DefaultCallerIdTemplate
                        bgColor = Color(DefaultCallerIdBgColor)
                        transparentBackground = false
                        template = DefaultCallerIdTemplate
                        spf.showContact = true
                        spf.durationSec = 0
                        spf.fontSizeSp = 14
                        spf.showForContacts = true
                        showContact = true
                        durationSec = 0
                        fontSizeSp = 14
                        showForContacts = true
                        spf.keepAfterAnswer = false
                        keepAfterAnswer = false
                        spf.showOutgoing = false
                        showOutgoing = false
                        spf.showSources = true
                        showSources = true
                        spf.theme = 0
                        theme = 0
                        spf.windowWidthDp = 0
                        spf.windowHeightDp = 0
                        windowWidthDp = 0
                        windowHeightDp = 0
                        FloatingWindow.hide(ctx)
                        confirmTrigger.value = false
                    }
                }
            ) {
                GreyText(Str(R.string.confirm_to_reset))
            }

            // Reset button
            StrokeButton(Str(R.string.reset), color = G.palette.error) {
                confirmTrigger.value = true
            }
        }
    ) {
        SettingsGroup(Str(R.string.ui_overlay_appearance)) {
        GreyText(Str(R.string.ui_overlay_live_hint))
        TextButton(onClick = { FloatingWindow.hide(ctx) }) { Text(Str(R.string.ui_close_preview)) }
        // BG Color
        LabeledRow(R.string.cn_caller_theme) {
            ComboBox(listOf(R.string.cn_theme_custom, R.string.cn_theme_dark, R.string.cn_theme_light).mapIndexed { index, label ->
                LabelItem(label = Str(label)) {
                    theme = index
                    spf.theme = index
                    FloatingWindow.update(ctx, bgColor = CallerIdAppearance.backgroundColor(
                        index, spf.bgColor, spf.transparentBackground))
                }
            }, theme)
        }
        LabeledRow(R.string.cn_show_outgoing, helpTooltip = Str(R.string.cn_outgoing_help)) {
            SwitchBox(showOutgoing) { showOutgoing = it; spf.showOutgoing = it }
        }
        LabeledRow(R.string.cn_show_sources) {
            SwitchBox(showSources) { showSources = it; spf.showSources = it }
        }
        LabeledRow(R.string.ui_overlay_transparent_background) {
            CheckBox(checked = transparentBackground, onCheckChange = {
                transparentBackground = it
                spf.transparentBackground = it
                FloatingWindow.update(ctx, bgColor = CallerIdAppearance.backgroundColor(spf.theme, spf.bgColor, it))
            })
        }
        LabeledRow(R.string.background_color) {
            ColorPickerButton(
                color = bgColor,
                onSelect = {
                    if (it != null) {
                        bgColor = it
                        spf.bgColor = it.toArgb()
                        spf.theme = 0
                        theme = 0
                        FloatingWindow.update(ctx, bgColor = CallerIdAppearance.backgroundColor(
                            spf.theme, spf.bgColor, spf.transparentBackground))
                    }
                }
            )
        }

        }
        SettingsGroup(Str(R.string.ui_overlay_behavior)) {
        GreyText(Str(R.string.caller_id_duration_help))
        run {
            NumberInputBox(
                intValue = durationSec,
                labelId = R.string.caller_id_duration,
                leadingIconId = R.drawable.ic_duration,
                onValueChange = { newValue, hasError ->
                    if (!hasError && newValue != null) {
                        durationSec = newValue.coerceIn(0, 600)
                        spf.durationSec = durationSec
                    }
                },
            )
        }

        LabeledRow(R.string.caller_id_show_contact) {
            SwitchBox(showContact) {
                showContact = it
                spf.showContact = it
            }
        }

        LabeledRow(R.string.caller_id_show_for_contacts, helpTooltip = Str(R.string.caller_id_contacts_help)) {
            SwitchBox(showForContacts) {
                showForContacts = it
                spf.showForContacts = it
            }
        }

        LabeledRow(R.string.caller_id_keep_after_answer, helpTooltip = Str(R.string.caller_id_keep_after_answer_help)) {
            SwitchBox(keepAfterAnswer) {
                keepAfterAnswer = it
                spf.keepAfterAnswer = it
            }
        }

        }
        SettingsGroup(Str(R.string.ui_overlay_size)) {
        NumberInputBox(
            intValue = fontSizeSp,
            labelId = R.string.caller_id_font_size,
            onValueChange = { value, hasError ->
                if (!hasError && value != null && value in 10..32) {
                    fontSizeSp = value
                    spf.fontSizeSp = value
                }
            },
        )

        GreyText(Str(R.string.cn_overlay_size_help))
        NumberInputBox(
            intValue = windowWidthDp,
            labelId = R.string.cn_overlay_width,
            onValueChange = { value, hasError ->
                if (!hasError && value != null && value in 0..1000) {
                    val normalized = if (value == 0) 0 else value.coerceAtLeast(220)
                    windowWidthDp = normalized
                    spf.windowWidthDp = normalized
                    FloatingWindow.update(ctx, widthDp = normalized)
                }
            },
        )
        NumberInputBox(
            intValue = windowHeightDp,
            labelId = R.string.cn_overlay_height,
            onValueChange = { value, hasError ->
                if (!hasError && value != null && value in 0..500) {
                    val normalized = if (value == 0) 0 else value.coerceIn(120, 500)
                    windowHeightDp = normalized
                    spf.windowHeightDp = normalized
                    FloatingWindow.update(ctx, heightDp = normalized)
                }
            },
        )

        }
        TextButton(onClick = { advanced = !advanced }) { Text(Str(R.string.ui_advanced)) }
        if (advanced) SettingsGroup(Str(R.string.content_template)) {
        // 2. Template
        StrInputBox(
            text = template,
            label = { Text(Str(R.string.content_template)) },
            helpTooltip = Str(R.string.help_caller_id_template).format(FullHtmlTagList),
            onValueChange = {
                template = it
                spf.template = it
            }
        )
        }
    }
}

@Composable
fun CallerID() {
    val ctx = LocalContext.current
    val spf = spf.CallerID(ctx)

    var isEnabled by remember { mutableStateOf(spf.isEnabled && Permission.phoneState.isGranted && Permission.showOverlay.isGranted) }

    LabeledRow(
        R.string.ui_overlay,
        helpTooltip = Str(R.string.help_caller_id),
        content = {
            val C = G.palette
            RowVCenterSpaced(4) {
                val trigger = remember { mutableStateOf(false) }
                ConfigDialog(trigger)

                    // Configuration stays available even while the overlay is disabled.
                    StrokeButton(
                        label = Str(R.string.cn_configure),
                        color = C.textGrey,
                    ) {
                        trigger.value = true
                    }

                SwitchBox(isEnabled) { isTurningOn ->
                    if (isTurningOn) {
                        G.permissionChain.ask(
                            ctx,
                            listOf(
                                PermissionWrapper(Permission.showOverlay),
                                // Permission.phoneState is needed to close the floating dialog when the call gets answered or rejected.
                                PermissionWrapper(Permission.phoneState),
                            )
                        ) { granted ->
                            if (granted) {
                                isEnabled = true
                                spf.isEnabled = true
                            }
                        }
                    } else {
                        isEnabled = false
                        spf.isEnabled = false
                        FloatingWindow.hide(ctx)
                    }
                }
            }
        }
    )
}

@Composable
fun CallerIDSummary() {
    val ctx = LocalContext.current
    val spf = spf.CallerID(ctx)

    val isEnabled by remember { mutableStateOf(spf.isEnabled && Permission.phoneState.isGranted && Permission.showOverlay.isGranted) }
    if (isEnabled) {
        val C = G.palette
        StrokeButton(
            label = "ID",
            icon = { GreyIcon16(R.drawable.ic_call) },
            color = C.textGrey,
            enabled = false,
        )
    }
}
