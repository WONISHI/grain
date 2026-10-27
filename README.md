<p align="center">
  <img src="src/assets/grain-symbol.svg" alt="Grain" width="96" height="96" />
</p>

<h1 align="center">Grain</h1>

<p align="center">面向 Android 的本机开发环境</p>

**Grain** 只装一个 App，就能在平板 / 手机上打开本地文件夹、编辑代码、跑终端，并预览本机启动的开发服务。

产品定位接近「装在口袋里的 VS Code 工作区」——文件、Node、npm、Shell 与预览都在设备本地完成，不依赖 Termux，也不把桌面端运行结果伪装成手机结果。

| | |
| --- | --- |
| 平台 | Android（Capacitor）优先；浏览器可用于界面开发 |
| 界面 | Vue 3 + TypeScript，Monaco Editor |
| 包名 | `app.grain.dev` |
| 版本 | `0.1.3`（npm） |

---

## 功能概览

### 工作区

- 欢迎页打开本地文件夹，支持最近打开记录
- 资源管理器：树形浏览、新建 / 重命名 / 删除 / 多选移动
- Monaco 编辑器：多标签、语言高亮、Emmet
- 全文搜索：活动栏搜索视图，支持大小写 / 全字 / 正则（桌面端 `@vscode/ripgrep`，Android 原生扫描）
- 设置页与 VS Code 风格布局（活动栏、侧栏、底栏）

### 运行与预览

- 一键启动：优先 `package.json` 脚本，其次 `index.html` / 静态服务
- 浮动预览窗口：地址栏、刷新、可拖拽缩放
- 内置开发者工具：元素检查、控制台、网络面板（含请求详情）

### Android 本机运行时

- 工作区文件：`files/workspace`，UTF-8 读写，原子写入
- 交互式终端：PTY + xterm.js，`Ctrl+C` 发往前台进程组
- 内置 **Node.js**（`libgrain_node.so`）与 **npm**
- 内置 **Python 3.13**（终端里的 `python` / `python3`）
- 内置 **Git**（本地操作 + `git clone https://...`；私有仓库需自行配置凭据）

更细的运行时约束与验收清单见 [docs/android-runtime.md](docs/android-runtime.md)。

---

## 技术栈

| 层 | 技术 |
| --- | --- |
| UI | Vue 3、Vite、TypeScript |
| 编辑器 | Monaco Editor、`emmet-monaco-es` |
| 终端 | `@xterm/xterm`、原生 PTY |
| 原生桥 | Capacitor 8、`GrainWorkspace` 插件 |
| Android | JDK 21、Android SDK 36、Gradle |

---

## 快速开始

### 环境要求

- Node.js 20+（建议 LTS）
- 打包 Android 时需要 JDK 21 与 Android SDK（可使用仓库内 `.android-sdk`，已 gitignore）

### 安装与开发

```sh
npm install
npm run dev
```

开发服务器默认：`http://127.0.0.1:43127`。

浏览器模式主要用于界面联调；完整文件 / 终端 / Node 能力请在 Android 设备或模拟器上验证。

### 构建 Web 并同步到 Android

```sh
npm run cap:sync
# 等价于：npm run build && npx cap sync android
```

### 打包 Debug APK

```sh
npm run apk
```

产物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

该脚本会构建前端、同步 Capacitor，再执行 `assembleDebug`。**不会**自动安装到设备；安装示例：

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 常用脚本

| 命令 | 说明 |
| --- | --- |
| `npm run dev` | 启动 Vite 开发服务器 |
| `npm run build` | 类型检查 + 生产构建 |
| `npm run cap:sync` | 构建并同步到 Android 工程 |
| `npm run apk` | 产出可安装的 debug APK |
| `npm run bundle:watcher` | 打包文件监听相关资源 |

---

## 仓库结构

```text
src/                              前端应用（编辑器、终端、预览、欢迎页）
  components/                     UI 组件
  plugins/workspace.ts            GrainWorkspace 原生接口类型
  editor/                         Monaco 封装与语言配置
  runtime/                        静态服务等运行时辅助
android/app/src/main/java/        原生文件、终端、网络事件等实现
scripts/                          APK 打包、Node 交叉编译等脚本
docs/android-runtime.md           Android 运行环境说明与验收边界
experiments/nodejs-mobile/        已归档的旧 Node 桥接实验（不参与正式构建）
```

本地工具目录（均已忽略）：`.android-sdk`、`.gradle-local`、`.runtime-build`。

---

## 设计原则

1. **本机优先**：编辑、保存、执行与预览都在设备上发生。
2. **诚实反馈**：不伪造安装成功或桌面端 Node 输出。
3. **渐进能力**：纯 JS 工作流优先保障；带原生二进制的 npm 包单独验证，不承诺任意包可用。
4. **熟悉交互**：布局与快捷操作贴近 VS Code，降低上手成本。

---

## 已知限制

- 停止运行目前主要终止直接子进程；完整进程组 / 后台服务回收仍在完善中。
- 含原生扩展的 npm 依赖需按设备单独验证。
- `npm run dev`（Vite 等）在真机上的完整 HMR 验收仍以设备实测为准。

---

## 许可证

私有项目（`private: true`）。如需开源或分发，请另行补充许可证条款。
