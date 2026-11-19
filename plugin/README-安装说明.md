# pet-brain 宿主插件 · 安装说明

## 一句话装好（只看这段就够了）

先把插件导出到共享区（设置页「大脑 → 宿主插件 → 导出插件到共享区」），
然后**把下面这句话原样丢给 DSHBox 的助手**：

> 把共享区里的 pet-brain 插件装进 profile

它会把两份文件放到该放的地方、改好 `package.json`。装完**完全退出 DSHBox 再打开**
（只退到后台不算），浏览器打开 `http://127.0.0.1:3080/pet-brain/health`，
看到 `{"ok":true,...}` 就成了。

不想手打，也可以把整份说明丢给它：
「照 `/sdcard/DSHBox/pet-brain-plugin/README-安装说明.md` 把 pet-brain 插件装进 profile」。

**下面是自己动手时才需要看的**：先决条件、导出后长什么样、三步手工安装、验收、排障。
如果上面那句话已经办完，剩下的都可以跳过。

---

这是桌面宠物「宿主模式」的宿主半边。装上之后，宠物说话用的是 DSHBox 已经登录的模型，
不需要在 App 里填 API Key。

对应 App 版本：**1.165 及以后**（插件自身版本 1.1.2）。

这份插件就装在 APK 里（`assets/pet-brain-plugin.zip`），不用另找文件；
设置页「大脑 → 宿主插件 → 导出插件到共享区」会把当前这份解到
`/sdcard/DSHBox/pet-brain-plugin/`，下面照着做即可。

没装它的时候，宠物会提示「连不上大脑」，因为 `http://127.0.0.1:3080/pet-brain/*` 没有东西应答。

## 这几版多了什么（1.1.0 → 1.1.2）

- 新路由 **`POST /pet-brain/compress`**：把最近一批原话压成 1–3 条要点，每条带等级 1–5
  （3 / 7 / 15 / 30 天 / 永久）。压缩由 App 攒够 10 条或超过 6 小时触发一次，条目存在 App 里。
- `/pet-brain/say` 接受一个新的 **`digest`** 字段：App 把没过期的要点按 600 字预算塞进来，
  插件拼在 system 里，提示词要求「该用就用，别逐条复述」。
- 请求日志多了 `digest=…chars`（这次带了多少字摘要）和 `compress turns=… items=… ms=…`，
  远程核对就看这两行。
- **1.1.1**：`/pet-brain/compress` 的 `maxTokens` 从 300 提到 **2000**。300 会被推理模型的思考吃光，
  接口常常返回空、压缩一直失败（日志里是 `compress failed … finish={"kind":"max-tokens"}`）。
- **1.1.2**：碎碎念也进上下文——`/say` 的历史取最后 **8** 条（原 6），system 里加了
  「不要重复你已经说过的句子或同样的意思」；`COMPRESS_PROMPT` 要求把同一类碎碎念合并成一条。

**改过插件必须完全退出 DSHBox 再打开**，宿主只在启动时加载插件。

## 先决条件：DSHBox 的共享区要打开

导出按钮写的是 DSHBox 挂出来的那个共享区。**先在 DSHBox 里把「共享区」打开**，手机上才会出现

```
/sdcard/DSHBox/
```

这个文件夹；在 DSHBox 的容器里它同时是 `/root/share/DSHBox/`，两边是同一份文件。
没打开的话，导出会直接提示「找不到共享区」，不会乱写别的地方。

**没识别到共享区**时，按权限分两条退路：

- 已经开了「所有文件访问」：落到系统下载目录 `/sdcard/Download/pet-brain-plugin/`，好找也好搬；
- 没开这个权限：落到 App 自己的外部目录

```
/sdcard/Android/data/com.dsh.pet/files/share/pet-brain-plugin/
```

App 目录这条不需要任何权限，拷出来一样能用；把 `pet-brain-plugin` 文件夹挪到下面说的位置即可。

另外，Android 11 起往共享区写文件需要「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）。
点导出按钮时如果还没开，App 会把你送到该开的那个设置页，开完回来再点一次就行。
Android 10 及以下只需要允许普通的存储权限。

## 导出出来是什么样

设置页「宿主插件」→「导出插件到共享区」，落地的目录树是：

```
/sdcard/DSHBox/pet-brain-plugin/
├─ README-安装说明.md   ← 本文件
└─ plugin/              ← 要装的就是这个文件夹
   ├─ package.json
   ├─ cordis.patch.yml
   └─ lib/index.js
```

## 装在哪

插件是 Node 代码，装在 **DSHBox 的 DSH 运行时**里，不是装在手机系统里。

下面的路径都是 DSHBox 容器里的路径。可以打开 DSHBox 的终端自己敲，也可以直接对 DSH 助手说
「把共享区里的 pet-brain 插件装进 profile」，把这份说明丢给它。

前两步只是往两个地方放文件，第三步才动 `package.json`。**先放文件、最后改清单**：`bundles`
里写了包名而宿主找不到那份代码时，DSHBox 会连带起不来。

**1. 放插件**（共享区已经挂好，一条 `cp` 就够了）

```
mkdir -p /root/projects/.dsh/dshbox/dshbox-plugins/pet-brain
cp -r /root/share/DSHBox/pet-brain-plugin/plugin /root/projects/.dsh/dshbox/dshbox-plugins/pet-brain/plugin
```

**2. 再放一份给 Node 解析**

