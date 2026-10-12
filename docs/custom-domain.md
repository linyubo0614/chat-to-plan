# 自定义域名：先验证手机可达，再切换入口

如果电脑已显示“固定中转已连接”，手机开 VPN 能访问 `/healthz`，不开 VPN 却打不开，说明当前 `workers.dev` 地址在该手机网络下不可达。普通上网正常并不代表这个域名也能访问。

0.7.2 支持把自有域名绑定到原来的 Cloudflare Worker。购买域名后仍需实测手机直连；**更换域名不保证所有运营商都稳定可达**。如果绑定后依然无法直连，应考虑可直连的中转／服务器，继续排查具体网络条件。

## 一、在哪里购买

可以在 [腾讯云域名注册](https://cloud.tencent.com/product/domain)、[阿里云万网](https://wanwang.aliyun.com/) 或 Cloudflare Registrar 注册域名。腾讯云和阿里云更便于使用国内账号及支付方式；Cloudflare Registrar 的支付要求以其页面为准。

- 搜索自己喜欢的名称，例如 `suishoujishi.com`；是否可注册以查询结果为准。
- 先比较首年和续费价格，再购买。选择自己能管理的完整域名，而不是别人代管的共享子域名。
- 当前方案只需要域名，不必另买服务器、网站建站套餐或付费 CDN。
- 使用自己的账号注册，按平台要求完成验证。这里不需要把账号、密码或证件发给项目维护者。

本文下面用 `example.com` 作示例，实际操作替换成自己购买的域名。

## 二、把域名 DNS 接入 Cloudflare

1. 登录已部署中转的那个 Cloudflare 账号，选择添加域名／添加站点，填写 `example.com`，选择 Free 方案。
2. Cloudflare 会提供两条 nameserver（NS）地址。
3. 到域名注册商控制台找到域名的 DNS 服务器／nameserver 设置，把注册商原来的 NS 改成 Cloudflare 提供的两条地址。
4. 回到 Cloudflare，等待该域名状态变为 Active／已激活。DNS 生效和证书签发需要等待，不要在仍为 Pending 时开始配对。

这是修改 **NS 服务器**，不是创建两条名为 NS 的普通 DNS 解析记录。首次使用的新域名最简单；已有网站、邮箱等记录时须保留相应 DNS 配置。

参考：[Cloudflare 完整 DNS 接入步骤](https://developers.cloudflare.com/dns/zone-setups/full-setup/setup/)。

## 三、绑定现有 Worker

推荐使用子域名 `planner.example.com`，让根域名还能用于其他用途。有两种方式，任选其一。

### 从电脑程序绑定

1. 更新电脑完整 Windows 包，保留原 `data` 文件夹。
2. 点击“停止服务”。在“自定义域名（可选）”中填写 `https://planner.example.com`，点“保存入口”。不要加 `/healthz`。
3. 点击“配置 / 更新免费中转”。程序会在原 Worker 上添加自定义域名绑定，保留原 Worker 名称、凭据、记录和已有 `workers.dev` 地址。
4. 等待成功，点击“启动服务”，确认显示“固定中转已连接”。域名必须在同一 Cloudflare 账号中已经激活，程序不会替你购买或完成注册商的 NS 修改。

### 从 Cloudflare 控制台绑定

1. 在 Workers 和 Pages 中找到现有中转 Worker，名称形如 `chat-planner-xxxxxxxxxxxx`。不要另建一个新的中转 Worker。
2. 打开该 Worker 的设置，在“Domains & Routes／域名与路由”中添加 Custom Domain／自定义域名，填写 `planner.example.com`。
3. 等待绑定和 HTTPS 证书就绪。使用 Custom Domain 功能即可，不需要单独把子域名 CNAME 到 `workers.dev`。
4. 在电脑程序停止服务，填写 `https://planner.example.com`，保存入口并重新启动。

参考：[Workers Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/)。

## 四、手机不开 VPN 验证

保持电脑服务和中转连接运行，在手机浏览器打开：

```text
https://planner.example.com/healthz
```

1. 关闭手机 VPN，在实际使用的 Wi-Fi 下检查。
2. 再切换移动网络检查；若只在其中一种网络可用，应记录这一差异。
3. 返回 `{"status":"ok"}` 才说明手机到电脑的完整链路可用。
4. 在电脑重新显示配对二维码，手机扫描或粘贴新的完整配对链接。新版配对链接应包含自定义域名，不是原来的 `workers.dev`。

第一次切换域名需要让手机确认新入口。之后保留该域名、Worker 和 `data`，正常重启无需重新扫码；原来的任务与备忘数据不会丢失。

- 浏览器打不开：先检查 DNS、证书与该网络的可达性，不能通过修改 App 超时来解决。
- `Desktop offline`／HTTP 503：域名已访问到中转，但电脑未连接或正在忙，检查“启动服务”和中转状态。
- Hello World、HTML 或其他内容：绑定到了错误的 Worker／页面，核对现有中转名称。

清空电脑的自定义域名栏并保存，可恢复原来的 `workers.dev` 入口。HITA 依然是可选扩展，不影响任务、备忘或重要日期。
