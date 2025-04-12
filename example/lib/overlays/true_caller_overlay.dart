import 'dart:developer';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

class TrueCallerOverlay extends StatefulWidget {
  const TrueCallerOverlay({Key? key}) : super(key: key);

  @override
  State<TrueCallerOverlay> createState() => _TrueCallerOverlayState();
}

class _TrueCallerOverlayState extends State<TrueCallerOverlay> {
  bool isGold = true;

  final _goldColors = const [
    Color(0xFFa2790d),
    Color(0xFFebd197),
    Color(0xFFa2790d),
  ];

  final _silverColors = const [
    Color(0xFFAEB2B8),
    Color(0xFFC7C9CB),
    Color(0xFFD7D7D8),
    Color(0xFFAEB2B8),
  ];

  // 用于拖动的变量
  Offset? _moveStartPosition;
  Offset? _resizeStartPosition;
  double initialWidth = 300.0;  // 初始宽度
  double initialHeight = 400.0; // 初始高度

  static const String _kPortNameOverlay = 'OVERLAY';
  final _receivePort = ReceivePort();
  String? latestMessageFromOverlay;

  @override
  void initState() {
    super.initState();
    // 初始化时获取当前悬浮窗位置
    // FlutterOverlayWindow.getOverlayPosition().then((value) {
    //   log("Initial Overlay Position: $value");
    // });

    final res = IsolateNameServer.registerPortWithName(
      _receivePort.sendPort,
      _kPortNameOverlay,
    );
    log("$res: OVERLAY");
    _receivePort.listen((message) {
      log("message from OVERLAY: $message");
      setState(() {
        latestMessageFromOverlay = 'Latest Message From Overlay: $message';
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 12.0),
          width: double.infinity,
          // height: double.infinity,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: isGold ? _goldColors : _silverColors,
            ),
            borderRadius: BorderRadius.circular(12.0),
          ),
          child: GestureDetector(
            onTap: () {
              setState(() {
                isGold = !isGold;
              });
              FlutterOverlayWindow.getOverlayPosition().then((value) {
                log("Overlay Position: $value");
              });
            },
            child: Stack(
              children: [
                Column(
                  children: [
                    ListTile(
                      leading: Container(
                        height: 80.0,
                        width: 80.0,
                        decoration: BoxDecoration(
                          border: Border.all(color: Colors.black54),
                          shape: BoxShape.circle,
                          image: const DecorationImage(
                            image: NetworkImage(
                                "https://api.multiavatar.com/x-slayer.png"),
                          ),
                        ),
                      ),
                      title: const Text(
                        "X-SLAYER",
                        style: TextStyle(
                            fontSize: 20.0, fontWeight: FontWeight.bold),
                      ),
                      subtitle: const Text("Sousse , Tunisia"),
                    ),
                    const Divider(color: Colors.black54),
                    Text(latestMessageFromOverlay ?? 'dddd'),
                    const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 12.0),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text("+216 21065826"),
                              Text("Last call - 1 min ago"),
                            ],
                          ),
                          Text(
                            "Flutter Overlay",
                            style: TextStyle(
                                fontSize: 15.0, fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),
                    // 添加控制按钮
                    Padding(
                      padding: const EdgeInsets.all(8.0),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          // 移动按钮
                          GestureDetector(
                            onPanStart: (details) {
                              _moveStartPosition = details.globalPosition;
                            },
                            onPanUpdate: (details) async {
                              if (_moveStartPosition != null) {
                                final currentPos = await FlutterOverlayWindow.getOverlayPosition();
                                double newX = (currentPos?.x ?? 0.0) + details.delta.dx;
                                double newY = (currentPos?.y ?? 0.0) + details.delta.dy;
                                await FlutterOverlayWindow.moveOverlay(
                                    OverlayPosition(newX, newY)
                                );
                              }
                            },
                            onPanEnd: (details) {
                              _moveStartPosition = null;
                              FlutterOverlayWindow.getOverlayPosition().then((value) {
                                log("New Overlay Position: $value");
                              });
                            },
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 16.0, vertical: 8.0),
                              decoration: BoxDecoration(
                                color: Colors.black54,
                                borderRadius: BorderRadius.circular(8.0),
                              ),
                              child: const Text(
                                "Drag to Move",
                                style: TextStyle(color: Colors.white),
                              ),
                            ),
                          ),
                          // 缩放按钮
                          GestureDetector(
                            onPanStart: (details) {
                              _resizeStartPosition = details.globalPosition;
                            },
                            onPanUpdate: (details) async {
                              if (_resizeStartPosition != null) {
                                initialWidth += details.delta.dx;
                                initialHeight += details.delta.dy;
                                // 设置最小尺寸限制
                                initialWidth = initialWidth.clamp(150.0, double.infinity);
                                initialHeight = initialHeight.clamp(200.0, double.infinity);
                                await FlutterOverlayWindow.resizeOverlay(
                                  initialWidth.round(),
                                  initialHeight.round(),
                                  true,
                                );
                                setState(() {}); // 更新UI
                              }
                            },
                            onPanEnd: (details) {
                              _resizeStartPosition = null;
                            },
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 16.0, vertical: 8.0),
                              decoration: BoxDecoration(
                                color: Colors.black54,
                                borderRadius: BorderRadius.circular(8.0),
                              ),
                              child: const Text(
                                "Drag to Resize",
                                style: TextStyle(color: Colors.white),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const Spacer(),

                  ],
                ),
                Positioned(
                  top: 0,
                  right: 0,
                  child: IconButton(
                    onPressed: () async {
                      await FlutterOverlayWindow.closeOverlay();
                    },
                    icon: const Icon(
                      Icons.close,
                      color: Colors.black,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}