# 🚲 锁车了吗 (BikeLocker) - 校园青桔单车防超时扣费助手

专门为校园短途骑行（无固定停车点、不连校园 Wi-Fi）量身定制的轻量级 Android 防遗忘锁车提醒应用。

---

## 💡 为什么需要它？

买青桔单车月卡后，单次骑行可抵扣 5 元。但校园内骑行到达宿舍、教学楼或食堂后，常因拔腿就走而忘记在滴滴 App 里手动关锁或网络延迟未扣款成功，导致车在原地静止却按分钟一直计费（每次被动多花 1~3 元）。

**传统提醒的弊端：**
* ❌ **固定地点（地理围栏）**：校内停放点每天都不一样，根本定不了固定 GPS 范围。
* ❌ **连接 Wi-Fi 触发**：很多人在校内走动不常连 Wi-Fi。
* ❌ **固定倒计时**：每次骑行距离不同（有时 3 分钟，有时 15 分钟），时间定长了多扣费，定短了骑行中乱叫。

**BikeLocker 的解决方案：**
* ✅ **GPS 车速监测**：识别骑行中（> 7.5 km/h）。
* ✅ **90 秒防误判缓冲期**：校内等红绿灯、避让行人停滞时，重新起步自动恢复，**绝不误报**。
* ✅ **硬件步频传感器识别离车**：一旦车速归零且检测到你**迈步离开超过 30 步**，立即判定你已到达目的地，当场触发**SOS 节奏强震动 + 全屏亮屏弹窗**（无语音播报，避免校园教室/图书馆走廊尴尬）！
* ✅ **按需激活，平时后台 0 耗电**：骑车时开启，还车后彻底退出，不常驻后台。

---

## 🛠️ 项目架构与源码说明

全部源代码均配有极为详尽、通俗易懂的中文注释：

| 模块文件 | 核心职责 |
| :--- | :--- |
| 模块文件 | 核心职责 |
| :--- | :--- |
| [`MainActivity.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/MainActivity.kt) | 极简仪表盘界面：一键启停守护、实时显示车速/下车步数/防误判倒计时、体验强震动与弹窗、开启滴滴自动感应 |
| [`DidiAutoDetectService.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/service/DidiAutoDetectService.kt) | 滴滴前台打开自动感应常驻服务：息屏深度休眠 0 耗电，亮屏检测到打开滴滴自动激活守护 |
| [`AppUsageHelper.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/detector/AppUsageHelper.kt) | 顶层应用检测工具：基于 UsageStatsManager 检测滴滴前台运行与授权状态 |
| [`MotionStateMachine.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/detector/MotionStateMachine.kt) | 核心算法状态机：严谨裁决【骑行 -> 疑似停下 -> 下车步行 -> 触发强报警】完整生命周期 |
| [`RideMonitorService.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/service/RideMonitorService.kt) | 前台守护服务：锁屏保活，统一管理 GPS 测速与硬件计步传感器事件 |
| [`AlertHelper.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/alert/AlertHelper.kt) | 强力报警器：触发节奏强震动、点亮屏幕与高优先级通知（静音纯震动模式） |
| [`NotificationActionReceiver.kt`](file:///c:/Users/29012/Desktop/BikeLocker/app/src/main/java/com/campus/bikelocker/receiver/NotificationActionReceiver.kt) | 通知栏操作响应：点击“我已锁车”立即销毁服务防耗电；点击“查滴滴”直达 App |

---

## 🚀 如何生成 APK 并安装到小米手机？

为了让你最省心、无需在自己电脑上折腾庞大的 Android SDK 与 Java 开发环境，本项目内置了 **GitHub Actions 云端自动编译脚本**（完全免费）：

### 方法 A：利用 GitHub 免费云端打包（最推荐，耗时约 2 分钟）
1. 在 GitHub 上新建一个仓库（可设为私有 Private 或公开 Public）。
2. 在本机 `BikeLocker` 文件夹内打开终端，运行以下 3 行命令推送代码：
   ```bash
   git init
   git add .
   git commit -m "feat: init BikeLocker app"
   git remote add origin 你的GitHub仓库地址.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库页面，点击上方的 **Actions** 标签页，你会看到名为 `自动编译生成 BikeLocker APK` 的工作流正在自动运行。
4. 约 1.5 分钟构建完成后，点击该次构建，在页面底部的 **Artifacts** 处直接点击下载 **`BikeLocker-App-Debug.zip`**。
5. 解压后将 `app-debug.apk` 发送到微信传输助手或 QQ，在小米手机上点击安装即可！

### 方法 B：使用 Android Studio 本地编译
如果你电脑上安装了 Android Studio：
1. 打开 Android Studio，选择 **Open**，选中 `c:\Users\29012\Desktop\BikeLocker` 目录。
2. 待 Gradle 同步完成后，手机开启 USB 调试连接电脑，点击绿色的 **Run (运行)** 按钮，即可直接将 App 烧录进手机。

---

## 📱 小米手机（HyperOS / MIUI）必做保活与授权设置

小米系统的后台省电策略非常激进，为防止手机放入口袋息屏后被系统误杀后台导致无法提醒，安装好 App 后完成以下设置：

1. **允许自启动**：
   * 打开「设置」->「应用设置」->「应用管理」-> 找到「锁车了吗」-> 开启【自启动】。
2. **省电策略设为无限制**：
   * 在「锁车了吗」的应用信息页向下滚动，找到【省电策略】-> 勾选【无限制】。
3. **开启「使用情况访问权限」（如果开启滴滴自动感应）**：
   * 打开 App 内的「滴滴打开自动感应」开关，在弹出的系统设置中找到【锁车了吗】，勾选【允许访问使用记录】。
4. **多任务界面加锁**：
   * 打开本 App 后，从屏幕底部上滑呼出多任务后台界面；
   * 长按「锁车了吗」卡片，点击弹出的小锁图标【🔒】，锁定常驻后台。

---

## 🚴 校园日常使用指引

1. **扫码取车**：
   * 找到青桔单车，扫码开锁；
   * 顺手点击桌面的「锁车了吗」大按钮【开启骑行防遗忘监测】（或通过通知栏快捷启动），然后把手机放进口袋即可，无需保持亮屏。
2. **骑车途中**：
   * 途中即使遇到路口红灯停下 30~60 秒，系统会自动进入“90秒防误判缓冲期”，重新起步后自动恢复，绝不乱报。
3. **到达目的地**：
   * 停好车，拔腿走向宿舍或教学楼；
   * 只要走出 **30 步左右**，手机会在裤兜里剧烈节奏强震动并亮屏弹出横幅：**“🚨 青桔单车锁车了吗？检测到你已下车步行超30步，请在滴滴确认关锁！”**
   * 点开通知上的【查滴滴】确认关锁，或者在手动锁好后点击【我已锁车】，监测服务立刻自动销毁，零多余耗电！
