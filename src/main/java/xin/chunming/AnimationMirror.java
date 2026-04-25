package xin.chunming;// pom.xml 依赖
// com.microsoft.playwright:playwright:1.44.0
// org.jsoup:jsoup:1.17.2

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;



public class AnimationMirror {

    static String TARGET_URL;//
    static Path OUTPUT_DIR;//Path.of("output");
    // url -> 本地相对路径
    static Map<String, String> urlToLocal = new ConcurrentHashMap<>();

    public static void saving(String TARGET, String OUTPUT,String statepath) throws Exception {

//        Files.createDirectories(new File(OUTPUT + File.separator + "output").toPath());
        TARGET_URL = TARGET;
        OUTPUT_DIR = Path.of(OUTPUT);
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true) //
            );
            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setIgnoreHTTPSErrors(true)
                            .setStorageStatePath(Paths.get(statepath))
                            .setViewportSize(1920, 1080)
            );
            Page page = context.newPage();

            // ① 拦截所有响应，保存资源
            page.onResponse(response -> {
                try {
                    saveResource(response);
                } catch (Exception e) {
                    System.err.println("跳过: " + response.url() + " -> " + e.getMessage());
                }
            });

            // ② 加载页面，等待网络空闲
            page.navigate(TARGET_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            // ③ WASM/动态资源可能懒加载，滚动+等待触发
            triggerLazyLoad(page);

            // ④ 拿到最终 HTML，替换所有资源引用
            String html = page.content();
            String rewritten = rewriteHtml(html);
            Files.writeString(OUTPUT_DIR.resolve("index.html"), rewritten);

            System.out.println("完成，共保存 " + urlToLocal.size() + " 个资源");
            browser.close();
        }
    }

    static void saveResource(Response response) throws Exception {
        String url = response.url();
        // 跳过 data URI 和 blob
        if (url.startsWith("data:") || url.startsWith("blob:")) return;
        // 跳过非 2xx
        if (response.status() < 200 || response.status() >= 300) return;

        URI uri = new URI(url);
        // 构建本地路径：host/path
        String localRel = uri.getHost() + uri.getPath();
        // 处理无扩展名的路径（如 /api/data）
        if (!localRel.contains(".")) localRel += ".bin";
        // 防止路径穿越
        localRel = localRel.replaceAll("[^a-zA-Z0-9./\\-_]", "_");

        Path localPath = OUTPUT_DIR.resolve(localRel);
        Files.createDirectories(localPath.getParent());

        if (!Files.exists(localPath)) {
            byte[] body = response.body();
            Files.write(localPath, body);
            System.out.println("保存: " + url + " -> " + localRel);
        }

        urlToLocal.put(url, localRel);
    }

    static void triggerLazyLoad(Page page) throws Exception {
        // 滚动触发懒加载
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
        Thread.sleep(2000);
        page.evaluate("window.scrollTo(0, 0)");
        // 等 WASM 初始化（通常需要几秒）
        Thread.sleep(5000);
        // 如果有动画暂停按钮，可以在这里模拟点击触发全帧加载
        // page.click(".play-btn");
        Thread.sleep(3000);
    }

    static String rewriteHtml(String html) {
        Document doc = Jsoup.parse(html);

        // 替换 src / href / data-src 等属性
        String[] attrs = {"src", "href", "data-src", "data-url"};
        for (String attr : attrs) {
            doc.select("[" + attr + "]").forEach(el -> {
                String val = el.attr(attr);
                if (urlToLocal.containsKey(val)) {
                    el.attr(attr, urlToLocal.get(val));
                }
            });
        }

        // 替换内联 style 里的 url()
        doc.select("[style]").forEach(el -> {
            String style = el.attr("style");
            for (Map.Entry<String, String> e : urlToLocal.entrySet()) {
                style = style.replace(e.getKey(), e.getValue());
            }
            el.attr("style", style);
        });

        // 替换 <script> 内容里硬编码的 CDN 路径（WASM fetch 路径等）
        doc.select("script").forEach(el -> {
            String src = el.html();
            for (Map.Entry<String, String> e : urlToLocal.entrySet()) {
                src = src.replace(e.getKey(), e.getValue());
            }
            el.html(src);
        });

        return doc.outerHtml();
    }
}
