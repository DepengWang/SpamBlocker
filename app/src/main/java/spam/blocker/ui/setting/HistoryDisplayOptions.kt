package spam.blocker.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import spam.blocker.R
import spam.blocker.ui.history.HistoryOptions
import spam.blocker.ui.widgets.SwitchBox
import spam.blocker.util.spf

@Composable
fun HistoryDisplayOptions() {
    val ctx = LocalContext.current
    val prefs = spf.HistoryOptions(ctx)
    LabeledRow(R.string.cn_history_location) {
        SwitchBox(prefs.showGeoLocation) { enabled ->
            prefs.showGeoLocation = enabled
            HistoryOptions.showHistoryGeoLocation.value = enabled
        }
    }
    LabeledRow(R.string.cn_history_carrier) {
        SwitchBox(prefs.showCarrier) { enabled ->
            prefs.showCarrier = enabled
            HistoryOptions.showHistoryCarrier.value = enabled
        }
    }
}
