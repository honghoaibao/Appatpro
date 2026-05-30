package com.atpro.ui.accounts

import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import android.app.Activity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atpro.db.entity.AccountEntity

// ── Design tokens (shared palette) ───────────────────────────
private val BgDark     = Color(0xFF0D0D14)
private val CardDark   = Color(0xFF1A1A2E)
private val BorderDark = Color(0xFF374151)
private val Purple     = Color(0xFF6C63FF)
private val Green      = Color(0xFF10B981)
private val Amber      = Color(0xFFF59E0B)
private val RedBad     = Color(0xFFEF4444)
private val TextSec    = Color(0xFF9CA3AF)
private val TextMuted  = Color(0xFF6B7280)

// ─────────────────────────────────────────────────────────────
//  Root screen
// ─────────────────────────────────────────────────────────────

@Composable
fun AccountsScreen(vm: AccountsViewModel) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var query    by remember { mutableStateOf("") }
    var filter   by remember { mutableStateOf("all") }
    val activity = LocalContext.current as? Activity

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
        // ── Toolbar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity?.finish() }) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "Quay lại", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Tài khoản",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "${accounts.size} tài khoản · tự động lưu khi farm",
                    color = TextMuted, fontSize = 11.sp,
                )
            }
        }

        // ── Search ──
        OutlinedTextField(
            value       = query,
            onValueChange = { query = it },
            modifier    = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("@username", color = Color(0xFF4B5563)) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            },
            trailingIcon = if (query.isNotEmpty()) {{
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Rounded.Clear, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }} else null,
            singleLine = true,
            shape      = RoundedCornerShape(12.dp),
            colors     = OutlinedTextFieldDefaults.colors(
                focusedContainerColor   = CardDark,
                unfocusedContainerColor = CardDark,
                focusedBorderColor      = Purple,
                unfocusedBorderColor    = Color.Transparent,
                focusedTextColor        = Color.White,
                unfocusedTextColor      = Color.White,
            ),
        )

        Spacer(Modifier.height(8.dp))

        // ── Filter chips ──
        LazyRow(
            contentPadding      = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chips = listOf(
                "all"        to "Tất cả",
                "active"     to "Hoạt động",
                "checkpoint" to "Checkpoint",
                "banned"     to "Bị khóa",
            )
            items(chips) { (key, label) ->
                val count = when (key) {
                    "active"     -> accounts.count { it.status == "active" && !it.checkpoint }
                    "checkpoint" -> accounts.count { it.checkpoint }
                    "banned"     -> accounts.count { it.status == "banned" }
                    else         -> accounts.size
                }
                FilterChip(
                    selected = filter == key,
                    onClick  = { filter = key },
                    label    = { Text("$label ($count)", fontSize = 12.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor  = Purple.copy(alpha = 0.15f),
                        selectedLabelColor      = Purple,
                        containerColor          = CardDark,
                        labelColor              = TextSec,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor         = BorderDark,
                        selectedBorderColor = Purple.copy(alpha = 0.5f),
                        enabled = true, selected = filter == key,
                    ),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Account list ──
        if (accounts.isEmpty()) {
            EmptyAccountsView()
        } else {
            LazyColumn(
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.username }) { account ->
                    SwipeToDeleteAccountRow(account, onDelete = { vm.delete(account.username) })
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
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor   = CardDark,
            title = { Text("Xóa @${account.username}?", color = Color.White) },
            text  = { Text("Không thể khôi phục.", color = TextSec) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() }) {
                    Text("Xóa", color = RedBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Hủy", color = TextSec)
                }
            },
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardDark)
            .clickable { clipboard.setText(AnnotatedString("@${account.username}")) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor(account))
        )

        Spacer(Modifier.width(12.dp))

        // Username + stats
        Column(Modifier.weight(1f)) {
            Text(
                "@${account.username}",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("${account.sessionsCount}", "phiên")
                MiniStat("${account.totalLikes}",    Icons.Rounded.Favorite,  iconTint = Color(0xFFE57373))
                MiniStat("${account.totalFollows}",  Icons.Rounded.Group,     iconTint = Color(0xFF90CAF9))
            }
        }

        // Status badge
        StatusBadge(account)
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Text("$value $label", color = TextMuted, fontSize = 11.sp)
}

@Composable
private fun MiniStat(value: String, icon: ImageVector, iconTint: Color = TextMuted) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = TextMuted, fontSize = 11.sp)
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun StatusBadge(account: AccountEntity) {
    val (label, color) = when {
        account.checkpoint       -> "Checkpoint" to Amber
        account.status == "banned" -> "Bị khóa" to RedBad
        else                       -> "Hoạt động" to Green
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun statusColor(account: AccountEntity): Color = when {
    account.checkpoint         -> Amber
    account.status == "banned" -> RedBad
    else                       -> Green
}

@Composable
private fun EmptyAccountsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.ManageAccounts,
            contentDescription = null,
            tint     = TextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Chưa có tài khoản nào",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Tài khoản sẽ tự động được lưu\nkhi bạn bắt đầu farm",
            color = TextMuted, fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
