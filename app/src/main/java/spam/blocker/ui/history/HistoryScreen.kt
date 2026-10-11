package spam.blocker.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import spam.blocker.ui.M
import spam.blocker.R
import spam.blocker.def.Def
import spam.blocker.ui.widgets.FabWrapper
import spam.blocker.ui.widgets.SearchBox
import spam.blocker.ui.widgets.StrokeButton
import spam.blocker.ui.widgets.Str
import spam.blocker.util.LockedDebouncer
import spam.blocker.util.TimeUtils.FreshnessColor
import spam.blocker.util.spf
import spam.blocker.G
import spam.blocker.ui.setting.ScreenHeading

object HistoryOptions {
    val showHistoryIndicator : MutableState<Boolean> = mutableStateOf(false)
    val showHistoryGeoLocation : MutableState<Boolean> = mutableStateOf(false)
    val showHistoryCarrier : MutableState<Boolean> = mutableStateOf(false)
    val forceShowSIM : MutableState<Boolean> = mutableStateOf(false)
    val showHistoryPassed : MutableState<Boolean> = mutableStateOf(false)
    val showHistoryBlocked : MutableState<Boolean> = mutableStateOf(false)
    val showHistoryTimeColor : MutableState<Boolean> = mutableStateOf(false)
    val historyTimeColors : SnapshotStateList<FreshnessColor> = mutableStateListOf()
}

@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
) {
    val ctx = LocalContext.current
    val resources = LocalResources.current

    // Load history options
    LaunchedEffect(true) {
        val spf = spf.HistoryOptions(ctx)
        HistoryOptions.apply {
            showHistoryIndicator.value = spf.showIndicator
            showHistoryGeoLocation.value = spf.showGeoLocation
            showHistoryCarrier.value = spf.showCarrier
            forceShowSIM.value = spf.forceShowSim
            // these two are loaded at startup for calculating the unread count on bottom bar
//            showHistoryPassed.value = spf.showPassed
//            showHistoryBlocked.value = spf.showBlocked
            showHistoryTimeColor.value = spf.showTimeColor
            historyTimeColors.apply {
                clear()
                addAll(spf.loadTimeColors())
            }
        }
    }

    // Hide FAB on scrolling to the last item
    val listState = rememberLazyListState()
    val fabVisible by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
            lastVisibleIndex < lastItemIndex || listState.firstVisibleItemIndex == 0
        }
    }

    FabWrapper(
        fabRow = { positionModifier ->
            // Two FABs
            HistoryFabs(
                visible = fabVisible,
                vm = vm,
                modifier = positionModifier,
            )
        }
    ) {
        Column(modifier = M.padding(8.dp, 8.dp, 8.dp, 2.dp)) {
            val lookupTrigger = remember { mutableStateOf(false) }
            NumberLookupDialog(lookupTrigger)
            ScreenHeading(Str(if (vm.forType == Def.ForNumber) R.string.ui_history_title_calls else R.string.ui_history_title_sms),
                resources.getString(R.string.ui_history_summary, vm.records.size, vm.records.count { it.isBlocked() })) {
                if (vm.forType == Def.ForNumber) TextButton(onClick = { lookupTrigger.value = true }) {
                    Text(Str(R.string.cn_manual_lookup))
                }
            }
            // Use a Debouncer here as there could be LOTS of history records.
            val debouncer = remember { LockedDebouncer() }
            SearchBox(vm.searchEnabled, vm.filter) {
                debouncer.debounce {
                    vm.reload(ctx)
                }
            }
            if (vm.records.isEmpty()) {
                val filtered = vm.filter.value.isNotBlank() || !HistoryOptions.showHistoryPassed.value || !HistoryOptions.showHistoryBlocked.value
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                        Text(Str(if (filtered) R.string.ui_history_no_matches else if (vm.forType == Def.ForNumber)
                            R.string.ui_history_empty_calls else R.string.ui_history_empty_sms), fontSize = 18.sp)
                        Text(Str(if (filtered) R.string.ui_history_no_matches_hint else R.string.ui_history_empty_hint),
                            color = G.palette.textGrey, fontSize = 13.sp)
                    }
                }
            } else HistoryList(lazyState = listState, vm = vm)
        }
    }
}
