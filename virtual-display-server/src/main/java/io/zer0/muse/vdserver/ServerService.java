package io.zer0.muse.vdserver;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import io.zer0.muse.vdproto.IVirtualDisplayService;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * v2.2.1 虚拟屏:服务端实现。
 *
 * 唯一持有 VirtualDisplay 强引用的地方 —— 只要本进程存活,虚拟屏不会被回收。
 * 截图走"显示屏 surface 直连 ImageReader"(无编码器、无镜像屏):创建虚拟屏时把
 * ImageReader 的 surface 直接作为显示屏输出面,requestScreenshot 抓最新一帧转 JPEG。
 *
 * 生命周期:idle watcher 15s 无调用即退出(主应用侧负责按需重启)。
 */
public final class ServerService extends IVirtualDisplayService.Stub {

    private static final long IDLE_TIMEOUT_MS = 15_000L;
    private static final long IDLE_SCAN_INTERVAL_MS = 5_000L;
    private static final long PUBLISH_INTERVAL_MS = 5_000L;
    private static final long SCREENSHOT_TIMEOUT_MS = 1_000L;
    private static final long SHELL_TIMEOUT_MS = 10_000L;
    private static final int MAX_JPEG_BYTES = 900_000;
    private static final int MAX_ACTIVE_DISPLAYS = 3;

    private final DisplayManager displayManager;
    private final Map<Integer, DisplayState> displays = new HashMap<>();
    private int compatibleDisplayId = -1;

    private volatile long lastActiveAt = System.currentTimeMillis();

    public ServerService(FakeContext context) throws Exception {
        this.displayManager = createDisplayManager(context);
    }

    // ── IVirtualDisplayService 契约 ─────────────────────────────────────────

    @Override
    public synchronized int ensureDisplay(int width, int height, int dpi) {
        touch();
        int w = align16(width);
        int h = align16(height);
        DisplayState current = displays.get(compatibleDisplayId);
        if (current != null && w == current.width && h == current.height && dpi == current.dpi) {
            return current.id;
        }
        if (compatibleDisplayId >= 0) {
            destroyDisplayLocked(compatibleDisplayId);
        }
        DisplayState created = createDisplayLocked(w, h, dpi, "muse-vd-compat");
        if (created == null) return -1;
        compatibleDisplayId = created.id;
        Main.log("ensureDisplay " + w + "x" + h + " dpi=" + dpi + " -> id=" + created.id);
        return created.id;
    }

    @Override
    public synchronized int createDisplay(int width, int height, int dpi) {
        touch();
        int w = align16(width);
        int h = align16(height);
        if (displays.size() >= MAX_ACTIVE_DISPLAYS) {
            Main.log("createDisplay rejected: active display limit " + MAX_ACTIVE_DISPLAYS);
            return -1;
        }
        DisplayState created = createDisplayLocked(w, h, dpi, "muse-vd-agent");
        if (created == null) return -1;
        Main.log("createDisplay " + w + "x" + h + " dpi=" + dpi + " -> id=" + created.id);
        return created.id;
    }

