# Chess PVP Addon

为 Touhou Little Maid 添加玩家棋类对战与抱棋盘玩法。当前分支：**Minecraft 1.20.1 / Forge**，附属版本 **1.0.3**。

## 版本分支

| 分支 | Minecraft | 加载器 | JDK |
| --- | --- | --- | --- |
| [`main`](https://github.com/QSZZhiYuan/chesspvp/tree/main)（默认） | 1.21.1 | NeoForge | 21 |
| [`1.20.1-forge`](https://github.com/QSZZhiYuan/chesspvp/tree/1.20.1-forge) | 1.20.1 | Forge | 17 |
| [`26.2-neoforge`](https://github.com/QSZZhiYuan/chesspvp/tree/26.2-neoforge) | 26.2 | NeoForge | 25 |

## 玩法

- 支持五子棋、国际象棋和中国象棋；空手右键无人入座的棋盘，先后加入玩家一和玩家二。
- **Shift + 左键**抱起整副棋盘，保留棋局与原位置，不占物品栏。
- 抱起后左键砸向玩家或女仆，棋盘回到原位；原本轮到对手时获得一次落子机会。
- 第一、第三人称显示携带棋盘与当前棋子，并调整玩家双臂姿势。
- 赢得任意一种棋类对局，可解锁 **「棋圣 / Chess Sage」**；兼容原模组的玩家对女仆获胜事件。
- 女仆入座时使用原模组对局逻辑；右键继续用于下棋。

## 安装

客户端与服务端均需安装本分支的附属和对应 Minecraft/加载器版本的 **Touhou Little Maid 1.5.3**。
升级时同步替换旧附属包。不同 Minecraft 分支的 JAR 不通用；`-sources.jar` 不是游戏模组文件。

## 构建

使用 **JDK 17**。工程使用 Java toolchain，不包含本机 JDK 绝对路径。

先准备对应版本的 Touhou Little Maid 开发依赖（Python 3，仅标准库）：

```sh
python scripts/prepare_dependency.py
```

脚本从官方 Modrinth CDN 下载清单中固定版本，并校验 SHA-256。
也可以自行准备兼容前置，通过 `-Pmaid_jar=/absolute/path/to/maid.jar` 指定，跳过清单脚本。

```sh
./gradlew build
```

Windows 使用 `.\gradlew.bat build`。产物位于 `build/libs/`。
Gradle wrapper 已纳入版本控制；前置 JAR、构建目录、开发存档、日志和 IDE 文件不提交。
前置仅用作开发/运行依赖，不嵌入附属 JAR。

## 验证

1.0.3 的成就条件已核对前置真实触发器，并静态检查全部 16 种条件组合。
本轮整理仅编译与静态检查，未启动客户端或服务器；详细记录见 [VERIFICATION.md](VERIFICATION.md)。

## 更新记录与许可

更新内容见 [CHANGELOG.md](CHANGELOG.md)。附属元数据保持 **All Rights Reserved**；MDK 模板许可见 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。
模型和贴图由 Touhou Little Maid 前置提供，本仓库不复制或分发前置二进制文件。
