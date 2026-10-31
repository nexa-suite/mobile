import 'package:flutter/material.dart';

import 'nexa_buyer_palette.dart';

/// A borderless touch surface with an explicit keyboard focus indicator.
final class NexaActionSurface extends StatefulWidget {
  const NexaActionSurface({
    super.key,
    required this.onTap,
    required this.child,
    this.color = NexaBuyerPalette.cardSurface,
    this.radius = 16,
  });

  final VoidCallback onTap;
  final Widget child;
  final Color color;
  final double radius;

  @override
  State<NexaActionSurface> createState() => _NexaActionSurfaceState();
}

final class _NexaActionSurfaceState extends State<NexaActionSurface> {
  bool _focused = false;

  @override
  Widget build(BuildContext context) => Material(
    color: widget.color,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(widget.radius),
      side: _focused
          ? BorderSide(
              color:
                  ThemeData.estimateBrightnessForColor(widget.color) ==
                      Brightness.dark
                  ? Colors.white
                  : NexaBuyerPalette.primary,
              width: 2,
            )
          : BorderSide.none,
    ),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: widget.onTap,
      onFocusChange: (focused) => setState(() => _focused = focused),
      child: widget.child,
    ),
  );
}
