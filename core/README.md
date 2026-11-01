# Mihomo Android 桥接

固定 Mihomo v1.19.32、Go 1.27.3。Android 使用 arm64-v8a / x86_64 AAR，
`with_gvisor` 包含原生 mesh 所需实现。依赖和许可证见 NOTICE.md、go.mod、go.sum。

## 运行边界

实际 VPN 使用原生 `ParseRawConfig → ApplyConfig → tunnel`，Android 继续拥有
TUN FD、网络绑定和 `VpnService.protect`。原生额外监听器、外部控制器、任意文件
路径、系统路由与 Geo 更新器由应用边界禁用，不依赖 localhost 作为认证保障。

离线导入、单节点验证和 URLTest 使用私有图，不安装完整配置或启用健康检查。
28 类 option 来自固定核心的结构；新增协议目前只有构造/字段测试，不能据此
宣称所有真实服务端互通。能力清单见 ../docs/mihomo-compatibility.json。

完整运行时支持原生 DNS/fake-IP、HTTP/TLS/QUIC 嗅探、DSCP、规则、子规则、
命名出站以及解码后的 domain/ipcidr/classical Provider。规则正则保留预算。
完整 VPN 构造不再额外创建私有 DNS、嗅探器和全部规则目标。

更新先验证候选代际，已有 TCP 保留旧适配器，新流量使用新代际，不重建 TUN。
原生 general、resolver 和 QUIC 环境在最终停止时恢复。UDP 使用原生源 socket
SNAT，多个目的地回包经受控测试；非原生队列仍有独立的合计字节预算。

## 私有数据与输出

`ImportProfile`、Provider 计划/缓存、运行 definition 和 `ExportConfiguration`
都是私有数据，可能含凭据，不能记录日志。导出仅在显式确认后进行，重建标准
inline 规则源、保留 DNS 策略顺序，剔除内部桥接字段。它不是脱敏报告。

`Probe`、`ProbeDelay`、启动/更新失败只返回固定代码及有界计数，不返回原始
异常、订阅地址或规则载荷。`VPNConnections` 默认不含目标；用户明确启用
目标查看后，才在本机展示目标、实际出站链及规则类型，不保存历史。

## 验证

从根目录运行 tools/verify-native-core.ps1、tools/verify-core.ps1、
tools/verify-localization.ps1 和 tools/verify-security.ps1。原生内部 ABI 与
executor/tunnel 源码哈希被固定；升级核心必须重做语义审查和 Android JNI 测试。
详细范围及设备局限见 ../docs/merge-audit-preview25.md、../docs/security-testing.md。