    private DisplayState createDisplayLocked(int width, int height, int dpi, String name) {
        if (width < 320 || width > 1920 || height < 320 || height > 2560 || dpi < 120 || dpi > 640) {
            Main.log("create display rejected: invalid metrics " + width + "x" + height + " dpi=" + dpi);
            return null;
        }
        if (displays.size() >= MAX_ACTIVE_DISPLAYS) {
            Main.log("create display rejected: active display limit " + MAX_ACTIVE_DISPLAYS);
            return null;
        }
        ImageReader reader = null;
        VirtualDisplay virtualDisplay = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            virtualDisplay = displayManager.createVirtualDisplay(
                    name, width, height, dpi, reader.getSurface(), computeFlags());
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                if (virtualDisplay != null) virtualDisplay.release();
                reader.close();
                return null;
            }
            int id = virtualDisplay.getDisplay().getDisplayId();
            DisplayState state = new DisplayState(id, width, height, dpi, virtualDisplay, reader);
            displays.put(id, state);
            applyImePolicy(id);
            return state;
        } catch (Throwable t) {
            Main.log("create display failed: " + t);
            try {
                if (virtualDisplay != null) virtualDisplay.release();
            } catch (Throwable ignored) {
                // Preserve the original creation failure.
            }
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
                // Preserve the original creation failure.
            }
            return null;
        }
    }

    @Override
    public synchronized boolean launchApp(String packageName, int id) {
        touch();
        if (packageName == null || !packageName.matches("[a-zA-Z][a-zA-Z0-9_.]*")) {
            return false;
        }
        if (!displays.containsKey(id)) {
            throw new IllegalStateException("display not found: " + id);
        }
        try {
            String component = resolveLauncherComponent(packageName);
            if (component == null) {
                Main.log("launchApp: no launcher activity for " + packageName);
                return false;
            }
            // --activity-multiple-task(FLAG_ACTIVITY_MULTIPLE_TASK): 强制在目标虚拟屏开新任务。
            // 只用 --display 时,已存在的任务会被“复用”回原屏(task reuse),应用根本进不了虚拟屏。
            // 注:本框架无 --activity-new-task 选项(am 会报 Unknown option)。
            String command = "am start --display " + id
                    + " --activity-multiple-task -n " + component;
            String output = execShellCapture(command);
            boolean ok = !output.contains("Error:")
                    && !output.contains("Error type")
                    && !output.contains("Exception")
                    && !output.contains("Warning: Activity not started");
            Main.log("launchApp " + component + " on display " + id + " -> " + ok + " | "
                    + output.trim().replace('\n', '~'));
            return ok;
        } catch (Throwable t) {
            Main.log("launchApp failed: " + t);
            return false;
        }
    }

    @Override
    public synchronized void destroyDisplay(int id) {
        touch();
        destroyDisplayLocked(id);
    }

    private void destroyDisplayLocked(int id) {
        DisplayState state = displays.remove(id);
        if (state == null) return;
        if (compatibleDisplayId == id) compatibleDisplayId = -1;
        state.release();
    }

    @Override
    public synchronized byte[] requestScreenshot(int id) {
        touch();
        DisplayState state = displays.get(id);
        if (state == null) {
            // Let the client distinguish a stale display ID from a valid display
            // that simply has not produced a frame yet.
            throw new IllegalStateException("display not found: " + id);
        }
        Image image = null;
        try {
            long deadline = System.currentTimeMillis() + SCREENSHOT_TIMEOUT_MS;
            while (image == null && System.currentTimeMillis() < deadline) {
                image = state.imageReader.acquireLatestImage();
                if (image == null) {
                    Thread.sleep(20L);
                }
            }
            if (image == null) {
                return null;
            }
            return imageToJpeg(image);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Main.log("requestScreenshot failed: " + t);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @Override
    public boolean isAlive() {
        touch();
        return true;
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    public void startIdleWatcher() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastActiveAt > IDLE_TIMEOUT_MS) {
                    Main.log("idle timeout, exiting");
                    System.exit(0);
                }
                handler.postDelayed(this, IDLE_SCAN_INTERVAL_MS);
            }
        }, IDLE_SCAN_INTERVAL_MS);
    }

    /**
     * 周期性重发布 binder(每 5s)。服务端空闲 15s 自退,所以重发次数有界。
     * 目的:宿主进程可能晚于服务端启动/重启(instrument/崩溃),单次广播会丢;
     * 重发让注册表在任何启动顺序下都能收敛。注意:广播不刷新 lastActiveAt。
     */
    public void startPublishLoop(String hostPackage, String handoffToken) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                BinderHandoff.publish(ServerService.this, hostPackage, handoffToken);
                handler.postDelayed(this, PUBLISH_INTERVAL_MS);
            }
        }, 0L);
    }

    private void touch() {
        lastActiveAt = System.currentTimeMillis();
    }

    private static final class DisplayState {
        final int id;
        final int width;
        final int height;
        final int dpi;
        final VirtualDisplay virtualDisplay;
        final ImageReader imageReader;

        DisplayState(int id, int width, int height, int dpi, VirtualDisplay virtualDisplay, ImageReader imageReader) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.virtualDisplay = virtualDisplay;
            this.imageReader = imageReader;
        }

        void release() {
            try {
                virtualDisplay.release();
            } catch (Throwable ignored) {
                // Resource release is best-effort; server exit remains a final fallback.
            }
            try {
                imageReader.close();
            } catch (Throwable ignored) {
                // Resource release is best-effort; server exit remains a final fallback.
            }
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private static DisplayManager createDisplayManager(FakeContext context) throws Exception {
        Class<?> cls = Class.forName("android.hardware.display.DisplayManager");
        try {
            java.lang.reflect.Constructor<?> ctor =
                    cls.getDeclaredConstructor(android.content.Context.class);
            ctor.setAccessible(true);
            return (DisplayManager) ctor.newInstance(context);
        } catch (NoSuchMethodException notFound) {
            Class<?> globalCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = globalCls.getMethod("getInstance").invoke(null);
            java.lang.reflect.Constructor<?> ctor =
                    cls.getDeclaredConstructor(android.content.Context.class, globalCls);
            ctor.setAccessible(true);
            return (DisplayManager) ctor.newInstance(context, global);
        }
    }

    /**
     * flags 按版本加。部分位值是 @SystemApi(不在公共 SDK jar 中,编译期不可引用),
     * 这里用与 framework 一致的位字面量;PUBLIC/PRESENTATION/OWN_CONTENT_ONLY 走公共常量。
     */
    private static int computeFlags() {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 6)   // SUPPORTS_TOUCH
                | (1 << 7)   // ROTATES_WITH_CONTENT
                | (1 << 8);  // DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) {
            flags |= (1 << 10)  // TRUSTED
                    | (1 << 11) // OWN_DISPLAY_GROUP
                    | (1 << 12) // ALWAYS_UNLOCKED
                    | (1 << 13); // TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags |= (1 << 14)  // OWN_FOCUS
                    | (1 << 15); // DEVICE_DISPLAY_GROUP
        }
        return flags;
    }

    /** 虚拟屏 IME 策略设为 LOCAL(键盘显示在虚拟屏上);失败仅记日志。 */
    private static void applyImePolicy(int id) {
        try {
            Class<?> windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal");
            Object windowManagerService = windowManagerGlobal.getMethod("getWindowManagerService").invoke(null);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method method : windowManagerService.getClass().getMethods()) {
                String name = method.getName();
                if (("setDisplayImePolicy".equals(name) || "setShouldShowIme".equals(name))
                        && method.getParameterTypes().length == 2) {
                    target = method;
                    if ("setDisplayImePolicy".equals(name)) {
                        break;
                    }
                }
            }
            if (target == null) {
                return;
            }
            if ("setDisplayImePolicy".equals(target.getName())) {
                // WindowManagerPolicyConstants.DISPLAY_IME_POLICY_LOCAL = 1
                target.invoke(windowManagerService, id, 1);
            } else {
                target.invoke(windowManagerService, id, true);
            }
        } catch (Throwable t) {
            Main.log("applyImePolicy skipped: " + t);
        }
    }

    private static int align16(int value) {
        return (value + 15) / 16 * 16;
    }

    /** 以 shell 身份执行单条命令,返回合并输出(stdout+stderr);异常返回错误文本。 */
    private static String execShellCapture(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {"sh", "-c", command});
            String out = drain(process.getInputStream());
            String err = drain(process.getErrorStream());
            if (!process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                Main.log("execShellCapture timed out after " + SHELL_TIMEOUT_MS + "ms");
                return "";
            }
            return out + err;
        } catch (Throwable t) {
            Main.log("execShellCapture failed: " + t);
            return "";
        }
    }

    /** 解析 launcher 活动(pkg/.Act 或 pkg/com.full.Act);解析失败返回 null。 */
    private static String resolveLauncherComponent(String packageName) {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    "sh", "-c", "cmd package resolve-activity --brief " + packageName,
            });
            String output = drain(process.getInputStream());
            drain(process.getErrorStream());
            if (!process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                Main.log("resolveLauncherComponent timed out after " + SHELL_TIMEOUT_MS + "ms");
                return null;
            }
            String component = null;
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.contains("/")) {
                    component = trimmed;
                }
            }
            if (component == null) {
                return null;
            }
            // 防注入:组件名只允许包名/活动名字符
            if (!component.matches("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")) {
                return null;
            }
            return component;
        } catch (Throwable t) {
            Main.log("resolveLauncherComponent failed: " + t);
            return null;
        }
    }

    private static String drain(java.io.InputStream stream) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] compress(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    /** RGBA_8888 ImageReader 帧 → JPEG 字节(含 rowStride padding 处理与体积护栏)。 */
    private static byte[] imageToJpeg(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(plane.getBuffer());
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) {
            padded.recycle();
        }
        try {
            byte[] bytes = compress(cropped, 80);
            if (bytes.length > MAX_JPEG_BYTES) {
                bytes = compress(cropped, 60);
            }
            return bytes;
        } finally {
            cropped.recycle();
        }
    }
}
