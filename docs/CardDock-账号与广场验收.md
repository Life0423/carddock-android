# 卡坞账号与广场

用户可以直接浏览广场，在「我的 → 登录或注册」创建账号。注册成功即登录。游客点击上传、收藏或下载会进入同一个账号表单，成功后返回发起操作的页面。作者在「我的发布」查看已上架的模板和下载数，下架入口在广场模板行。

个人模板、空间卡片与本机订单授权保存在设备上。下载模板和收藏按登录账号分开。退出会清除本机登录凭据；离线退出时会提示服务端仍未确认撤销。联网改密会撤销该账号所有旧会话，本次操作的设备收到新会话。服务端会话期限为 30 天。

## 本地联调

在根工程用以下命令启动开发服务。SQLite 路径使用开发机自己的私有目录，保留该文件即可保留账号、发布、下载与排行。Debug APK 默认访问手机回环端口 8765；下面将它转到电脑上仅监听本机的 18765 端口。

```powershell
python -B tools/square/server.py --db "$env:LOCALAPPDATA/CardDock-square.db" --port 18765
```

另开终端，替换为当前授权的设备序列号。

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $adb -s <设备序列号> reverse tcp:8765 tcp:18765
```

发布构建通过 `carddockSquareEndpoint` 指定 HTTPS 服务。beta.8 内测包接入 `https://8.140.236.38`；手机直连，无需电脑端口转发。

## 内测服务与恢复

阿里云上的 `carddock-square.service` 用独立系统用户运行 Python 3.11，监听 `127.0.0.1:19865`。独立的 `carddock-edge.service` 在 443 提供 HTTPS，由 Caddy 管理 Let's Encrypt 短期 IP 证书与续期。80 端口继续由原站点提供服务。源文件在 `/opt/carddock-square/server.py`，边缘服务配置在 `/etc/carddock-square/Caddyfile`。核验公网连接可使用 `GET /templates`，空广场返回 `[]`。

`carddock-backup.timer` 每天把 SQLite 快照写入 `/var/lib/carddock-square/backups`。恢复演练用服务用户把选定备份复制到新的文件名，校验通过再安排切换。命令示例为 `python3.11 /opt/carddock-square/server.py --db 备份.db --backup-to 恢复演练.db`。已有目标文件会被拒绝覆盖。本机备份与数据库位于同一台服务器。

「我的 → 账号管理 → 注销账号」需要当前密码。成功后撤销该账号所有会话，删除账号、发布和下载记录，并清理本机对应的广场收藏与下载。个人模板和空间卡片独立保存在手机。

## 账号存储与兼容

密码由服务端使用独立随机盐和 scrypt 派生。数据库保存会话令牌的 SHA-256 摘要。Android 使用 Keystore 和 AES-GCM 原子保存会话，文件名按签发服务地址区分。模型配置继续使用原文件名、密钥别名和加密格式。

升级原广场数据库时，既有发布作者、下载者和旧配置中的账号名都会被保留。首次升级时继续提供原有的 `CARDDOCK_SQUARE_ACCOUNTS` 配置，可同时保留尚无发布或下载记录的旧用户名。普通注册无法取得这些账号。管理员核对旧用户身份后，可在开发机交互设置初始密码。

```powershell
python -B tools/square/server.py --db "<已有数据库路径>" --migrate-user "<旧账号名>"
```

迁移完成后，旧邀请码凭据失效；该账号可沿用本机按旧用户名保存的下载和收藏。新注册账号按服务端生成的独立 ID 存储本机副本。新部署运行用户名密码注册无需配置邀请码。
