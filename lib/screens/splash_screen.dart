import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../widgets/app_illustrations.dart';

// ════════════════════════════════════════════════════════════════
//  SplashScreen — shows AT PRO logo animation then routes to setup
// ════════════════════════════════════════════════════════════════

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});
  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _lineCtrl;
  late final Animation<double> _lineAnim;

  @override
  void initState() {
    super.initState();
    _lineCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    )..forward();
    _lineAnim = CurvedAnimation(parent: _lineCtrl, curve: Curves.easeOut);
  }

  @override
  void dispose() { _lineCtrl.dispose(); super.dispose(); }

  void _onLogoComplete() {
    if (mounted) context.go('/setup');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D0D14),
      body: Stack(
        children: [
          // Background grid (subtle)
          CustomPaint(painter: _GridPainter(), child: const SizedBox.expand()),

          // Center content
          Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Animated logo
                SplashLogoWidget(onComplete: _onLogoComplete),
                const SizedBox(height: 40),

                // Loading bar
                AnimatedBuilder(
                  animation: _lineAnim,
                  builder: (_, __) => Container(
                    width: 160,
                    height: 2,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(1),
                      color: const Color(0xFF1A1A2E),
                    ),
                    child: FractionallySizedBox(
                      alignment: Alignment.centerLeft,
                      widthFactor: _lineAnim.value,
                      child: Container(
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(1),
                          gradient: const LinearGradient(
                            colors: [Color(0xFF6C63FF), Color(0xFFEC4899)],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                // Version text
                const Text(
                  'v1.4.7',
                  style: TextStyle(
                    color: Color(0xFF4B5563),
                    fontSize: 12,
                    letterSpacing: 2,
                  ),
                ),
              ],
            ),
          ),

          // Bottom badge
          const Positioned(
            bottom: 40,
            left: 0, right: 0,
            child: Center(
              child: Text(
                'TỰ ĐỘNG HÓA TIKTOK',
                style: TextStyle(
                  color: Color(0xFF374151),
                  fontSize: 10,
                  letterSpacing: 4,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Grid background painter ───────────────────────────────────

class _GridPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFF1A1A2E).withOpacity(0.6)
      ..strokeWidth = 0.5;

    const spacing = 40.0;
    for (double x = 0; x < size.width; x += spacing) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), paint);
    }
    for (double y = 0; y < size.height; y += spacing) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(_) => false;
}
