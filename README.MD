# hepKingsoft

**基于 Java 与 Playwright 的高某教育出版社新形态某教材平台配套资源提取与本地保存工具。**

项目面向 [高某教育出版社新形态某教材平台] 中**本人有权访问**的教材配套资源，利用浏览器自动化识别和保存网页中已经展示的 PDF、Word、PPT、视频及 H5 互动内容。

它并不是通用文件格式转换器，也不提供激活码破解、付费权限绕过或 DRM 解密功能。对文档类资源，主要保存的是**浏览器渲染后的页面图像**，而非原始的 PDF、DOCX 或 PPTX 文件。

> [!IMPORTANT]
> 本项目是浏览器渲染、页面自动化和网络资源本地化的学习实践。请仅对自己有合法访问与保存权限的内容使用，遵守网站服务条款及相关著作权规定。**禁止将其用于未经授权的批量获取、传播或商业化 否则一切侵权行为与项目作者无关 后果自负.**

## 目录

- [功能与支持情况](#功能与支持情况)
- [工作原理](#工作原理)
- [运行环境](#运行环境)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [三种运行模式与 detailurl.txt](#三种运行模式与-detailurltxt)
- [输出文件结构](#输出文件结构)
- [不同资源的处理方式](#不同资源的处理方式)
- [失败重试与排查](#失败重试与排查)
- [PNG 透明背景处理](#png-透明背景处理)
- [源码结构](#源码结构)
- [当前限制与已知问题](#当前限制与已知问题)
- [版本记录](#版本记录)
- [隐私、版权与免责声明](#隐私版权与免责声明)
- [致谢](#致谢)

## 功能与支持情况

| 资源类型 | 当前实现 | 保存结果 | 说明 |
| --- | --- | --- | --- |
| PDF 文档 | ✅ 已实现 | 逐页 PNG | 滚动触发页面渲染，再对页面元素截图；**不导出原始 PDF** |
| Word 文档 | ✅ 已实现 | 逐页 PNG | 将网页中的 SVG 页面绘制到 Canvas，再保存为 PNG；**不导出 DOCX** |
| PPT 演示文稿 | ✅ 已实现 | 逐页 PNG | 点击缩略图后对主展示区的 SVG 截图；**不保留动画、切换效果及可编辑结构** |
| 视频 | ✅ 支持部分场景 | 视频文件（代码以 `.mp4` 命名） | 从页面 `<video>` 元素的 `src` 获取地址并发起 HTTP 下载；不保证适用于 `blob:`、分段流或需要特殊授权的媒体 |
| H5 互动动画 | ⚠️ 实验性支持 | `index.html` 与相关静态资源 | 监听网络响应、保存资源并重写部分引用；是否能离线正常运行取决于页面结构 |
| 图片和ZIP 等不可预览附件 | ❌ 尚未自动支持 | — | 可能需要在具有访问权限的前提下通过官方提供的方式单独获取(抓包试着看看JSON响应里有没有) |

其他已实现的辅助能力：

- 通过 Playwright 保存和复用浏览器登录状态，减少重复登录。
- 支持按教材资源序号遍历，或从 `detailurl.txt` 读取完整资源详情 URL；不同资源分别保存。
- 对 PDF、Word、PPT 自动识别相应的网页 DOM 结构，采用不同的提取策略。
- 针对部分 iframe 预览页尝试定位、展开并进入实际渲染页面。 
- 普通枚举模式可启用 `retry`，重新尝试**输出目录为空**的资源序号。
- 提供 ImageMagick 脚本，批量将 PNG 图片的透明背景填充为白色。

## 工作原理

平台中的部分文档通过 iframe 嵌入阿里云 IMM / 在线 Office 预览页面。资源在浏览器中展示时，页面可能已经将文档转换为 Canvas 或 SVG 等可视化 DOM 元素。本项目采用“**访问已授权页面 → 识别资源类型 → 提取浏览器已渲染的内容**”的方式工作。

```text
教材资源页面（已登录、具备相应访问权限）
  │
  ├─ Java / Playwright 打开资源 URL
  │    └─ 加载 state.json 中的浏览器登录状态
  │
  ├─ 查找预览 iframe / 资源页面
  │
  ├─ 检测页面类型
  │    ├─ .pdf-page          → 滚动、等待渲染、逐页截图
  │    ├─ .canvas-unit       → SVG → Canvas → Base64 PNG
  │    ├─ .thumbnail_slide   → 激活幻灯片 → SVG 元素截图
  │    ├─ <video src="...">   → OkHttp 请求媒体资源
  │    └─ hep5 页面          → 监听响应、保存 H5 依赖、重写部分链接
  │
  └─ 保存到 output01/<资源序号>/
```

这里的关键区别是：**网页能够预览某个文档，不等于程序能够获得它的原始文件**。对于已经被在线预览服务栅格化、拆分或动态重绘的文档，目前实现优先保存视觉结果，不能保证还原原始文件的文本层、排版结构或交互行为。

## 运行环境

### 必需组件

| 组件 | 要求 / 仓库配置 |
| --- | --- |
| JDK | **JDK 25**：当前 `pom.xml` 明确设置 `maven.compiler.source` / `target` 为 `25` |
| Maven | 可正常运行 `mvn` 的 Maven 3.x |
| Playwright for Java | **1.42.0**（`pom.xml`） |
| 浏览器 | Playwright 对应版本的 **Chromium** |
| 网络 | 可访问 Maven 依赖源、Playwright 浏览器下载源以及目标教材站点 |
| 可选工具 | ImageMagick（仅用于运行 `fill_transparentpicture.sh`） |

源码还依赖：

- `okhttp:3.8.1`：在视频分支发起 HTTP 下载。
- `jsoup:1.10.2`：在 H5 分支解析并改写 HTML。

> [!NOTE]
> 以上是**当前仓库**的配置，不代表项目只能使用这些具体版本。若要升级 JDK、Playwright 或其他依赖，应修改 `pom.xml` 后自行验证。首次运行会下载 Maven 依赖；如缺少浏览器可执行文件，还需要安装与当前 Playwright 版本匹配的 Chromium。 okhttp本人偷懒为了抓包方便 关闭了tls证书验证 请知悉.

### 检查环境

```bash
java -version
mvn -version
```

注意 `mvn -version` 显示的 Java 版本，必须与本项目配置兼容。终端中存在多个 JDK 时，请先正确配置 `JAVA_HOME`。

## 快速开始

### 1. 获取源码

```bash
git clone https://github.com/fu1323/hepKingsoft.git
cd hepKingsoft
```

### 2. 编译并准备 Chromium

```bash
mvn compile
```

如果 Playwright 没有自动准备好 Chromium，或启动时提示找不到浏览器，可以显式安装：

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

Linux 环境如提示缺少 Chromium 系统依赖，请参考 [Playwright 浏览器安装文档](https://playwright.dev/java/docs/browsers) 完善依赖。请注意本项目依赖的是 `pom.xml` 中指定的 Playwright 版本，而不是机器上任意安装的 Chrome 版本。

### 3. 登录并生成 `state.json`

**首次使用必须先运行 `xin.chunming.cookie`，再运行 `xin.chunming.Main`。** 这里的类名 `cookie` 全小写，是源码中的实际名称。

```bash
mvn compile exec:java -Dexec.mainClass=xin.chunming.cookie
```

程序会：

1. 打开一个可见的 Chromium 窗口，并访问代码中预设的教材资源页面。
2. 提示你在浏览器里**手动完成登录**。
3. 登录完成、确认已经能正常访问资源页后，回到运行 Maven 的终端按下 **Enter**。
4. 将登录状态保存为项目工作目录下的 `state.json`。

登录入口目前写在 [`cookie.java`](src/main/java/xin/chunming/cookie.java) 中：


这个地址只是仓库目前使用的预设入口。如果页面已变化，或需要对其他教材进行授权登录，请调整为你**有权限访问**的相应页面，并在程序浏览器中完成正常登录。

`state.json` 是 Playwright Storage State 格式，除 Cookie 外还可能包含 Local Storage 等登录信息。它是**敏感文件**，不可发布、上传到 Issue 或提交到 Git 仓库。建议在本地 `.gitignore` 中加入：

```gitignore
state.json
output*/
```

如果登录过期或权限有变化，重新执行 `cookie` 类生成新的状态文件即可。

### 4. 选择运行模式并配置输出目录

打开 [`Main.java`](src/main/java/xin/chunming/Main.java)，在类开头找到配置字段（以下是提交 `cc321b1` 中的实际默认值）：

```java
private static boolean retry = false;
private static boolean read_urlDetailTXT = true;
private static boolean headLess = false;
private static int start = 0;
private static int end = 100;
private static String baseUrl = "https://abooks.hep.com.cn/xxxxxxx/";
private static String baseDir = "output04";
```

**首次运行前必须选择模式。** 当前 `read_urlDetailTXT` 默认是 `true`，因此程序会读取工作目录中的 `detailurl.txt`；如果你没有创建该文件，请先改成 `false` 以使用教材序号枚举模式。

- **模式一：普通枚举**。设置 `read_urlDetailTXT = false`、`retry = false`；程序依次访问 `baseUrl + i`，其中 `i` 属于 `[start, end)`，例如 `start=0`、`end=100` 会访问序号 0～99。
- **模式二：普通枚举失败重试**。设置 `read_urlDetailTXT = false`、`retry = true`；扫描 `baseDir` 下的空资源目录，按目录名重试对应序号。它不是通用断点续传。
- **模式三：详情链接列表**。设置 `read_urlDetailTXT = true`；从 `detailurl.txt` 逐行读取完整资源详情 URL，去除完全相同的重复行，然后逐条访问。此模式下 `start`、`end` 会被覆盖为 `0` 和读取到的 URL 数量，`retry` 不生效。

`baseDir` 是相对于**运行时工作目录**的输出目录；不存在时程序会自动创建。`headLess=false` 表示显示 Chromium 窗口，方便检查登录、激活提示及页面加载。

部分教材二维码采用 `https://abooks.hep.com.cn/<教材 ID>/<资源序号>` 形式，适合模式一、二；另一些资源可以使用完整的 `resource/detail` 链接，适合模式三。仅处理你有权访问和保存的资源。

### 5. 运行主程序

```bash
mvn compile exec:java -Dexec.mainClass=xin.chunming.Main
```

程序将使用 `state.json` 创建浏览器上下文，访问指定资源并自动选择处理策略。成功输出会保存在 `baseDir` 指定的目录下。

> [!TIP]
> 仓库没有配置交互式 CLI 参数解析。**运行模式、教材 URL、序号范围、输出目录和重试开关目前都需要在源码中修改。** 使用 IntelliJ IDEA 时，也可以直接运行 `cookie.main()` 和 `Main.main()`，但要注意 IDE 的 Working directory，否则可能找不到 `state.json`。

## 配置说明

主要设置集中在 [`Main.java`](src/main/java/xin/chunming/Main.java) 的类级静态字段：

| 配置项 | `cc321b1` 默认值 | 作用 |
| --- | --- | --- |
| `read_urlDetailTXT` | `true` | `true`：读取详情链接列表；`false`：普通教材序号枚举 |
| `retry` | `false` | 仅在 `read_urlDetailTXT=false` 时启用空目录重试 |
| `start` / `end` | `0` / `100` | 普通模式的起始序号（含）和结束序号（不含） |
| `baseUrl` | `https://abooks.hep.com.cn/1247811/` | 普通模式下拼接资源序号的 URL 前缀 |
| `baseDir` | `output04` | 输出目录 |
| `headLess` | `false` | `false` 显示主浏览器，`true` 隐藏浏览器 |
| `statePath` | `state.json` | Playwright 登录状态文件 |
| `DEVICE_SCALE` | `2` | 浏览器设备像素比，影响截图质量和资源占用 |

此外，源码中有固定等待、页面滚动和 iframe 查找逻辑。部分页面超时被设置为无限等待，运行时若长时间停滞，请结合浏览器窗口和日志判断原因。

`AnimationMirror` 可能自行启动另一个 Chromium，其无头模式不一定受 `Main.headLess` 控制。

## 三种运行模式与 detailurl.txt

### 模式一：按教材 ID 枚举

```java
private static boolean read_urlDetailTXT = false;
private static boolean retry = false;
private static int start = 0;
private static int end = 20;
private static String baseUrl = "https://abooks.hep.com.cn/1247811/";
```

依次尝试 `baseUrl + 0` 到 `baseUrl + 19`。序号是否有效、资源是否可访问仍由网站决定。

### 模式二：重试失败的枚举资源

保持原来的 `baseDir`、`baseUrl`，设置：

```java
private static boolean read_urlDetailTXT = false;
private static boolean retry = true;
```

程序根据原输出目录中的空子目录尝试恢复；不会自动检查文件是否缺页，也不会重试详情 URL 列表。

### 模式三：读取完整详情 URL（本次新增）

在**运行工作目录**创建 `detailurl.txt`，每行一个完整 URL，例如：

```text
https://abooks.hep.com.cn/resource/detail?dirId=YOUR_DIR_ID&projectId=YOUR_PROJECT_ID&resourceId=YOUR_RESOURCE_ID
https://abooks.hep.com.cn/resource/detail?dirId=ANOTHER_DIR_ID&projectId=ANOTHER_PROJECT_ID&resourceId=ANOTHER_RESOURCE_ID
```

上面的 `YOUR_...` 是占位符，请换成自己有权限访问的真实资源链接。设置：

```java
private static boolean read_urlDetailTXT = true;
private static boolean retry = false;
```

程序逐行读取 `detailurl.txt`，以 `ArrayList` 保存并去除**完全相同**的重复字符串，再按列表顺序调用 `captureDocument()`。保存目录为 `baseDir/0/`、`baseDir/1/` 等，**目录编号代表列表索引，不是教材资源 ID**。

**当前实现注意事项：**

- 文件必须存在，否则 `FileReader("detailurl.txt")` 会抛出异常；路径相对于程序工作目录。
- 代码使用 `FileReader` 默认字符集，没有显式指定 UTF-8；跨平台使用时建议保证文件编码与运行环境一致。
- 只做字符串去重，没有对每行调用 `trim()`，空行和首尾空格可能形成无效 URL；请勿留空行。
- 列表模式会覆盖 `start` 和 `end`，并忽略 `retry`；不支持从任意索引恢复列表下载。
- 如果调整列表顺序，`baseDir/数字/` 的对应资源也会变化；建议保留原列表作为记录。

## 输出文件结构

以 `baseDir = "output04"` 为例，可能得到如下结果：

```text
hepKingsoft/
├── pom.xml
├── README.MD                 # 仓库中原有的 README 文件名
├── src/
│   └── main/java/xin/chunming/
│       ├── cookie.java       # 交互式登录并保存浏览器状态
│       ├── Main.java         # 遍历资源与文档/视频提取
│       └── AnimationMirror.java # H5 页面资源本地化
├── state.json                # 本地登录状态；不要提交
├── detailurl.txt              # 模式三所需；每行一个完整资源详情 URL
└── output04/                 # 运行后生成
    ├── 0/
    │   ├── page_000.png
    │   ├── page_001.png
    │   └── ...
    ├── 1/
    │   └── <随机文件名>.mp4
    ├── 2/
    │   ├── index.html
    │   └── <资源域名>/...
    └── 3/                    # 也可能为空：无资源、加载失败或不支持
```

这是**示意结构**：不同序号对应哪种类型由实际页面决定，文件是否生成也取决于页面是否可访问、能否正确渲染、下载是否完成等因素。

页面命名通常使用 `page_000.png`、`page_001.png` 等格式；部分 PPT 页码由预览页自身提供。**不要仅根据出现了文件夹就判断该资源已经完整保存。**

## 不同资源的处理方式

### PDF：滚动加载 + 页面截图

PDF 分支会检查 iframe 中的 `.pdf-page` 节点，滚动浏览区触发延迟渲染，并使用 Playwright 的元素截图接口将已渲染页面保存为 PNG。

特点：

- 不依赖浏览器提供“下载原文件”按钮。
- 适合保留能够正常预览的页面外观。
- 受网页缩放、设备像素比、渲染完成时机及 DOM 结构影响。
- 当前不会把 PNG 自动合并成 PDF，也不保留可搜索文本层。

### Word：SVG → Canvas → PNG

Word 分支定位 `.canvas-unit` 中的 SVG 内容，依次完成：

```text
网页 SVG 节点
   ↓ XMLSerializer 序列化
SVG 字符串 → Blob URL → Image
   ↓ drawImage()
临时 Canvas
   ↓ toDataURL('image/png')
Base64 → Java 解码 → page_XXX.png
```

在一些在线预览场景下，直接读取 Canvas 或依赖页面现成的位图不够可靠，因此当前实现通过**重建临时 Canvas**获取已经渲染的页面。该方式保存的是页面图像，并不是恢复 Word 的原始可编辑结构。外链图片、特殊字体和复杂 SVG 特性仍有可能影响最终结果。

### PPT：选中幻灯片 + SVG 截图

PPT 分支读取 `.thumbnail_slide`，遍历缩略图列表，通过模拟 `mousedown` 激活对应页面，等待主内容更新，再对 `#uil-content svg` 截图。

结果是每张幻灯片的 PNG 图像，而不是 `.pptx` 文件。原始切换动画、逐项出现的效果、备注、音视频嵌入对象以及可编辑图层均不保证保留。部分页面若尚未加载完全，可能出现漏页或空白截图。

### 视频：读取媒体地址并下载

当前视频逻辑大致如下：

1. 检查页面是否包含 `<video>` 元素。
2. 读取该元素的 `src` 属性。
3. 交由 OkHttp 发起下载请求，写入随机文件名的 `.mp4` 文件。

它适用于 `src` 可直接访问、返回完整媒体文件的部分场景，**没有实现通用 HLS / DASH 分片合并、`blob:` / MSE 数据重组、DRM 解密或任意鉴权视频处理**。另外，下载使用异步回调，页面任务结束不一定表示媒体已经完全写入磁盘，应以文件大小与可播放性核实结果。

### H5：网络响应镜像 + 引用改写

`AnimationMirror.java` 负责 H5 资源本地化：

1. 用 Playwright 重新打开目标 H5 页面，并监听网络响应。
2. 对满足条件的 HTTP 响应读取 body，按照 `域名/路径` 保存到输出目录。
3. 通过滚动和等待触发一部分懒加载请求。
4. 获取页面 HTML，使用 Jsoup 改写已识别资源的 `src`、`href`、`data-src`、`data-url`、内联样式和部分内联脚本引用。
5. 保存入口文件 `index.html`。

需要注意：该方法并不是通用离线网站打包器。对于运行时动态拼接的 URL、查询参数不同但路径相同的响应、跨域 API、Service Worker、大体积 WASM、后续交互才触发的请求等，可能无法完整保存或改写。

**建议使用本地 HTTP 服务预览，而不是直接以 `file://` 打开。** 例如，进入某个包含 `index.html` 的输出目录后：

```bash
python3 -m http.server 8000
```

然后在浏览器中访问 `http://127.0.0.1:8000/`。这有助于减少某些资源在 `file://` 模式下遇到的同源限制，但**不保证**动态 API 或依赖远程服务器的互动内容能离线运行。

## 失败重试与排查

### 启用失败目录重试

**仅适用于普通枚举模式。** 普通模式运行完成后，若部分资源序号对应的目录为空，可以在 `Main.java` 类开头将：

```java
private static boolean retry = false;
```

改为：

```java
private static boolean retry = true;
private static boolean read_urlDetailTXT = false;
```

再次运行 `Main`，程序将扫描 `baseDir` 下的子目录，寻找**不包含非隐藏普通文件**的目录，并尝试重新打开对应序号。

> [!WARNING]
> 当前的 `retry` **不是完整性校验，也不是断点续传**。只要某个目录存在一个非隐藏普通文件，即使缺页、文件不完整或资源层级里还有缺失内容，也可能被视为不需要重试。建议人工抽查保存结果。

### 常见问题

| 现象 | 排查方向 |
| --- | --- |
| `detailurl.txt` 不存在 | 当前默认启用了列表模式；创建该文件或将 `read_urlDetailTXT` 设为 `false` |
| 列表模式遇到无效 URL | 检查空行、首尾空格和链接格式；当前不会自动 `trim()` |
| `state.json` 不存在 / 无法读取 | 先运行 `xin.chunming.cookie`，并检查工作目录是否一致 |
| 进入页面后仍要求登录 | 登录状态失效；重新生成 `state.json`，确认登录域名及页面权限 |
| 提示教材激活码 | 在有头模式下检查页面；需要按官方要求使用合法激活码，本项目不能绕过授权 |
| 浏览器启动报错 | 核对 JDK / Maven / Playwright 版本，执行 Chromium 安装命令 |
| PDF / Word / PPT 无输出 | 查看 iframe 是否变化、网页是否渲染完成，以及 DOM 选择器是否仍匹配 |
| 截图为空白或不完整 | 适度增加等待时间，降低处理速度，观察实际显示内容 |
| 视频保存失败 / 文件不可播放 | 核对 `video.src` 是否是直接媒体 URL，检查 HTTP 返回及文件是否下载完成 |
| H5 动画离线打不开 | 改用本地 HTTP 服务；检查缺失脚本、WASM、跨域请求或动态 API |
| 程序一直不结束 | 检查页面等待、网络请求和无限超时配置；必要时中断后缩小序号范围 |
| 重试后仍为空 | 不一定是网络错误，也可能是资源不存在、无权限或当前类型不受支持 |

建议首次调试时保留 `setHeadless(false)`，只处理少量资源，并查看终端输出与浏览器中的真实页面状态。网页选择器来自当前实现，平台页面改版后可能需要调整代码。

## PNG 透明背景处理

仓库包含 [`fill_transparentpicture.sh`](fill_transparentpicture.sh)，用于将 PNG 的透明区域填充为白色，保留原有的相对目录结构。

使用前安装 [ImageMagick](https://imagemagick.org/) 并确认可以运行：

```bash
magick -version
```

例如，要处理 `output01` 中已经导出的图片：

```bash
cp fill_transparentpicture.sh output01/
cd output01
sh fill_transparentpicture.sh
```

脚本会递归查找当前目录中的 PNG，忽略 `./output/` 目录，并把结果写入 `output/` 下对应的路径：

```text
output01/
├── 0/page_000.png            # 原图
├── 0/page_001.png
└── output/
    └── 0/
        ├── page_000.png       # 白色背景版本
        └── page_001.png
```

原图不会被原位覆盖。建议先检查处理结果，再决定是否删除或替换原文件。

## 源码结构

主要入口及职责如下：

| 文件 | 作用 |
| --- | --- |
| [`pom.xml`](pom.xml) | Maven 工程参数与 Java 依赖 |
| [`src/main/java/xin/chunming/cookie.java`](src/main/java/xin/chunming/cookie.java) | 打开浏览器，人工登录，并导出 `state.json` |
| [`src/main/java/xin/chunming/Main.java`](src/main/java/xin/chunming/Main.java) | 主入口、序号遍历、iframe 检测、文档截图、视频下载及重试 |
| [`src/main/java/xin/chunming/AnimationMirror.java`](src/main/java/xin/chunming/AnimationMirror.java) | H5 网络响应保存、HTML 引用改写及本地入口生成 |
| [`fill_transparentpicture.sh`](fill_transparentpicture.sh) | 批量为透明 PNG 填充白色背景 |

当前代码更接近**可运行的研究原型 / 自动化脚本集合**，而非已经完成组件化和测试覆盖的通用软件。若有意继续开发，可以优先考虑把硬编码参数外置、统一日志与异常处理、增加结果完整性清单、将下载状态与资源目录关联，以及增加可选的 PDF 图片合并功能。

## 当前限制与已知问题

1. **不支持所有资源类型。** ZIP 等不可预览附件尚无自动处理逻辑；原网站是否提供下载入口及用户是否具有下载权限，应以实际授权情况为准。
2. **H5 本地化不一定完整。** 大体积资源（例如部分 WASM）读取响应体可能失败；动态请求、部分内嵌引用和跨域 API 也可能无法离线复现。
3. **对网页结构有依赖。** `.pdf-page`、`.canvas-unit`、`.thumbnail_slide` 和 `#uil-content svg` 等选择器可能随着平台更新而失效。
4. **文档主要以 PNG 输出。** 不能由此恢复原文档的文本层、公式可编辑性、注释、页面动画等信息。
5. **成功与否尚无统一校验。** 当前重试主要判断输出目录是否为空，不能准确识别所有部分失败。
6. **视频下载适用范围有限。** 当前仅实现基于 `video.src` 的 HTTP 请求，不支持通用分段媒体流处理。
7. **性能、稳定性受页面影响较大。** 多次固定等待、浏览器渲染速度、网络延迟以及预览服务自身错误都可能影响结果。
8. **详情链接列表的恢复能力有限。** 目前列表模式不支持 `retry`，按行索引命名目录，修改列表顺序可能影响输出对应关系。
9. **视频 HTTPS 证书验证被禁用。** 当前 `downloadVideo()` 使用 `getUnsafeOkHttpClient()`，信任所有证书并接受任意主机名，可能遭遇中间人攻击。建议恢复默认 TLS 校验，针对具体证书问题进行修复，不要在不可信网络中依赖此设置。
10. **尚无正式 CLI 和自动化测试说明。** 配置需要修改源码；仓库也没有提供明确的独立发布包或可执行 Fat JAR 构建步骤。

## 版本记录

根据项目原有 README 和提交记录整理：

| 时间 | 变更 |
| --- | --- |
| 2026-04-17 | 完善视频与 PPT 提取逻辑 |
| 2026-04-25 | 新增 H5 动画资源提取逻辑 |
| 2026-09 | 增加 `retry` 开关，可针对空输出目录再次尝试 |
| 2026-10 | 修复若干导致资源提取失败的问题 |
| 2026-11-07（`cc321b1`） | 新增 `detailurl.txt` 完整 URL 列表模式、`start/end` 范围配置和集中参数；视频下载改用跳过 TLS 证书校验的 OkHttp 客户端（存在安全风险） |

具体代码变更请参阅仓库的 [Commit History](https://github.com/fu1323/hepKingsoft/commits/main/)。

## 隐私、版权与免责声明

- **登录凭据：** `state.json` 可能包含有效的 Cookie 和其他会话信息，具有账号访问风险。请妥善保存，不要公开共享；会话失效后应重新正常登录获取。
- **版权归属：** 教材、文档、视频、演示文稿、互动资源及其他第三方内容的权利均归其各自权利人所有。本项目不对这些内容主张著作权。
- **使用边界：** 本项目仅面向有权访问相关资源的学习、研究与个人合法使用场景，不授予任何超出平台许可或法律规定的复制、传播、再分发权限。
- **功能边界：** 本项目不破解激活码、不解密 DRM，也不保证所有预览内容可以被保存或离线运行。
- **软件现状：** 代码按现状提供，不承诺兼容所有教材、页面版本、运行环境或媒体类型。使用者应自行评估合规性并承担相应责任。
- **开源许可：** 截至此次 README 整理，仓库根目录未见单独的 `LICENSE` 文件；**公开源码不等于自动授予任何开源许可证下的使用权**。如计划允许他人修改和再分发代码，建议仓库维护者单独选择并添加许可证。

## 致谢

项目出于对网页渲染、浏览器自动化与在线 Office 预览机制的学习兴趣而开发。感谢 **Claude** 在方案讨论、代码调试及实现思路方面提供的帮助。

项目仓库：**[fu1323/hepKingsoft](https://github.com/fu1323/hepKingsoft)**

# 26.11 针对旧版由于虚拟滚动导致word最多只有两页的问题 bug修复   Word 虚拟滚动提取模块：MutationObserver 技术解析

> 对应文件：`src/main/java/xin/chunming/WordCapture.java`、`src/main/resources/word-observer.js`。本文基于当前补丁源码分析，解释设计动机、执行链路、关键数据结构、失败恢复及已知边界；并非对真实金山预览站点的完整兼容性保证。

## 1. 原有实现为什么只保存两页

原 `Main.captureWordMode()` 从 `document.querySelectorAll('.canvas-unit')` 获取节点列表，使用列表下标 `idx` 命名 `page_000.png`、`page_001.png`，并用 `Set<Integer> savedIndices` 去重。这隐含了一个错误前提：**DOM 节点下标等于文档页码**。

虚拟滚动组件为了节省内存，可能始终保留两个 `.canvas-unit`，随着滚动替换其 SVG 内容：

| 时刻 | DOM 槽位 0 | DOM 槽位 1 | 原代码结果 |
|---|---|---|---|
| 初始 | 第 1 页 | 第 2 页 | 保存 0、1 |
| 滚动后 | 第 3 页 | 第 4 页 | `savedIndices` 已包含 0、1，全部跳过 |
| 再滚动 | 第 5 页 | 第 6 页 | 继续跳过 |

此外，原实现按连续无新增保存次数退出，无法区分“渲染慢”“正在滚动”“确实到底”。

## 2. 新方案的整体架构

```text
Main.captureDocument()
  └── captureWordMode(page, frame, savePath)
        └── WordCapture.capture(...)
              ├── installObserver() 读取并注入 word-observer.js
              ├── toTop() 回到顶部
              ├── captureLoop (WordCapture.run)
              │     ├── scan() 主动补扫
              │     ├── drainQueue() 消费 SVG 快照
              │     │     ├── take() → inFlight
              │     │     ├── Blob → Image → Canvas → PNG Base64
              │     │     ├── Java Base64 解码 + 临时文件写入 + rename
              │     │     └── ack(ok=true/false) / drop()
              │     ├── status() 检查 pending、unstable、队列、底部
              │     └── scrollDown() 继续滚动
              ├── 最后补扫 4 次
              └── stop() 释放 observer 和定时器
```

职责边界：**JavaScript 观察并冻结 DOM 状态；Java 串行驱动 Playwright、处理图像、写入文件。** 没有额外的 Java Playwright 工作线程。

## 3. 为什么选择 MutationObserver

`MutationObserver` 是浏览器原生 DOM 变化订阅机制。此处监听：

```javascript
observer.observe(document.documentElement, {
  subtree: true,
  childList: true,
  attributes: true,
  characterData: true
});
```

- `childList`：捕获 `.canvas-unit` 新增、移除，及内部 SVG 子节点替换。
- `attributes`：捕获 class、SVG 属性等修改，包括 pending 状态切换。
- `characterData`：捕获文本节点变化。
- `subtree`：将监听范围扩展到子树。

`mutationRelated()` 过滤与 `.canvas-unit` 无关的变更，`scheduleScan()` 合并密集事件，80ms 后扫描一次，避免每次 DOM 微小修改都立即完整序列化 SVG。

**局限**：它不是滚动监听器；`scrollTop` 改变本身不会触发 DOM mutation；Canvas 像素更新也不会自动触发。代码因此另设 `setInterval(scan, 150)` 和 Java 主动 `scan()` 兜底。

## 4. SVG 从候选到队列的状态转换

`word-observer.js` 的关键结构：

```javascript
const candidates = new WeakMap();
const queued = new Map();
const inFlight = new Map();
const committed = new Map();
```

| 结构 | Key | Value | 用途 |
|---|---|---|---|
| `candidates` | `.canvas-unit` DOM 对象 | 页面身份、SVG 指纹、首次观察时间 | 判断当前渲染版本是否稳定 |
| `queued` | 页面身份 `key` | **完整 SVG 字符串快照**及尺寸 | 等待 Java 领取 |
| `inFlight` | 页面身份 `key` | 已领取快照 | 避免同一版本被并发重复领取 |
| `committed` | 页面身份 `key` | 成功保存的 SVG 指纹 `sig` | 避免重复保存相同版本 |

一次扫描的主要步骤：

1. 找到所有 `.canvas-unit`。
2. 跳过带 `.doc-pending-canvas-unit` 的节点。
3. 要求内部有非空 SVG，且宽高有效。
4. `XMLSerializer().serializeToString(svg)` 冻结 SVG XML；必要时补 `xmlns:xlink`。
5. 计算内容签名 `sig`，识别页面身份 `key`。
6. 将 `(key, sig)` 与 `candidates` 中上次观察结果比较；发生变化则重置稳定计时。
7. 同一版本稳定至少 `STABLE_MS = 450` 毫秒，且不在已提交、待处理或处理中集合，才放入 `queued`。

这里的“稳定”指**两次扫描之间序列化 SVG 内容一致且经过一定时间**，并不等价于网络资源、字体、外链图片都已完全加载。

### 为什么必须存字符串快照

错误方案：把 DOM 元素 `unit` 或 `svg` 引用交给 Java，等保存时再读取。虚拟滚动可能在这段时间把节点内容改成下一页。

当前方案：

```javascript
queued.set(identity.key, {
  key: identity.key,
  sig,
  kind: identity.kind,
  svgStr: xml,
  w,
  h
});
```

此时 `svgStr` 是独立字符串，后续 DOM 被回收不会改变已入队的内容。这是解决虚拟滚动漏页的关键之一。

## 5. 页面身份：为什么不用 DOM 下标

`pageIdentity(unit, xmlSig)` 尝试三层识别：

**第一优先级：页面在滚动内容中的位置。** 从可滚动祖先 `scrollRoot()` 获取 `scrollTop`，结合元素与滚动根的 `getBoundingClientRect()` 估算文档内的纵向位置，量化为 `pos:${Math.round(y / 20)}`。它识别的是页面所处的布局位置，而不是复用的 DOM 槽位。

**第二优先级：页码相关属性。** 如果位置方案不可用，查找当前元素及部分祖先的 `data-page-index`、`data-page-number`、`data-page-no`、`data-page-num`、`data-page-id`、`page-index`。

**第三优先级：SVG 内容签名。** 以 `svg:${xmlSig}` 兜底。两个内容完全相同的页面可能被合并。

需要特别强调：**位置键是启发式识别，不是文档真实页码**。虚拟列表若用 `transform` 重排、滚动锚点修正、动态页高或回收槽位的布局坐标，位置估算可能漂移。代码对小幅位置抖动做了缓和处理，但无法保证所有预览器布局都正确。

`signature()` 使用两组 32 位 FNV 风格散列及 XML 长度，适合快速变化检测，**不是密码学哈希，也不能保证零碰撞**。

## 6. Java 怎样消费队列

`WordCapture.drainQueue()` 每轮最多消费 40 个任务：

```java
Map<String, Object> item = (Map<String, Object>) frame.evaluate(
    "() => window.__hepWordCapture.take()");
```

`take()` 从 `queued` 移走一项并放入 `inFlight`。Java 随后通过 `frame.evaluate(SVG_TO_PNG_JS, item)` 把已捕获的 SVG XML 转为 PNG Base64。

### SVG → PNG 的浏览器原生管线

```text
SVG XML 字符串
  → Blob(type=image/svg+xml)
  → URL.createObjectURL(blob)
  → new Image() / img.src = blob URL
  → 等待 img.onload
  → canvas.getContext('2d').drawImage(...)
  → canvas.toDataURL('image/png')
  → Base64 字符串
  → Java Base64.getDecoder().decode(...)
  → PNG 文件
```

浏览器负责 SVG 解析和光栅化，Java 不需要自己实现 SVG path/text 渲染。`SCALE = 1` 维持当前输出倍率；设为 2 会将 Canvas 宽高扩大两倍，像素数约变为四倍，内存与编码成本也明显增加。

`URL.revokeObjectURL(url)` 在成功/失败回调中释放临时 URL。Canvas 导出可能因跨域资源污染、安全限制或 SVG 资源不完整失败，代码会返回 `null`，Java 进入重试。

### 文件写入与 ACK

Java 先将 PNG 写入 `page_XXX.png.tmp`，再通过 `Files.move(..., REPLACE_EXISTING)` 替换目标文件。相比直接写最终 PNG，可以降低异常中断留下半张最终文件的概率；**但没有显式请求 `ATOMIC_MOVE`，因此不能宣称文件替换在所有文件系统上原子化**。

成功后：

```javascript
ack({ key, sig, ok: true })
```

`inFlight` 被清理，`committed[key] = sig`，后续扫描相同版本不再入队。

失败后：

```javascript
ack({ key, sig, ok: false })
```

快照重新放回 `queued`；Java 按 `key + '|' + sig` 记录失败次数，达到 3 次后调用 `drop(key)`，清除阻塞任务，并在本次运行中将其版本视为已处理。

因此，`drop()` **不是保存成功**，只是防止坏任务无限阻塞；日志中的 `permanentlyFailed` 必须单独关注。

## 7. 背压：为什么先保存再滚动

如果 Java 每次都直接向下滚动，虚拟列表可能在 SVG 完成序列化前就回收页面。新主循环先 `scan()`、`drainQueue()`、`status()`，只要有待处理/处理中任务，或者本轮刚处理了任务，就暂不滚动：

```java
if (queued > 0 || inFlight > 0 || processed > 0) {
    bottomSince = -1;
    continue;
}
```

此外，`MAX_QUEUE = 16` 限制浏览器端快照积压，避免长文档把大量 SVG XML 永久堆在浏览器内存中。这是一种简化的背压机制：**处理速度决定滚动速度**。

## 8. 如何决定下一次滚动

`WordCapture.run()` 采用单线程循环，关键参数：

| 参数 | 当前值 | 含义 |
|---|---:|---|
| `SCROLL_STEP` | 450px | 每次移动的距离 |
| `MIN_DWELL_MS` | 1400ms | 每个滚动位置至少观察的时间 |
| `MAX_RENDER_WAIT_MS` | 5500ms | 遇到 pending/unstable 时最多等待的时间 |
| `BOTTOM_QUIET_MS` | 5000ms | 到底且无新 DOM 变化的安静窗口 |
| `BOTTOM_PENDING_TIMEOUT_MS` | 15000ms | 底部仍有未完成页面时的超时 |
| `MAX_ROUNDS` | 5000 | 循环安全上限 |

当队列为空、当前视口已观察足够久，并且没有仍需等待的 pending/unstable 页面时，调用：

```java
frame.evaluate("(step) => window.__hepWordCapture.scrollDown(step)", SCROLL_STEP);
```

浏览器端 `scrollDown()` 修改真正滚动根的 `scrollTop`。滚动根通过 `.canvas-unit` 的可滚动祖先、`#workspace` 和 `document.scrollingElement` 依次识别，而不是固定假定 `#workspace` 一定是滚动元素。

**注意**：达到 `MAX_RENDER_WAIT_MS` 后，即使当前页仍不稳定，主循环也可能继续滚动。这是防止永久卡死的折中，不是“保证绝不漏页”的机制。若页面特别慢，应增加等待或记录未完成页以便二次抓取。

## 9. 何时结束抓取

与原来的“连续两轮没有新文件就退出”不同，新代码要求：

1. `scrollTop + clientHeight` 接近 `scrollHeight`，确认处于滚动底部；
2. `queued == 0`、`inFlight == 0`；
3. `pendingUnits == 0`、`unstableUnits == 0`；
4. 在底部持续至少 5 秒；
5. 距离上次相关 DOM 变化至少 5 秒。

若底部持续 15 秒仍存在未完成页面，则输出超时警告并结束。主循环结束后再主动扫描四次，尝试接住延迟入队的快照。`finally` 调用 `stop()`，断开观察器、清除定时器。

这套终止条件比“DOM 数量不变”可靠，但仍然依赖滚动根识别正确、网页不在更晚时间异步加载新内容。

## 10. 页面命名与更新策略

Java 用 `LinkedHashMap<String,Integer> pageNumbers` 将首次出现的 `pageKey` 分配为 `0、1、2...`，写为 `page_000.png`、`page_001.png` 等。若同一 `key` 后续出现新 `sig`，覆盖更新对应 PNG，并增加 `updatedPages`。

这意味着**输出文件序号是首次捕获顺序，不一定等于 Word 原始页码**。如果页面位置识别不稳定，可能发生同一页拆成多个文件，或者不同页覆盖同一文件。长期改进应优先解析可靠的真实页码元数据，并在输出中记录 `pageKey → 文件名 → SVG 指纹` 清单。

## 11. 典型执行示例

```text
进入 Word 模式
→ 注入观察器并回到顶部
→ .canvas-unit[0] 显示第1页，.canvas-unit[1] 显示第2页
→ 序列化两个 SVG，等待稳定
→ queued = {page1, page2}
→ Java take(page1)，转换 PNG，写入，ack(true)
→ Java take(page2)，转换 PNG，写入，ack(true)
→ 队列空、视口稳定 → scrollDown(450)
→ 虚拟滚动复用同两个 DOM 节点，内容变成第3、4页
→ MutationObserver 触发扫描，检测到新 SVG/页面位置
→ queued = {page3, page4}
→ Java 保存 page_002.png、page_003.png
→ 继续滚动直到到底且安静窗口满足
→ 最后补扫、断开观察器、输出统计
```

上述是**理想执行序列**。实际是否准确识别第 3、4 页取决于预览器 DOM 布局和 `pageIdentity()` 的可用性。

## 12. 排障指南

### A. 仍然只保存两页

查看日志中的 `root`、`scroll`、`DOM`、`pending`、`unstable`。如果 `scroll` 的 `top` 不变化，说明找错了滚动容器。如果 `top` 变化但没有新任务，检查 `pageIdentity()` 是否持续返回相同 key、SVG XML 是否真的更新。

### B. 保存出重复页

打印每次入队的 `key`、`kind`、`sig`、SVG 长度、页面 `getBoundingClientRect().top`。重点排查位置量化在滚动期间漂移，导致同一页产生多个 key。

### C. 保存的 PNG 不完整或缺字

检查 SVG 是否依赖外部 CSS、字体、`<image href>`、`<use>` 引用。`STABLE_MS=450` 只证明 XML 短时间不变，不保证依赖加载完毕。可临时同时落盘 `.svg` 便于定位问题属于捕获阶段还是 Canvas 阶段。

### D. 到底后迟迟不退出

检查是否有持续变化的 SVG 属性、pending 节点、动画元素，或滚动根 `scrollHeight` 不断变化。必要时将“已稳定页面”与“无关动画变化”区分。

### E. 出现 `永久失败`

关注 `SVG 转 PNG 返回空数据`、Base64 解码错误、文件系统权限或外部资源问题。当前 `drop()` 会跳过该版本，不能把最终保存数量当成全部成功。

## 13. 仍值得改进的地方

1. **真实页码识别**：尽量从预览器稳定的页码属性或业务元数据获得页码，替代坐标推断。
2. **位置计算验证**：当前 `root === document.scrollingElement` 时的坐标换算在部分页面布局下可能需要修正；最好实测滚动前后同一页的 key 是否不变。
3. **保存清单**：记录 key、sig、输出路径、保存时间、失败次数，支持中断后续传与问题复现。
4. **持久失败队列**：对 `drop()` 的页面输出单独 JSON 报告，避免静默漏页。
5. **资源内联**：如 SVG 引用外部图片或字体，可考虑在合规前提下先将依赖资源内联，再光栅化。
6. **精确完成判定**：若能拿到文档真实总页数，应与成功保存的唯一页码集合核对，而不只依赖滚动到底。
7. **稳定性策略**：为单页设置独立计时与超时；当前 WeakMap 候选状态在 DOM 复用时虽可重置，但无法解决所有异步资源加载情形。

## 14. 核心总结

原版代码将 **DOM 节点下标** 当作 **文档页码**，在虚拟滚动场景下自然会漏页。新版的关键不是单纯“加一个监听器”，而是完整地调整了数据流：

> **观察 DOM 变化 → 识别页面版本 → 冻结 SVG 字符串 → 排队 → Java 转 PNG → 文件写入 → ACK → 再滚动。**

`MutationObserver` 负责及时发现变化；稳定性检测避免抓取半成品；快照避免 DOM 复用污染数据；队列与 ACK 避免保存过程丢任务；滚动背压与底部安静窗口降低漏页和误退出概率。

这是一套适合当前 Playwright + Java 项目的工程化改进，但它不是对所有虚拟列表都通用的完美分页算法。**真正决定最终正确率的仍是可靠的页面身份识别和渲染完成判据。**
