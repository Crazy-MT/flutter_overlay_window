package flutter.overlay.window.flutter_overlay_window;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

public class OverlayService extends Service implements View.OnTouchListener {

    // SharedPreferences Keys
    private static final String PREFS_NAME = "FlutterOverlayWindowPrefs";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";
    private static final String KEY_OVERLAY_WIDTH = "overlay_width";
    private static final String KEY_OVERLAY_HEIGHT = "overlay_height";
    private static final String KEY_ICON_X = "icon_x";
    private static final String KEY_ICON_Y = "icon_y";
    private static final int INVALID_POS = -9999; // Use a specific invalid value

    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    private WindowManager windowManager = null;
    private FlutterView flutterView;
    private MethodChannel flutterChannel = new MethodChannel(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(), OverlayConstants.OVERLAY_TAG);
    private BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG).getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    private LinearLayout buttonContainer;
    private float moveLastX, moveLastY;
    private float resizeLastX, resizeLastY;
    private boolean isMoving = false;
    private boolean isResizing = false;

    // ... Existing fields ...
    private ImageView floatingIcon;
    private boolean isIconMode = false;
    private float iconLastX, iconLastY;
    private boolean iconDragging;

    // SharedPreferences Instance
    private SharedPreferences sharedPreferences;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- SharedPreferences Helper Methods ---
    private void saveOverlayState(int x, int y, int width, int height) {
        if (sharedPreferences == null) return;
        Log.d("OverlayService", "Saving Overlay State: x=" + x + ", y=" + y + ", width=" + width + ", height=" + height);
        sharedPreferences.edit()
                .putInt(KEY_OVERLAY_X, x)
                .putInt(KEY_OVERLAY_Y, y)
                .putInt(KEY_OVERLAY_WIDTH, width)
                .putInt(KEY_OVERLAY_HEIGHT, height)
                .apply();
    }
    private Map<String, Integer> loadOverlayState() {
        if (sharedPreferences == null) return new HashMap<>();
        Map<String, Integer> state = new HashMap<>();
        state.put(KEY_OVERLAY_X, sharedPreferences.getInt(KEY_OVERLAY_X, INVALID_POS));
        state.put(KEY_OVERLAY_Y, sharedPreferences.getInt(KEY_OVERLAY_Y, INVALID_POS));
        state.put(KEY_OVERLAY_WIDTH, sharedPreferences.getInt(KEY_OVERLAY_WIDTH, INVALID_POS));
        state.put(KEY_OVERLAY_HEIGHT, sharedPreferences.getInt(KEY_OVERLAY_HEIGHT, INVALID_POS));
        Log.d("OverlayService", "Loading Overlay State: " + state);
        return state;
    }
    private void saveIconState(int x, int y) {
        if (sharedPreferences == null) return;
        Log.d("OverlayService", "Saving Icon State: x=" + x + ", y=" + y);
        sharedPreferences.edit()
                .putInt(KEY_ICON_X, x)
                .putInt(KEY_ICON_Y, y)
                .apply();
    }
    private Map<String, Integer> loadIconState() {
        if (sharedPreferences == null) return new HashMap<>();
        Map<String, Integer> state = new HashMap<>();
        state.put(KEY_ICON_X, sharedPreferences.getInt(KEY_ICON_X, INVALID_POS));
        state.put(KEY_ICON_Y, sharedPreferences.getInt(KEY_ICON_Y, INVALID_POS));
        Log.d("OverlayService", "Loading Icon State: " + state);
        return state;
    }
    // --- End SharedPreferences Helper Methods ---

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        Log.d("OverLay", "Destroying the overlay window service");

        // Save final state before destroying (optional, but good practice)
        if (windowManager != null && flutterView != null && !isIconMode) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            saveOverlayState(params.x, params.y, params.width, params.height);
        } else if (windowManager != null && floatingIcon != null && isIconMode) {
            WindowManager.LayoutParams iconParams = (WindowManager.LayoutParams) floatingIcon.getLayoutParams();
            saveIconState(iconParams.x, iconParams.y);
        }

        if (windowManager != null) {
            if (flutterView != null) {
                windowManager.removeView(flutterView);
                flutterView.detachFromFlutterEngine();
                flutterView = null;
            }
            if (buttonContainer != null) {
                windowManager.removeView(buttonContainer);
                buttonContainer = null;
            }
            if (floatingIcon != null) {
                windowManager.removeView(floatingIcon);
                floatingIcon = null;
            }
            windowManager = null;
        }
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;

        // Detach listeners? Flutter engine detach is handled above.
        if (flutterChannel != null) {
            flutterChannel.setMethodCallHandler(null);
        }
        if (overlayMessageChannel != null) {
            overlayMessageChannel.setMessageHandler(null);
        }
        super.onDestroy();
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void updateButtonPosition() {
        if (windowManager != null && buttonContainer != null) {
            WindowManager.LayoutParams flutterParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            WindowManager.LayoutParams buttonParams = (WindowManager.LayoutParams) buttonContainer.getLayoutParams();

            // 将按钮放在 flutterView 正下方
            buttonParams.x = flutterParams.x;

            // 使用 flutterView 的实际高度，而不是 screenHeight()
            int flutterHeight = flutterParams.height == -1 ? flutterView.getHeight() : flutterParams.height;
            buttonParams.y = flutterParams.y + flutterHeight / 2;

            // 添加一个小的间距（可选）
            // buttonParams.y += dpToPx(10);
            windowManager.updateViewLayout(buttonContainer, buttonParams);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void showFloatingIcon() {
        if (windowManager == null) return; // Ensure windowManager is available

        floatingIcon = new ImageView(getApplicationContext());
        floatingIcon.setImageResource(R.drawable.visible); // Replace with your icon resource
        int iconSize = dpToPx(36); // Adjust size as needed

        // Load saved icon position
        Map<String, Integer> savedIconState = loadIconState();
        int initialIconX = savedIconState.getOrDefault(KEY_ICON_X, INVALID_POS);
        int initialIconY = savedIconState.getOrDefault(KEY_ICON_Y, INVALID_POS);
        // Calculate default position if none saved
        if (initialIconX == INVALID_POS) {
            initialIconX = szWindow.x - iconSize - dpToPx(10); // Default to right edge with margin
        }
        if (initialIconY == INVALID_POS) {
            initialIconY = szWindow.y / 3; // Default vertical position
        }

        // Ensure default position is within bounds
        initialIconX = Math.max(0, Math.min(initialIconX, szWindow.x - iconSize));
        initialIconY = Math.max(statusBarHeightPx(), Math.min(initialIconY, szWindow.y - iconSize - navigationBarHeightPx()));

        WindowManager.LayoutParams iconParams = new WindowManager.LayoutParams(
                iconSize,
                iconSize,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        iconParams.gravity = Gravity.TOP | Gravity.LEFT;
        iconParams.x = initialIconX; // Start at right edge
        iconParams.y = initialIconY; // Middle of screen vertically

        /*floatingIcon.setOnLongClickListener(v -> {
            stopSelf();
            return true;
        });*/

        // Dragging logic
        floatingIcon.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingIcon.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    iconDragging = false;
                    iconLastX = event.getRawX();
                    iconLastY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - iconLastX;
                    float dy = event.getRawY() - iconLastY;
                    if (!iconDragging && dx * dx + dy * dy < 25) {
                        return true;
                    }
                    iconLastX = event.getRawX();
                    iconLastY = event.getRawY();
                    params.x += (int) dx;
                    params.y += (int) dy;

                    // Keep icon within screen bounds during drag
                    params.x = Math.max(0, Math.min(params.x, szWindow.x - params.width));
                    params.y = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - params.height - navigationBarHeightPx()));

                    windowManager.updateViewLayout(floatingIcon, params);
                    iconDragging = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean wasDragging = iconDragging; // Store drag status
                    iconDragging = false; // Reset drag status
                    if (wasDragging) {
                        // Snap to edge only if it was dragged
                        snapIconToEdge(); // This will also save the final snapped position
                    } else {
                        // Handle click: reopen overlay
                        reopenOverlay();
                    }
                    return true; // Consumed the up event
                case MotionEvent.ACTION_CANCEL:
                    iconDragging = false; // Reset drag status on cancel
                    // Optionally snap to edge here too if needed, or just save current pos
                    Log.d("TAG", "MTMTMT showFloatingIcon: " + params.x);
                    saveIconState(params.x, params.y);
                    return true;
            }
            return false;
        });

        windowManager.addView(floatingIcon, iconParams);

        // Snap to edge after dragging
