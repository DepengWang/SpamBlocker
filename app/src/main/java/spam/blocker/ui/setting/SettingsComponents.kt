package spam.blocker.ui.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import spam.blocker.G
import spam.blocker.R
import spam.blocker.ui.widgets.ResIcon
import spam.blocker.ui.widgets.Str

@Composable
fun ScreenHeading(title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = G.palette.textGrey, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        trailing()
    }
}

/** Compact grouping keeps common controls visible and uses one consistent disclosure target. */
@Composable
fun SettingsGroup(
    title: String?,
    horizontalPadding: Int = 12,
    isCollapsed: MutableState<Boolean>? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    contentCollapsed: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding.dp, vertical = 5.dp),
        color = G.palette.dialogBg, shape = RoundedCornerShape(16.dp)) {
        Column {
            if (title != null) {
                Row(Modifier.fillMaxWidth()
                    .let { base -> if (isCollapsed == null) base else base.clickable {
                        isCollapsed.value = !isCollapsed.value
                        onToggle?.invoke(isCollapsed.value)
                    } }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = G.palette.textGrey, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (isCollapsed != null) ResIcon(R.drawable.ic_dropdown_arrow,
                        color = G.palette.textGrey, modifier = Modifier.size(18.dp)
                            .rotate(if (isCollapsed.value) 0f else 180f))
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (isCollapsed?.value == true) contentCollapsed?.invoke() else content()
            }
        }
    }
}

@Composable
fun SettingsNavigationRow(title: String, summary: String, icon: Int = R.drawable.ic_settings,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ResIcon(icon, color = G.palette.textGrey, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, color = G.palette.textGrey,
                modifier = Modifier.padding(top = 3.dp))
        }
        ResIcon(R.drawable.ic_right_arrow, color = G.palette.textGrey, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun SettingsSubheading(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = G.palette.textGrey,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun StatusPill(text: String, color: Color = G.palette.infoBlue) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = color, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** A dedicated page rather than a tall, scrollable settings popup. */
@Composable
fun SettingsPage(title: String, onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    navigation: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit) {
    val backLabel = Str(R.string.ui_back)
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = G.palette.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = backLabel }) {
                        ResIcon(R.drawable.ic_right_arrow,
                            modifier = Modifier.size(22.dp).rotate(180f))
                    }
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                        modifier = Modifier.weight(1f))
                    actions()
                }
                navigation()
                HorizontalDivider(color = G.palette.textGrey.copy(alpha = 0.12f))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content)
            }
        }
    }
}