```
mkdir -p /root/projects/.dsh/profiles/web/node_modules/@local
cp -r /root/projects/.dsh/dshbox/dshbox-plugins/pet-brain/plugin \
      /root/projects/.dsh/profiles/web/node_modules/@local/pet-brain
```

`bundles` 里写的是包名，`require` 是从 profile 的 `node_modules` 里找的。这里不跑
`pnpm install`，`file:` 依赖不会自己建链接，所以这份必须手动放。

**3. 登记**

先备份：

```
cp /root/projects/.dsh/profiles/web/package.json \
   /root/projects/.dsh/profiles/web/package.json.bak
```

再编辑 `/root/projects/.dsh/profiles/web/package.json`，加两处：

- `dependencies` 里加一行：
  `"@local/pet-brain": "file:/root/projects/.dsh/dshbox/dshbox-plugins/pet-brain/plugin"`
- `dsh.profile.bundles` 里加一行：`"@local/pet-brain"`

**4. 生效**

**完全退出 DSHBox 再打开**（只退到后台不算）。宿主启动时才读 `package.json`，只改文件不重启，
`/pet-brain/*` 还是 404。

真的起不来，把备份还原回去（`mv package.json.bak package.json`）就能恢复。

## 怎么确认装好了

在手机浏览器打开：

```
http://127.0.0.1:3080/pet-brain/health
```

看到类似 `{"ok":true,"name":"@local/pet-brain","model":"..."}` 就成功了。
如果还是 404，说明插件没被加载，检查第 2 步的 `bundles` 有没有写进去、有没有真的重启。

再验证记账（新版本才有）：

```
http://127.0.0.1:3080/pet-brain/usage
```

返回 `{"ok":true,"days":[]}` 表示路由通了、还没记过账；宠物说一句话之后再看，应该出现
`{"day":"2026-11-08","calls":1,"input":185,"output":67,"cacheRead":0}` 这样的行。
App 设置页「关于 → 用量」读的就是这个接口。记忆页在「关于 → 记忆」。

## 十条路由

| 路由 | 方法 | 做什么 |
| --- | --- | --- |
| `/pet-brain/health` | GET | 活着没、用的哪个模型、记了几条记忆、跑了多久 |
| `/pet-brain/say` | POST | 说话（唯一会调用模型的地方），每次记一行 token 账 |
| `/pet-brain/diag` | POST | App 把现场写进宿主日志 |
| `/pet-brain/habits` | POST | App 推使用习惯快照 |
| `/pet-brain/stat` | GET | 日志多大、记忆几条 |
| `/pet-brain/facts` | GET | 列出她记住的事 |
| `/pet-brain/forget` | POST | 删一条（带 `text`）或全清（空 body） |
| `/pet-brain/usage` | GET | 按天汇总 token（真实 usage，不估算） |
| `/pet-brain/cleanup` | POST | 压日志、裁记忆 |
| `/pet-brain/compress` | POST | 把一批原话压成带等级的要点（1.1.0 起） |

## 它会读写什么

- `$DSH_HOME/pet-memory/pet-brain.log`：每次请求一行，最多留 200 行
- `$DSH_HOME/pet-memory/facts.json`：她记住的关于你的事，最多 60 条
- `$DSH_HOME/pet-memory/habits.json`：App 推过来的使用习惯快照
- `$DSH_HOME/pet-memory/usage.jsonl`：每次说话的 token 账（`{at,kind,input,output,cacheRead}`），一行一条

App 设置页「关于 → 存储与清理 → 立即清理」会顺带把日志压到 200 行、记忆裁到 60 条。

## 常见问题

**`/pet-brain/health` 还是 404**
插件没被加载。按顺序查：① `profiles/web/package.json` 的 `dsh.profile.bundles` 里有没有
`"@local/pet-brain"`；② `profiles/web/node_modules/@local/pet-brain` 在不在（这一步最容易漏，
`file:` 依赖不会自己建链接，必须手动 `cp`）；③ 是不是**完全退出** DSHBox 之后重新打开的。

**宠物一直说「连不上大脑」**
先看 `/pet-brain/health` 是不是 200。是 200 还说连不上，多半是 App 里选的是「自填 API Key」而 key 是空的，
去「大脑 → 自动 / 只用 DSHBox 的登录态」切一下。

**摘要不生效（宠物记不住更早的聊天）**
先看 `/pet-brain/compress` 是不是 404：是就说明插件还是旧版，重新导出、覆盖两份副本、
完全退出 DSHBox 再打开。然后看 `pet-brain.log` 里有没有 `compress turns=… items=…`：
有 `items=0` 是这一批没压出东西（正常），有 `digest=…chars` 说明摘要已经喂给模型了。

**改完插件没反应**
改 `lib/index.js` 之后必须**完全退出 DSHBox 再打开**，宿主只在启动时加载插件。
`cp` 到 `node_modules/@local/pet-brain` 的那一份也要一起改，两份不一致时以 `node_modules` 那份为准。

**想看插件在干什么**
`$DSH_HOME/pet-memory/pet-brain.log`，每行一次请求：`say kind=chat in=16chars out=25chars
think=164chars memo=0 tok=185/67 ms=3048`。`think` 是模型的思考长度，`tok` 是真实 usage。

**装完起不来（DSHBox 打不开）**
`mv /root/projects/.dsh/profiles/web/package.json.bak /root/projects/.dsh/profiles/web/package.json`
还原，然后完全退出重开。多半是 `bundles` 写了包名但代码没放到位。