//        floatingIcon.post(() -> snapIconToEdge());
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void snapIconToEdge() {
        if (floatingIcon == null || windowManager == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingIcon.getLayoutParams();
        int iconWidth = floatingIcon.getWidth();
        if (iconWidth == 0) iconWidth = params.width; // Use layout param width if view not measured yet
        int screenWidth = szWindow.x;

        // Snap to nearest edge (left or right)
        int finalX = (params.x + (iconWidth / 2)) < screenWidth / 2 ? 0 : screenWidth - iconWidth;

        // Keep y within bounds (already constrained during move, but double-check)
        int finalY = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - params.height - navigationBarHeightPx()));


        // Update only if position changed to avoid unnecessary layout/save
        if (params.x != finalX || params.y != finalY) {
            params.x = finalX;
            params.y = finalY;
            windowManager.updateViewLayout(floatingIcon, params);
        }
        // Save the final snapped position
        saveIconState(params.x, params.y);
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void reopenOverlay() {
        if (windowManager == null || !isIconMode) return;

        // Remove floating icon
        if (floatingIcon != null) {
            windowManager.removeView(floatingIcon);
            floatingIcon = null;
        }
        isIconMode = false;

        // Recreate flutterView
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(engine);
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setOnTouchListener(this);

        // Recreate buttonContainer (similar to onStartCommand)
        buttonContainer = new LinearLayout(getApplicationContext());
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setBackgroundColor(Color.TRANSPARENT);

        ImageView closeIV = getCloseImageView();
        ImageView invisibleImageView = getInvisibleImageView();

        ImageView moveImageView = getMoveImageView();

        ImageView resizeImageView = getResizeImageView();

        buttonContainer.addView(closeIV);
        buttonContainer.addView(invisibleImageView);
        buttonContainer.addView(moveImageView);
        buttonContainer.addView(resizeImageView);

        // Load saved overlay state
        Map<String, Integer> savedOverlayState = loadOverlayState();
        int lastX = savedOverlayState.getOrDefault(KEY_OVERLAY_X, INVALID_POS);
        int lastY = savedOverlayState.getOrDefault(KEY_OVERLAY_Y, INVALID_POS);
        int lastWidth = savedOverlayState.getOrDefault(KEY_OVERLAY_WIDTH, INVALID_POS);
        int lastHeight = savedOverlayState.getOrDefault(KEY_OVERLAY_HEIGHT, INVALID_POS);

        // --- Determine initial parameters ---
        int initialWidth = (lastWidth != INVALID_POS) ? lastWidth : (WindowSetup.width == -1999 ? WindowManager.LayoutParams.MATCH_PARENT : WindowSetup.width);
        int initialHeight = (lastHeight != INVALID_POS) ? lastHeight : (WindowSetup.height != -1999 ? WindowSetup.height : screenHeight()); // Use dpToPx here
        int initialX = (lastX != INVALID_POS) ? lastX : 0; // Default X
        int initialY = (lastY != INVALID_POS) ? lastY : -statusBarHeightPx(); // Default Y

        // Restore window params
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                // Use saved position if available, otherwise default
                initialX,
                initialY,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = WindowSetup.gravity;

        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0,
                0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        buttonParams.gravity = WindowSetup.gravity;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }

        windowManager.addView(flutterView, params);
        windowManager.addView(buttonContainer, buttonParams);
        flutterView.post(this::updateButtonPosition);

        // Notify Flutter side if needed
        flutterChannel.invokeMethod("overlayRestored", null);
    }

    private int calculateDefaultHeight() {
        // Provide a sensible default height, e.g., half the screen height minus system bars
        updateScreenSize(); // Make sure szWindow is up-to-date
        int usableHeight = szWindow.y - statusBarHeightPx() - navigationBarHeightPx();
        return Math.max(dpToPx(200), usableHeight / 2); // Example: half screen or 200dp minimum
    }

    private void updateScreenSize() {
        if (windowManager != null) {
            Display display = windowManager.getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealSize(szWindow); // Use getRealSize for full screen dimensions
            } else {
                DisplayMetrics displaymetrics = new DisplayMetrics();
                display.getMetrics(displaymetrics); // Fallback for older APIs
                szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
            }
            Log.d("OverlayService", "Screen size updated: " + szWindow.x + "x" + szWindow.y);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        mResources = getApplicationContext().getResources();
        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            closeOverlayWindow(false);
            return START_STICKY;
        }
        closeOverlayWindow(true);

        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG));
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterChannel.setMethodCallHandler((call, result) -> {
            if (call.method.equals("updateFlag")) {
                String flag = call.argument("flag").toString();
                updateOverlayFlag(result, flag);
            } else if (call.method.equals("updateOverlayPosition")) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlay(x, y, result);
            } else if (call.method.equals("resizeOverlay")) {
                int width = call.argument("width");
                int height = call.argument("height");
                boolean enableDrag = call.argument("enableDrag");
                resizeOverlay(width, height, enableDrag, result);
            }
        });
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message);
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            windowManager.getDefaultDisplay().getSize(szWindow);
        } else {
            DisplayMetrics displaymetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displaymetrics);
            int w = displaymetrics.widthPixels;
            int h = displaymetrics.heightPixels;
            szWindow.set(w, h);
        }
        int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
        int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;

        // 创建按钮容器和按钮
        buttonContainer = new LinearLayout(getApplicationContext());
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setBackgroundColor(Color.TRANSPARENT); // Optional: ensure container is transparent

        ImageView closeIV = getCloseImageView();
        ImageView invisibleImageView = getInvisibleImageView();

        ImageView moveImageView = getMoveImageView();

        ImageView resizeImageView = getResizeImageView();

        buttonContainer.addView(closeIV);
        buttonContainer.addView(invisibleImageView);
        buttonContainer.addView(moveImageView);
        buttonContainer.addView(resizeImageView);

        // Load saved state or use defaults
        Map<String, Integer> savedOverlayState = loadOverlayState();
        int lastX = savedOverlayState.getOrDefault(KEY_OVERLAY_X, INVALID_POS);
        int lastY = savedOverlayState.getOrDefault(KEY_OVERLAY_Y, INVALID_POS);
        int lastWidth = savedOverlayState.getOrDefault(KEY_OVERLAY_WIDTH, INVALID_POS);
        int lastHeight = savedOverlayState.getOrDefault(KEY_OVERLAY_HEIGHT, INVALID_POS);

        // Determine initial parameters
        int startXIntent = intent != null ? intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
        int startYIntent = intent != null ? intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;

        int initialWidth = (lastWidth != INVALID_POS) ? lastWidth : (WindowSetup.width == -1999 ? WindowManager.LayoutParams.MATCH_PARENT : WindowSetup.width);
        int initialHeight = (lastHeight != INVALID_POS) ? lastHeight : (WindowSetup.height != -1999 ? WindowSetup.height : screenHeight()); // Use dpToPx here

        // Prioritize saved position, then intent position, then default
        int initialX, initialY;
        boolean useAbsolutePosition = false;
        if (lastX != INVALID_POS && lastY != INVALID_POS) {
            initialX = lastX;
            initialY = lastY;
            useAbsolutePosition = true;
            Log.d("OverlayService", "Using saved position: " + initialX + "," + initialY);
        } else if (startXIntent != OverlayConstants.DEFAULT_XY || startYIntent != OverlayConstants.DEFAULT_XY) {
            initialX = (startXIntent == OverlayConstants.DEFAULT_XY) ? 0 : dpToPx(startXIntent); // Convert intent DP to PX if used
            initialY = (startYIntent == OverlayConstants.DEFAULT_XY) ? -statusBarHeightPx() : dpToPx(startYIntent);
            useAbsolutePosition = true; // Intent position implies absolute
            Log.d("OverlayService", "Using intent position: " + initialX + "," + initialY);
        } else {
            // No saved or intent position, use default gravity based positioning
            initialX = 0; // Let gravity handle positioning
            initialY = 0;
            useAbsolutePosition = false;
            Log.d("OverlayService", "Using default gravity position.");
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                useAbsolutePosition ? initialX : 0, // Use position only if absolute
                useAbsolutePosition ? initialY : 0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0,
                0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }
        params.gravity = WindowSetup.gravity;
        buttonParams.gravity = WindowSetup.gravity;

        flutterView.setOnTouchListener(this);
        windowManager.addView(flutterView, params);
        windowManager.addView(buttonContainer, buttonParams);
        flutterView.post(this::updateButtonPosition); // 等待 flutterView 布局完成
        isRunning = true;
