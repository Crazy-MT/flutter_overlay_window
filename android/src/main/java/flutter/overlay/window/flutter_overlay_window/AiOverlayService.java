/*
package flutter.overlay.window.flutter_overlay_window;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences; // Import SharedPreferences
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
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

public class AiOverlayService extends Service implements View.OnTouchListener {
    // ... (Existing constants)

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
    private MethodChannel flutterChannel = null; // Initialize later
    private BasicMessageChannel<Object> overlayMessageChannel = null; // Initialize later
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
        // ... (no changes needed here, but ensure it's called after layout updates)
        if (windowManager != null && buttonContainer != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams flutterParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            WindowManager.LayoutParams buttonParams = (WindowManager.LayoutParams) buttonContainer.getLayoutParams();

            int flutterViewHeight = flutterView.getHeight(); // Get current measured height
            if (flutterViewHeight == 0 && flutterParams.height > 0) {
                // If measurement hasn't happened but height is fixed, use that
                flutterViewHeight = flutterParams.height;
            } else if (flutterViewHeight == 0) {
                // Fallback if height is still unknown (e.g., match_parent or wrap_content before layout)
                Log.w("OverlayService", "FlutterView height is 0 in updateButtonPosition, using fallback.");
                flutterViewHeight = dpToPx(100); // Or some reasonable default
            }


            buttonParams.x = flutterParams.x;
            // Position buttons relative to the bottom-right corner of the flutterView
            // Adjust if gravity makes x relative to right edge
            boolean isGravityRight = (flutterParams.gravity & Gravity.RIGHT) == Gravity.RIGHT;
            boolean isGravityBottom = (flutterParams.gravity & Gravity.BOTTOM) == Gravity.BOTTOM;

            // Adjust button X based on flutterView X and width if needed (depends on desired anchor)
            // For simplicity, keeping button X aligned with flutterView X (top-left) for now.
            // buttonParams.x = flutterParams.x + (isGravityRight ? 0 : flutterParams.width - buttonContainer.getWidth());


            buttonParams.y = flutterParams.y + flutterViewHeight - buttonContainer.getHeight(); // Place at bottom
            if (isGravityBottom) {
                // If flutterView y is relative to bottom, adjust button y accordingly
                buttonParams.y = flutterParams.y - buttonContainer.getHeight(); // Place *above* bottom edge
            }


            // Ensure buttons stay within screen bounds (optional but recommended)
            if (buttonParams.x < 0) buttonParams.x = 0;
            if (buttonParams.y < 0) buttonParams.y = 0;
            // Consider screen width/height if needed for bounds check


            try {
                if (buttonContainer.isAttachedToWindow()) {
                    windowManager.updateViewLayout(buttonContainer, buttonParams);
                } else {
                    Log.w("OverlayService", "Button container not attached, cannot update layout.");
                }
            } catch (IllegalArgumentException e) {
                // View not attached, ignore. This can happen during teardown.
                Log.e("OverlayService", "Error updating button layout: " + e.getMessage());
            }
        } else {
            Log.w("OverlayService", "Cannot update button position - views not ready.");
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Added LAYOUT_IN_SCREEN
                PixelFormat.TRANSLUCENT
        );
        iconParams.gravity = Gravity.TOP | Gravity.LEFT; // Use absolute positioning
        iconParams.x = initialIconX;
        iconParams.y = initialIconY;

        floatingIcon.setOnLongClickListener(v -> {
            stopSelf(); // Consider asking for confirmation?
            return true;
        });

        // Dragging logic for the icon
        floatingIcon.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) floatingIcon.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    iconDragging = false;
                    iconLastX = event.getRawX();
                    iconLastY = event.getRawY();
                    // Return true to consume the event and receive MOVE/UP
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - iconLastX;
                    float dy = event.getRawY() - iconLastY;
                    // Start dragging only if moved significantly
                    if (!iconDragging && (dx * dx + dy * dy) < 25) { // Threshold to prevent accidental drags on click
                        return true; // Not dragging yet
                    }
                    iconDragging = true; // Now we are dragging
                    iconLastX = event.getRawX();
                    iconLastY = event.getRawY();

                    // Update position
                    params.x += (int) dx;
                    params.y += (int) dy;

                    // Keep icon within screen bounds during drag
                    params.x = Math.max(0, Math.min(params.x, szWindow.x - params.width));
                    params.y = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - params.height - navigationBarHeightPx()));


                    windowManager.updateViewLayout(floatingIcon, params);
                    return true; // Consumed the move event
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
                    saveIconState(params.x, params.y);
                    return true;

            }
            return false; // Didn't consume the event
        });

        try {
            windowManager.addView(floatingIcon, iconParams);
            // No need to snap immediately, let user place it or load saved pos.
        } catch (IllegalStateException e) {
            Log.e("OverlayService", "Error adding floating icon: " + e.getMessage());
            // Handle case where service might be stopping concurrently
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void snapIconToEdge() {
        if (floatingIcon == null || windowManager == null || !floatingIcon.isAttachedToWindow()) return;
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
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void reopenOverlay() {
        if (windowManager == null || !isIconMode) return;
        Log.d("OverlayService", "Reopening overlay");

        // Remove floating icon
        if (floatingIcon != null) {
            if (floatingIcon.isAttachedToWindow()) {
                windowManager.removeView(floatingIcon);
            }
            floatingIcon = null;
        }
        isIconMode = false;

        // Re-initialize Flutter related channels if they were nulled
        setupChannels(); // Ensure channels are ready

        // Recreate flutterView
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            Log.e("OverlayService", "Flutter engine cache is null on reopen!");
            // Handle this error appropriately - maybe stop the service?
            stopSelf();
            return;
        }
        engine.getLifecycleChannel().appIsResumed();
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(engine);
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setOnTouchListener(this); // Re-set touch listener

        // Recreate buttonContainer
        buttonContainer = new LinearLayout(getApplicationContext());
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setBackgroundColor(Color.TRANSPARENT);
        // Set a minimum size or fixed size for buttons if needed for initial positioning
        // buttonContainer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));


        // Add buttons back
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
        int initialWidth = (lastWidth != INVALID_POS) ? lastWidth : (WindowSetup.width == -1999 ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(WindowSetup.width));
        int initialHeight = (lastHeight != INVALID_POS) ? lastHeight : (WindowSetup.height != -1999 ? dpToPx(WindowSetup.height) : calculateDefaultHeight()); // Use dpToPx here
        int initialX = (lastX != INVALID_POS) ? lastX : 0; // Default X
        int initialY = (lastY != INVALID_POS) ? lastY : -statusBarHeightPx(); // Default Y


        // --- Create LayoutParams ---
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                // Use saved position if available, otherwise default
                initialX,
                initialY,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                // Use WindowSetup.flag if needed, ensure other flags are present
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR // May interfere with positioning? Test.
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        // Use absolute positioning, remove default gravity if restoring position
        // If using saved X/Y, set gravity to TOP | LEFT
        if (lastX != INVALID_POS || lastY != INVALID_POS) {
            params.gravity = Gravity.TOP | Gravity.LEFT;
        } else {
            params.gravity = WindowSetup.gravity; // Use configured gravity only if no position saved
        }

        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // Initial position will be updated by updateButtonPosition
                0,
                0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Added LAYOUT_IN_SCREEN
                PixelFormat.TRANSLUCENT
        );
        // Button gravity should match flutterView's effective gravity (TOP | LEFT if position is restored)
        buttonParams.gravity = params.gravity;


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }

        try {
            windowManager.addView(flutterView, params);
            windowManager.addView(buttonContainer, buttonParams);

            // Crucial: Update button position *after* both views are added and flutterView has a chance to lay out
            flutterView.post(this::updateButtonPosition);

            isRunning = true; // Mark as running again
            Log.d("OverlayService", "Overlay reopened and views added.");
        } catch (IllegalStateException e) {
            Log.e("OverlayService", "Error reopening overlay: " + e.getMessage());
            // Clean up if adding failed
            if (flutterView != null && flutterView.isAttachedToWindow()) windowManager.removeView(flutterView);
            if (buttonContainer != null && buttonContainer.isAttachedToWindow()) windowManager.removeView(buttonContainer);
            flutterView = null;
            buttonContainer = null;
            stopSelf(); // Stop service if reopening failed
        }


        // Notify Flutter side if needed
        flutterChannel.invokeMethod("overlayRestored", null);
    }

    private void setupChannels() {
        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            Log.e("OverlayService", "Flutter engine cache is null during channel setup!");
            return;
        }
        if (flutterChannel == null) {
            flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG);
        }
        // Re-set the handler if it might have been cleared or if the engine instance changed (unlikely here)
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
            } else {
                result.notImplemented();
            }
        });

        if (overlayMessageChannel == null) {
            overlayMessageChannel = new BasicMessageChannel<>(engine.getDartExecutor(), OverlayConstants.MESSENGER_TAG, JSONMessageCodec.INSTANCE);
        }
        overlayMessageChannel.setMessageHandler((message, reply) -> {
            if (WindowSetup.messenger != null) {
                WindowSetup.messenger.send(message);
            } else {
                Log.w("OverlayService", "WindowSetup.messenger is null, cannot forward message.");
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mResources = getApplicationContext().getResources(); // Initialize resources

        // Check if we should close
        if (intent != null && intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false)) {
            Log.d("OverlayService", "Intent to close window received.");
            closeOverlayWindow(false); // Minimize to icon, don't set isRunning to false yet
            return START_STICKY; // Keep service running for the icon
        }

        // If already running and not icon mode, maybe update position based on intent? Or just ignore?
        if (isRunning && !isIconMode) {
            Log.d("OverlayService", "Service already running with overlay active. Ignoring start command or updating.");
            // Optional: Handle updates if needed based on intent extras
            return START_STICKY;
        }

        // If in icon mode, reopen the overlay
        if (isIconMode) {
            Log.d("OverlayService", "Service running in icon mode. Reopening overlay.");
            reopenOverlay();
            return START_STICKY;
        }


        // --- Proceed with Initial Setup ---
        Log.d("OverlayService", "Starting service and creating overlay for the first time or after full stop.");
        // Ensure previous views are cleaned up if any somehow persisted (shouldn't happen with onDestroy)
        cleanupViews();

        FlutterEngine engine = FlutterEngineCache.getInstance().get(OverlayConstants.CACHED_TAG);
        if (engine == null) {
            Log.e("OverlayService", "FlutterEngine cache is null on start! Cannot start service.");
            stopSelf();
            return START_NOT_STICKY; // Don't restart if engine isn't ready
        }
        engine.getLifecycleChannel().appIsResumed();

        // Setup Flutter View
        flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
        flutterView.attachToFlutterEngine(engine);
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setOnTouchListener(this);

        // Setup Channels
        setupChannels(); // Use the helper method

        // Setup Window Manager
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e("OverlayService", "WindowManager is null! Cannot create overlay.");
            stopSelf();
            return START_NOT_STICKY;
        }
        updateScreenSize(); // Get screen dimensions


        // Load saved state or use defaults
        Map<String, Integer> savedOverlayState = loadOverlayState();
        int lastX = savedOverlayState.getOrDefault(KEY_OVERLAY_X, INVALID_POS);
        int lastY = savedOverlayState.getOrDefault(KEY_OVERLAY_Y, INVALID_POS);
        int lastWidth = savedOverlayState.getOrDefault(KEY_OVERLAY_WIDTH, INVALID_POS);
        int lastHeight = savedOverlayState.getOrDefault(KEY_OVERLAY_HEIGHT, INVALID_POS);

        // Determine initial parameters
        int startXIntent = intent != null ? intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
        int startYIntent = intent != null ? intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;

        int initialWidth = (lastWidth != INVALID_POS) ? lastWidth : (WindowSetup.width == -1999 ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(WindowSetup.width));
        int initialHeight = (lastHeight != INVALID_POS) ? lastHeight : (WindowSetup.height != -1999 ? dpToPx(WindowSetup.height) : calculateDefaultHeight()); // Use dpToPx here

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


        // Setup Button Container
        buttonContainer = new LinearLayout(getApplicationContext());
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setBackgroundColor(Color.TRANSPARENT);
        // Add buttons
        ImageView closeIV = getCloseImageView();
        ImageView invisibleImageView = getInvisibleImageView();
        ImageView moveImageView = getMoveImageView();
        ImageView resizeImageView = getResizeImageView();
        buttonContainer.addView(closeIV);
        buttonContainer.addView(invisibleImageView);
        buttonContainer.addView(moveImageView);
        buttonContainer.addView(resizeImageView);


        // Create Layout Params
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                useAbsolutePosition ? initialX : 0, // Use position only if absolute
                useAbsolutePosition ? initialY : 0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR // Consider if this affects absolute positioning
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        WindowManager.LayoutParams buttonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, // Position updated later
                0,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Added LAYOUT_IN_SCREEN
                PixelFormat.TRANSLUCENT
        );

        // Set Gravity
        if (useAbsolutePosition) {
            params.gravity = Gravity.TOP | Gravity.LEFT; // Absolute position
            buttonParams.gravity = Gravity.TOP | Gravity.LEFT;
        } else {
            params.gravity = WindowSetup.gravity; // Configured gravity
            buttonParams.gravity = WindowSetup.gravity;
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
            params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
        }


        // Add Views to Window Manager
        try {
            windowManager.addView(flutterView, params);
            windowManager.addView(buttonContainer, buttonParams);
            // Schedule button position update after layout
            flutterView.post(this::updateButtonPosition);
            isRunning = true;
            isIconMode = false;
            instance = this; // Set instance
            Log.d("OverlayService", "Overlay and buttons added to window manager.");
        } catch (Exception e) {
            Log.e("OverlayService", "Error adding views to window manager: " + e.getMessage(), e);
            cleanupViews(); // Clean up partially added views
            stopSelf();
            return START_NOT_STICKY;
        }


        // Start Foreground Notification (moved to onCreate)
        return START_STICKY;
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

    private int calculateDefaultHeight() {
        // Provide a sensible default height, e.g., half the screen height minus system bars
        updateScreenSize(); // Make sure szWindow is up-to-date
        int usableHeight = szWindow.y - statusBarHeightPx() - navigationBarHeightPx();
        return Math.max(dpToPx(200), usableHeight / 2); // Example: half screen or 200dp minimum
    }

    private void cleanupViews() {
        Log.d("OverlayService", "Cleaning up existing views.");
        if (windowManager != null) {
            if (flutterView != null && flutterView.isAttachedToWindow()) {
                try {
                    windowManager.removeView(flutterView);
                } catch (Exception e) { Log.e("OverlayService", "Error removing flutterView: " + e.getMessage()); }
            }
            if (buttonContainer != null && buttonContainer.isAttachedToWindow()) {
                try {
                    windowManager.removeView(buttonContainer);
                } catch (Exception e) { Log.e("OverlayService", "Error removing buttonContainer: " + e.getMessage()); }
            }
            if (floatingIcon != null && floatingIcon.isAttachedToWindow()) {
                try {
                    windowManager.removeView(floatingIcon);
                } catch (Exception e) { Log.e("OverlayService", "Error removing floatingIcon: " + e.getMessage()); }
            }
        }
        flutterView = null;
        buttonContainer = null;
        floatingIcon = null;
    }

    @NonNull
    private ImageView getResizeImageView() {
        ImageView resizeImageView = new ImageView(getApplicationContext());
        resizeImageView.setImageResource(R.drawable.icon_menu_chat_drag);
        LinearLayout.LayoutParams resizeParams = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        resizeParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        resizeImageView.setLayoutParams(resizeParams);
        resizeImageView.setOnTouchListener((v, event) -> {
            if (flutterView == null || !flutterView.isAttachedToWindow()) return false; // Guard
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    resizeLastX = event.getRawX();
                    resizeLastY = event.getRawY();
                    isResizing = true;
                    return true; // Consume event
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        float dx1 = event.getRawX() - resizeLastX;
                        float dy1 = event.getRawY() - resizeLastY;
                        resizeLastX = event.getRawX();
                        resizeLastY = event.getRawY();

                        // Calculate new width/height (handle MATCH_PARENT/-1 and WRAP_CONTENT/-2)
                        int currentWidth = params.width > 0 ? params.width : flutterView.getWidth();
                        int currentHeight = params.height > 0 ? params.height : flutterView.getHeight();

                        int newWidth = currentWidth + (int) dx1;
                        int newHeight = currentHeight + (int) dy1;

                        // Set minimum size limits
                        int minWidthPx = dpToPx(100);
                        int minHeightPx = dpToPx(100);
                        newWidth = Math.max(newWidth, minWidthPx);
                        newHeight = Math.max(newHeight, minHeightPx);

                        // Constrain maximum size to screen dimensions (optional)
                        updateScreenSize();
                        newWidth = Math.min(newWidth, szWindow.x - params.x); // Max width based on current X
                        newHeight = Math.min(newHeight, szWindow.y - params.y - statusBarHeightPx() - navigationBarHeightPx()); // Max height based on current Y


                        params.width = newWidth;
                        params.height = newHeight;

                        try {
                            if (flutterView != null && flutterView.isAttachedToWindow()) {
                                windowManager.updateViewLayout(flutterView, params);
                                // Update button position after resizing
                                flutterView.post(this::updateButtonPosition);
                            }
                        } catch (IllegalArgumentException e) {
                            Log.e("OverlayService", "Error updating layout during resize: " + e.getMessage());
                            isResizing = false; // Stop resizing if view is gone
                        }
                    }
                    return true; // Consume event
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isResizing) {
                        isResizing = false;
                        // Save the final size and position after resizing
                        if (flutterView != null && flutterView.isAttachedToWindow()) {
                            WindowManager.LayoutParams finalParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                            saveOverlayState(finalParams.x, finalParams.y, finalParams.width, finalParams.height);
                        }
                        // Ensure button position is correct after resize is finished
                        if (flutterView != null) flutterView.post(this::updateButtonPosition);
                    }
                    return true; // Consume event
            }
            return false;
        });
        return resizeImageView;
    }


    @NonNull
    private ImageView getMoveImageView() {
        ImageView moveImageView = new ImageView(getApplicationContext());
        moveImageView.setImageResource(R.drawable.icon_menu_chat_position);
        LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        moveParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        moveImageView.setLayoutParams(moveParams);
        moveImageView.setOnTouchListener((v, event) -> {
            if (flutterView == null || !flutterView.isAttachedToWindow()) return false; // Guard
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            // Ensure gravity is absolute for manual moving
            if (params.gravity != (Gravity.TOP | Gravity.LEFT)) {
                Log.w("OverlayService", "Switching gravity to TOP|LEFT for move operation.");
                params.gravity = Gravity.TOP | Gravity.LEFT;
                // May need to adjust initial x, y if gravity changes, but usually fine if starting drag
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    moveLastX = event.getRawX();
                    moveLastY = event.getRawY();
                    isMoving = true;
                    return true; // Consume event
                case MotionEvent.ACTION_MOVE:
                    if (isMoving) {
                        float dx12 = event.getRawX() - moveLastX;
                        float dy12 = event.getRawY() - moveLastY;
                        moveLastX = event.getRawX();
                        moveLastY = event.getRawY();

                        params.x += (int) dx12;
                        params.y += (int) dy12;

                        // Keep within screen bounds (simple version)
                        updateScreenSize();
                        params.x = Math.max(0, Math.min(params.x, szWindow.x - params.width));
                        params.y = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - params.height - navigationBarHeightPx()));


                        try {
                            if (flutterView != null && flutterView.isAttachedToWindow()) {
                                windowManager.updateViewLayout(flutterView, params);
                                // Update button position continuously while moving
                                updateButtonPosition(); // Call directly as view isn't resizing
                            }
                        } catch (IllegalArgumentException e) {
                            Log.e("OverlayService", "Error updating layout during move: " + e.getMessage());
                            isMoving = false; // Stop moving if view is gone
                        }
                    }
                    return true; // Consume event
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isMoving) {
                        isMoving = false;
                        // Save the final position
                        if (flutterView != null && flutterView.isAttachedToWindow()) {
                            WindowManager.LayoutParams finalParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                            saveOverlayState(finalParams.x, finalParams.y, finalParams.width, finalParams.height);
                            Log.d("OverlayService", "Move finished, saved state.");
                            // Final button position update just in case
                            updateButtonPosition();
                        }
                    }
                    return true; // Consume event
            }
            return false;
        });
        return moveImageView;
    }

    @NonNull
    private ImageView getCloseImageView() {
        ImageView closeIV = new ImageView(getApplicationContext());
        closeIV.setImageResource(R.drawable.icon_menu_chat_close);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        closeParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        closeIV.setLayoutParams(closeParams);
        closeIV.setOnClickListener(v -> {
            Log.d("OverlayService", "Close button clicked, stopping service.");
            stopSelf(); // Stop the service entirely
        });
        return closeIV;
    }

    @NonNull
    private ImageView getInvisibleImageView() {
        ImageView invisibleIV = new ImageView(getApplicationContext()); // Renamed variable
        invisibleIV.setImageResource(R.drawable.eye_invisible);
        LinearLayout.LayoutParams invisibleParams = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)); // Renamed variable
        invisibleParams.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        invisibleIV.setLayoutParams(invisibleParams); // Use renamed variable
        invisibleIV.setOnClickListener(v -> {
            Log.d("OverlayService", "Minimize button clicked.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                closeOverlayWindow(false); // Minimize to icon
            } else {
                Log.w("OverlayService", "Icon mode not supported below Jelly Bean MR1");
                stopSelf(); // Fallback: just stop the service
            }
        });
        return invisibleIV; // Return renamed variable
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void closeOverlayWindow(boolean isRestarting) { // Renamed param for clarity
        Log.d("OverlayService", "Closing overlay window (minimize). isRestarting=" + isRestarting);
        if (isIconMode) {
            Log.w("OverlayService", "Already in icon mode, closeOverlayWindow called again?");
            return; // Avoid closing if already minimized
        }

        // Save the current overlay state BEFORE removing the view
        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            saveOverlayState(params.x, params.y, params.width, params.height);
        } else {
            Log.w("OverlayService", "Cannot save overlay state, view not ready.");
        }

        if (windowManager != null) {
            // Remove FlutterView and ButtonContainer
            if (flutterView != null) {
                if (flutterView.isAttachedToWindow()) {
                    windowManager.removeView(flutterView);
                }
                // Don't detach from engine here if we want to reuse it quickly
                // flutterView.detachFromFlutterEngine(); // Consider detaching only in onDestroy
                flutterView = null;
            }
            if (buttonContainer != null) {
                if (buttonContainer.isAttachedToWindow()) {
                    windowManager.removeView(buttonContainer);
                }
                buttonContainer = null;
            }

            // Create and show the floating icon only if not restarting immediately
            if (!isRestarting) {
                Log.d("OverlayService", "Showing floating icon.");
                showFloatingIcon(); // This will load the icon's saved position
                isIconMode = true;
                isRunning = true; // Service is still running, just in icon mode
                // Update notification? Maybe change text to "Minimized"? (Optional)
            } else {
                Log.d("OverlayService", "Closing overlay because service is restarting/stopping.");
                // If isRestarting is true, it means the service is likely stopping completely
                // or being restarted by the system/developer. Don't show the icon.
                isIconMode = false;
                isRunning = false;
                // Clean up icon if it somehow exists
                if (floatingIcon != null && floatingIcon.isAttachedToWindow()) {
                    windowManager.removeView(floatingIcon);
                    floatingIcon = null;
                }
            }

        } else {
            Log.w("OverlayService", "WindowManager is null during closeOverlayWindow.");
            isRunning = false; // Mark as not running if WM is gone
        }
    }

    // --- Utility and System Methods ---

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        updateScreenSize(); // Ensure szWindow is up-to-date
        // This calculation seems complex and might depend on flags.
        // Let's simplify: return the actual screen height. Layout params handle positioning.
        return szWindow.y;
        // Original calculation:
        // return inPortrait() ?
        //         dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
        //         :
        //         dm.heightPixels + statusBarHeightPx();
    }


    private int statusBarHeightPx() {
        if (mStatusBarHeight == null || mStatusBarHeight <= 0) { // Recalculate if needed
            mStatusBarHeight = 0; // Default to 0
            int resourceId = mResources.getIdentifier("status_bar_height", "dimen", "android");
            if (resourceId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(resourceId);
            }
            if (mStatusBarHeight <= 0) {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP); // Fallback DP
            }
        }
        return mStatusBarHeight;
    }


    // Check if navigation bar is visible (can be complex, simplified check)
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private boolean isNavigationBarVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            updateScreenSize(); // Ensure szWindow has real size
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            int usableHeight = metrics.heightPixels;
            return szWindow.y > usableHeight; // If real height > usable height, nav bar likely exists
        }
        return true; // Assume visible on older versions
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    int navigationBarHeightPx() {
        if (!isNavigationBarVisible()) {
            return 0; // Return 0 if nav bar is hidden or gestural
        }
        if (mNavigationBarHeight == null || mNavigationBarHeight < 0) { // Recalculate if needed (use < 0 check)
            mNavigationBarHeight = 0; // Default to 0
            int resourceId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");
            if (resourceId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(resourceId);
            }
            if (mNavigationBarHeight <= 0) {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP); // Fallback DP
            }
        }
        // Return 0 if calculated height is 0 (e.g., resource not found and fallback is 0)
        return Math.max(0, mNavigationBarHeight);
    }


    private void updateOverlayFlag(MethodChannel.Result result, String flag) {
        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowSetup.setFlag(flag); // Update the static flag setting
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            // Reconstruct flags, ensuring essential ones remain
            params.flags = WindowSetup.flag | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | // Keep this? Test if it causes issues
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

            // Adjust alpha for clickable flag on S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WindowSetup.flag == clickableFlag) {
                params.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER;
            } else {
                params.alpha = 1.0f; // Reset alpha if not clickable or below S
            }
            windowManager.updateViewLayout(flutterView, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    // Called from Flutter
    private void resizeOverlay(int width, int height, boolean enableDrag, MethodChannel.Result result) {
        Log.d("OverlayService", "resizeOverlay called from Flutter: w=" + width + ", h=" + height + ", drag=" + enableDrag);
        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            // Use MATCH_PARENT (-1) or specific DP value
            params.width = (width == -1 || width == -1999) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
            params.height = (height == -1 || height == -1999) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(height);
            WindowSetup.enableDrag = enableDrag; // Update drag state

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
        } else {
            result.success(false);
        }
    }

    // Static version - Deprecated or modify to call instance method?
    // Let's keep the instance method resizeOverlay called from Flutter and remove this static one
    // public static boolean resizeOverlay(int width, int height, boolean enableDrag) { ... }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void moveOverlay(int x, int y, MethodChannel.Result result) {
        Log.d("OverlayService", "moveOverlay called from Flutter: x=" + x + ", y=" + y);
        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            // Ensure absolute positioning for explicit moves
            params.gravity = Gravity.TOP | Gravity.LEFT;
            // Convert DP from Flutter to PX
            params.x = dpToPx(x);
            params.y = dpToPx(y);

            // Bounds check (optional but recommended)
            updateScreenSize();
            params.x = Math.max(0, Math.min(params.x, szWindow.x - params.width));
            params.y = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - params.height - navigationBarHeightPx()));

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
            if (result != null) result.success(false);
        }
    }


    public static Map<String, Double> getCurrentPosition() {
        if (instance != null && instance.flutterView != null && instance.flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) instance.flutterView.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            // Convert current PX position back to DP for Flutter
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    // Static version - Deprecated or modify? Let's remove it and rely on Flutter calls to the instance method.
    // public static boolean moveOverlay(int x, int y) { ... }


    @Override
    public void onCreate() {
        super.onCreate(); // Call super.onCreate()
        Log.d("OverlayService", "onCreate");
        // Initialize instance here
        instance = this;
        // Initialize SharedPreferences here as well
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mResources = getApplicationContext().getResources(); // Ensure resources are ready early

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class); // Or your MainActivity? Check intent target.
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT; // Add UPDATE_CURRENT if needed
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        // FLAG_ACTIVITY_NEW_TASK might be needed if launching activity from service context
        // notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher"); // Or "drawable"
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle != null ? WindowSetup.overlayTitle : "Overlay Active") // Use defaults
                .setContentText(WindowSetup.overlayContent != null ? WindowSetup.overlayContent : "Tap to manage")
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Make it non-dismissible
                .setPriority(NotificationCompat.PRIORITY_MIN) // Less intrusive notification
                .setVisibility(WindowSetup.notificationVisibility != 0 ? WindowSetup.notificationVisibility : NotificationCompat.VISIBILITY_PRIVATE) // Use default visibility
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);

    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Overlay Window Service", // More descriptive name
                    NotificationManager.IMPORTANCE_LOW // Use LOW or MIN importance for less intrusion
            );
            serviceChannel.setDescription("Notification channel for the overlay window service.");
            // serviceChannel.setShowBadge(false); // Optionally disable badge
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d("OverlayService", "Notification channel created.");
            } else {
                Log.e("OverlayService", "NotificationManager is null, cannot create channel.");
            }
        }
    }


    private int getDrawableResourceId(String resType, String name) {
        if (name == null || name.isEmpty()) {
            Log.w("OverlayService", "getDrawableResourceId: name is null or empty");
            return 0;
        }
        try {
            // Ensure the name format is correct (e.g., 'ic_launcher' or just 'launcher')
            String resourceName = name.startsWith("ic_") ? name : "ic_" + name;
            int id = getApplicationContext().getResources().getIdentifier(resourceName, resType, getApplicationContext().getPackageName());
            if (id == 0) {
                Log.w("OverlayService", "Resource not found: " + resourceName + " in " + resType);
                // Try without 'ic_' prefix as a fallback
                id = getApplicationContext().getResources().getIdentifier(name, resType, getApplicationContext().getPackageName());
                if (id == 0) {
                    Log.w("OverlayService", "Resource also not found: " + name + " in " + resType);
                }
            }
            return id;
        } catch (Resources.NotFoundException e) {
            Log.e("OverlayService", "Error getting resource ID: " + name, e);
            return 0;
        }
    }

    private int dpToPx(int dp) {
        if (mResources == null) {
            Log.e("OverlayService", "dpToPx: mResources is null!");
            // Fallback to a rough estimate if resources not available
            return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (float) dp, mResources.getDisplayMetrics());
    }

    private int dpToPx(double dp) {
        if (mResources == null) {
            Log.e("OverlayService", "dpToPx(double): mResources is null!");
            return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (float) dp, mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        if (mResources == null || mResources.getDisplayMetrics().density <= 0) {
            Log.e("OverlayService", "pxToDp: mResources or density is invalid!");
            // Fallback to a rough estimate
            return (double) px / Resources.getSystem().getDisplayMetrics().density;
        }
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        if (mResources == null) {
            Log.w("OverlayService", "inPortrait: mResources is null, cannot determine orientation.");
            // Assume portrait as a fallback? Or use system service?
            Configuration config = Resources.getSystem().getConfiguration();
            return config.orientation == Configuration.ORIENTATION_PORTRAIT;
        }
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    // Main flutterView touch listener (for dragging the whole view if enabled)
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        // Only handle touch if dragging is enabled *and* not currently moving/resizing via buttons
        if (!WindowSetup.enableDrag || isMoving || isResizing) {
            return false; // Don't interfere with button actions or if drag is disabled
        }

        if (windowManager != null && flutterView != null && flutterView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            // Ensure gravity is absolute for dragging
            if (params.gravity != (Gravity.TOP | Gravity.LEFT)) {
                Log.w("OverlayService", "Switching gravity to TOP|LEFT for view drag.");
                params.gravity = Gravity.TOP | Gravity.LEFT;
                // Might need position adjustment here if gravity was different
            }


            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false; // Reset dragging flag
                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    // Cancel any existing animations
                    if (mTrayAnimationTimer != null) {
                        mTrayAnimationTimer.cancel();
                    }
                    return true; // Consume event to receive MOVE/UP

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;
                    // Start dragging only if moved significantly
                    if (!dragging && (dx * dx + dy * dy) < 25) { // Drag threshold
                        return true; // Not dragging yet
                    }
                    dragging = true; // Now dragging

                    lastX = event.getRawX();
                    lastY = event.getRawY();

                    // Update position (absolute coordinates)
                    params.x += (int) dx;
                    params.y += (int) dy;

                    // Keep within screen bounds during drag
                    updateScreenSize();
                    int viewWidth = params.width > 0 ? params.width : flutterView.getWidth();
                    int viewHeight = params.height > 0 ? params.height : flutterView.getHeight();
                    if (viewWidth <= 0 || viewHeight <=0) {
                        // If size is unknown, skip bounds check or use estimate
                        Log.w("OverlayService", "View size unknown during drag, bounds check skipped.");
                    } else {
                        params.x = Math.max(0, Math.min(params.x, szWindow.x - viewWidth));
                        params.y = Math.max(statusBarHeightPx(), Math.min(params.y, szWindow.y - viewHeight - navigationBarHeightPx()));
                    }


                    try {
                        if (flutterView != null && flutterView.isAttachedToWindow()) {
                            windowManager.updateViewLayout(flutterView, params);
                            // Update button position continuously during drag
                            updateButtonPosition();
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e("OverlayService", "Error updating layout during view drag: " + e.getMessage());
                        dragging = false; // Stop dragging if view is gone
                    }
                    return true; // Consume event

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { // Only process if we were actually dragging
                        dragging = false; // Reset drag flag
                        // Save final position
                        if (flutterView != null && flutterView.isAttachedToWindow()) {
                            WindowManager.LayoutParams finalParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
                            saveOverlayState(finalParams.x, finalParams.y, finalParams.width, finalParams.height);
                            Log.d("OverlayService", "View drag finished, saved state.");
                            // Final button position update
                            updateButtonPosition();

                            // Optional: Snap to edge (Tray animation)
                            if (!WindowSetup.positionGravity.equals("none")) {
                                lastYPosition = finalParams.y; // Use the final y position
                                startTrayAnimation();
                            }
                        }
                        return true; // Consume event
                    }
                    // If not dragging (i.e., it was just a tap), return false
                    // to allow Flutter to handle the tap event if the overlay is touchable.
                    return false;

                default:
                    return false; // Don't consume other actions
            }
        }
        return false; // Default if checks fail
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void startTrayAnimation() {
        if (windowManager == null || flutterView == null || !flutterView.isAttachedToWindow()) return;
        updateScreenSize(); // Ensure screen size is current

        mTrayTimerTask = new TrayAnimationTimerTask();
        if (mTrayAnimationTimer != null) {
            mTrayAnimationTimer.cancel(); // Cancel previous timer if any
        }
        mTrayAnimationTimer = new Timer();
        try {
            mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 16); // Schedule at ~60fps
        } catch (Exception e) {
            Log.e("OverlayService", "Error scheduling tray animation: " + e.getMessage());
        }
    }


    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params; // Get params inside run or constructor

        public TrayAnimationTimerTask() {
            super();
            // Ensure view is valid when creating the task
            if (flutterView == null || !flutterView.isAttachedToWindow()) {
                Log.w("OverlayService", "TrayAnimationTimerTask: flutterView is not valid.");
                this.cancel(); // Cancel the task immediately
                return;
            }
            params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            if (params == null) {
                Log.w("OverlayService", "TrayAnimationTimerTask: params are null.");
                this.cancel();
                return;
            }

            mDestY = params.y; // Destination Y is the current Y after drag
            lastYPosition = params.y; // Update lastYPosition here too

            int viewWidth = params.width > 0 ? params.width : flutterView.getWidth();
            if (viewWidth <= 0) {
                Log.w("OverlayService", "TrayAnimationTimerTask: View width is unknown, using default for snap calculation.");
                viewWidth = dpToPx(200); // Or some fallback
            }


            switch (WindowSetup.positionGravity) {
                case "auto":
                    // Snap to nearest edge based on center point
                    mDestX = (params.x + (viewWidth / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - viewWidth;
                    break;
                case "left":
                    mDestX = 0;
                    break;
                case "right":
                    mDestX = szWindow.x - viewWidth;
                    break;
                default: // "none" or invalid
                    Log.d("OverlayService", "TrayAnimation: No snap gravity set or invalid value.");
                    // No animation needed if gravity is none
                    this.cancel(); // Cancel the task as no animation is required
                    if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                    return;
            }
            Log.d("OverlayService", "TrayAnimation: Target X=" + mDestX + ", Y=" + mDestY);
        }

        @Override
        public void run() {
            // Check view validity inside run loop as well
            if (flutterView == null || !flutterView.isAttachedToWindow() || windowManager == null || params == null) {
                Log.w("OverlayService", "TrayAnimation run: View or WindowManager became invalid, cancelling.");
                this.cancel();
                if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                return;
            }

            // Ensure animation runs on the main thread
            mAnimationHandler.post(() -> {
                // Check again inside the handler, as state might change between post and execution
                if (flutterView == null || !flutterView.isAttachedToWindow() || windowManager == null || params == null) {
                    Log.w("OverlayService", "TrayAnimation post: View or WindowManager became invalid, cancelling.");
                    this.cancel();
                    if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                    return;
                }

                // Animation logic (simple interpolation)
                int currentX = params.x;
                int currentY = params.y; // Y doesn't change in this snap logic, but keep for consistency
                int newX = currentX + (mDestX - currentX) / 4; // Approach destination faster initially
                int newY = currentY + (mDestY - currentY) / 4; // Adjust Y if needed in future

                params.x = newX;
                params.y = newY; // Apply Y change if any

                try {
                    if (flutterView.isAttachedToWindow()) { // Check again before updating layout
                        windowManager.updateViewLayout(flutterView, params);
                        // Update button position during animation
                        updateButtonPosition();
                    } else {
                        Log.w("OverlayService", "TrayAnimation run: View detached during animation, cancelling.");
                        this.cancel();
                        if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e("OverlayService", "TrayAnimation run: Error updating layout: " + e.getMessage());
                    this.cancel();
                    if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                    return;
                }


                // Stop condition (check if close enough to target)
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    Log.d("OverlayService", "TrayAnimation finished.");
                    params.x = mDestX; // Snap exactly to destination
                    params.y = mDestY;
                    try {
                        if (flutterView.isAttachedToWindow()) {
                            windowManager.updateViewLayout(flutterView, params);
                            // Final button position update
                            updateButtonPosition();
                            // Save the final snapped position
                            saveOverlayState(params.x, params.y, params.width, params.height);
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e("OverlayService", "TrayAnimation finish: Error updating layout: " + e.getMessage());
                    }
                    // Cancel timer and task
                    this.cancel();
                    if(mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
                }
            });
        }
    }
}
*/
