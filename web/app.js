/* MockVerse 前端逻辑（D25）
 *
 * 零依赖、无构建。所有接口都走同源（Nginx 反代），所以不需要 CORS 配置。
 *
 * 三个关键实现决定：
 *
 * 1. **流式用 fetch + ReadableStream，不用 EventSource。**
 *    EventSource 只能发 GET，而 /answer/stream 是 POST。用 fetch 拿到 body 的
 *    reader 后手工按 SSE 帧解析（见 parseSse）。
 *
 * 2. **tool 事件收到任意后续事件就撤掉。**
 *    D19 定的契约：judge_code 要 0.6~10s，这段时间没有文字。只在 chunk 到达时撤提示
 *    是不够的——判题超时或工具失败时不会再有文字，提示会一直挂着。
 *
 * 3. **判题的 stderr 要过滤 JVM 启动噪声。**
 *    容器里 JAVA_TOOL_OPTIONS 会让每个 java 子进程往 stderr 打
 *    "Picked up JAVA_TOOL_OPTIONS: ..."，那不是候选人的输出。
 */

'use strict';

// ---------------------------------------------------------------------------
// DOM
// ---------------------------------------------------------------------------

const $ = (id) => document.getElementById(id);

const el = {
  welcome: $('welcome'),
  chat: $('chat'),
  messages: $('messages'),
  sessionInfo: $('sessionInfo'),
  chipRound: $('chipRound'),
  chipTopic: $('chipTopic'),
  btnNew: $('btnNew'),
  statusDot: $('statusDot'),
  statusText: $('statusText'),
  selCategory: $('selCategory'),
  selDifficulty: $('selDifficulty'),
  btnStart: $('btnStart'),
  startHint: $('startHint'),
  taAnswer: $('taAnswer'),
  btnSend: $('btnSend'),
  composerHint: $('composerHint'),
  judgeToggle: $('judgeToggle'),
  judgeBody: $('judgeBody'),
  judgeChev: $('judgeChev'),
  taCode: $('taCode'),
  taStdin: $('taStdin'),
  taExpected: $('taExpected'),
  btnJudge: $('btnJudge'),
  judgeStatus: $('judgeStatus'),
  judgeResult: $('judgeResult'),
};

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

const state = {
  sessionId: null,
  maxRounds: 5,
  round: 0,
  busy: false,
  /** 当前"正在判题…"提示元素。收到任意后续事件时移除。 */
  toolHint: null,
  /**
   * 会话级判题结果流（D26 二修）。**只开一条、多次提交复用**。
   * 见 judgeStream() 的注释：每次提交都新开一条的话，旧连接要等服务端下次写入失败
   * 才会被回收，日志里会持续刷 "当前连接数=N" + Broken pipe。
   */
  judgeStream: null,
};

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

function setStatus(text, kind) {
  el.statusText.textContent = text;
  el.statusDot.className = 'dot' + (kind ? ' ' + kind : '');
}

function setHint(node, text, isErr) {
  node.textContent = text || '';
  node.className = 'hint' + (isErr ? ' err' : '');
}

function scrollToBottom() {
  el.messages.scrollIntoView({ block: 'end', behavior: 'smooth' });
}

/** 追加一条消息气泡。返回气泡元素，便于流式追加文本。 */
function addMessage(role, text, who) {
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + role;

  if (who) {
    const w = document.createElement('div');
    w.className = 'who';
    w.textContent = who;
    wrap.appendChild(w);
  }

  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = text || '';
  wrap.appendChild(bubble);

  el.messages.appendChild(wrap);
  scrollToBottom();
  return bubble;
}

