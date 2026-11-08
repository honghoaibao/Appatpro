package com.atpro.ui.f4f

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import com.atpro.tasks.f4f.F4fWithdrawChannel
import com.atpro.tasks.f4f.F4fWithdrawItem
import com.atpro.tasks.f4f.F4fWithdrawRequest
import com.atpro.ui.theme.AtBadge
import com.atpro.ui.theme.AtButton
import com.atpro.ui.theme.AtButtonStyle
import com.atpro.ui.theme.AtCard
import com.atpro.ui.theme.AtChip
import com.atpro.ui.theme.AtColors
import com.atpro.ui.theme.AtDialog
import com.atpro.ui.theme.AtEmptyState
import com.atpro.ui.theme.AtStatTile
import com.atpro.ui.theme.AtTextField

// ─────────────────────────────────────────────────────────────────────────────
//  Rút tiền — 5 kênh: ngân hàng · ZaloPay · PayPal · Crypto · Thẻ cào
//
//  AN TOÀN: rút tiền KHÔNG hoàn tác được →
//   1) kiểm tra dữ liệu cục bộ (F4fWithdrawRequest.validate) trước khi mở xác nhận;
//   2) luôn có hộp thoại xác nhận liệt kê mọi trường;
//   3) sau khi xác nhận, ô số coin bị xoá → muốn rút tiếp phải nhập lại + xác nhận lại;
//   4) ViewModel khoá khi đang gửi; không có bất kỳ lần thử lại tự động nào.
// ─────────────────────────────────────────────────────────────────────────────

private val DEFAULT_BANKS = listOf("Vietcombank", "Techcombank", "MB Bank", "ACB", "BIDV", "VPBank", "VietinBank", "TPBank", "Sacombank")
private val DEFAULT_NETWORKS = listOf("bep20", "trc20", "erc20")
private val DEFAULT_TELCOS = listOf("Viettel", "MobiFone", "VinaPhone")

