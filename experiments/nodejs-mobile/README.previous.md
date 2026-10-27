# Grain

当前产品目标是独立 Android 编辑器：用户只安装 Grain，界面接近 VS Code，
最终在手机本机提供 Node、npm、终端和项目预览。

新增原生工作区与命令面板的实现范围、运行时约束和真机验收清单见
[Android 运行环境说明](docs/android-runtime.md)。完整 Node/npm 尚未验证，
下面原有内嵌插件说明属于桥接实验，不代表完整开发环境已经可用。

面向 Android 的移动端前端编辑器。浏览器里已经可以写 `main.js`、点运行、在控制台看输出，并在预览里看脚本画出的页面。设备上的 Node 仍是后面的事。

Grain is a mobile frontend editor aimed at Android. In the browser you can edit `main.js`, run it, read the console, and see any DOM the script creates. On-device Node comes later.

## 本地运行 / Run locally

浏览器预览不需要下载 nodejs-mobile。在 macOS 上如果要跳过插件的 iOS 二进制下载：

The browser preview does not download nodejs-mobile. On macOS, skip the plugin's iOS binary download with:

```bash
CAPACITOR_NODEJS_SKIP_DOWNLOAD=1 npm i
npm run dev
```

打开 [http://127.0.0.1:43127](http://127.0.0.1:43127)。编辑 `main.js`，点右上角「运行」，或按 Ctrl+Enter（macOS 为 ⌘Enter）。`console.log` / `info` / `warn` / `error`、未捕获异常和未处理的 Promise 拒绝会出现在下方控制台，带时间戳。脚本如果改了 `document.body`，右侧预览会显示页面；只打印日志时，预览写着「已在控制台输出」。`src/App.vue` 可以编辑，但不会被运行。控制台里的 Node 页签仍是设备运行时的 Ping，浏览器里是桩。

Open [http://127.0.0.1:43127](http://127.0.0.1:43127). Edit `main.js`, then press Run or Ctrl+Enter (⌘Enter on macOS). `console.log` / `info` / `warn` / `error`, uncaught exceptions, and unhandled rejections show in the console with timestamps. If the script writes `document.body`, the preview pane shows that page. Console-only scripts leave the preview on “已在控制台输出”. `src/App.vue` is editable and is not executed. The Node tab is the device runtime ping; in the browser it stays a stub.

```bash
npm run build
npx cap sync android
```

用 Android Studio 打开 `android/`，跑到真机或模拟器。应用模块按 JDK 21 编译。首次构建时，Gradle 会下载 Node.js for Mobile Apps 的二进制（插件默认 Node.js 18.20.4）。这些文件不在 npm 包里。

Open `android/` in Android Studio and run it on a device or emulator. The app module compiles with JDK 21. The first build downloads the Node.js for Mobile Apps binaries through Gradle (Node.js 18.20.4 by default). They are not inside the npm package.

`npm run cap:sync` 等价于先 `npm run build` 再 `cap sync android`。

`npm run cap:sync` is `npm run build` followed by `cap sync android`.

## 目录 / Layout

```text
src/                         Vue 壳、CodeMirror 6、main.js 沙箱运行与控制台
src/plugins/grain-node.ts    Capawesome Nodejs 门面：waitReady / start / send / listeners
public/nodejs/               打进 webDir 的 Node 项目，package.json 的 main 是 index.js
capacitor.config.ts          appId、nodeDir、startMode、Android 调试开关
android/                     Capacitor Android 工程
```

品牌标记在 `public/favicon.svg`（页面图标）。源文件是 `src/assets/grain-symbol.svg` 和 `src/assets/grain-logo.png`。Android 启动图标与闪屏用的是同一枚标记。

The mark is `public/favicon.svg`. Sources are `src/assets/grain-symbol.svg` and `src/assets/grain-logo.png`. The Android launcher and splash use that same mark.

## Node 运行时 / Node runtime

`capacitor.config.ts`：

- `appId`: `app.grain.dev`
- `appName`: `Grain`
- `webDir`: `dist`。Vite 会把 `public/nodejs` 复制到 `dist/nodejs`
- `plugins.Nodejs.nodeDir`: `nodejs`（相对 webDir）
- `plugins.Nodejs.startMode`: `manual`
- `android.allowMixedContent` 与 `android.webContentsDebuggingEnabled` 为之后的 localhost 预览打开
- `server.cleartext` 允许明文 HTTP，同样只为本地预览。正式包不要开

插件是 [`@capawesome/capacitor-nodejs`](https://capawesome.io/docs/sdks/capacitor/nodejs/)。壳侧只用文档里的方法，没有自造原生接口：

- `Nodejs.start()`
- `Nodejs.isReady()` 和 `ready` 事件。门面把这两步包成 `waitReady()`
- `Nodejs.send({ eventName, args })`
- `Nodejs.addListener('message', ...)`

脚本要 `require('bridge')`，运行时才算就绪。`channel.on` 接收 `Nodejs.send`，`channel.post` 回到 `message` 监听。这一版的事件是 `grain:ping` 和 `grain:log`。

The shell only calls the documented plugin methods. The device script must `require('bridge')` before the runtime is ready. `channel.on` receives `Nodejs.send`, and `channel.post` comes back through the `message` listener. The stub events are `grain:ping` and `grain:log`.

Web 端插件方法会抛 `Unimplemented`。`grain-node.ts` 在浏览器里不调用它们，只在本地复现 ping → log，方便看界面。

On the web, the plugin methods throw `Unimplemented`. `grain-node.ts` does not call them in the browser. It only echoes ping → log locally so the UI can be checked.

### 限制 / Limits

这些来自插件和 nodejs-mobile，不是 Grain 的私有接口：

- 每个进程只能 `start` 一次。没有 `stop()`，也不能再启动。
- 不支持 `child_process`。不能在手机上 `spawn('npm')`。
- 用户项目的安装要以后在进程内做，例如 `@npmcli/arborist`。这一版没有安装器。
- 在桌面预先放依赖时，进 `public/nodejs` 执行 `npm install --omit=dev`，再 `npm run build`。官方也建议之后用 esbuild 打成单文件，加快启动。当前 Node 项目没有依赖。
- 持久文件放 `app.datadir()`。项目目录会在应用更新时被覆盖。
- iOS 工程没有生成。插件只支持 Swift Package Manager，不支持 CocoaPods。在 macOS 上再执行 `npx cap add ios`。iOS 二进制在 macOS 的 `npm install` 里下载；可以设 `CAPACITOR_NODEJS_SKIP_DOWNLOAD=1` 跳过。

These limits come from the plugin and nodejs-mobile:

- `start` runs once per process. There is no `stop()`, and the runtime cannot be started again.
- `child_process` is unavailable, so the phone cannot `spawn('npm')`.
- Installing user packages later has to happen in-process, for example with `@npmcli/arborist`. This milestone has no installer.
- To vendor dependencies on a desktop, run `npm install --omit=dev` inside `public/nodejs`, then `npm run build`. The plugin docs also recommend bundling that project with esbuild later. The Node project currently has no dependencies.
- Store persistent files in `app.datadir()`. The project directory is replaced when the app updates.
- The iOS project is not generated. The plugin supports Swift Package Manager only, not CocoaPods. On macOS, run `npx cap add ios`. iOS binaries download during `npm install` on macOS. Set `CAPACITOR_NODEJS_SKIP_DOWNLOAD=1` to skip that.

## 下一步 / Next

1. 在 Android 真机或模拟器上点「启动 Node」，确认终端里出现 bridge 就绪和 `datadir`，再 Ping。
2. 做进程内 npm 安装，不要走 `child_process`。
3. 让设备上的静态服务把项目页面放进预览 WebView。混合内容和 WebView 调试已经打开。

1. On an Android device or emulator, tap Start, confirm the bridge-ready log and `datadir`, then Ping.
2. Add an in-process npm install. Do not use `child_process`.
3. Serve the project into the preview WebView. Mixed content and WebView debugging are already enabled.
