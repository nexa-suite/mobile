import 'dart:typed_data';

import 'package:flutter/material.dart';

/// Full brand wordmark, using the vector coordinates of logo-nexa-blue.svg.
final class NexaWordmark extends StatelessWidget {
  const NexaWordmark({
    super.key,
    this.width = 128,
    this.color = const Color(0xFF2563EB),
  });
  final double width;
  final Color color;
  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Nexa',
    image: true,
    child: SizedBox(
      width: width,
      height: width * 98 / 315,
      child: CustomPaint(painter: _WordmarkPainter(color)),
    ),
  );
}

final class _WordmarkPainter extends CustomPainter {
  const _WordmarkPainter(this.color);
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 315, size.height / 98);
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        1.950793,
        0.0,
        0,
        0,
        0.0,
        1.950793,
        0,
        0,
        0,
        0,
        1,
        0,
        -16.241238,
        -8.659146,
        0,
        1,
      ]),
    );
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        1.277565,
        0.0,
        0,
        0,
        0.0,
        1.277565,
        0,
        0,
        0,
        0,
        1,
        0,
        -1146.89545,
        -3406.484354,
        0,
        1,
      ]),
    );
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        1.0,
        0.0,
        0,
        0,
        0.0,
        1.0,
        0,
        0,
        0,
        0,
        1,
        0,
        5.211018,
        0.0,
        0,
        1,
      ]),
    );
    {
      final path = Path();
      path.moveTo(906.867, 2682.043);
      path.lineTo(906.867, 2676.444);
      path.cubicTo(907.401, 2676.004, 907.978, 2675.607, 908.599, 2675.252);
      path.cubicTo(910.488, 2674.173, 912.627, 2673.633, 915.016, 2673.633);
      path.cubicTo(917.404, 2673.633, 919.525, 2674.228, 921.379, 2675.418);
      path.cubicTo(923.233, 2676.608, 924.686, 2678.168, 925.737, 2680.1);
      path.cubicTo(926.789, 2682.031, 927.314, 2684.092, 927.314, 2686.284);
      path.lineTo(927.314, 2705.84);
      path.lineTo(921.321, 2705.84);
      path.lineTo(921.321, 2687.479);
      path.cubicTo(921.321, 2685.055, 920.577, 2683.074, 919.088, 2681.536);
      path.cubicTo(917.6, 2679.997, 915.664, 2679.228, 913.282, 2679.228);
      path.cubicTo(911.661, 2679.228, 910.229, 2679.579, 908.988, 2680.282);
      path.cubicTo(908.162, 2680.75, 907.455, 2681.337, 906.867, 2682.043);
      path.moveTo(899.025, 2705.84);
      path.lineTo(899.025, 2674.297);
      path.lineTo(905.019, 2674.297);
      path.lineTo(905.019, 2705.84);
      path.lineTo(899.025, 2705.84);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.restore();
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        1.0,
        0.0,
        0,
        0,
        0.0,
        1.0,
        0,
        0,
        0,
        0,
        1,
        0,
        2.224607,
        0.0,
        0,
        1,
      ]),
    );
    {
      final path = Path();
      path.moveTo(950.025, 2706.504);
      path.cubicTo(946.871, 2706.504, 944.038, 2705.79, 941.525, 2704.363);
      path.cubicTo(939.013, 2702.935, 937.026, 2700.973, 935.565, 2698.478);
      path.cubicTo(934.104, 2695.982, 933.374, 2693.173, 933.374, 2690.052);
      path.cubicTo(933.374, 2686.942, 934.091, 2684.15, 935.524, 2681.677);
      path.cubicTo(936.957, 2679.203, 938.908, 2677.244, 941.376, 2675.8);
      path.cubicTo(943.844, 2674.355, 946.594, 2673.633, 949.627, 2673.633);
      path.cubicTo(952.538, 2673.633, 955.111, 2674.3, 957.347, 2675.634);
      path.cubicTo(959.582, 2676.967, 961.339, 2678.802, 962.618, 2681.137);
      path.cubicTo(963.896, 2683.472, 964.535, 2686.14, 964.535, 2689.139);
      path.cubicTo(964.535, 2689.582, 964.507, 2690.058, 964.452, 2690.567);
      path.cubicTo(964.397, 2691.076, 964.308, 2691.652, 964.186, 2692.293);
      path.lineTo(937.574, 2692.293);
      path.lineTo(937.574, 2687.33);
      path.lineTo(961.016, 2687.33);
      path.lineTo(958.824, 2689.239);
      path.cubicTo(958.802, 2687.125, 958.398, 2685.306, 957.679, 2683.827);
      path.cubicTo(956.994, 2682.42, 955.88, 2681.179, 954.508, 2680.365);
      path.cubicTo(953.135, 2679.552, 951.47, 2679.145, 949.511, 2679.145);
      path.cubicTo(947.452, 2679.145, 945.656, 2679.59, 944.123, 2680.481);
      path.cubicTo(942.591, 2681.372, 941.401, 2682.615, 940.554, 2684.208);
      path.cubicTo(939.707, 2685.802, 939.284, 2687.689, 939.284, 2689.87);
      path.cubicTo(939.284, 2692.072, 939.73, 2694.003, 940.621, 2695.664);
      path.cubicTo(941.511, 2697.324, 942.77, 2698.61, 944.397, 2699.523);
      path.cubicTo(946.024, 2700.436, 947.906, 2700.893, 950.042, 2700.893);
      path.cubicTo(951.802, 2700.893, 953.429, 2700.578, 954.923, 2699.947);
      path.cubicTo(956.417, 2699.316, 957.684, 2698.386, 958.724, 2697.158);
      path.lineTo(962.559, 2701.042);
      path.cubicTo(961.054, 2702.813, 959.209, 2704.166, 957.023, 2705.102);
      path.cubicTo(954.837, 2706.037, 952.504, 2706.504, 950.025, 2706.504);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.restore();
    {
      final path = Path();
      path.moveTo(990.749, 2705.84);
      path.lineTo(983.441, 2695.54);
      path.lineTo(986.943, 2690.664);
      path.lineTo(997.987, 2705.84);
      path.lineTo(990.749, 2705.84);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    {
      final path = Path();
      path.moveTo(982.304, 2693.937);
      path.lineTo(980.937, 2692.011);
      path.lineTo(979.792, 2690.999);
      path.lineTo(967.341, 2674.297);
      path.lineTo(974.629, 2674.297);
      path.lineTo(983.577, 2686.599);
      path.lineTo(985.607, 2689.354);
      path.lineTo(982.304, 2693.937);
      path.close();
      path.moveTo(966.76, 2705.84);
      path.lineTo(979.775, 2688.342);
      path.lineTo(983.195, 2692.692);
      path.lineTo(973.782, 2705.84);
      path.lineTo(966.76, 2705.84);
      path.close();
      path.moveTo(987.097, 2688.266);
      path.lineTo(983.763, 2683.702);
      path.lineTo(990.433, 2674.297);
      path.lineTo(994.795, 2674.297);
      path.lineTo(995.497, 2676.884);
      path.lineTo(987.097, 2688.266);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        1.0,
        0.0,
        0,
        0,
        0.0,
        1.0,
        0,
        0,
        0,
        0,
        1,
        0,
        -1.930007,
        -0.0,
        0,
        1,
      ]),
    );
    {
      final path = Path();
      path.moveTo(1015.933, 2706.504);
      path.cubicTo(1013.023, 2706.504, 1010.4, 2705.782, 1008.064, 2704.338);
      path.cubicTo(1005.729, 2702.894, 1003.883, 2700.935, 1002.528, 2698.461);
      path.cubicTo(1001.172, 2695.987, 1000.494, 2693.212, 1000.494, 2690.135);
      path.cubicTo(1000.494, 2687.025, 1001.172, 2684.231, 1002.528, 2681.751);
      path.cubicTo(1003.883, 2679.272, 1005.726, 2677.299, 1008.056, 2675.833);
      path.cubicTo(1010.386, 2674.367, 1013, 2673.633, 1015.9, 2673.633);
      path.cubicTo(1018.313, 2673.633, 1020.46, 2674.131, 1022.342, 2675.127);
      path.cubicTo(1024.223, 2676.124, 1025.737, 2677.501, 1026.882, 2679.261);
      path.cubicTo(1028.028, 2681.021, 1028.661, 2683.041, 1028.783, 2685.321);
      path.lineTo(1028.783, 2694.8);
      path.cubicTo(1028.65, 2697.08, 1028.019, 2699.103, 1026.89, 2700.868);
      path.cubicTo(1025.762, 2702.633, 1024.254, 2704.014, 1022.367, 2705.01);
      path.cubicTo(1020.479, 2706.006, 1018.335, 2706.504, 1015.933, 2706.504);
      path.close();
      path.moveTo(1016.896, 2700.843);
      path.cubicTo(1019.874, 2700.843, 1022.267, 2699.844, 1024.076, 2697.847);
      path.cubicTo(1025.886, 2695.849, 1026.791, 2693.262, 1026.791, 2690.085);
      path.cubicTo(1026.791, 2687.949, 1026.379, 2686.071, 1025.554, 2684.449);
      path.cubicTo(1024.729, 2682.828, 1023.573, 2681.563, 1022.084, 2680.656);
      path.cubicTo(1020.596, 2679.748, 1018.855, 2679.294, 1016.863, 2679.294);
      path.cubicTo(1014.871, 2679.294, 1013.111, 2679.754, 1011.584, 2680.672);
      path.cubicTo(1010.056, 2681.591, 1008.853, 2682.861, 1007.973, 2684.482);
      path.cubicTo(1007.093, 2686.104, 1006.653, 2687.966, 1006.653, 2690.069);
      path.cubicTo(1006.653, 2692.172, 1007.093, 2694.034, 1007.973, 2695.655);
      path.cubicTo(1008.853, 2697.277, 1010.062, 2698.547, 1011.6, 2699.465);
      path.cubicTo(1013.139, 2700.384, 1014.904, 2700.843, 1016.896, 2700.843);
      path.close();
      path.moveTo(1026.426, 2705.84);
      path.lineTo(1026.426, 2697.374);
      path.lineTo(1027.521, 2689.654);
      path.lineTo(1026.426, 2682.034);
      path.lineTo(1026.426, 2674.297);
      path.lineTo(1032.419, 2674.297);
      path.lineTo(1032.419, 2705.84);
      path.lineTo(1026.426, 2705.84);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.restore();
    canvas.restore();
    canvas.save();
    canvas.transform(
      Float64List.fromList([
        0.101703,
        0.089242,
        0,
        0,
        -0.088387,
        0.100729,
        0,
        0,
        0,
        0,
        1,
        0,
        214.221006,
        -329.366605,
        0,
        1,
      ]),
    );
    {
      final path = Path();
      path.fillType = PathFillType.evenOdd;
      path.moveTo(1157.498, 2321.44);
      path.lineTo(1230.639, 2415.823);
      path.cubicTo(1230.639, 2415.823, 1181.476, 2414.888, 1156.913, 2415.667);
      path.cubicTo(1132.289, 2416.448, 1082.898, 2420.51, 1082.898, 2420.51);
      path.lineTo(1157.498, 2321.44);
      path.close();
      canvas.drawPath(path, Paint()..color = color);
    }
    canvas.restore();
    canvas.restore();
  }

  @override
  bool shouldRepaint(_WordmarkPainter oldDelegate) =>
      oldDelegate.color != color;
}
