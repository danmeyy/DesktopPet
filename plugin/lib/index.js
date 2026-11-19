'use strict'

/**
 * pet-brain —— 桌面宠物 APK 的「宿主半边」。
 *
 * APK 在宿主模式下把请求打到 http://127.0.0.1:3080/pet-brain/*：
 *   GET  /pet-brain/health   → 宿主与插件状态
 *   POST /pet-brain/say      → {kind,text,ctx,history} 交给宿主已登录的模型作答
 *   POST /pet-brain/diag     → {msg} 写一行日志
 *   POST /pet-brain/habits   → 本机习惯快照落盘
 *   GET  /pet-brain/stat     → {logBytes, facts}
 *   GET  /pet-brain/facts    → {facts:[…]} 记忆全量，给 App 的记忆页面看
 *   POST /pet-brain/forget   → {text} 删一条；空 body 清空
 *   POST /pet-brain/cleanup  → 压日志、裁记忆
 *
 * 记忆与日志放在 $DSH_HOME/pet-memory/（默认 /root/projects/.dsh/pet-memory）。
 * 这个插件不发布 service，只在 webServer 上挂裸路由——/api 命名空间的令牌
 * 校验是 API handler 自己做的，这里不走它，所以 APK 不需要任何令牌。
 */

const fs = require('node:fs')
const path = require('node:path')

exports.name = '@local/pet-brain'
exports.inject = ['webServer']

const PREFIX = '/pet-brain'
const MAX_LOG_LINES = 200
const MAX_FACTS = 60
const MAX_BODY_BYTES = 256 * 1024

const WHISPER_PROMPT =
  '你是主人手机桌面上的Q版蓝发小女仆，会时不时主动碎碎念一句。说话要自然随意、短短一句（20字以内），' +
  '温柔乖巧带点俏皮，说人话不啰嗦，不要解释你自己，不要提你是AI。'

const CHAT_PROMPT =
  '你是主人手机桌面上的Q版蓝发小女仆，正在和主人聊天。语气温柔乖巧、带点俏皮，简短自然' +
  '（一般不超过60字），可以用主人身边正在发生的事当话题，不要提你是AI，不要长篇大论。'

/** 压缩聊天上下文：等级决定这条在 App 里存多久（1=3天 … 4=30天，5=永久）。 */
const COMPRESS_PROMPT =
  '你在整理手机桌面宠物和主人的一段聊天。把它压成 1–3 条要点，每条一行，格式严格是「等级|要点」。' +
  '等级：1=一般（留3天）2=有用（留7天）3=重要（留15天）4=很想记住（留30天）5=永久。' +
  '「宠物(碎碎念)」是宠物自己主动说的短句：多半是寒暄，同一类重复的合并成一条（比如“常提醒主人翻页/喝水”），' +
  '不要逐条罗列，只有带信息量的才单独记。' +
  '只留以后聊天还用得上的：聊了什么、主人的偏好和情绪、答应过的事、正在进行的计划。' +
  '不要寒暄、不要复述原句、不要编号和解释，每条不超过40字，宁少勿多。'

/** 自定义人设时补的硬约束，与 App 侧 Persona.constraint() 保持一致。 */
function personaHint(kind) {
  return kind === 'chat'
    ? '（照这个人设说话，但依旧简短：一般不超过60字，可以拿主人身边正在发生的事当话题，不要长篇大论，不要提你是AI。）'
    : '（照这个人设说话，但每次只说一句：20字以内，别问问题，别复述环境原文，不要提你是AI。）'
}

const REMEMBER_HINT =
  '\n\n只有当主人说到了**长期有效**的偏好、习惯、固定安排或约定时（比如作息、忌口、每周的固定安排、' +
  '答应过的事），才在回答最后附上 <remember>要点</remember>，写成像"主人…"的一句陈述，最多两条。' +
  '当前正在做什么、临时状态、情绪、天气这类一次性的事一律不要记；没有可记的就完全不要出现这个标签。'

