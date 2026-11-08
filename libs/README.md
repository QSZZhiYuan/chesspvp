# 开发依赖

本目录不提交 Touhou Little Maid JAR。依赖文件名、来源与 SHA-256 见 `dependency-source.json`。

在仓库根目录执行 `python scripts/prepare_dependency.py`；没有公开下载地址的分支需加 `--file /path/to/maid.jar`。
Gradle 构建也支持 `-Pmaid_jar=/absolute/path/to/maid.jar`。
