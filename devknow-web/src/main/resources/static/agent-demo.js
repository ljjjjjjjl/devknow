/*
 * devknow · Agent 流式输出演示
 *
 * 为什么不用 EventSource：它只支持 GET，而 agentConfigId + query 要走请求体。
 * 所以用 fetch + ReadableStream 手动解析 SSE —— 顺带把"SSE 就是一段
 * text/event-stream 的纯文本"这件事显式地写出来，而不是藏在浏览器里。
 *
 * 多轮：第一轮不带 conversationId，后端新建会话后用 start 事件把 id 推回来；
 * 之后每一轮都带上它，服务端据此恢复上下文。点"新会话"清空 id 重新开始。
 */

(() => {
  'use strict';

  const API = '/api/agent/chat/stream';
  const LOG_LIMIT = 300;

  /** 距底部多少像素以内还算"贴着底"，超出就不再自动跟随 */
  const STICK_THRESHOLD = 80;

  const el = {
    messages: document.getElementById('messages'),
    empty: document.getElementById('empty'),
    composer: document.getElementById('composer'),
    query: document.getElementById('query'),
    configId: document.getElementById('config-id'),
    send: document.getElementById('send'),
    stop: document.getElementById('stop'),
    reset: document.getElementById('reset'),
    conv: document.getElementById('conv'),
    jump: document.getElementById('jump'),
    hint: document.getElementById('hint'),
    cites: document.getElementById('cites'),
    citeCount: document.getElementById('cite-count'),
    log: document.getElementById('log'),
    logCount: document.getElementById('log-count'),
    clearLog: document.getElementById('clear-log'),
    status: document.getElementById('status'),
    statusText: document.getElementById('status-text'),
  };

  let controller = null;   // 当前请求的 AbortController
  let streaming = false;
  let conversationId = null; // null = 下一轮会新建会话
  let logTotal = 0;        // 累计事件数（日志本身只留最近 LOG_LIMIT 条）
  let deltaLine = null;    // 本次回答的 delta 汇总行，null 表示还没开始
  let deltaCount = 0;

  // ---------------------------------------------------------------- 滚动

  /**
   * 用户是不是还贴着底？
   *
   * 每来一个 delta 都无条件滚到底，会把长回答的开头一路顶出视野 ——
   * 看上去就像内容被截断了；而且他想往回读还会被不停拽回来。
   * 所以只在"他本来就在底部"时才跟随。
   */
  function isNearBottom() {
    return el.messages.scrollHeight - el.messages.scrollTop - el.messages.clientHeight
      <= STICK_THRESHOLD;
  }

  function scrollToBottom() {
    el.messages.scrollTop = el.messages.scrollHeight;
    updateJump();
  }

  /** 贴着底就跟随，否则亮出"跳到最新" */
  function follow(stick) {
    if (stick) {
      scrollToBottom();
    } else {
      updateJump();
    }
  }

  function updateJump() {
    el.jump.hidden = isNearBottom();
  }

  el.messages.addEventListener('scroll', updateJump);
  el.jump.addEventListener('click', scrollToBottom);

  // ---------------------------------------------------------------- 发送

  el.composer.addEventListener('submit', (e) => {
    e.preventDefault();
    submit();
  });

  el.query.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  });

  el.stop.addEventListener('click', () => {
    if (controller) {
      controller.abort();
      setHint('已请求停止');
    }
  });

  el.clearLog.addEventListener('click', () => {
    el.log.textContent = '';
    logTotal = 0;
    deltaLine = null;
    deltaCount = 0;
    el.logCount.textContent = '0';
  });

  el.reset.addEventListener('click', newConversation);

  el.empty.addEventListener('click', (e) => {
    if (e.target.classList.contains('sample')) {
      el.query.value = e.target.textContent;
      submit();
    }
  });

  function submit() {
    const query = el.query.value.trim();
    const agentConfigId = Number(el.configId.value) || 1;
    if (!query) {
      setHint('先输入一个问题');
      return;
    }
    if (streaming) {
      setHint('上一条还在生成中');
      return;
    }
    el.query.value = '';
    appendUserBubble(query);
    if (el.empty) el.empty.remove();
    run(agentConfigId, query);
  }

  /**
   * 开一个新会话：清掉 conversationId 与消息区。
   *
   * 消息区要连 #empty 一起恢复 —— 它在第一次提问时被移除了，
   * 而示例按钮的点击是委托在 #empty 上的，所以直接把原节点挂回去最省事。
   */
  function newConversation() {
    if (streaming) {
      setHint('先等这一条结束，或点"停止"');
      return;
    }
    conversationId = null;
    updateConv();

    el.messages.textContent = '';
    el.messages.appendChild(el.empty);
    el.cites.textContent = '';
    el.citeCount.textContent = '0';
    el.query.value = '';

    setStatus('idle', '就绪');
    setHint('已开新会话，下一句会新建一个');
    updateJump();
  }

  function updateConv() {
    const active = conversationId !== null;
    el.conv.textContent = active ? `会话 #${conversationId}` : '尚未开始';
    el.conv.classList.toggle('active', active);
  }

  // ---------------------------------------------------------------- 主流程

  async function run(agentConfigId, query) {
    streaming = true;
    toggleSending(true);
    setStatus('busy', '生成中…');
    setHint('');

    // 引用与 delta 汇总都属于"这一次回答"，新一轮开始就清空
    el.cites.textContent = '';
    el.citeCount.textContent = '0';
    deltaLine = null;
    deltaCount = 0;

    const card = createAnswerCard();
    const state = { text: '', steps: 0, done: null };
    const startedAt = performance.now();
    controller = new AbortController();

    // 有会话就带上，让服务端恢复上下文；null 时整个字段不发，
    // 后端按"新建会话"处理（显式传 null 也可以，但少个字段更干净）
    const payload = { agentConfigId, query };
    if (conversationId !== null) {
      payload.conversationId = conversationId;
    }

    try {
      const resp = await fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      if (!resp.ok) {
        // 出错时后端返回的是普通 JSON（不是 SSE），所以先看状态码
        throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
      }
      if (!resp.body) {
        throw new Error('响应没有可读流');
      }

      await readStream(resp.body, (event, data) => {
        handleEvent(event, data, state, card);
      });

      if (!state.done) {
        setStatus('err', '流结束但没有 done 事件');
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        card.meta.appendChild(badge('已停止', 'err'));
        setStatus('idle', '已停止');
      } else {
        card.meta.appendChild(badge(`请求失败：${err.message}`, 'err'));
        setStatus('err', '请求失败');
      }
    } finally {
      streaming = false;
      controller = null;
      card.cursor.classList.add('hidden');
      toggleSending(false);
      appendLog('note', `前端计时 ${Math.round(performance.now() - startedAt)} ms`);
      updateJump();
    }
  }

  // ---------------------------------------------------------------- SSE 解析

  /**
   * 逐块读 text/event-stream，按空行切事件块。
   * 不能假设"一次 read 就是一个事件" —— TCP 分片与事件边界完全无关。
   */
  async function readStream(body, onEvent) {
    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      // 统一换行：SSE 规范允许 \r\n，而 \r\n\r\n 会让 indexOf('\n\n') 漏掉分隔
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

      let cut;
      while ((cut = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, cut);
        buffer = buffer.slice(cut + 2);
        dispatch(block, onEvent);
      }
    }
    if (buffer.trim()) dispatch(buffer, onEvent);
  }

  function dispatch(block, onEvent) {
    let event = 'message';
    const dataLines = [];

    for (const rawLine of block.split('\n')) {
      const line = rawLine.replace(/\r$/, '');
      if (!line || line.startsWith(':')) continue;          // 空行 / 心跳注释
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));    // 规范允许冒号后一个空格
      }
    }
    if (!dataLines.length) return;

    const text = dataLines.join('\n');
    let data = text;
    try {
      data = JSON.parse(text);
    } catch (_) {
      /* 不是 JSON 就按纯文本处理 */
    }
    onEvent(event, data);
  }

  // ---------------------------------------------------------------- 事件处理

  function handleEvent(event, data, state, card) {
    appendLog(event, data);

    // 必须在改 DOM 之前判断：内容一变 scrollHeight 就变了
    const stick = isNearBottom();

    switch (event) {
      case 'start':
        // 会话 id 到手。第一轮之后每轮都带上它，追问才能接上上下文
        conversationId = data.conversationId;
        updateConv();
        break;

      case 'step':
        state.steps = data.step;
        setStepBadge(card, `第 ${data.step} 步`);
        break;

      case 'delta':
        state.text += data.text;
        // textContent 而不是 innerHTML：模型输出是不可信内容
        card.body.textContent = state.text;
        follow(stick);
        break;

      case 'tool':
        card.meta.appendChild(badge(`🔧 ${data.name}`, 'tool'));
        setHint(`正在执行 ${data.name}…`);
        break;

      case 'citation':
        // 引用渲染在右栏，不影响消息区高度，所以不用跟着滚
        addCitation(data);
        break;

      case 'done':
        state.done = data;
        // 步数徽章 setStepBadge 已经放了一个（"第 N 步"），这里不再重复加"2 步"
        card.meta.appendChild(badge(`${data.elapsedMs} ms`));
        card.meta.appendChild(badge(`${(data.citations || []).length} 条引用`));
        card.meta.appendChild(badge(data.model));
        // 流式拼出来的文本与最终答案对不上时以后端为准，并在日志里留痕
        if (data.answer && data.answer !== state.text) {
          appendLog('error', '流式文本与最终答案不一致，已以后端返回覆盖');
          state.text = data.answer;
          card.body.textContent = data.answer;
        }
        setStatus('ok', `完成 · ${data.elapsedMs} ms`);
        setHint('');
        follow(stick);
        break;

      case 'error':
        card.meta.appendChild(badge(`错误 ${data.code}：${data.message}`, 'err'));
        setStatus('err', '后端返回错误');
        setHint('');
        follow(stick);
        break;

      default:
        break;
    }
  }

  // ---------------------------------------------------------------- DOM

  function appendUserBubble(text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg user';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;
    wrap.appendChild(bubble);
    el.messages.appendChild(wrap);
    scrollToBottom();
  }

  function createAnswerCard() {
    const wrap = document.createElement('div');
    wrap.className = 'msg';

    const bubble = document.createElement('div');
    bubble.className = 'bubble answer';

    const body = document.createElement('div');
    body.className = 'answer-body';

    const cursor = document.createElement('span');
    cursor.className = 'cursor';

    body.appendChild(cursor);
    bubble.appendChild(body);

    const meta = document.createElement('div');
    meta.className = 'meta';

    wrap.append(bubble, meta);
    el.messages.appendChild(wrap);
    scrollToBottom();

    return { wrap, body, cursor, meta, stepBadge: null };
  }

  function setStepBadge(card, text) {
    if (!card.stepBadge) {
      card.stepBadge = badge(text, 'step');
      card.meta.insertBefore(card.stepBadge, card.meta.firstChild);
    } else {
      card.stepBadge.textContent = text;
    }
  }

  function addCitation(data) {
    const a = document.createElement('a');
    a.className = 'cite';
    a.href = data.url || '#';
    a.target = '_blank';
    a.rel = 'noopener noreferrer';

    const id = document.createElement('span');
    id.className = 'id';
    id.textContent = `#${data.documentId}`;

    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = data.title || '(无标题)';

    a.append(id, title);
    el.cites.appendChild(a);
    el.citeCount.textContent = String(el.cites.childElementCount);
  }

  function badge(text, kind) {
    const span = document.createElement('span');
    span.className = kind ? `badge ${kind}` : 'badge';
    span.textContent = text;
    return span;
  }

  function appendLog(event, data) {
    // delta 一个 token 一行的话，一次回答就能刷出 400+ 行，把真正要看的事件全淹掉。
    // 合并成一行计数，并让它始终贴在日志末尾。
    if (event === 'delta') {
      if (!deltaLine) {
        deltaLine = document.createElement('div');
        deltaLine.className = 'e-delta';
        el.log.appendChild(deltaLine);
      }
      deltaCount += 1;
      deltaLine.textContent = `[delta] ×${deltaCount}`;
      el.log.appendChild(deltaLine);      // appendChild 会把已有节点移到末尾
    } else {
      const line = document.createElement('div');
      line.className = `e-${event}`;
      const payload = typeof data === 'string' ? data : JSON.stringify(data);
      line.textContent = `[${event}] ${payload}`;
      el.log.appendChild(line);
    }

    while (el.log.childElementCount > LOG_LIMIT) {
      // 别把还在用的 delta 汇总行删掉，否则下一个 delta 会把它又插回末尾
      if (el.log.firstChild === deltaLine) break;
      el.log.removeChild(el.log.firstChild);
    }
    el.log.scrollTop = el.log.scrollHeight;

    logTotal += 1;
    el.logCount.textContent = String(logTotal);
  }

  function toggleSending(busy) {
    el.send.disabled = busy;
    el.stop.disabled = !busy;
  }

  function setHint(text) {
    el.hint.textContent = text;
  }

  function setStatus(kind, text) {
    el.status.className = kind === 'busy' ? 'status busy'
      : kind === 'ok' ? 'status ok'
      : kind === 'err' ? 'status err'
      : 'status';
    el.statusText.textContent = text;
  }
})();
