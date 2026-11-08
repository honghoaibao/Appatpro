package com.atpro.ui.accounts

import androidx.compose.animation.*
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtType
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.ImeAction
import com.atpro.db.entity.AccountEntity
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtEmptyState
import com.atpro.ui.theme.AtTextField
import com.atpro.ui.theme.AtTopBar
import com.atpro.ui.theme.staggerIn

// ── Design tokens ──────────────────────────────────────────────
private val BgDark get() = AtColors.Bg
private val CardDark get() = AtColors.Surface
private val BorderDark get() = AtColors.Hairline
private val Purple get() = AtColors.Accent
private val Green get() = AtColors.Success
private val Amber get() = AtColors.Warning
private val RedBad get() = AtColors.Danger
private val TextSec get() = AtColors.TextSec
private val TextMuted get() = AtColors.TextMuted

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun AccountsScreen(vm: AccountsViewModel, onNavigateUp: (() -> Unit)? = null) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var query  by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }

    val filtered = remember(accounts, query, filter) {
        accounts.filter { acc ->
            val matchSearch = query.isEmpty() || acc.username.lowercase().contains(query.lowercase())
            val matchFilter = when (filter) {
                "active"     -> acc.status == "active" && !acc.checkpoint
                "checkpoint" -> acc.checkpoint
                "banned"     -> acc.status == "banned"
                else         -> true
            }
            matchSearch && matchFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
    ) {
        // v1.10.0: bỏ hàng chọn nền tảng (chỉ TikTok có danh sách tài khoản; 5 nền tảng còn lại là trang giải thích trống).
        AtTopBar(
            title    = "Tài khoản",
            subtitle = "${accounts.size} tài khoản",
            onBack   = onNavigateUp,
        )
        TikTokAccountsBody(
            accounts = accounts,
            filtered = filtered,
            query    = query,
            filter   = filter,
            onQuery  = { query = it },
            onFilter = { filter = it },
            onDelete = vm::delete,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  TikTok accounts body
// ─────────────────────────────────────────────────────────────

@Composable
private fun TikTokAccountsBody(
    accounts:        List<AccountEntity>,
    filtered:        List<AccountEntity>,
    query:           String,
    filter:          String,
    onQuery:         (String) -> Unit,
    onFilter:        (String) -> Unit,
    onDelete:        (String) -> Unit,
) {
    AtTextField(
        value         = query,
        onValueChange = onQuery,
        modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder   = "Tìm @username",
        imeAction     = ImeAction.Search,
        leading       = { Icon(Icons.Rounded.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp)) },
        trailing      = if (query.isNotEmpty()) {{
            IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Rounded.Clear, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }} else null,
    )

    // Hàng lọc chỉ hiện khi có tài khoản checkpoint / bị khóa (nếu không thì chỉ có "Tất cả" — thừa).
    val hasIssues = accounts.any { it.checkpoint || it.status == "banned" }
    if (hasIssues) {
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chips = listOf(
                "all" to "Tất cả", "active" to "Hoạt động",
                "checkpoint" to "Checkpoint", "banned" to "Bị khóa",
            )
            items(chips) { (key, label) ->
                val count = when (key) {
                    "active"     -> accounts.count { it.status == "active" && !it.checkpoint }
                    "checkpoint" -> accounts.count { it.checkpoint }
                    "banned"     -> accounts.count { it.status == "banned" }
                    else         -> accounts.size
                }
                AtChip(
                    text     = "$label ($count)",
                    selected = filter == key,
                    onClick  = { onFilter(key) },
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    if (accounts.isEmpty()) {
        EmptyAccountsView()
    } else {
        LazyColumn(
            contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(filtered, key = { _, a -> a.username }) { index, account ->
                Box(Modifier.staggerIn(index.coerceAtMost(8), stepMs = 35)) {
                    SwipeToDeleteAccountRow(account, onDelete = { onDelete(account.username) })
                }
            }

        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Swipe-to-delete row
// ─────────────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeToDeleteAccountRow(
    account:  AccountEntity,
    onDelete: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == EndToStart) { showConfirm = true; false } else false
        }
    )

    if (showConfirm) {
        AtDialog(
            onDismiss   = { showConfirm = false },
            title       = "Xóa @${account.username}?",
            message     = "Không thể khôi phục.",
            confirmText = "Xóa",
            onConfirm   = { showConfirm = false; onDelete() },
            danger      = true,
        )
    }

    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(RedBad.copy(alpha = 0.15f))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, null, tint = RedBad)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        AccountRow(account)
    }
}

// ─────────────────────────────────────────────────────────────
//  Account row
// ─────────────────────────────────────────────────────────────

@Composable
private fun AccountRow(account: AccountEntity) {
    val clipboard = LocalClipboardManager.current

    AtCard(
        modifier       = Modifier.fillMaxWidth(),
        onClick        = { clipboard.setText(AnnotatedString("@${account.username}")) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(account))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "@${account.username}",
                    color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("${account.sessionsCount}", "phiên")
                    MiniStat("${account.totalLikes}",   Icons.Rounded.Favorite, iconTint = AtColors.Rose)
                    MiniStat("${account.totalFollows}", Icons.Rounded.Group,    iconTint = AtColors.Sky)
                }
            }
            // Tài khoản hoạt động đã có chấm xanh — chỉ hiện nhãn khi bất thường.
            if (account.checkpoint || account.status == "banned") StatusBadge(account)
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Text("$value $label", color = TextMuted, fontSize = 11.sp)
}

@Composable
private fun MiniStat(value: String, icon: ImageVector, iconTint: Color = TextMuted) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = TextMuted, fontSize = 11.sp)
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun StatusBadge(account: AccountEntity) {
    val (label, color) = when {
        account.checkpoint         -> "Checkpoint" to Amber
        account.status == "banned" -> "Bị khóa"   to RedBad
        else                       -> "Hoạt động"  to Green
    }
    AtBadge(text = label, color = color)
}

private fun statusColor(account: AccountEntity): Color = when {
    account.checkpoint         -> Amber
    account.status == "banned" -> RedBad
    else                       -> Green
}

@Composable
private fun EmptyAccountsView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AtEmptyState(
            icon     = Icons.Rounded.ManageAccounts,
            title    = "Chưa có tài khoản",
        )
    }
}
