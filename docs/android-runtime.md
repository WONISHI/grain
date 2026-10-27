# Grain 独立 Android 运行环境

产品约束：只安装 Grain；文件、Node、npm、Python、命令执行和预览均在手机本机。
界面沿用 Vue 3 + Capacitor，接近 VS Code 的工作区。无需 Termux 或云端服务。

## 当前实现与验证边界

- `GrainWorkspacePlugin` 注册为 Grain 自己的 Capacitor 原生插件。
- 文件存于 `files/workspace`，限制相对路径，UTF-8 读写；写入使用临时文件 + 原子重命名。
- 编辑器在 Android 加载设备文件，编辑后排队写回；执行命令前再次保存全部已知文件。
- 终端页是交互式 PTY：`/system/bin/sh -i` 跑在伪终端里，npm 能看到 TTY 并持续输出进度。Ctrl+C 发给前台进程组。
- 单次 `execute` 仍走管道，供测试调用。交互终端里的 `cd` 会保留。
- 停止按钮只终止直接子进程，尚未实现进程组、孙进程回收和后台服务生命周期。
- 网页不模拟终端，不使用桌面电脑上的 Node 冒充手机运行结果。
- Node 可执行文件以 `libgrain_node.so` 打进 APK。模拟器上直接执行返回 `v22.23.4`。npm 10.9.9 的 JS 打进安装包，由这份 Node 执行。V8 快照按 ARMv8.0 基线生成，不把编译机 Apple 芯片的 FJCVTZS、点积和 LSE 写进内置代码。旧二进制在没有这些指令的平板上，`npm -v` 会报 Illegal instruction。当前可执行段已不再包含 FJCVTZS。模拟器重新安装后 `npm -v` 仍返回 `10.9.9`。发版时平板没有连上调试器，所以还没有在那台设备上复测。
- 文件存在检测不等于子进程、npm 或 Vite 已验证。
- 前端构建通过。原生编译和设备验证需另外执行，不能从前端构建推断成功。

## 独立 Node 路线的验证门槛

现有 `@capawesome/capacitor-nodejs` 基于 Node 18.20.4，线程内运行且不支持
`child_process`，只能保留用于旧桥接实验，不能作为完整 npm 环境的验收结果。

Android arm64 的独立 Node 可执行程序已按原生库布局打进 APK，名字是
`libgrain_node.so`。这是可执行程序，不能把普通 `libnode.so` 改名后直接执行。
终端里的 `node` 会调用这份文件。已核对 ELF 解释器、动态库只依赖系统
`libc`/`libm`/`libdl`/`liblog`、16 KB 对齐，并在模拟器上跑通 `node -v`。

Node 源码提供 `android-configure`，但 Android 不在 Node 官方支持平台内，
所以交叉编译通过也不能替代设备测试。

npm 10.9.9 的 JS 放在 `files/runtime/npm`，终端里的 `npm` 和 `npx` 用内置 Node 执行这些文件。模拟器上 `npm -v` 返回 `10.9.9`。
`npm install -g` 的安装目录是 `files/runtime/prefix`。子进程需要的 `node` 命令是指向 `libgrain_node.so` 的符号链接，放在 `files/runtime/bin` 并加入 `PATH`。模拟器上这条链接可以执行，`npm install -g create-vue @vue/cli create-react-app` 已完成。
已核对：`create-vue --version` 为 `3.24.0`，`vue --version` 为 `@vue/cli 5.0.9`，`create-react-app --version` 为 `5.1.0`。终端启动时，以及每次 `npm` 命令结束时，会给全局 bin 建立同名 shell 函数，并用内置 Node 执行对应的 JS。Android 没有 `/usr/bin/env`，所以 `#!/usr/bin/env node` 不能直接当命令执行。直接执行这个脚本时，平板上的 shell 会报 `inaccessible or not found`。
带原生可执行文件的依赖仍然要单独验证。生成项目后的 `npm run dev` 还没有作为验收结果。

Git 2.51.0 以 `libgrain_git.so` 打进 APK，HTTPS 助手是 `libgrain_git_remote_http.so`（`git-remote-http` / `git-remote-https`）。终端里的 `git` 调用这份文件，模拟器上 `git -v` 返回 `git version 2.51.0`。`git init` 使用随包模板。已静态链接 OpenSSL 与 curl，并随包提供 `cacert.pem`（`SSL_CERT_FILE` / `GIT_SSL_CAINFO`），可用 `git clone https://...`。私有仓库仍需自行配置凭据。

CPython 3.13.7 以 `libgrain_python.so` 打进 APK，标准库在首次打开终端时解压到 `files/runtime/python`。终端 PATH 里的 `python` 和 `python3` 都指向这份解释器。扩展模块放在应用原生库目录，名字是 `libpyext_<模块名>.so`，因为 targetSdk 36 不允许从应用可写目录加载 `.so`。`ssl`、`hashlib`、`sqlite3` 随包提供。`pip` 没有打进安装包。

完整终端需要 JNI PTY、窗口尺寸、输入/控制键、进程组退出、后台生命周期。
当前 Java 管道实现为文件和命令通路的第一阶段验证，不作为最终终端后端。

## 构建准备

构建工具仅放在项目的 `.android-sdk`、`.gradle-local` 与 `.runtime-build`，
均不纳入版本控制。可复用本机 IDE 的 JDK 21，不修改系统 Java 配置。

已获取 Node 22.23.4 官方源码，SHA-256：
`7b9adc82d612d0a2a863d96c46de2292552e250cee51956ebbe0146c42aacae9`。
安装 NDK 27.0.12077973 后，可在 macOS 执行：

```sh
bash scripts/build-android-node.sh
```

脚本拒绝误打包 macOS 二进制，核对 ELF 后把剥离符号的可执行文件写到
`android/app/src/main/jniLibs/arm64-v8a/libgrain_node.so`。成功退出不代表 npm 验证通过。

## 真机验收清单

1. 冷启动后 Node/npm 版本来自手机实际进程；退出重启后文件仍存在。
2. Node 读写工作区，创建子进程并捕获退出码。
3. 安装纯 JS 包并运行 `require`/`import`；lockfile 与安装错误可见。
4. `npm run` 可正确调用本地 `.bin`，验证 install 生命周期脚本。
5. Vue 和 React Vite 项目启动，App 中打开本机 HTTP 服务，修改后 HMR 生效。
6. 停止后端口释放，重复运行不遗留进程；Ctrl+C 与交互式提示正常。
7. 包含原生可执行文件的依赖单列兼容性结果，不承诺任意 npm 包。

来源：
- https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- https://github.com/nodejs/node/blob/main/BUILDING.md#android
- https://capawesome.io/docs/sdks/capacitor/nodejs/
