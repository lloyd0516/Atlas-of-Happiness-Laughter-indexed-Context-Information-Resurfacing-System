# Atlas 2.0 中文开发者 README 设计

## 目标读者

README 面向首次接触仓库的合作开发者。目标是让其在不通读全部 Java 源码的情况下，
理解 Atlas 2.0 的产品目标、页面功能、采集与回顾数据流、文件输入输出、关键组件、
本地构建方式及开发约束。

README 不是研究论文、参与者使用手册或完整 API 文档。

## 内容结构

README 采用“快速建立产品心智模型，再进入技术细节”的顺序：

1. 项目定位与最新版分支说明；
2. 核心用户流程；
3. 页面和功能导航；
4. Mermaid 系统架构与数据流图；
5. 系统输入、处理和输出；
6. Moment JSON 核心结构；
7. Daily 与 Location resurfacing 机制；
8. 关键类、资源和目录导航；
9. 环境要求、API Key 配置和构建命令；
10. Android 权限与 USB 摄像头硬件依赖；
11. 单元测试和 APK 构建；
12. 常见问题、已知限制、隐私与协作约定。

## 写作与展示规则

- 使用中文正文，保留类名、字段名、命令和必要的英文术语。
- README 顶部展示当前 launcher 图标。
- 使用一张 Mermaid flowchart 表达采集、持久化、回顾和通知之间的关系。
- 使用表格说明页面职责、核心组件和权限用途。
- 提供精简且真实的 Moment JSON 示例；不复制完整事件文件。
- 所有构建命令必须与当前 Gradle 模块和 Java/Android 技术栈一致。
- 明确 `user-study-prototype` 是当前 2.0，`master` 下 1.1 不是最新版。
- 明确 “push/resurfacing” 是 Android 本地通知，不是远程推送服务。

## 事实与安全边界

- 仅描述当前源码已经实现的功能，不承诺不存在的后台服务、云同步或跨设备能力。
- 不写入真实 Speechmatics、AMap、OpenWeather 或其他 API Key。
- 不写入个人 Android SDK、JDK 或文件系统路径。
- API Key 示例只使用环境变量名和 `local.properties` 属性名。
- 不添加不存在的截图、CI badge、Release 链接或许可证声明。
- 说明事件数据位于 App 私有外部存储下的 `joyful_moment` 目录，并以 JSON 和媒体文件为
  source of truth。
- 说明 location reminder 在真机上受权限、GPS、系统后台限制和厂商省电策略影响。

## 验收标准

- 新开发者能从 README 找到记录、补充、回顾、删除和提醒功能的入口。
- 采集链路从 USB/PCM 输入到 laughter detection、事件聚合和 JSON 输出有清晰说明。
- `save_push` 与 `save_no_push` 的通知资格差异有明确说明。
- Short、Long、Location 三条 resurfacing 路径及其跳转目标有明确说明。
- 构建、测试和 API Key 配置命令可直接复制，并且不泄露凭据。
- Markdown 无断链、本地绝对路径、占位符或与当前代码不一致的版本信息。
- README 修改完成后运行 Markdown 静态检查、链接检查和项目单元测试。
- 所有改动保留在本地，未经用户明确许可不得 push。
