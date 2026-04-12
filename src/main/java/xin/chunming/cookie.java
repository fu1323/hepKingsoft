package xin.chunming;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.*;

import java.nio.file.Paths;
import java.nio.file.Paths;

public class cookie {


    public static void main(String[] args) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)); // 必须设为 false，否则你没法手动登录

            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            // 1. 访问登录页面
            page.navigate("https://2d.hep.com.cn/1264201/31");

            // 2. 这里暂停程序，等待你在浏览器里手动完成登录
            // 登录成功进入资源页后，在控制台按回车继续
            System.out.println("请在浏览器中完成登录，完成后回到这里按回车...");
            new java.util.Scanner(System.in).nextLine();

            // 3. 关键：将当前所有的 Cookie 和 Session 状态保存到 JSON 文件
            context.storageState(new BrowserContext.StorageStateOptions()
                    .setPath(Paths.get("state.json")));

            System.out.println("登录状态已保存到 state.json");
            browser.close();
        }
    }


}
