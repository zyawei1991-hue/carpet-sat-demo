# Screen-Link 传输规范 v0.1

消息格式见 `screen-link.schema.json`，本文件只讲怎么传。

## 拓扑

手机 App 是**服务端**，展示端是**客户端**。反直觉，但正确：App 是数据源，且展示端不需要被外部寻址。

```
展示端(1.75-B)  ──Wi-Fi──►  手机 App (HTTPS + WSS, :8443)
      │
      └──Wi-Fi──►  小智官方云（语音 ASR/LLM/TTS，与上面完全独立）
```

两条 Wi-Fi 连接走同一个 AP，互不依赖。见 DECISIONS D2/D4。

## 通道

| 通道 | 协议 | 用途 | 频率 |
|---|---|---|---|
| 控制面 | WebSocket (WSS) | `hello` / `state` / `event` / `command` / `ack` | state 1 Hz；event 即时 |
| 批量面 | HTTP GET (HTTPS) | `track_pull` 返回轨迹点数组、围栏 GeoJSON、地图瓦片 | 按需 |

## 会话建立

1. 展示端启动 → 读配网信息 → TCP 连 `:8443`
2. TLS 握手，**展示端不校验证书**（自签，演示设备）
3. 展示端发 `hello`，带 `screen_id` / `fw` / `dock_type` / `protocols`
4. App 校验 `protocols` 含 1 → 回 `ack`，开始 1 Hz `state` 推送
5. 版本不兼容 → App 回 `ack` 带 `error: "proto_mismatch"`，展示端显示黄屏而不是崩溃

## 保活与断线

- App 每 1 Hz 发 `state`，这本身就是心跳。**3 s 没收到 state，展示端把链路图标变灰**；10 s 没收到则重连
- 展示端不主动发心跳，省流量省电
- 重连退避：1s / 2s / 5s / 10s / 30s 封顶
- 断线期间展示端**保留最后一帧 state 并打灰色水印「链路中断 mm:ss」**，不要黑屏。演示时黑屏等于事故

## 可靠性

- `event` 必须回 `ack`（带 `for_event_id`）。App 3 s 未收到 ack 重发一次，再 3 s 放弃并记日志
- `state` 不重发、不去重，靠 `seq` 单调递增检测丢帧。丢了无所谓，下一秒就是新的
- 展示端按 `event_id` 去重，保留最近 32 个 id（环形缓冲，够小）

## 安全边界

这套服务**只在局域网内监听，且只有演示设备能连**。但仍然是个无认证的 TLS 服务端，能读车主位置。所以：

- 默认只 bind `127.0.0.1`，演示时通过 App 设置页显式开启局域网监听
- 开启时 `hello` 必须携带预共享 `screen_id`（出厂烧录，App 侧白名单），不在白名单直接断
- 演示结束后设置页关掉，或 App 退出即关
- 不要把这套服务部署到公网。**任何情况下都不要。** 需要远程查看就等真正的业务后端

## 待办

- [ ] App 侧 HTTP 服务选型：NanoHTTPD（轻）vs Ktor（和 Kotlin 协程顺）——倾向 Ktor，W2 定
- [ ] 展示端固件的 WebSocket 客户端：小智固件已依赖 `esp_websocket_client`，复用，不引新库
- [ ] 轨迹点数组的压缩：1 Hz 采样 2 小时 = 7200 点，JSON 太大，考虑抽稀到 30s 或走二进制