// ---------------------------------------------------------------------------
// 轻量 Markdown（只给"最终消息"用）
// ---------------------------------------------------------------------------
//
// 为什么需要（D26）：知识库 Agent 的回答是 Markdown（`**加粗**`、`- 列表`、
// `` `代码` ``），但气泡一直用 textContent 塞纯文本——界面上直接显示成
// "**Redis 过期删除策略**"，手机上满屏星号。验收标准是"非技术朋友能看懂"，
// 满屏语法符号不合格。
//
// 为什么自己写 20 行而不是引 marked：整个前端零依赖零构建是 D25 的既定选择，
// 为四个语法引一个库不划算。
//
// **顺序不能反**：先转义 HTML 再套标签。反过来的话，模型输出里的 `<script>`
// 会被当成标签执行——回答来自 LLM，而 LLM 的输入里有候选人贴的内容。

function escapeHtml(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** 行内：`code` 与 **bold**。入参必须已经转义过。 */
function inlineMd(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

/** 只支持标题 / 无序列表 / 空行分段，够用且不会误伤。 */
function mdToHtml(text) {
  const lines = escapeHtml(text || '').split('\n');
  const parts = [];
  let list = null;
  const flushList = () => {
    if (list) {
      parts.push('<ul>' + list.join('') + '</ul>');
      list = null;
    }
  };

  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    const li = line.match(/^\s*[-*]\s+(.*)$/);
    if (li) {
      if (!list) list = [];
      list.push('<li>' + inlineMd(li[1]) + '</li>');
      continue;
    }
    flushList();
    if (!line.trim()) {
      parts.push('');                 // 空行 = 段落分隔，下面统一换成 <br>
      continue;
    }
    const h = line.match(/^#{1,6}\s+(.*)$/);
    if (h) {
      parts.push('<div class="md-h">' + inlineMd(h[1]) + '</div>');
      continue;
    }
    parts.push(inlineMd(line));
  }
  flushList();

  return parts.join('<br>')
    .replace(/(<br>){3,}/g, '<br><br>')      // 连续空行压成一个段落间距
    // 块级元素前后不留换行（它们自带 margin）。
    // **必须是 `(?:<br>)+`**：写成 `<br>+` 时 `+` 修饰的是 `>`，只能匹配一个。
    .replace(/(?:<br>)+(<ul>)/g, '$1')
    .replace(/(<\/ul>)(?:<br>)+/g, '$1')
    .replace(/(<\/div>)(?:<br>)+/g, '$1');
}

function showToolHint(toolName) {
  removeToolHint();
  const hint = document.createElement('div');
  hint.className = 'tool-hint';
  hint.innerHTML = '<span class="spinner"></span>';
  hint.appendChild(document.createTextNode(toolLabel(toolName)));
  el.messages.appendChild(hint);
  state.toolHint = hint;
  scrollToBottom();
}

function removeToolHint() {
  if (state.toolHint && state.toolHint.parentNode) {
    state.toolHint.parentNode.removeChild(state.toolHint);
  }
  state.toolHint = null;
}

/** Agent 名 -> 中文显示名。内部标识符（interviewer_agent）不该出现在用户界面上。 */
function agentLabel(author) {
  const map = {
    interviewer_agent: '面试官',
    knowledge_agent: '知识库助手',
    resume_agent: '简历诊断',
    root_coordinator: '面试官',
    system: '系统',
  };
  return map[author] || author || '面试官';
}

/** 工具名的中文说明——给非技术用户看的，不直接显示 judge_code 这种标识符。 */
function toolLabel(toolName) {
  if (toolName === 'judge_code') return '正在编译并运行你的代码…';
  if (toolName === 'pick_question') return '正在题库里找题…';
  return '正在调用工具：' + toolName;
}

function setBusy(busy) {
  state.busy = busy;
  el.btnSend.disabled = busy;
  el.taAnswer.disabled = busy;
  el.btnStart.disabled = busy;
  setStatus(busy ? '思考中…' : '就绪', busy ? 'busy' : 'online');
}

// ---------------------------------------------------------------------------
// SSE 解析
// ---------------------------------------------------------------------------

/**
 * 解析一段 SSE 文本，回调每个事件的 payload 字符串。
 *
 * SSE 帧格式：`data:<payload>\n\n`。一个 TCP chunk 可能包含多个帧，
 * 也可能把一帧切成两半——所以必须维护缓冲区，只处理完整的帧（以 \n\n 结尾）。
 *
 * 这里只处理 data 行（服务端没用 event:/id:/retry:）。
 */
function parseSse(buffer, onEvent) {
  const frames = buffer.split('\n\n');
  const rest = frames.pop();          // 最后一段可能不完整，留到下次
  for (const frame of frames) {
    const lines = frame.split('\n');
    const dataLines = [];
    for (const line of lines) {
      if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
    if (dataLines.length > 0) {
      onEvent(dataLines.join('\n'));
    }
  }
  return rest;
}

/**
 * 发起一个流式请求并逐事件回调。
 *
 * @param url    请求地址
 * @param body   请求体（JSON）
 * @param onEvent(payload: string)  每个 SSE data 帧
 * @throws Error 非 2xx 时抛出，message 里带服务端的 detail
 */
async function streamPost(url, body, onEvent) {
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!resp.ok) {
    // 服务端用 ProblemDetail（RFC 7807），detail 字段是给人看的中文说明
    let detail = resp.status + ' ' + resp.statusText;
    try {
      const problem = await resp.json();
      if (problem.detail) detail = problem.detail;
    } catch (_) { /* 非 JSON 响应，用默认文案 */ }
    throw new Error(detail);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = parseSse(buffer, onEvent);
  }
  // 收尾：有些服务端不补最后的 \n\n
  buffer += decoder.decode();
  if (buffer.trim()) {
    parseSse(buffer + '\n\n', onEvent);
  }
}

// ---------------------------------------------------------------------------
// 开始面试
// ---------------------------------------------------------------------------

async function startInterview() {
  const category = el.selCategory.value;
  const difficulty = el.selDifficulty.value;

  const params = new URLSearchParams({ userId: '1' });
  if (category) params.set('category', category);
  if (difficulty) params.set('difficulty', difficulty);

  setBusy(true);
  setHint(el.startHint, '正在抽题并生成开场白…');

  try {
    const resp = await fetch('/api/interview/start?' + params.toString(), { method: 'POST' });
    if (!resp.ok) {
      let detail = resp.status + ' ' + resp.statusText;
      try {
        const p = await resp.json();
        if (p.detail) detail = p.detail;
      } catch (_) { /* ignore */ }
      throw new Error(detail);
    }

    const data = await resp.json();
    // sessionId 是 19 位雪花 ID，后端已用 JacksonConfig 把它序列化成**字符串**。
    // 若后端还是旧版本（返回数字），这里会静默丢精度——所以显式断言一次，
    // 让"后端没升级"这件事在浏览器控制台里可见，而不是变成一个 400 的谜题。
    if (typeof data.sessionId === 'number') {
      console.warn(
        '[MockVerse] sessionId 以 number 返回，超过 2^53 会丢精度。' +
        '请确认后端已启用 JacksonConfig（Long -> String）。当前值：' + data.sessionId
      );
    }
    state.sessionId = String(data.sessionId);
    state.round = 0;

    el.welcome.hidden = true;
    el.chat.hidden = false;
    el.sessionInfo.hidden = false;
    el.chipTopic.textContent = (data.category || '—') + ' · ' + (data.difficulty || '—');
    updateRoundChip();

    addMessage('system', '本次题目：\n' + data.question, '题库');
    addMessage('agent', data.agentMessage, '面试官');
    setHint(el.startHint, '');
    el.taAnswer.focus();
  } catch (e) {
    setHint(el.startHint, '开始失败：' + e.message, true);
  } finally {
    setBusy(false);
  }
}

function updateRoundChip() {
  el.chipRound.textContent = '第 ' + state.round + ' / ' + state.maxRounds + ' 轮';
}

// ---------------------------------------------------------------------------
// 发送回答（流式）
// ---------------------------------------------------------------------------

async function sendAnswer() {
  if (state.busy) return;

  const text = el.taAnswer.value.trim();
  if (!text) {
    setHint(el.composerHint, '先写点什么再发送', true);
    return;
  }
  if (!state.sessionId) {
    setHint(el.composerHint, '还没有会话，请先开始面试', true);
    return;
  }

  el.taAnswer.value = '';
  setHint(el.composerHint, '');
  setBusy(true);

  addMessage('user', text, '你');

  // 后端按 author 切分 turn（D19 规则：doOnNext 靠 author 变化切段），
  // 前端必须跟着切——否则「面试官过渡语 + 知识库答案」会被拼进同一个气泡，
  // 标签还停留在第一个 author 上（D25 实测踩到）。
  let bubble = null;
  let bubbleAuthor = null;
  let finished = false;
  // 本轮生成的气泡：流式期间按纯文本追加（增量无法安全地半截渲染 Markdown），
  // 收尾时统一渲染一次。见 mdToHtml 的注释。
  const agentBubbles = [];

  try {
    await streamPost(
      '/api/interview/' + state.sessionId + '/answer/stream',
      { answer: text },
      (payload) => {
        let evt;
        try {
          evt = JSON.parse(payload);
        } catch (_) {
          return;           // 不认识的事件忽略，不报错（契约扩展要求）
        }

        // 任何后续事件都撤掉"正在判题…"（D19 契约）
        if (evt.delta === undefined) removeToolHint();

        if (typeof evt.delta === 'string') {
          removeToolHint();
          const author = evt.author || 'interviewer_agent';
          // author 变了就开新气泡——路由转移（coordinator → knowledge_agent）
          // 在 UI 上应该看得出来是两个人在说话
          if (!bubble || author !== bubbleAuthor) {
            bubble = addMessage('agent', '', agentLabel(author));
            bubbleAuthor = author;
            agentBubbles.push(bubble);
          }
          bubble.textContent += evt.delta;
          scrollToBottom();
          return;
        }

        if (evt.tool) {
          showToolHint(evt.tool);
          return;
        }

        if (typeof evt.round === 'number') {
          state.round = evt.round;
          finished = !!evt.finished;
          updateRoundChip();
          return;
        }

        if (evt.message) {
          addMessage('system', evt.message, '提示');
        }
      }
    );

    if (finished) {
      addMessage('system', '本场面试已结束（共 ' + state.round + ' 轮）。点右上角「换一场」可以重新开始。', '系统');
    }
  } catch (e) {
    addMessage('system', '发送失败：' + e.message, '错误');
  } finally {
    // 流结束后统一渲染 Markdown：此时气泡里的 textContent 是完整纯文本，
    // 一次性转成 HTML 才不会出现"半个 ** 被吃掉"的中间态。
    for (const b of agentBubbles) {
      b.innerHTML = mdToHtml(b.textContent);
    }
    removeToolHint();
    setBusy(false);
    el.taAnswer.focus();
  }
}

// ---------------------------------------------------------------------------
// 判题
// ---------------------------------------------------------------------------

/**
 * 会话级判题结果流：**同一会话只开一条，多次提交复用**（D26 二修）。
 *
 * 为什么不能每次提交都开一条：SSE 客户端 `abort()` 之后，服务端**要等到下一次写入
 * 失败**才知道对端走了（`SseEmitter` 没有主动探测）。于是每次判题都留一条僵尸连接，
 * 服务端日志里 `当前连接数` 一路涨、下次推送时报 `Broken pipe`（D26 实测到的 WARN 噪音）。
 * 复用一条流，连接数恒定为 1。
 *
 * 结果按 `submissionId` 分发：流上收到的事件先进 `recent`，有等待者就直接给。
 * 这样即使结果比 `POST` 的响应先到，也不会丢。
 */
function judgeStream(sessionId) {
  if (state.judgeStream && !state.judgeStream.dead) {
    return state.judgeStream;
  }

  const s = {
    /** submissionId → resolve 回调 */
    waiters: new Map(),
    /** submissionId → 已到达但还没人领的结果（防"结果比 POST 响应先到"） */
    recent: new Map(),
    dead: false,
    /** 首次 `fetch` 拿到响应头后置 true——用来决定提交前要不要等 300ms */
    ready: false,
    controller: new AbortController(),
  };

  (async () => {
    try {
      const resp = await fetch('/api/interview/' + sessionId + '/judge/stream',
        { signal: s.controller.signal });
      if (!resp.ok) throw new Error('结果流连不上：' + resp.status);
      s.ready = true;
      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = parseSse(buffer, (payload) => {
          let evt;
          try { evt = JSON.parse(payload); } catch (_) { return; }   // 注释帧/坏帧忽略
          const w = s.waiters.get(evt.submissionId);
          if (w) {
            s.waiters.delete(evt.submissionId);
            w(evt);
          } else {
            s.recent.set(evt.submissionId, evt);
            // 只留最近 20 条：没人领的结果没有任何价值，攒着只会占内存
            while (s.recent.size > 20) {
              s.recent.delete(s.recent.keys().next().value);
            }
          }
        });
      }
    } catch (_) {
      // 网络错误 / abort：落到下面统一标记死亡，由调用方走轮询兜底
    }
    s.dead = true;
    // 流断了：把还在等的人全部放掉（返回 null），他们会去轮询
    for (const [, w] of s.waiters) w(null);
    s.waiters.clear();
    if (state.judgeStream === s) {
      state.judgeStream = null;
    }
  })();

  state.judgeStream = s;
  return s;
}

/**
 * 等某个提交的结果。流已断或已死时立刻返回 null（调用方靠轮询）。
 * **不会永久 pending**——否则 `Promise.race` 里它会让超时兜底失效。
 */
function awaitJudgeResult(s, submissionId) {
  const cached = s.recent.get(submissionId);
  if (cached) {
    s.recent.delete(submissionId);
    return Promise.resolve(cached);
  }
  if (s.dead) {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => s.waiters.set(submissionId, resolve));
}

async function submitJudge() {
  const code = el.taCode.value.trim();
  if (!code) {
    setJudgeStatus('先贴一段代码', 'err');
    return;
  }
  if (!state.sessionId) {
    setJudgeStatus('还没有会话，请先开始面试', 'err');
    return;
  }

  el.btnJudge.disabled = true;
  setJudgeStatus('已提交，等待判题结果…', 'warn');
  el.judgeResult.hidden = true;

  // 先有结果流，再提交——反过来的话可能判得太快、结果在订阅前就推过了。
  // 流是会话级复用的（见 judgeStream 注释），不是每次提交都新开。
  const stream = judgeStream(state.sessionId);
  // 轮询的停止开关放在 try 外面：finally 也要能停掉它（提前返回/抛错时）
  const pollCtl = { stop: false };
  /** POST 成功后才有值；finally 用它清掉等待者（见 finally 的注释） */
  let submittedId = null;
  // 60s 总闸：只停轮询。若 SSE 那时也没给结果，下面的 `await pollPromise` 会拿到 null
  // 并抛超时——**不能**改成 abort 流，那会让"流先到但慢"的正常路径误伤。
  const timeout = setTimeout(() => { pollCtl.stop = true; }, 60000);

  try {
    // 稍等一下让**首次**连接建立（已连上的会话这里等于空转 300ms）
    if (!stream.ready) {
      await new Promise((r) => setTimeout(r, 300));
    }

    const body = { code };
    if (el.taStdin.value.trim()) body.stdin = el.taStdin.value;
    if (el.taExpected.value.trim()) body.expectedOutput = el.taExpected.value;

    const resp = await fetch('/api/interview/' + state.sessionId + '/judge', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) {
      let detail = resp.status + ' ' + resp.statusText;
      try {
        const p = await resp.json();
        if (p.detail) detail = p.detail;
      } catch (_) { /* ignore */ }
      throw new Error(detail);
    }
    const submitted = await resp.json();
    submittedId = submitted.submissionId;

    // 判题结果有两条路，**并行跑、谁先到用谁**（D26）：
    //  ① SSE 结果流：本地/局域网下最快，推送即到；
    //  ② 轮询：以前只是"流断了才用"的兜底，现在必须从一开始就并行跑。
    //     原因是**中间层会压住"长时间没有数据"的响应**：实测经 Cloudflare Tunnel
    //     提交后判题 1.2 秒就完成了、服务端也推送成功，但客户端 24 秒收到 0 字节，
    //     要等 SSE 的 60s 超时才降级轮询——用户白等一分钟。
    //     独立实验（docs/evidence/D26/sse_probe_server.py）证明这与响应体大小无关：
    //     先发 4KB 再静默，同样收不到。
    const pollPromise = pollJudge(state.sessionId, submitted.submissionId, pollCtl);
    const evt = await Promise.race([
      awaitJudgeResult(stream, submitted.submissionId),
      pollPromise,
    ]).catch(() => null);

    if (evt) {
      pollCtl.stop = true;
      renderJudgeResult(evt);
    } else {
      // 两条路都没结果：把轮询的最终状态如实说出来，不再假装是"流断开"
      const last = await pollPromise;
      if (last) {
        renderJudgeResult(last);
      } else {
        throw new Error('判题结果超时（60s）');
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      setJudgeStatus('等待超时（60s）', 'err');
    } else {
      setJudgeStatus(e.message, 'err');
    }
  } finally {
    clearTimeout(timeout);
    pollCtl.stop = true;      // 任何退出路径都停掉轮询，别让它在后台继续打接口
    // **清掉本次的等待者**：超时/异常退出时结果可能永远不来，
    // 留在 Map 里就是一条只增不减的泄漏（流不断的话它活到换场）。
    if (submittedId) stream.waiters.delete(submittedId);
    el.btnJudge.disabled = false;
  }
}

/**
 * 轮询取判题结果。最多等 60s，超时返回 null（**不抛**，方便和 SSE 竞速时统一处理）。
 *
 * @param ctl 可选的 `{stop:boolean}`：SSE 先拿到结果时置 true，让轮询立刻收手，
 *            否则它会继续每秒钟打一次接口直到 60s。
 */
async function pollJudge(sessionId, submissionId, ctl) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline && !(ctl && ctl.stop)) {
    const resp = await fetch('/api/interview/' + sessionId + '/judge/' + submissionId);
    if (resp.ok) {
      const s = await resp.json();
      if (s.status !== 'PENDING') {
        return { submissionId: s.submissionId, status: s.status, result: s.result };
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return null;
}

function setJudgeStatus(text, kind) {
  el.judgeStatus.textContent = text || '';
  el.judgeStatus.className = 'judge-status' + (kind ? ' ' + kind : '');
}

/** JVM 启动噪声：容器里 JAVA_TOOL_OPTIONS 会让每个 java 子进程打这一行。 */
const JVM_NOISE = /^Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):.*$/gm;

function cleanStderr(text) {
  if (!text) return '';
  return text.replace(JVM_NOISE, '').trim();
}

function renderJudgeResult(evt) {
  const r = evt && evt.result;
  if (!r) {
    setJudgeStatus('判题状态：' + (evt ? evt.status : '未知'), 'err');
    return;
  }

  const lines = [];
  if (r.compiled === false) {
    setJudgeStatus('编译失败', 'err');
    lines.push('✗ 编译失败');
    if (r.compileOutput) lines.push('', r.compileOutput);
  } else if (r.timedOut) {
    setJudgeStatus('运行超时', 'err');
    lines.push('✗ 运行超时（' + (r.durationMs != null ? r.durationMs : '?') + ' ms）');
  } else if (r.error) {
    setJudgeStatus('判题异常', 'err');
    lines.push('✗ ' + r.error);
  } else if (r.outputMatched === true) {
    setJudgeStatus('✓ 通过', 'ok');
    lines.push('✓ 输出与期望一致');
  } else if (r.outputMatched === false) {
    setJudgeStatus('✗ 输出不符', 'err');
    lines.push('✗ 输出与期望不符');
  } else {
    setJudgeStatus('✓ 运行完成（未设置期望输出）', 'ok');
    lines.push('✓ 编译并运行成功');
  }

  if (r.className) lines.push('', '类名：' + r.className);
  // 注意：后端把所有 Long 序列化成字符串（雪花 ID 精度问题），
  // 所以 durationMs / exitCode 到这里是 "876" 而不是 876。用 != null 判空即可，
  // 不要用 typeof === 'number'——那会让耗时永远显示不出来（D25 踩过）。
  if (r.durationMs != null) lines.push('耗时：' + r.durationMs + ' ms');
  if (r.exitCode != null) lines.push('退出码：' + r.exitCode);

  if (r.stdout) {
    lines.push('', '── 标准输出 ──', r.stdout.replace(/\s+$/, ''));
  }
  const err = cleanStderr(r.stderr);
  if (err) {
    lines.push('', '── 标准错误 ──', err);
  }
  if (r.expectedOutput) {
    lines.push('', '── 期望输出 ──', r.expectedOutput);
  }

  el.judgeResult.textContent = lines.join('\n');
  el.judgeResult.hidden = false;
}

// ---------------------------------------------------------------------------
// 事件绑定
// ---------------------------------------------------------------------------

el.btnStart.addEventListener('click', startInterview);
el.btnSend.addEventListener('click', sendAnswer);

el.taAnswer.addEventListener('keydown', (e) => {
  // Enter 发送，Shift+Enter 换行——聊天框的通用习惯
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendAnswer();
  }
});

el.btnNew.addEventListener('click', () => {
  if (state.busy) return;
  // 判题结果流绑的是**上一个会话**，换场必须断开，否则新会话的结果会推给旧流
  // （服务端按 sessionId 分发，旧流永远收不到新会话的结果 → 只能靠轮询兜底）
  if (state.judgeStream) {
    state.judgeStream.controller.abort();
    state.judgeStream = null;
  }
  state.sessionId = null;
  state.round = 0;
  el.messages.innerHTML = '';
  el.chat.hidden = true;
  el.sessionInfo.hidden = true;
  el.welcome.hidden = false;
  el.judgeBody.hidden = true;
  el.judgeToggle.classList.remove('open');
  el.judgeChev.textContent = '▾';
  el.judgeResult.hidden = true;
  setJudgeStatus('');
  setHint(el.startHint, '');
  setHint(el.composerHint, '');
  setStatus('就绪', 'online');
});

el.judgeToggle.addEventListener('click', () => {
  const open = el.judgeBody.hidden;
  el.judgeBody.hidden = !open;
  el.judgeToggle.classList.toggle('open', open);
  el.judgeChev.textContent = open ? '▴' : '▾';
});

el.btnJudge.addEventListener('click', submitJudge);

// 启动时探一次健康检查，让用户知道后端通不通
(async () => {
  try {
    const resp = await fetch('/api/health', { method: 'GET' });
    if (resp.ok) {
      setStatus('就绪', 'online');
    } else {
      setStatus('后端异常（' + resp.status + '）', 'offline');
    }
  } catch (e) {
    setStatus('连不上后端', 'offline');
  }
})();