@Composable
internal fun WithdrawPanel(state: F4fHubUiState, vm: F4fHubViewModel, balance: String?) {
    var channel by remember { mutableStateOf(F4fWithdrawChannel.BANK) }
    var bankName by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("") }
    var accountNumber by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var network by remember { mutableStateOf("") }
    var wallet by remember { mutableStateOf("") }
    var telco by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<F4fWithdrawRequest?>(null) }

    val info = (state.withdrawInfo as? F4fLoad.Ok)?.data
    val banks = info?.banks?.takeIf { it.isNotEmpty() } ?: DEFAULT_BANKS
    val networks = info?.networks?.takeIf { it.isNotEmpty() } ?: DEFAULT_NETWORKS
    val telcos = info?.telcos?.takeIf { it.isNotEmpty() } ?: DEFAULT_TELCOS

    fun build(): F4fWithdrawRequest {
        val amount = amountText.digitsToLong() ?: 0L
        return when (channel) {
            F4fWithdrawChannel.BANK    -> F4fWithdrawRequest.Bank(bankName, accountName, accountNumber, amount)
            F4fWithdrawChannel.ZALOPAY -> F4fWithdrawRequest.ZaloPay(accountName, phone, amount)
            F4fWithdrawChannel.PAYPAL  -> F4fWithdrawRequest.PayPal(email, amount)
            F4fWithdrawChannel.CRYPTO  -> F4fWithdrawRequest.Crypto(network, wallet, amount)
            F4fWithdrawChannel.CARD    -> F4fWithdrawRequest.Card(telco, amount)
        }
    }

    PanelColumn {
        LoadBlock(state.withdrawStats, onRetry = { vm.refresh(F4fHubTab.WITHDRAW) }) { s ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AtStatTile("Đang chờ", s.pending.fmt(), Modifier.weight(1f), AtColors.Warning)
                AtStatTile("Đã rút", s.completed.fmt(), Modifier.weight(1f), AtColors.Success)
                AtStatTile("Bị từ chối", s.rejected.fmt(), Modifier.weight(1f))
            }
        }

        GroupTitle("Tạo yêu cầu rút")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(F4fWithdrawChannel.values().toList()) { c ->
                AtChip(text = c.label, selected = channel == c, onClick = { channel = c; error = null })
            }
        }

        AtCard(Modifier.fillMaxWidth()) {
            if (!balance.isNullOrBlank()) {
                Text("Số dư hiện có: $balance", color = AtColors.TextSec, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (channel) {
                    F4fWithdrawChannel.BANK -> {
                        ChoiceRow("Ngân hàng", banks, bankName) { bankName = it }
                        AtTextField(bankName, { bankName = it; error = null }, label = "Tên ngân hàng", placeholder = "Chọn ở trên hoặc nhập")
                        AtTextField(accountName, { accountName = it.uppercase(); error = null }, label = "Chủ tài khoản", placeholder = "NGUYEN VAN A")
                        AtTextField(accountNumber, { accountNumber = it.filter(Char::isLetterOrDigit); error = null }, label = "Số tài khoản", keyboardType = KeyboardType.Number)
                    }
                    F4fWithdrawChannel.ZALOPAY -> {
                        AtTextField(accountName, { accountName = it; error = null }, label = "Chủ tài khoản ZaloPay")
                        AtTextField(phone, { phone = it.filter(Char::isDigit); error = null }, label = "Số điện thoại ZaloPay", keyboardType = KeyboardType.Phone)
                    }
                    F4fWithdrawChannel.PAYPAL -> {
                        AtTextField(email, { email = it; error = null }, label = "Email PayPal", keyboardType = KeyboardType.Email)
                    }
                    F4fWithdrawChannel.CRYPTO -> {
                        ChoiceRow("Mạng chuyển", networks, network) { network = it; error = null }
                        AtTextField(wallet, { wallet = it.trim(); error = null }, label = "Địa chỉ ví", placeholder = "0x… / T…")
                    }
                    F4fWithdrawChannel.CARD -> {
                        ChoiceRow("Nhà mạng", telcos, telco) { telco = it; error = null }
                    }
                }
                AtTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit); error = null },
                    label = "Số coin muốn rút",
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    error = error,
                )
            }
            Spacer(Modifier.height(16.dp))
            AtButton(
                text = if (state.withdrawBusy) "Đang gửi yêu cầu…" else "Rút tiền",
                onClick = {
                    val req = build()
                    val problem = req.validate()
                    if (problem != null) error = problem else { error = null; confirm = req }
                },
                enabled = !state.withdrawBusy,
            )
        }

        GroupTitle("Lịch sử rút tiền")
        LoadBlock(state.withdrawList, onRetry = { vm.refresh(F4fHubTab.WITHDRAW) }) { items ->
            if (items.isEmpty()) {
                AtEmptyState(Icons.Rounded.AccountBalanceWallet, "Chưa có yêu cầu rút nào")
            } else {
                AtCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    items.forEach { WithdrawRow(it) }
                }
                if (state.withdrawHasMore) {
                    AtButton("Tải thêm", vm::loadMoreWithdrawHistory, style = AtButtonStyle.Tonal, height = 46.dp)
                }
            }
        }
    }

    confirm?.let { req ->
        AtDialog(
            onDismiss = { confirm = null },
            title = "Xác nhận rút ${req.channel.label}",
            message = "Không thể hoàn tác.",
            confirmText = "Xác nhận rút",
            onConfirm = {
                confirm = null
                amountText = ""            // muốn rút tiếp phải nhập lại số coin → tránh gửi trùng do bấm lại
                vm.submitWithdraw(req)
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                req.summary().forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(label, color = AtColors.TextMuted, fontSize = 13.sp, modifier = Modifier.weight(0.45f))
                        Text(value, color = AtColors.TextPrim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }
}

/** Hàng chip gợi ý lựa chọn (ngân hàng/mạng/nhà mạng); chọn = điền vào ô. */
@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Column {
        Text(label, color = AtColors.TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { o -> AtChip(text = o, selected = o.equals(selected, ignoreCase = true), onClick = { onPick(o) }) }
        }
    }
}

@Composable
private fun WithdrawRow(w: F4fWithdrawItem) {
    val destination = listOfNotNull(
        w.bankName?.takeIf { it.isNotBlank() }, w.accountNumber?.takeIf { it.isNotBlank() },
        w.telco?.takeIf { it.isNotBlank() }, w.network?.takeIf { it.isNotBlank() },
        w.address?.takeIf { it.isNotBlank() }?.let { if (it.length > 14) it.take(6) + "…" + it.takeLast(4) else it },
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                F4fWithdrawChannel.values().firstOrNull { it.name.equals(w.type, ignoreCase = true) }?.label ?: w.type.ifBlank { "Rút tiền" },
                color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            if (destination.isNotBlank()) Text(destination, color = AtColors.TextMuted, fontSize = 12.sp, maxLines = 1)
            Text(w.createdAt, color = AtColors.TextMuted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(w.amount, color = AtColors.TextPrim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (w.status.isNotBlank()) AtBadge(w.status, statusTone(w.status), Modifier.padding(top = 3.dp))
        }
    }
}
