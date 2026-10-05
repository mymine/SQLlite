# SQLlite · Android SQLite 数据库管理工具

一款基于 Jetpack Compose 的现代化 Android SQLite 数据库管理工具，拥有悬浮玻璃底栏与完整的数据管理能力：浏览与编辑数据、表结构管理、跨库对比、SQL 控制台，以及 SQL / CSV / TXT / JSON 多种格式的导入导出。

- **应用名**：SQLlite
- **包名**：`com.dbstudio.app`
- **当前版本**：1.3.0（versionCode 4）
- **运行环境**：Android 8.0+（minSdk 26），编译 SDK 37

---

## ✨ 功能特性

### 数据库管理
- 通过系统文件选择器打开任意 `.db / .sqlite / .sqlite3` 文件
- **内置文件浏览器**：可直接进入 `/storage/emulated/0` 等目录选择文件（首次需授予「所有文件访问」权限）
- 新建空数据库、最近打开记录、关闭数据库、将修改「写回原件」

### 表管理
- 表 / 视图列表，展示行数与列数
- **新建表**：可视化列编辑器（列名、类型、主键、非空），自动生成 `CREATE TABLE`
- **重命名表**、**删除表**（二次确认）
- 表详情：列结构、索引、建表语句、数据预览
- **添加列** / **删除列**（`ALTER TABLE ... ADD/DROP COLUMN`）

### 数据浏览与编辑
- 表数据分页浏览（每页行数可配置）、点击表头排序
- 单行新增 / 编辑 / 删除
- **表内搜索**：对所有列做模糊匹配（`CAST(col AS TEXT) LIKE ?`）
- **多选**：长按进入多选，支持全选 / 反选 / 批量删除 / 批量导出

### 数据库与表对比
- **表结构对比**：逐列比出新增 / 删除 / 变更（类型、NOT NULL、默认值、主键）
- **表数据对比**：按主键或整行匹配，标出新增 / 删除 / 变更的行与变更列
- **支持跨数据库文件对比**：左右两侧可各自选择不同的数据库文件与表

### SQL 控制台
- 多语句脚本执行（自动区分查询与写入）
- 载入 `.sql` 文件执行，结果以表格展示

### 导入 / 导出
- **导出**：CSV、TXT（制表符）、JSON、SQL（`INSERT` 语句）
- **导入**：CSV、JSON（自动识别并推断列）
- **整表 / 整库脚本导出**：仅表结构（`schema.sql`）或 表结构 + 数据（`dump.sql`）
- **SQL 脚本导入**：一键执行建表 + 插数据脚本

### 界面
- **悬浮玻璃底栏**（Haze 实时背景模糊 + 半透明胶囊 + 高光边框），随明暗主题自适应
- 5 个一级入口：数据库 · 数据 · 对比 · SQL · 设置
- 深色 / 浅色 / 跟随系统主题，渐变背景光晕
- 适配状态栏与导航栏安全区（Edge-to-Edge）

---

## 🧱 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| 语言 | Kotlin |
| 构建 | Gradle 9.7.1 + Android Gradle Plugin 9.4.1（内置 Kotlin） |
| UI | Jetpack Compose（Compose BOM 2026.09）+ Material 3 |
| 毛玻璃效果 | Haze 1.6.10（`haze` + `haze-materials`） |
| 数据库 | Android `SQLiteDatabase`（自封装 `SqliteEngine`） |
| JSON | `org.json` |
| 最低 / 目标版本 | minSdk 26 / targetSdk 36 / compileSdk 37 |

> 不使用任何 ORM 或第三方数据库库，所有 SQL 操作由 `SqliteEngine` 直接基于 `SQLiteDatabase` 实现。

---

## 📁 项目结构

