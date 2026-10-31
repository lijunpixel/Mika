# Windows / Pixel 8：用 USB 安全连接 Mika MCP

此文档是 Mika 上游 [电脑连接](mcp.md) 的 Windows PowerShell 辅助工作流，不会替代上游的 MCP 协议，也不会把手机能力暴露到公网。

## 前提

- Windows PowerShell 5.1 或 PowerShell 7。
- 安装 [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)，确保 `adb` 在 `PATH` 中。
- Android 手机已安装含 USB MCP 功能的 Mika，至少手动打开过一次。
- USB 数据线已接好，手机已允许这台电脑进行 **USB 调试**。不要为了连接 Mika 修改无关的开发者选项，尤其不要开启可能触发系统重置的 16 KB 内存页大小切换。

## 一键建立连接

在仓库根目录的 PowerShell 中执行：

```powershell
.\tools\windows\mika-usb-mcp.ps1 -Action Connect -CheckHealth
```

连接多台手机时，指定 `-Serial`；手机序列号可用 `adb devices` 查看：

```powershell
.\tools\windows\mika-usb-mcp.ps1 -Action Connect -Serial YOUR_DEVICE_SERIAL -CheckHealth
```

默认把 Windows `127.0.0.1:8765` 转到手机的 Mika MCP。若该端口被占用，可以使用：

```powershell
.\tools\windows\mika-usb-mcp.ps1 -Action Connect -LocalPort 18765 -CheckHealth
```

仅在你要把连接信息粘贴进可信 MCP 客户端时，使用 `-ShowConfig`：

```powershell
.\tools\windows\mika-usb-mcp.ps1 -Action Connect -ShowConfig
```

这会打印带有 `Authorization: Bearer ...` 的 MCP JSON 配置。脚本**默认不打印令牌、不将令牌保存为文件**。请勿把带令牌的输出发到 GitHub、聊天群或终端日志中。

电脑端 MCP 服务类型：**Streamable HTTP**，地址为 `http://127.0.0.1:8765/mcp`（或你指定的其他本地端口）。令牌必须来自手机当前这次启动，旧令牌在重启或关闭连接后不可复用。

## 日常操作

```powershell
# 查看手机连接和转发状态，不显示令牌
.\tools\windows\mika-usb-mcp.ps1 -Action Status

# USB 重插或 adb 重启后，仅恢复端口转发，不轮换令牌
.\tools\windows\mika-usb-mcp.ps1 -Action RefreshForward

# 关闭手机端 MCP，并撤销属于这台设备的端口转发（自定义端口需传相同 -LocalPort）
.\tools\windows\mika-usb-mcp.ps1 -Action Close
```

**手机重启后**，重新执行 `Connect`，然后把新令牌更新到 MCP 客户端配置中。`-Action RefreshForward` 不能启动一份已被手机重启关闭的 MCP 服务。

## 安全与限制

- 默认只使用 `adb forward` + `127.0.0.1`，不要求局域网监听、Tailscale 对外开放或路由器转发。
- 端口已被另一台设备/其他映射占用时拒绝覆盖；可改用 `-LocalPort`。
- ADB 多设备时必须明确选设备，避免控制错误的手机。
- 连接脚本不负责解锁手机、不请求 PIN、不绕过需要你亲手处理的支付或登录。
- `-CheckHealth` 只发送 MCP initialize 请求，不下发真实操作任务。
- `Close` 需要设备当前在线才能吊销手机端令牌。使用过 `-LocalPort 18765` 时，关闭时也必须传入 `-LocalPort 18765`；拔线不等于关闭服务。
- 当前脚本中的 `Status`、`-Action RefreshForward` 读取现存连接信息：手机重启或 MCP 已关闭时应执行 `Connect`。
- 此脚本仅作 Windows 辅助工具，不会绕过 Android 的系统安全边界。

## 验收清单

1. 单设备、已授权时 `Connect -CheckHealth` 返回 `ADB forward: ready`，且 initialize HTTP 状态成功。
2. 未授权/离线设备明确报错；多设备但未指定 `-Serial` 时拒绝继续。
3. `Status` 不打印令牌；带 `-ShowConfig` 时才打印 JSON。
4. 端口被其他 ADB 映射占用时拒绝重绑。
5. 拔线再连接后 `-Action RefreshForward` 可恢复映射。
6. `Close` 后手机端令牌失效；重启手机后必须重新 `Connect`。

> 说明：当前这批补充文件属于 Fork 的增强功能。是否能在某台具体 Pixel 8 上运行，需要在真实 USB 连接环境中按照验收清单复测。
