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

仓库包含 `atlas_keys.properties`，默认构建会把 Speechmatics、高德和天气 API key 注入到 APK。

```powershell
cd android_apps/1.1_main
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-8.0.482.8-hotspot'
$env:ANDROID_HOME='C:\Users\<you>\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :atlasapp:assembleDebug
```

## ADB 安装

直接安装仓库中已构建好的 APK：

```powershell
adb install -r apks\atlasapp-debug.apk
```

如果你本地重新构建了，也可以安装新生成的 debug APK：

```powershell
adb install -r atlasapp\build\outputs\apk\debug\atlasapp-debug.apk
```

## 覆盖 API key

如果需要临时覆盖仓库里的 key，可以通过环境变量或未提交的 `local.properties` 配置：

```properties
speechmatics.api.key=your_key_here
amap.api.key=your_key_here
```