//        moveOverlay(dx, dy, null);
//        updateButtonPosition(); // 确保初始位置正确
        return START_STICKY;
    }

    @NonNull
    private ImageView getResizeImageView() {
        ImageView resizeImageView = new ImageView(getApplicationContext());
        resizeImageView.setImageResource(R.drawable.icon_menu_chat_drag);
        LinearLayout.LayoutParams resizeParams = new LinearLayout.LayoutParams(
                dpToPx(24), // Width in pixels (adjust as needed)
                dpToPx(24)  // Height in pixels (adjust as needed)
        );
        resizeParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8)); // Optional: add margins
        resizeImageView.setLayoutParams(resizeParams);
        resizeImageView.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    resizeLastX = event.getRawX();
                    resizeLastY = event.getRawY();
                    isResizing = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float dx1 = event.getRawX() - resizeLastX;
                        float dy1 = event.getRawY() - resizeLastY;
                        resizeLastX = event.getRawX();
                        resizeLastY = event.getRawY();

                        // 计算新的宽高
                        int newWidth = params.width == -1 ? dpToPx(200) : params.width + (int) dx1;
                        int newHeight = params.height == -1 ? dpToPx(200) : params.height + (int) dy1;

                        // 设置最小尺寸限制
                        newWidth = Math.max(newWidth, dpToPx(100));
                        newHeight = Math.max(newHeight, dpToPx(100));

                        params.width = newWidth;
                        params.height = newHeight;
                        windowManager.updateViewLayout(flutterView, params);

                        updateButtonPosition(); // 更新按钮位置
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isResizing = false;
                    // Save the final size and position after resizing
                    if (flutterView != null && flutterView.isAttachedToWindow()) {
                        WindowManager.LayoutParams finalParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                        saveOverlayState(finalParams.x, finalParams.y, finalParams.width, finalParams.height);
                    }
                    return true;
            }
            return false;
        });
        return resizeImageView;
    }

    @NonNull
    private ImageView getMoveImageView() {
        ImageView moveImageView = new ImageView(getApplicationContext());
        moveImageView.setImageResource(R.drawable.icon_menu_chat_position); // Replace with your move icon resource
        LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(
                dpToPx(24), // Width in pixels (adjust as needed)
                dpToPx(24)  // Height in pixels (adjust as needed)
        );
        moveParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8)); // Optional: add margins
        moveImageView.setLayoutParams(moveParams);
        moveImageView.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    moveLastX = event.getRawX();
                    moveLastY = event.getRawY();
                    isMoving = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isMoving) {
                        float dx12 = event.getRawX() - moveLastX;
                        float dy12 = event.getRawY() - moveLastY;
                        moveLastX = event.getRawX();
                        moveLastY = event.getRawY();
                        params.x += dx12;
                        params.y += dy12;
                        windowManager.updateViewLayout(flutterView, params);
                        updateButtonPosition();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isMoving = false;
                    // Save the final position
                    if (flutterView != null && flutterView.isAttachedToWindow()) {
                        saveOverlayState(params.x, params.y, params.width, params.height);
                        Log.d("OverlayService", "Move finished, saved state.");
                    }
                    return true;
            }
            return false;
        });
        return moveImageView;
    }

    @NonNull
    private ImageView getCloseImageView() {
        ImageView closeIV = new ImageView(getApplicationContext());
        closeIV.setImageResource(R.drawable.icon_menu_chat_close); // Replace with your move icon resource
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                dpToPx(24), // Width in pixels (adjust as needed)
                dpToPx(24)  // Height in pixels (adjust as needed)
        );
        closeParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8)); // Optional: add margins
        closeIV.setLayoutParams(closeParams);
        closeIV.setOnClickListener(v -> stopSelf());
        return closeIV;
    }

    @NonNull
    private ImageView getInvisibleImageView() {
        ImageView closeIV = new ImageView(getApplicationContext());
        closeIV.setImageResource(R.drawable.eye_invisible); // Replace with your move icon resource
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                dpToPx(24), // Width in pixels (adjust as needed)
                dpToPx(24)  // Height in pixels (adjust as needed)
        );
        closeParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8)); // Optional: add margins
        closeIV.setLayoutParams(closeParams);
        closeIV.setOnClickListener(v -> closeOverlayWindow(false));
        return closeIV;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void closeOverlayWindow(boolean isRunning) {

        // Save the current overlay state BEFORE removing the view
        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            saveOverlayState(params.x, params.y, params.width, params.height);
        } else {
            Log.w("OverlayService", "Cannot save overlay state, view not ready.");
        }

        if (windowManager != null) {
            if (flutterView != null) {
                windowManager.removeView(flutterView);
                flutterView.detachFromFlutterEngine();
                flutterView = null;
            }
            if (buttonContainer != null) {
                windowManager.removeView(buttonContainer);
                buttonContainer = null;
            }

            // Create and show the floating icon
            showFloatingIcon();
            isIconMode = true;
            OverlayService.isRunning = isRunning;
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ?
                dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                :
                dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }


    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null) {
            WindowSetup.setFlag(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1;
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.width = (width == -1999 || width == -1) ? -1 : dpToPx(width);
            params.height = (height != 1999 || height != -1) ? dpToPx(height) : height;
            WindowSetup.enableDrag = enableDrag;
            try {
                windowManager.updateViewLayout(flutterView, params);
                // Save state after resize
                saveOverlayState(params.x, params.y, params.width, params.height);
                // Update button position after resize
                flutterView.post(this::updateButtonPosition);
                result.success(true);
            } catch (IllegalArgumentException e) {
                Log.e("OverlayService", "Error resizing overlay: " + e.getMessage());
                result.success(false);
            }
            result.success(true);
        } else {
            result.success(false);
        }
    }

    public static boolean resizeOverlay(int width, int height, boolean enableDrag) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