function report(message) {
  try {
    process.stderr.write(`@local/pet-brain: ${message}\n`)
  } catch {}
}

function homeDir() {
  const home = process.env.DSH_HOME
  if (typeof home === 'string' && home.length > 0) return home
  return '/root/projects/.dsh'
}

function memoryDir() {
  const dir = path.join(homeDir(), 'pet-memory')
  try {
    fs.mkdirSync(dir, { recursive: true })
  } catch (error) {
    report(`mkdir ${dir} failed: ${error && error.message}`)
  }
  return dir
}

const logFile = () => path.join(memoryDir(), 'pet-brain.log')
const factsFile = () => path.join(memoryDir(), 'facts.json')
const habitsFile = () => path.join(memoryDir(), 'habits.json')

const usageFile = () => path.join(memoryDir(), 'usage.jsonl')

/**
 * 记一次调用的真实 token（取自宿主适配器上报的 usage）。
 * 适配器没上报就是 0——不要用字数去猜，宁可显示「未报告」。
 */
function recordUsage(kind, usage) {
  const num = (v) => (typeof v === 'number' && isFinite(v) && v >= 0 ? Math.round(v) : 0)
  const metered = {
    input: num(usage && usage.inputTokens),
    output: num(usage && usage.outputTokens),
    cacheRead: num(usage && usage.cacheReadTokens)
  }
  try {
    fs.appendFileSync(usageFile(), `${JSON.stringify({ at: Date.now(), kind, ...metered })}\n`)
  } catch (error) {
    report(`usage write failed: ${error && error.message}`)
  }
  return metered
}

