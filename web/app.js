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
    removeToolHint();
    setBusy(false);
    el.taAnswer.focus();
  }
}

// ---------------------------------------------------------------------------
// 判题
// ---------------------------------------------------------------------------

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

  // 先开结果流，再提交——反过来的话可能判得太快、结果在订阅前就推过了
  const streamUrl = '/api/interview/' + state.sessionId + '/judge/stream';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60000);

  try {
    // 结果流用 GET。注意这里不是 fetch 的 streamPost（那个是 POST），
    // 单独写一小段，但复用同一个 SSE 解析器。
    // 立即挂一个 catch：若下面的 POST 先失败，我们会跳进 catch 分支，
  // streamPromise 永远不会被 await，而 finally 里的 abort() 会让它 reject——
  // 那会变成一个"未处理的 Promise 拒绝"，在控制台里冒红字（D25 二查）。
  // 这里先兜住，真正的错误由后续 await 处理。
  const streamPromise = (async () => {
      const resp = await fetch(streamUrl, { signal: controller.signal });
      if (!resp.ok) throw new Error('结果流连不上：' + resp.status);
      const reader = resp.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) return null;
        buffer += decoder.decode(value, { stream: true });
        let found = null;
        buffer = parseSse(buffer, (payload) => {
          try { found = JSON.parse(payload); } catch (_) { /* ignore */ }
        });
        if (found) {
          controller.abort();
          return found;
        }
      }
    })().catch(() => null);   // abort / 网络错误都归一成 null，由调用方走轮询兜底

    // 稍等一下让流连上，再提交
    await new Promise((r) => setTimeout(r, 300));

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

    const evt = await streamPromise;
    if (!evt) {
      // 流断了但提交成功：退化为轮询（D20 的兜底接口）
      setJudgeStatus('结果流断开，改用轮询…', 'warn');
      const evt2 = await pollJudge(state.sessionId, submitted.submissionId);
      renderJudgeResult(evt2);
    } else {
      renderJudgeResult(evt);
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      setJudgeStatus('等待超时（60s）', 'err');
    } else {
      setJudgeStatus(e.message, 'err');
    }
  } finally {
    clearTimeout(timeout);
    controller.abort();
    el.btnJudge.disabled = false;
  }
}

/** 轮询兜底（SSE 不可用时）。最多等 60s。 */
async function pollJudge(sessionId, submissionId) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const resp = await fetch('/api/interview/' + sessionId + '/judge/' + submissionId);
    if (resp.ok) {
      const s = await resp.json();
      if (s.status !== 'PENDING') {
        return { submissionId: s.submissionId, status: s.status, result: s.result };
      }
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('轮询超时');
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