```
DBStudio/
├── settings.gradle / build.gradle / gradle.properties
├── app/
│   ├── build.gradle
│   ├── dbstudio.jks                 # release 签名库
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dbstudio/app/
│       │   ├── MainActivity.kt              # 入口 Activity
│       │   ├── data/
│       │   │   ├── DatabaseRef.kt           # 数据库引用模型
│       │   │   ├── DbManager.kt             # 全局会话状态（单例）
│       │   │   ├── DbStore.kt               # 最近记录 / 偏好持久化
│       │   │   ├── AppPrefs.kt              # 可观察偏好
│       │   │   ├── db/
│       │   │   │   ├── Models.kt            # ColumnInfo / QueryResult / ColumnSpec ...
│       │   │   │   └── SqliteEngine.kt      # SQLite 核心封装（查询/DDL/DML/事务）
│       │   │   ├── io/
│       │   │   │   ├── CsvCodec.kt          # CSV / 分隔符文本
│       │   │   │   ├── JsonCodec.kt         # JSON 编解码
│       │   │   │   ├── SqlScript.kt         # SQL 脚本拆句
│       │   │   │   └── SqlExport.kt         # 表结构 / 表+数据 导出
│       │   │   └── diff/
│       │   │       ├── DiffModels.kt        # 差异模型
│       │   │       └── Differs.kt           # 结构对比 / 数据对比
│       │   ├── ui/
│       │   │   ├── AppRoot.kt               # 根布局（玻璃底栏 + 页面调度）
│       │   │   ├── Screen.kt                # 页面定义与导航栈
│       │   │   ├── components/
│       │   │   │   ├── GlassBottomBar.kt    # 悬浮玻璃底栏
│       │   │   │   ├── StorageBrowser.kt    # 内置文件浏览器
│       │   │   │   ├── SelectionBar.kt      # 多选操作条
│       │   │   │   └── Common.kt            # 卡片 / 按钮 / 搜索框 / 对话框
│       │   │   ├── screens/
│       │   │   │   ├── DatabasesScreen.kt   # 数据库页
│       │   │   │   ├── DataScreen.kt        # 数据页
│       │   │   │   ├── DiffScreen.kt        # 对比页
│       │   │   │   ├── SqlScreen.kt         # SQL 控制台
│       │   │   │   ├── TableDetailScreen.kt # 表详情
│       │   │   │   └── SettingsScreen.kt    # 设置页
│       │   │   └── theme/Theme.kt           # 配色与主题
│       │   └── util/DbFileHelper.kt         # SAF 与本地文件转换
│       └── res/                             # 图标 / 主题 / 字符串
```

---

## 🚀 构建

### 环境要求
- JDK 17+（推荐 21）
- Android SDK：platform `android-37`、build-tools `37.0.0`
- Gradle 9.7.1（AGP 9.4.1 要求 Gradle ≥ 9.6）

### 命令行构建

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk

# 调试包
gradle :app:assembleDebug

# 发布包（已配置 release 签名，见 app/build.gradle）
gradle :app:assembleRelease
```

> 本项目的 `buildDir` 会被重定向到 `/opt/grbuild`（在 `build.gradle` 中配置），以规避某些环境下工作区文件系统随机写性能较差的问题。如不需要可自行移除该行。

release 签名信息（演示用）：keystore `app/dbstudio.jks`，别名 / 口令均为 `dbstudio`。**正式发布请替换为你自己的签名。**

---

## 📌 使用提示

1. **访问 SD 卡目录**：首次点击「浏览存储」会跳转设置页，请授予「所有文件访问（MANAGE_EXTERNAL_STORAGE）」权限，之后即可浏览 `/storage/emulated/0`。若不想授权，也可继续使用系统文件选择器。
2. **编辑外部文件**：通过系统选择器打开的文件会先复制为本地工作副本，「写回原件」可将改动保存回原文件。
3. **敏感操作**：删除表 / 删除列 / 批量删除行均不可撤销，界面均有二次确认。

---

## 📝 版本历史

| 版本 | 内容 |
| --- | --- |
| 1.0.0 | 首个版本：数据库管理、数据浏览编辑、SQL 控制台、表/数据对比、玻璃底栏 |
| 1.1.0 | 首页美化；内置存储浏览器（修复 SD 卡访问）；表/数据搜索；支持两库对比 |
| 1.2.0 | 适配状态栏与导航栏；数据页对齐修复；表管理（建/删/改表、增删列）|
| 1.3.0 | 表结构 / 表+数据 的 SQL 导出导入；表与数据行的多选（全选 / 反选 / 批量操作） |

---

## 📄 许可

本项目为演示用途，可自由修改与使用。