function dayKey(ts) {
  const d = new Date(ts)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 按本地日期汇总，最新的一天在前。 */
function readUsageDays() {
  let raw = ''
  try {
    raw = fs.readFileSync(usageFile(), 'utf8')
  } catch {
    return []
  }
  const byDay = new Map()
  for (const line of raw.split('\n')) {
    if (line.length === 0) continue
    let row
    try {
      row = JSON.parse(line)
    } catch {
      continue
    }
    if (!row || typeof row.at !== 'number') continue
    const day = dayKey(row.at)
    let agg = byDay.get(day)
    if (!agg) {
      agg = { day, calls: 0, input: 0, output: 0, cacheRead: 0 }
      byDay.set(day, agg)
    }
    agg.calls += 1
    agg.input += row.input || 0
    agg.output += row.output || 0
    agg.cacheRead += row.cacheRead || 0
  }
  return [...byDay.values()].sort((a, b) => (a.day < b.day ? 1 : -1))
}

/** 本地时间戳，日志里带上便于和手机上的行为对齐。 */
function stamp() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const off = -d.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const abs = Math.abs(off)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
    `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  )
}

function appendLog(line) {
  try {
    fs.appendFileSync(logFile(), `${stamp()} ${line}\n`)
  } catch (error) {
    report(`log write failed: ${error && error.message}`)
  }
}

function trimLog() {
  const file = logFile()
  let before = 0
  try {
    before = fs.statSync(file).size
  } catch {
    return 0
  }
  let lines = []
  try {
    lines = fs.readFileSync(file, 'utf8').split('\n')
  } catch {
    return 0
  }
  const kept = lines.slice(Math.max(0, lines.length - MAX_LOG_LINES - 1))
  try {
    fs.writeFileSync(file, kept.join('\n'))
  } catch {
    return 0
  }
  let after = 0
  try {
    after = fs.statSync(file).size
  } catch {}
  return Math.max(0, before - after)
}

function readFacts() {
  try {
    const raw = fs.readFileSync(factsFile(), 'utf8')
    const list = JSON.parse(raw)
    return Array.isArray(list) ? list.filter((x) => typeof x === 'string' && x.trim().length > 0) : []
  } catch {
    return []
  }
}

function writeFacts(list) {
  try {
    fs.writeFileSync(factsFile(), JSON.stringify(list.slice(-MAX_FACTS), null, 0))
  } catch (error) {
    report(`facts write failed: ${error && error.message}`)
  }
}

function remember(items) {
  if (items.length === 0) return
  const list = readFacts()
  for (const item of items) {
    const text = item.trim()
    if (text.length === 0) continue
    if (list.includes(text)) continue
    list.push(text)
  }
  writeFacts(list)
}

/** 请求体：小、UTF-8、可能为空。 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'))
        req.destroy()
        return
      }
      parts.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')))
    req.on('error', reject)
  })
}

function sendJson(res, code, value) {
  if (res.writableEnded) return
  const body = JSON.stringify(value)
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(body)
  })
  res.end(body)
}

function service(ctx, name) {
  try {
    if (ctx && ctx.reflect && typeof ctx.reflect.get === 'function') {
      const found = ctx.reflect.get(name, false)
      if (found !== undefined) return found
    }
  } catch {}
  try {
    return ctx[name]
  } catch {
    return undefined
  }
}

/** 当前默认模型；拿不到就回 undefined，让 /say 报一个能看懂的错误。 */
function currentModel(ctx) {
  const picker = service(ctx, 'agentDefaultModel')
  if (picker && typeof picker.currentSelection === 'function') {
    try {
      const selection = picker.currentSelection()
      if (selection && selection.provider && selection.model) return selection
    } catch (error) {
      report(`currentSelection failed: ${error && error.message}`)
    }
  }
  return undefined
}

/** 把 App 传来的上下文折成一句话，别让模型看到 JSON。 */
function contextLine(ctxObj) {
  if (!ctxObj || typeof ctxObj !== 'object') return ''
  const parts = []
  const time = typeof ctxObj.time === 'string' ? ctxObj.time : ''
  if (time.length > 0) parts.push(`现在${time}`)
  const app = typeof ctxObj.app === 'string' ? ctxObj.app : ''
  if (app.length > 0) parts.push(`主人正在用「${app}」`)
  const screen = typeof ctxObj.screen === 'string' ? ctxObj.screen : ''
  if (screen.length > 0) parts.push(`屏幕上是：${screen.slice(0, 160)}`)
  if (typeof ctxObj.battery === 'number') {
    parts.push(`电量${Math.round(ctxObj.battery)}%${ctxObj.charging ? '（在充电）' : ''}`)
  }
  const idle = typeof ctxObj.idleSec === 'number' ? ctxObj.idleSec : 0
  if (idle > 0) parts.push(`已经${Math.round(idle / 60)}分钟没碰屏幕`)
  const habit = typeof ctxObj.habit === 'string' ? ctxObj.habit : ''
  if (habit.length > 0) parts.push(`按以往习惯，这个时间点主人常在：${habit}`)
  return parts.join('，')
}

/** 历史压成一段文字：手搓消息对象会碰上消息身份校验，这里绕开它。 */
function historyText(history) {
  if (!Array.isArray(history) || history.length === 0) return ''
  const lines = []
  for (const item of history.slice(-8)) {
    if (!item || typeof item !== 'object') continue
    const text = typeof item.text === 'string' ? item.text.trim() : ''
    if (text.length === 0) continue
    const who = item.role === 'pet' ? '你' : '主人'
    lines.push(`${who}：${text.slice(0, 300)}`)
  }
  return lines.join('\n')
}

/** 从回答里剥掉 <remember>…</remember>，返回 [正文, 记忆数组]。 */
function splitRemember(text) {
  const memories = []
  let body = ''
  let i = 0
  while (i < text.length) {
    const start = text.indexOf('<remember>', i)
    if (start < 0) {
      body += text.slice(i)
      break
    }
    body += text.slice(i, start)
    const end = text.indexOf('</remember>', start)
    if (end < 0) break
    const item = text.slice(start + 10, end).replace(/<\/remember>/g, '').trim()
    if (item.length > 0) memories.push(item)
    i = end + 11
  }
  return [body.trim(), memories]
}

/** 流式收集纯文本；不依赖 BlockAssembler，省掉一条包解析路径。 */
async function streamText(ctx, options) {
  const llm = service(ctx, 'llm')
  if (!llm || typeof llm.stream !== 'function') throw new Error('宿主没有 llm 服务')
  let text = ''
  let think = 0
  let failure
  let usage = null
  for await (const chunk of llm.stream(options)) {
    if (chunk && chunk.type === 'text-delta' && typeof chunk.text === 'string') text += chunk.text
    // 推理模型的思考过程也占 maxTokens；记下长度，答案为空时能看出是被它吃光了
    if (chunk && chunk.type === 'reasoning-delta' && typeof chunk.text === 'string') think += chunk.text.length
    if (chunk && chunk.type === 'usage' && chunk.usage) usage = chunk.usage
    if (chunk && chunk.type === 'finish') failure = chunk.reason
  }
  if (text.trim().length === 0) {
    throw new Error(`模型没有返回文字（finish=${JSON.stringify(failure) ?? 'unknown'} think=${think}）`)
  }
  return { text, think, usage }
}

/** 组装一次 /say 的模型调用。 */
async function ask(ctx, payload) {
  const selection = currentModel(ctx)
  if (selection === undefined) throw new Error('宿主没有配置默认模型（agentDefaultModel 未就绪）')

  const kind = payload.kind === 'chat' ? 'chat' : 'whisper'
  const text = typeof payload.text === 'string' ? payload.text.trim() : ''
  const line = contextLine(payload.ctx)
  const history = historyText(payload.history)

  let userText
  if (kind === 'chat') {
    userText = [line.length > 0 ? `（当前：${line}）` : '', text].filter((s) => s.length > 0).join('\n')
  } else {
    userText = `${line.length > 0 ? `（${line}）` : ''}现在没人说话，你自己碎碎念一句。`
  }
  if (userText.length === 0) userText = '（主人什么也没说，你自己碎碎念一句。）'

  const digest = typeof payload.digest === 'string' ? payload.digest.trim() : ''
  const facts = readFacts()
  const persona = typeof payload.persona === 'string' ? payload.persona.trim() : ''
  let system = persona.length > 0
    ? `${persona}\n\n${personaHint(kind)}`
    : kind === 'chat' ? CHAT_PROMPT : WHISPER_PROMPT
  if (facts.length > 0) {
    system += `\n\n你记得关于主人的事（该用就用，别逐条复述、别说"我记得"）：\n${facts.join('\n')}`
  }
  if (digest.length > 0) {
    system += `\n\n你和主人之前聊过的（越靠后越近；该用就用，别逐条复述、别说"我记得"）：\n${digest}`
  }
  if (history.length > 0) {
    system += '\n\n下面附了你们最近的对话，其中「你：」那几行是你自己刚说过的话（含碎碎念）。' +
      '不要重复你已经说过的句子或同样的意思，换个说法或换个话题。'
  }
  if (kind === 'chat') system += REMEMBER_HINT

  const messageText = history.length > 0 ? `${history}\n${userText}` : userText

  return streamText(ctx, {
    provider: selection.provider,
    model: selection.model,
    system,
    messages: [
      {
        role: 'user',
        content: [{ type: 'text', text: messageText }]
      }
    ],
    temperature: kind === 'chat' ? 0.9 : 1.1,
    // 800 是留给推理模型的余量：思考 token 也算在 maxTokens 里，卡 80 会让它
    // 想完就没额度说话，表现是回复被截断或干脆空。
    maxTokens: kind === 'chat' ? 800 : 600
  })
}

/** 解析压缩结果：容忍编号、冒号、空格、Markdown 列表。 */
function parseDigest(text) {
  const items = []
  for (const raw of String(text || '').split(/\r?\n/)) {
    // 先剥列表符号，再剥「1. 」这种编号——别把等级自己的数字也剥掉
    let line = raw.trim().replace(/^[-*·•\s]+/, '').replace(/^\d+[.、)）]\s*/, '')
    if (line.length === 0) continue
    const m = /^[\[(（]?\s*([1-5])\s*[\])）]?\s*[|｜:：,，]\s*(.+)$/.exec(line)
    if (!m) continue
    const body = m[2].trim()
    if (body.length === 0) continue
    items.push({ lv: Number(m[1]), text: body.slice(0, 60) })
    if (items.length >= 4) break
  }
  return items
}

/** 把一批原话压成带等级的要点；省 token：输入截尾、输出限 300 token。 */
async function compress(ctx, payload) {
  const selection = currentModel(ctx)
  if (selection === undefined) throw new Error('宿主没有配置默认模型（agentDefaultModel 未就绪）')
  const turns = Array.isArray(payload.turns) ? payload.turns : []
  const lines = []
  for (const turn of turns.slice(-40)) {
    if (!turn || typeof turn.text !== 'string') continue
    const text = turn.text.trim()
    if (text.length === 0) continue
    lines.push(`${turn.who === 'her' ? '宠物' : '主人'}：${text.slice(0, 300)}`)
  }
  if (lines.length === 0) return { items: [] }
  const { text } = await streamText(ctx, {
    provider: selection.provider,
    model: selection.model,
    system: COMPRESS_PROMPT,
    messages: [{ role: 'user', content: [{ type: 'text', text: lines.join('\n') }] }],
    temperature: 0.3,
    maxTokens: 2000
  })
  return { items: parseDigest(text) }
}

exports.apply = function apply(ctx) {
  const started = Date.now()

  const dispose = []

  const route = (pathname, handler) => {
    dispose.push(
      ctx.webServer.register({
        kind: 'exact',
        path: pathname,
        handler: (req, res) => {
          Promise.resolve()
            .then(() => handler(req, res))
            .catch((error) => {
              report(`${pathname} failed: ${error && error.stack ? error.stack : error}`)
              sendJson(res, 500, { ok: false, error: 'E_INTERNAL', message: String((error && error.message) || error) })
            })
        }
      })
    )
  }

  route(`${PREFIX}/health`, (req, res) => {
    const selection = currentModel(ctx)
    sendJson(res, 200, {
      ok: true,
      name: '@local/pet-brain',
      version: '1.1.2',
      model: selection ? `${selection.provider}/${selection.model}` : null,
      facts: readFacts().length,
      uptimeSec: Math.round((Date.now() - started) / 1000)
    })
  })

  route(`${PREFIX}/say`, async (req, res) => {
    if (req.method !== 'POST') {
      sendJson(res, 405, { ok: false, error: 'E_METHOD', message: 'POST only' })
      return
    }
    const raw = await readBody(req)
    let payload
    try {
      payload = JSON.parse(raw || '{}')
    } catch {
      sendJson(res, 400, { ok: false, error: 'E_JSON', message: '请求体不是 JSON' })
      return
    }
    const began = Date.now()
    try {
      const { text: answer, think, usage } = await ask(ctx, payload)
      const [body, memories] = splitRemember(answer)
      remember(memories)
      const reply = body.length > 0 ? body : answer.trim()
      const metered = recordUsage(payload.kind === 'chat' ? 'chat' : 'whisper', usage)
      appendLog(
        `say kind=${payload.kind === 'chat' ? 'chat' : 'whisper'} in=${String(payload.text || '').length}chars ` +
          `out=${reply.length}chars think=${think}chars memo=${memories.length} ` +
          `digest=${String(payload.digest || '').length}chars ` +
          `tok=${metered.input}/${metered.output} ms=${Date.now() - began}`
      )
      sendJson(res, 200, { ok: true, text: reply })
    } catch (error) {
      appendLog(`say failed ms=${Date.now() - began} ${error && error.message}`)
      sendJson(res, 200, {
        ok: false,
        error: 'E_BRAIN',
        message: String((error && error.message) || error)
      })
    }
  })

  route(`${PREFIX}/compress`, async (req, res) => {
    if (req.method !== 'POST') {
      sendJson(res, 405, { ok: false, error: 'E_METHOD', message: 'POST only' })
      return
    }
    const raw = await readBody(req)
    let payload
    try {
      payload = JSON.parse(raw || '{}')
    } catch {
      sendJson(res, 400, { ok: false, error: 'E_JSON', message: '请求体不是 JSON' })
      return
    }
    const began = Date.now()
    try {
      const { items } = await compress(ctx, payload)
      appendLog(
        `compress turns=${Array.isArray(payload.turns) ? payload.turns.length : 0} ` +
          `items=${items.length} ms=${Date.now() - began}`
      )
      sendJson(res, 200, { ok: true, items })
    } catch (error) {
      appendLog(`compress failed ms=${Date.now() - began} ${error && error.message}`)
      sendJson(res, 200, {
        ok: false,
        error: 'E_BRAIN',
        message: String((error && error.message) || error)
      })
    }
  })

  route(`${PREFIX}/diag`, async (req, res) => {
    const raw = await readBody(req)
    let msg = raw
    try {
      const parsed = JSON.parse(raw || '{}')
      if (parsed && typeof parsed.msg === 'string') msg = parsed.msg
    } catch {}
    appendLog(`diag ${String(msg).slice(0, 400)}`)
    sendJson(res, 200, { ok: true })
  })

  route(`${PREFIX}/habits`, async (req, res) => {
    const raw = await readBody(req)
    if (raw.length === 0) {
      sendJson(res, 400, { ok: false, error: 'E_EMPTY' })
      return
    }
    try {
      fs.writeFileSync(habitsFile(), raw)
      appendLog(`habits ${raw.length}bytes`)
      sendJson(res, 200, { ok: true })
    } catch (error) {
      sendJson(res, 500, { ok: false, error: 'E_IO', message: String((error && error.message) || error) })
    }
  })

  route(`${PREFIX}/stat`, (req, res) => {
    let bytes = 0
    try {
      bytes = fs.statSync(logFile()).size
    } catch {}
    sendJson(res, 200, { ok: true, logBytes: bytes, facts: readFacts() })
  })

  route(`${PREFIX}/facts`, (req, res) => {
    sendJson(res, 200, { ok: true, facts: readFacts() })
  })

  route(`${PREFIX}/forget`, async (req, res) => {
    let payload = {}
    try {
      const raw = await readBody(req)
      if (raw.trim().length > 0) payload = JSON.parse(raw)
    } catch (error) {
      sendJson(res, 400, { ok: false, error: 'E_BODY', message: String((error && error.message) || error) })
      return
    }
    const list = readFacts()
    const drop = typeof payload.text === 'string' ? payload.text.trim() : ''
    if (drop.length === 0) {
      writeFacts([])
      appendLog(`forget all removed=${list.length}`)
      sendJson(res, 200, { ok: true, removed: list.length, left: 0 })
      return
    }
    const next = list.filter((x) => x !== drop)
    writeFacts(next)
    appendLog(`forget one removed=${list.length - next.length} left=${next.length}`)
    sendJson(res, 200, { ok: true, removed: list.length - next.length, left: next.length })
  })

  route(`${PREFIX}/usage`, (req, res) => {
    sendJson(res, 200, { ok: true, days: readUsageDays(), today: readUsageDays()[0] || null })
  })

  route(`${PREFIX}/cleanup`, (req, res) => {
    const freed = trimLog()
    const facts = readFacts()
    writeFacts(facts.slice(-MAX_FACTS))
    appendLog(`cleanup freed=${freed}bytes facts=${Math.min(facts.length, MAX_FACTS)}`)
    sendJson(res, 200, { ok: true, freedBytes: freed })
  })

  appendLog(`plugin ready routes=${dispose.length} home=${homeDir()}`)

  ctx.effect(() => () => {
    for (const off of dispose) {
      try {
        off()
      } catch {}
    }
  })
}
