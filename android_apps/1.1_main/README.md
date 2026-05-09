# Atlas Android App 1.1 Main

这是当前稳定的 Android App 版本，主 module 为 `atlasapp`。

详细说明请阅读：

```text
README_APP_PIPELINE.md
```

聚合逻辑图：

```text
docs/atlas_1_1_pipeline.svg
```

## 快速构建

```powershell
cd android_apps/1.1_main
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :atlasapp:assembleDebug
```

## 安全说明

仓库不提交真实 Speechmatics API key。请在 App Settings 中填写，或通过环境变量注入：

```powershell
$env:SPEECHMATICS_API_KEY='your_key_here'
```
