import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

// ════════════════════════════════════════════════════════════════
//  AppIllustrations — centralized SVG asset widgets
//  Dùng trong empty states, splash, permission screen
// ════════════════════════════════════════════════════════════════

class AppIllustrations {
  // ── Paths ──────────────────────────────────────────────────
  static const _base = 'assets/images';
  static const _icons = 'assets/icons';

  static const splashLogo       = '$_base/splash_logo.svg';
  static const farmingHero      = '$_base/farming_hero.svg';
  static const permissionSetup  = '$_base/permission_setup.svg';
  static const emptyAccounts    = '$_base/empty_accounts.svg';
  static const emptyLogs        = '$_base/empty_logs.svg';
  static const emptyStats       = '$_base/empty_stats.svg';
  static const emptyDevices     = '$_base/empty_devices.svg';
  static const iconSet          = '$_icons/icon_set.svg';
  static const notificationIcons = '$_icons/notification_icons.svg';
}

// ── Base SVG widget ───────────────────────────────────────────

class AppSvg extends StatelessWidget {
  final String asset;
  final double? width;
  final double? height;
  final BoxFit fit;
  final Color? colorOverride;

  const AppSvg(
    this.asset, {
    super.key,
    this.width,
    this.height,
    this.fit = BoxFit.contain,
    this.colorOverride,
  });

  @override
  Widget build(BuildContext context) {
    return SvgPicture.asset(
      asset,
      width:  width,
      height: height,
      fit:    fit,
      colorFilter: colorOverride != null
          ? ColorFilter.mode(colorOverride!, BlendMode.srcIn)
          : null,
    );
  }
}

// ── Splash Logo ───────────────────────────────────────────────

class SplashLogoWidget extends StatefulWidget {
  final VoidCallback? onComplete;
  const SplashLogoWidget({super.key, this.onComplete});
  @override
  State<SplashLogoWidget> createState() => _SplashLogoWidgetState();
}

class _SplashLogoWidgetState extends State<SplashLogoWidget>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _fade;
  late final Animation<double> _scale;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
    _fade  = Tween<double>(begin: 0, end: 1).animate(
      CurvedAnimation(parent: _ctrl, curve: const Interval(0, 0.6, curve: Curves.easeOut)));
    _scale = Tween<double>(begin: 0.7, end: 1).animate(
      CurvedAnimation(parent: _ctrl, curve: const Interval(0, 0.6, curve: Curves.elasticOut)));
    _ctrl.forward().then((_) {
      Future.delayed(const Duration(milliseconds: 600), () {
        widget.onComplete?.call();
      });
    });
  }

  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _ctrl,
    builder: (context, _) => Opacity(
      opacity: _fade.value,
      child: Transform.scale(
        scale: _scale.value,
        child: AppSvg(AppIllustrations.splashLogo, width: 200, height: 200),
      ),
    ),
  );
}

// ── Farming Hero (animated pulse) ────────────────────────────

class FarmingHeroWidget extends StatefulWidget {
  final bool isActive;
  const FarmingHeroWidget({super.key, this.isActive = false});
  @override
  State<FarmingHeroWidget> createState() => _FarmingHeroWidgetState();
}

class _FarmingHeroWidgetState extends State<FarmingHeroWidget>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulse;
  late final Animation<double> _scale;

  @override
  void initState() {
    super.initState();
    _pulse = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _scale = Tween<double>(begin: 0.97, end: 1.03).animate(
      CurvedAnimation(parent: _pulse, curve: Curves.easeInOut));
  }

  @override
  void dispose() { _pulse.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    if (!widget.isActive) {
      return AppSvg(AppIllustrations.farmingHero, width: 300, height: 220);
    }
    return AnimatedBuilder(
      animation: _pulse,
      builder: (_, __) => Transform.scale(
        scale: _scale.value,
        child: AppSvg(AppIllustrations.farmingHero, width: 300, height: 220),
      ),
    );
  }
}

// ── Empty State widget (generic) ──────────────────────────────

class EmptyStateWidget extends StatelessWidget {
  final String svgAsset;
  final String title;
  final String subtitle;
  final Widget? action;
  final double imageSize;

  const EmptyStateWidget({
    super.key,
    required this.svgAsset,
    required this.title,
    required this.subtitle,
    this.action,
    this.imageSize = 220,
  });

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          AppSvg(svgAsset, width: imageSize, height: imageSize * 0.75),
          const SizedBox(height: 24),
          Text(title,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              color: Color(0xFF9CA3AF),
            )),
          const SizedBox(height: 8),
          Text(subtitle,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 13,
              color: Color(0xFF6B7280),
              height: 1.5,
            )),
          if (action != null) ...[
            const SizedBox(height: 24),
            action!,
          ],
        ],
      ),
    ),
  );
}

// ── Convenience empty states ──────────────────────────────────

class EmptyAccountsWidget extends StatelessWidget {
  final VoidCallback? onAdd;
  const EmptyAccountsWidget({super.key, this.onAdd});
  @override
  Widget build(BuildContext context) => EmptyStateWidget(
    svgAsset: AppIllustrations.emptyAccounts,
    title:    'Chưa có tài khoản',
    subtitle: 'Thêm tài khoản TikTok để bắt đầu farm tự động',
    action: FilledButton.icon(
      onPressed: onAdd,
      icon:  const Icon(Icons.add_rounded, size: 18),
      label: const Text('Thêm tài khoản'),
      style: FilledButton.styleFrom(backgroundColor: const Color(0xFF6C63FF)),
    ),
  );
}

class EmptyLogsWidget extends StatelessWidget {
  const EmptyLogsWidget({super.key});
  @override
  Widget build(BuildContext context) => EmptyStateWidget(
    svgAsset: AppIllustrations.emptyLogs,
    title:    'Chưa có logs',
    subtitle: 'Logs realtime sẽ xuất hiện ở đây khi bạn bắt đầu farm',
  );
}

class EmptyStatsWidget extends StatelessWidget {
  const EmptyStatsWidget({super.key});
  @override
  Widget build(BuildContext context) => EmptyStateWidget(
    svgAsset: AppIllustrations.emptyStats,
    title:    'Chưa có dữ liệu',
    subtitle: 'Stats sẽ tự động cập nhật sau mỗi session farm',
  );
}

class EmptyDevicesWidget extends StatelessWidget {
  const EmptyDevicesWidget({super.key});
  @override
  Widget build(BuildContext context) => EmptyStateWidget(
    svgAsset: AppIllustrations.emptyDevices,
    title:    'Chưa kết nối thiết bị',
    subtitle: 'Nhập WS URL từ thiết bị khác\nđể kết nối qua mạng LAN',
  );
}
