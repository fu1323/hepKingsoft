package xin.chunming;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word 虚拟滚动采集器。
 *
 * 浏览器端 MutationObserver 负责将稳定的 SVG 序列化后入队；
 * Java 顺序取快照、SVG -> Canvas -> PNG、确认保存结果，再继续滚动。
 * 不以 .canvas-unit 在 NodeList 中的下标当作页码。
 */
public final class WordCapture {
    private static final String OBSERVER_RESOURCE = "/word-observer.js";
    private static final int MAX_ROUNDS = 5000;
    private static final int MAX_FAILURES_PER_VERSION = 3;
    private static final int SCROLL_STEP = 450;
    private static final int MIN_DWELL_MS = 1400;     // 每次滚动后至少观察这么久
    private static final int MAX_RENDER_WAIT_MS = 5500;
    private static final int BOTTOM_QUIET_MS = 5000;
    private static final int BOTTOM_PENDING_TIMEOUT_MS = 15000;
    private static final int SCALE = 1;              // 保持旧版 Word PNG 分辨率；改成 2 可提高分辨率

    private final Page page;
    private final Frame frame;
    private final Path saveDir;
    private final Map<String, Integer> pageNumbers = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private int savedUniquePages = 0;
    private int updatedPages = 0;
    private int permanentlyFailed = 0;

    private WordCapture(Page page, Frame frame, Path saveDir) {
        this.page = page;
        this.frame = frame;
        this.saveDir = saveDir;
    }

    public static void capture(Page page, Frame frame, Path saveDir) throws IOException {
        new WordCapture(page, frame, saveDir).run();
    }