//                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
//                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
//                params.y = instance.dpToPx(y);
//                instance.windowManager.updateViewLayout(instance.flutterView, params);

                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                params.width = (width == -1999 || width == -1) ? -1 : instance.dpToPx(width);
                params.height = (height != 1999 || height != -1) ? instance.dpToPx(height) : height;
                WindowSetup.enableDrag = enableDrag;
                instance.windowManager.updateViewLayout(instance.flutterView, params);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            params.y = dpToPx(y);
            try {
                windowManager.updateViewLayout(flutterView, params);
                // Save state after move
                saveOverlayState(params.x, params.y, params.width, params.height);
                // Update button position after move
                updateButtonPosition(); // Direct call should be fine here
                if (result != null) result.success(true);
            } catch (IllegalArgumentException e) {
                Log.e("OverlayService", "Error moving overlay: " + e.getMessage());
                if (result != null) result.success(false);
            }
        } else {
            if (result != null)
                result.success(false);
        }
    }


    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(int x, int y) {
        if (instance != null && instance.flutterView != null) {
            if (instance.windowManager != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
                instance.windowManager.updateViewLayout(instance.flutterView, params);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }


    @Override
    public void onCreate() {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (windowManager != null && WindowSetup.enableDrag) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    if (!dragging && dx * dx + dy * dy < 25) {
                        return false;
                    }
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    boolean invertX = WindowSetup.gravity == (Gravity.TOP | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.CENTER | Gravity.RIGHT)
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    boolean invertY = WindowSetup.gravity == (Gravity.BOTTOM | Gravity.LEFT)
                            || WindowSetup.gravity == Gravity.BOTTOM
                            || WindowSetup.gravity == (Gravity.BOTTOM | Gravity.RIGHT);
                    int xx = params.x + ((int) dx * (invertX ? -1 : 1));
                    int yy = params.y + ((int) dy * (invertY ? -1 : 1));
                    params.x = xx;
                    params.y = yy;
                    if (windowManager != null) {
                        windowManager.updateViewLayout(flutterView, params);
                    }
                    dragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lastYPosition = params.y;
                    if (!WindowSetup.positionGravity.equals("none")) {
                        if (windowManager == null) return false;
                        windowManager.updateViewLayout(flutterView, params);
                        WindowManager.LayoutParams finalParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                        saveOverlayState(finalParams.x, finalParams.y, finalParams.width, finalParams.height);
                        mTrayTimerTask = new TrayAnimationTimerTask();
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();

        public TrayAnimationTimerTask() {
            super();
            mDestY = lastYPosition;
            switch (WindowSetup.positionGravity) {
                case "auto":
                    mDestX = (params.x + (flutterView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - flutterView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - flutterView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null) {
                    windowManager.updateViewLayout(flutterView, params);
                    saveOverlayState(params.x, params.y, params.width, params.height);
                }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }


}