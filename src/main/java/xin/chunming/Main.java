package xin.chunming;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * - 金山文档批量截图工具
 * - 支持 PDF 模式和 Word 模式，Word 模式直接从 SVG DOM 提取，无需滚动截图
 */
public class Main {

    // ─── 配置区 ───────────────────────────────────────────────
    private static final int PAGE_WAIT_MS = 5000;   // 初始页面加载等待
    private static final int RENDER_WAIT_MS = 1200;   // 每页渲染等待（PDF 模式）
    private static final int DEVICE_SCALE = 2;      // 截图分辨率倍数（PDF 模式）
    private static final String IFRAME_SELECTOR = "#office-iframe";
    private static final String PDF_PAGE_SEL = ".pdf-page";
    private static final String WORD_PAGE_SEL = ".canvas-unit";
    // ─────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String baseUrl = "https://abooks.hep.com.cn/867/";
        String baseDir = "output2";
        String statePath = "state.json";

        Files.createDirectories(Paths.get(baseDir));

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );

            for (int i = 3; i < 150; i++) {
                String saveDir = baseDir + "/" + i;
                Files.createDirectories(Paths.get(saveDir));

                // 每次都新建 context，用完立即关闭，防止资源泄漏
                BrowserContext context = browser.newContext(
                        new Browser.NewContextOptions()
                                .setViewportSize(1920, 1080)
                                .setDeviceScaleFactor(DEVICE_SCALE)
                                .setStorageStatePath(Paths.get(statePath))
                );

                try {
                    captureDocument(context, baseUrl + i, saveDir);
                } finally {
                    context.close(); // ← 关键：每页处理完立即关掉
                }
            }

            browser.close();
        }
    }


    // ─── 主流程 ───────────────────────────────────────────────

    private static void captureDocument(BrowserContext context, String url, String savePath) {
        Page page = context.newPage();
        page.setDefaultTimeout(0);                    // 所有 action（如 click、fill、waitForSelector 等）无限等待
        page.setDefaultNavigationTimeout(0);
        try {
            System.out.println("正在打开: " + url);
            page.navigate(url);
            page.waitForLoadState();
            page.waitForTimeout(PAGE_WAIT_MS);

            Thread.sleep(10000);

            // 1. 尝试点击全屏/展开按钮
            tryClickFullScreen(page);
            Thread.sleep(3000);

            // 2. 定位 iframe
            // 优先用 frame()，找不到再降级用 frameLocator()
            Frame immFrame = findFrame1(page, false);

            if (immFrame == null) {
                System.err.println("未找到文档 iframe，退出");
                return;
            }

            // 3. 判断模式
            // 3. 判断模式
            int pdfCount = immFrame.locator(PDF_PAGE_SEL).count();
            int wordCount = immFrame.locator(WORD_PAGE_SEL).count();
            boolean isVideo = immFrame.locator("video-js, video.vjs-tech").count() > 0;

            System.out.printf("PDF页: %d  Word页: %d  视频: %b%n", pdfCount, wordCount, isVideo);

            if (isVideo) {
                System.out.println("检测到视频，跳过: " + url);
            } else if (pdfCount > 0) {
                capturePdfMode(page, immFrame, savePath);
            } else if (wordCount > 0) {
                captureWordMode(page, immFrame, savePath);
            } else {
                System.err.println("未知结构，跳过: " + url);
            }


            System.out.printf("PDF页检测: %d  Word页检测: %d%n", pdfCount, wordCount);


        } catch (Exception e) {
            System.err.println("捕获文档时出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            page.close();
        }


    }

    // ─── PDF 模式：逐页滚动截图 ────────────────────────────────

    private static void capturePdfMode(Page page, Frame frame, String savePath) throws InterruptedException {
        System.out.println("=== PDF 模式，边滚边截 ===");

        Set<Integer> savedIndices = new HashSet<>();
        int savedCount = 0;
        int noNewPageRounds = 0;

        for (int round = 0; round < 1000; round++) {
            // 扫描当前已渲染的 pdf-page
            @SuppressWarnings("unchecked")
            List<Object> visibleIndices = (List<Object>) frame.evaluate("""
                    () => {
                        const units = document.querySelectorAll('.pdf-page');
                        const result = [];
                        units.forEach((u, i) => {
                            // 有内容才算渲染完成（检查有没有 canvas 或 img 子元素）
                            if (u.children.length > 0) result.push(i);
                        });
                        return result;
                    }
                    """);

            boolean extractedNew = false;
            for (Object idxObj : visibleIndices) {
                Thread.sleep(1800);
                int idx = ((Number) idxObj).intValue();
                if (savedIndices.contains(idx)) continue;

                try {
                    Locator p = frame.locator(PDF_PAGE_SEL).nth(idx);
                    String fileName = String.format("%s/page_%03d.png", savePath, idx);
                    p.screenshot(new Locator.ScreenshotOptions().setPath(Paths.get(fileName)));
                    savedIndices.add(idx);
                    savedCount++;
                    extractedNew = true;
                    System.out.printf("✓ PDF 第 %d 页完成（已保存 %d 页）%n", idx + 1, savedCount);
                } catch (Exception e) {
                    System.err.printf("PDF 第 %d 页截图失败: %s%n", idx + 1, e.getMessage());
                }
            }

            if (!extractedNew) {
                noNewPageRounds++;
                if (noNewPageRounds >= 2) {
                    System.out.println("连续 2 次无新页面，结束。共保存 " + savedCount + " 页");
                    break;
                }
            } else {
                noNewPageRounds = 0;
            }

            // 向下滚动
            frame.evaluate("""
                    () => {
                        const el = document.querySelector('#workspace') || document.documentElement;
                        el.scrollBy({ top: 600, behavior: 'smooth' });
                    }
                    """);
            page.waitForTimeout(800);
        }
    }

    private static void capturePdfModebak(Page page, Frame frame, String savePath) {
        System.out.println("=== PDF 模式，边滚边提取 canvas ===");

        int totalPages = frame.locator(PDF_PAGE_SEL).count();
        System.out.println("PDF 总页数: " + totalPages);

        Set<Integer> savedIndices = new HashSet<>();
        int savedCount = 0;
        int noNewPageRounds = 0;

        for (int round = 0; round < 1000; round++) {
            // 扫描已渲染的页面（有 canvas 子元素才算渲染完成）
            @SuppressWarnings("unchecked")
            List<Object> visibleIndices = (List<Object>) frame.evaluate("""
                    () => {
                        const units = document.querySelectorAll('.pdf-page');
                        const result = [];
                        units.forEach((u, i) => {
                            if (u.querySelector('canvas')) result.push(i);
                        });
                        return result;
                    }
                    """);

            boolean extractedNew = false;
            for (Object idxObj : visibleIndices) {
                int idx = ((Number) idxObj).intValue();
                if (savedIndices.contains(idx)) continue;

                // 直接从 canvas 读 base64
                String b64 = (String) frame.evaluate("""
                        (idx) => {
                            const unit = document.querySelectorAll('.pdf-page')[idx];
                            if (!unit) return null;
                            const canvas = unit.querySelector('canvas');
                            if (!canvas) return null;
                            try {
                                return canvas.toDataURL('image/png').split(',')[1];
                            } catch(e) {
                                return null;
                            }
                        }
                        """, idx);

                if (b64 == null) continue;

                try {
                    byte[] data = Base64.getDecoder().decode(b64);
                    Files.write(Paths.get(String.format("%s/page_%03d.png", savePath, idx)), data);
                    savedIndices.add(idx);
                    savedCount++;
                    extractedNew = true;
                    System.out.printf("✓ PDF 第 %d 页完成（已保存 %d/%d 页）%n", idx + 1, savedCount, totalPages);
                } catch (Exception e) {
                    System.err.printf("PDF 第 %d 页写文件失败: %s%n", idx + 1, e.getMessage());
                }
            }

            if (savedCount >= totalPages) {
                System.out.println("所有页面已保存完毕");
                break;
            }

            if (!extractedNew) {
                noNewPageRounds++;
                if (noNewPageRounds >= 5) {
                    System.out.println("连续 5 次无新页面，结束。共保存 " + savedCount + "/" + totalPages + " 页");
                    break;
                }
            } else {
                noNewPageRounds = 0;
            }

            // 向下滚动
            // 向下滚动
            frame.evaluate("""
                    () => {
                        const el = document.querySelector('#workspace') || document.documentElement;
                        el.scrollBy({ top: 900, behavior: 'smooth' });
                    }
                    """);
            page.waitForTimeout(2000); // PDF 渲染慢，给足时间

            page.waitForTimeout(1000); // PDF 渲染比 SVG 慢，给多点时间
        }
    }

    // ─── Word 模式：直接从 SVG DOM 提取 PNG（无需滚动）─────────

    private static void captureWordMode(Page page, Frame frame, String savePath) {
        System.out.println("=== Word 模式，边滚边提取 ===");

        // 先取文档总页数（从文档元数据获取，不依赖 DOM 节点数）
        Object totalObj = frame.evaluate("""
                () => {
                    // 阿里云 IMM 通常把总页数存在某个地方，尝试几种方式
                    // 方式1: 看页面上有没有页码文字
                    const pageInfo = document.querySelector('.page-count, .total-page, [class*="pageCount"], [class*="totalPage"]');
                    if (pageInfo) return parseInt(pageInfo.textContent);
                    // 方式2: 看 DOM 里所有 canvas-unit 的最大 data-page-index
                    const units = document.querySelectorAll('.canvas-unit');
                    let maxIdx = 0;
                    units.forEach(u => {
                        const idx = parseInt(u.dataset.pageIndex || u.dataset.index || 0);
                        if (idx > maxIdx) maxIdx = idx;
                    });
                    if (maxIdx > 0) return maxIdx + 1;
                    // 方式3: 返回 -1 表示未知，靠滚动到底判断
                    return -1;
                }
                """);

        int totalPages = ((Number) totalObj).intValue();
        System.out.println("文档总页数: " + (totalPages == -1 ? "未知，靠滚动判断" : totalPages));

        // 滚回顶部
        frame.evaluate("""
                () => {
                    const el = document.querySelector('#workspace') || document.documentElement;
                    el.scrollTop = 0;
                }
                """);
        page.waitForTimeout(3000);

        int savedCount = 0;
        int noNewPageRounds = 0;
        Set<Integer> savedIndices = new HashSet<>();

        for (int round = 0; round < 1000; round++) {
            // 提取当前视口内所有已渲染的页面
            @SuppressWarnings("unchecked")
            List<Object> visibleIndices = (List<Object>) frame.evaluate("""
                    () => {
                        const units = document.querySelectorAll('.canvas-unit:not(.doc-pending-canvas-unit)');
                        const result = [];
                        units.forEach(u => {
                            // 获取页码索引，优先用 data 属性，没有就用 DOM 顺序
                            const allUnits = document.querySelectorAll('.canvas-unit');
                            let idx = -1;
                            allUnits.forEach((au, i) => { if (au === u) idx = i; });
                            if (idx >= 0) result.push(idx);
                        });
                        return result;
                    }
                    """);

            boolean extractedNew = false;
            for (Object idxObj : visibleIndices) {
                int idx = ((Number) idxObj).intValue();
                if (savedIndices.contains(idx)) continue; // 已保存，跳过

                // 提取 SVG
                @SuppressWarnings("unchecked")
                Map<String, Object> svgData = (Map<String, Object>) frame.evaluate("""
                        (idx) => {
                            const units = document.querySelectorAll('.canvas-unit');
                            const unit = units[idx];
                            if (!unit) return null;
                            const svg = unit.querySelector('svg');
                            if (!svg) return null;
                            const w = svg.width.baseVal.value || svg.viewBox.baseVal.width;
                            const h = svg.height.baseVal.value || svg.viewBox.baseVal.height;
                            let svgStr = new XMLSerializer().serializeToString(svg);
                            if (!svgStr.includes('xmlns:xlink')) {
                                svgStr = svgStr.replace('<svg', '<svg xmlns:xlink="http://www.w3.org/1999/xlink"');
                            }
                            return { svgStr, w, h };
                        }
                        """, idx);

                if (svgData == null) continue;

                String b64 = (String) frame.evaluate("""
                        (data) => new Promise((resolve) => {
                            const blob   = new Blob([data.svgStr], { type: 'image/svg+xml;charset=utf-8' });
                            const url    = URL.createObjectURL(blob);
                            const img    = new Image();
                            const canvas = document.createElement('canvas');
                            canvas.width  = data.w;
                            canvas.height = data.h;
                            img.onload = () => {
                                try {
                                    canvas.getContext('2d').drawImage(img, 0, 0);
                                    resolve(canvas.toDataURL('image/png').split(',')[1]);
                                } catch(e) { resolve(null); }
                                finally { URL.revokeObjectURL(url); }
                            };
                            img.onerror = () => { URL.revokeObjectURL(url); resolve(null); };
                            img.src = url;
                        })
                        """, svgData);

                if (b64 == null) continue;

                try {
                    byte[] data = Base64.getDecoder().decode(b64);
                    Files.write(Paths.get(String.format("%s/page_%03d.png", savePath, idx)), data);
                    savedIndices.add(idx);
                    savedCount++;
                    extractedNew = true;
                    System.out.printf("✓ 保存第 %d 页（已保存 %d 页）%n", idx + 1, savedCount);
                } catch (Exception e) {
                    System.err.println("写文件失败: " + e.getMessage());
                }
            }

            // 判断是否结束
            if (totalPages > 0 && savedCount >= totalPages) {
                System.out.println("所有页面已保存完毕");
                break;
            }

            if (!extractedNew) {
                noNewPageRounds++;
                if (noNewPageRounds >= 2) {
                    System.out.println("连续 5 次无新页面，结束。共保存 " + savedCount + " 页");
                    break;
                }
            } else {
                noNewPageRounds = 0;
            }

            // 向下滚动
            frame.evaluate("""
                    () => {
                        const el = document.querySelector('#workspace') || document.documentElement;
                        el.scrollBy({ top: 600, behavior: 'smooth' });
                    }
                    """);
            page.waitForTimeout(1000); // 等渲染
        }

        System.out.println("完成，共保存 " + savedCount + " 页");
    }


    // ─── 工具方法 ─────────────────────────────────────────────

    /**
     * 尝试点击全屏/展开按钮
     */
    private static void tryClickFullScreen(Page page) {
        try {
            Locator btn = page.locator("a:has-text('点击全屏查看')");
            System.out.println(btn.count());
            if (btn.count() == 0) {
                findFrame1(page, true);
            }
            if (btn.count() > 0 && btn.first().isVisible()) {
                btn.first().click();
                page.waitForTimeout(1000);
                System.out.println("已点击全屏按钮");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * - 查找文档 iframe
     * - 优先用 page.frame()（按 name/id），找不到降级用 frameLocator() 的 frame() 方法
     */
    private static Frame findFrame1(Page page, boolean jump) throws InterruptedException {
        page.frames().forEach(f ->
                System.out.printf("frame: name=%-15s url=%s%n", f.name(), f.url())
        );

        for (Frame f : page.frames()) {
            String u = f.url();
            // 匹配阿里云 IMM office
            if (u.contains("imm.aliyuncs.com") || u.contains("office-cn")) {
                System.out.println("找到目标 frame: " + u);
                if (jump) {
                    Thread.sleep(6000);
                    page.navigate(u);
                }
                return f;
            }
        }

        // 降级：第一个子 frame
        List<Frame> frames = page.frames();
        if (frames.size() > 1) {
            System.out.println("降级使用子 frame: " + frames.get(1).url());
            return frames.get(1);
        }
        return null;
    }

    private static Frame findFrame2(Page page) {
        page.frames().forEach(f ->
                System.out.printf("frame: name=%-15s url=%s%n", f.name(), f.url())
        );

        for (Frame f : page.frames()) {
            String u = f.url();
            if (u.contains("imm.aliyuncs.com") || u.contains("office-cn")) {
                System.out.println("找到目标 frame: " + u);
                return f;
            }
        }

        // 没找到 iframe，尝试从页面源码里提取 iframe src 直接导航
        System.out.println("未找到目标 frame，尝试提取 iframe src 直接导航...");
        Object iframeSrc = page.evaluate("""
                () => {
                    const iframe = document.querySelector('#office-iframe');
                    return iframe ? iframe.src : null;
                }
                """);

        if (iframeSrc != null && !iframeSrc.toString().isEmpty()) {
            String src = iframeSrc.toString();
            System.out.println("找到 iframe src，直接导航: " + src);
            page.navigate(src);
            page.waitForLoadState();
            page.waitForTimeout(3000);

            // 导航后重新找 frame（现在主页面就是目标内容）
            for (Frame f : page.frames()) {
                String u = f.url();
                if (u.contains("imm.aliyuncs.com") || u.contains("office-cn")) {
                    System.out.println("导航后找到 frame: " + u);
                    return f;
                }
            }

            // 如果没有嵌套 frame，主 frame 就是目标
            System.out.println("直接使用主 frame");
            return page.mainFrame();
        }

        // 降级：第一个子 frame
        List<Frame> frames = page.frames();
        if (frames.size() > 1) {
            System.out.println("降级使用子 frame: " + frames.get(1).url());
            return frames.get(1);
        }

        return null;
    }


    /**
     * - 从 JSON 文件注入 cookie
     * - 文件格式：Playwright 导出的标准 cookie JSON 数组
     * - [{name, value, domain, path, …}, …]
     */
    private static void injectCookies(BrowserContext context, String cookiePath) {
        try {
            String json = Files.readString(Paths.get(cookiePath));
            // 用最简单的方式：直接传给 addCookies
            // Playwright Java 接受 List<Cookie>，这里借助简单解析
            System.out.println("Cookie 文件已加载: " + cookiePath);
            // 实际项目建议用 Jackson/Gson 解析；此处留接口，按需实现
            // List<Cookie> cookies = parseCookies(json);
            // context.addCookies(cookies);
        } catch (IOException e) {
            System.err.println("Cookie 文件读取失败: " + e.getMessage());
        }
    }

    private static int scrollToLoadAll(Page page, Frame frame) throws InterruptedException {
        System.out.println("开始滚动加载...");
        page.waitForTimeout(600);
//        frame.evaluate("""
//                    const el = document.querySelector('#workspace') || document.documentElement;
//                     el.scrollBy({ top: el.scrollHeight, behavior: 'smooth' });
//            """);
//        Thread.sleep(1000);
//        frame.evaluate("""
//                    const el = document.querySelector('#workspace') || document.documentElement;
//                     el.scrollBy({ top: 0, behavior: 'smooth' });
//            """);
        // 确认容器
//        Object info = frame.evaluate("""
//        () => {
//            const sc = document.querySelector('#workspace');
//            if (!sc) return 'NOT FOUND';
//            return sc.tagName + ' scrollH=' + sc.scrollHeight + ' clientH=' + sc.clientHeight;
//        }
//        """);
//        System.out.println("容器信息: " + info);

        int lastCount = 0;
        int stableRounds = 0;

        for (int s = 0; s < 1500; s++) {
            frame.evaluate("""
                            const el = document.querySelector('#workspace') || document.documentElement;
                             el.scrollBy({ top: 800, behavior: 'smooth' });
                    """);


            int currentCount = frame.locator(".canvas-unit").count();
            System.out.printf("第 %d 次，节点数: %d%n", s + 1, currentCount);

            if (currentCount == lastCount) {
                stableRounds++;
                if (stableRounds >= 3) {
                    System.out.println("加载完成，总页数: " + currentCount);
                    break;
                }
            } else {
                stableRounds = 0;
            }
            lastCount = currentCount;
        }

        // 滚回顶部
        frame.evaluate("""
                const el = document.querySelector('#workspace') || document.documentElement;
                             el.scrollBy({ top: 0, behavior: 'smooth' });
                """);
        page.waitForTimeout(800);

        return frame.locator(".canvas-unit").count();
    }


}