    private void run() throws IOException {
        Files.createDirectories(saveDir);
        installObserver();
        try {
            frame.evaluate("() => window.__hepWordCapture.toTop()");
            page.waitForTimeout(1000);

            long positionSince = System.currentTimeMillis();
            long bottomSince = -1;
            int lastLoggedRound = -1;

            for (int round = 0; round < MAX_ROUNDS; round++) {
                frame.evaluate("() => window.__hepWordCapture.scan()");
                page.waitForTimeout(250);
                int processed = drainQueue();
                Map<String, Object> status = status();

                int queued = intValue(status.get("queued"));
                int inFlight = intValue(status.get("inFlight"));
                int pending = intValue(status.get("pendingUnits"));
                int unstable = intValue(status.get("unstableUnits"));
                boolean atBottom = Boolean.TRUE.equals(status.get("atBottom"));
                boolean canScroll = Boolean.TRUE.equals(status.get("canScroll"));
                long now = System.currentTimeMillis();

                if (round % 10 == 0 && round != lastLoggedRound) {
                    lastLoggedRound = round;
                    System.out.printf("[Word] root=%s, scroll=%s/%s, DOM=%s, pending=%d, unstable=%d, " +
                                    "queue=%d, 已保存=%d, 当前位置观察=%dms%n",
                            status.get("name"), status.get("top"), status.get("scrollHeight"),
                            status.get("units"), pending, unstable, queued, savedUniquePages,
                            now - positionSince);
                }

                // 先把队列消化完，再移动视口，否则虚拟滚动可能回收尚未捕获的 SVG。
                if (queued > 0 || inFlight > 0 || processed > 0) {
                    bottomSince = -1;
                    continue;
                }

                long dwell = now - positionSince;
                if (dwell < MIN_DWELL_MS ||
                        ((pending > 0 || unstable > 0) && dwell < MAX_RENDER_WAIT_MS)) {
                    page.waitForTimeout(350);
                    continue;
                }

                if (atBottom) {
                    if (bottomSince < 0) bottomSince = now;
                    long atBottomFor = now - bottomSince;
                    long lastMutationAgo = longValue(status.get("lastMutationAgoMs"));
                    if (pending == 0 && unstable == 0 &&
                            atBottomFor >= BOTTOM_QUIET_MS && lastMutationAgo >= BOTTOM_QUIET_MS) {
                        System.out.println("[Word] 已到底，SVG 队列清空且无新变化，结束。");
                        break;
                    }
                    if (atBottomFor >= BOTTOM_PENDING_TIMEOUT_MS) {
                        System.err.printf("[Word] 底部等待超时，仍 pending=%d / unstable=%d；可能有页面未完成渲染。%n",
                                pending, unstable);
                        break;
                    }
                    page.waitForTimeout(450);
                    continue;
                }
                bottomSince = -1;

                if (!canScroll) {
                    System.err.println("[Word] 没有识别到可滚动容器。请根据上面的 root 日志确认 #workspace / 真实滚动元素。");
                    // 仍给页面一次布局变更的机会，而不是立即误判已经完整。
                    page.waitForTimeout(500);
                    if (round > 30) break;
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> moved = (Map<String, Object>) frame.evaluate(
                        "(step) => window.__hepWordCapture.scrollDown(step)", SCROLL_STEP);
                if (doubleValue(moved.get("after")) <= doubleValue(moved.get("before"))) {
                    // 网页尚未真正滚动，可能已到底或正在调整滚动高度。
                    page.waitForTimeout(450);
                }
                positionSince = System.currentTimeMillis();
                page.waitForTimeout(400);
            }

            // 滚动主循环结束后做最后几次扫描，接住延迟入队的 SVG。
            for (int i = 0; i < 4; i++) {
                frame.evaluate("() => window.__hepWordCapture.scan()");
                page.waitForTimeout(250);
                drainQueue();
            }
            System.out.printf("[Word] 完成：保存 %d 个不同页面，覆盖更新 %d 次，永久失败 %d 个版本。%n",
                    savedUniquePages, updatedPages, permanentlyFailed);
            System.out.println("[Word] 备注：页码优先来自 DOM 属性或页面绝对位置；若只剩内容指纹兜底，完全相同的两页可能无法区分。");
        } finally {
            try {
                frame.evaluate("() => window.__hepWordCapture?.stop()");
            } catch (RuntimeException ignored) {
                // 关闭页面或 frame 导航时无需进一步操作。
            }
        }
    }

    private void installObserver() throws IOException {
        String script;
        try (InputStream in = WordCapture.class.getResourceAsStream(OBSERVER_RESOURCE)) {
            if (in == null) throw new IOException(
                    "找不到 /word-observer.js，请放在 src/main/resources/word-observer.js 并重新构建项目");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        frame.evaluate(script);
        System.out.println("=== Word 模式：MutationObserver + SVG 快照队列 ===");
    }

    private int drainQueue() {
        int processed = 0;
        for (int i = 0; i < 40; i++) { // 一轮最多消费 40 页；之后再检查视口状态
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) frame.evaluate(
                    "() => window.__hepWordCapture.take()");
            if (item == null) break;

            String key = (String) item.get("key");
            String sig = (String) item.get("sig");
            String retryKey = key + "|" + sig;
            boolean success = false;
            try {
                String b64 = (String) frame.evaluate(SVG_TO_PNG_JS, item);
                if (b64 == null || b64.isEmpty()) {
                    throw new IOException("SVG 转 PNG 返回空数据（可能是外部图片/CSS 引用问题）");
                }
                byte[] png = Base64.getDecoder().decode(b64);

                boolean isNew = !pageNumbers.containsKey(key);
                int index = pageNumbers.computeIfAbsent(key, ignored -> pageNumbers.size());
                Path target = saveDir.resolve(String.format("page_%03d.png", index));
                Path temp = saveDir.resolve(String.format("page_%03d.png.tmp", index));
                Files.write(temp, png);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

                success = true;
                failures.remove(retryKey);
                if (isNew) savedUniquePages++;
                else updatedPages++;
                System.out.printf("✓ Word %s -> %s (%s)；不同页面 %d%n",
                        key, target.getFileName(), isNew ? "新页面" : "内容更新", savedUniquePages);
            } catch (Exception e) {
                int attempts = failures.merge(retryKey, 1, Integer::sum);
                System.err.printf("[Word] 保存失败 %s (%d/%d)：%s%n",
                        key, attempts, MAX_FAILURES_PER_VERSION, e.getMessage());
                if (attempts >= MAX_FAILURES_PER_VERSION) {
                    frame.evaluate("(k) => window.__hepWordCapture.drop(k)", key);
                    permanentlyFailed++;
                }
            } finally {
                if (success || failures.getOrDefault(retryKey, 0) < MAX_FAILURES_PER_VERSION) {
                    frame.evaluate("(result) => window.__hepWordCapture.ack(result)",
                            Map.of("key", key, "sig", sig, "ok", success));
                }
            }
            processed++;
        }
        return processed;
    }

    private Map<String, Object> status() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) frame.evaluate(
                "() => window.__hepWordCapture.status()");
        return result;
    }

    private static int intValue(Object n) {
        return n instanceof Number ? ((Number) n).intValue() : 0;
    }

    private static long longValue(Object n) {
        return n instanceof Number ? ((Number) n).longValue() : 0;
    }

    private static double doubleValue(Object n) {
        return n instanceof Number ? ((Number) n).doubleValue() : 0;
    }

    // 与 Main.java 旧 Word 模式使用相同的 Blob -> Image -> Canvas -> PNG 流程。
    private static final String SVG_TO_PNG_JS = """
        (data) => new Promise((resolve) => {
            const width = Number(data.w), height = Number(data.h);
            if (!(width > 0 && height > 0)) return resolve(null);
            const scale = %d;
            const blob = new Blob([data.svgStr], {type: 'image/svg+xml;charset=utf-8'});
            const url = URL.createObjectURL(blob);
            const img = new Image();
            img.onload = () => {
                try {
                    const canvas = document.createElement('canvas');
                    canvas.width = Math.round(width * scale);
                    canvas.height = Math.round(height * scale);
                    const ctx = canvas.getContext('2d');
                    if (!ctx) return resolve(null);
                    ctx.scale(scale, scale);
                    ctx.drawImage(img, 0, 0, width, height);
                    resolve(canvas.toDataURL('image/png').split(',')[1]);
                } catch (e) {
                    console.error('SVG rasterize failed:', e);
                    resolve(null);
                } finally {
                    URL.revokeObjectURL(url);
                }
            };
            img.onerror = () => { URL.revokeObjectURL(url); resolve(null); };
            img.src = url;
        })
        """.formatted(SCALE);
}
