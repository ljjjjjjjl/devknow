/*
 * devknow · Agent 流式输出演示
 *
 * 为什么不用 EventSource：它只支持 GET，而 agentConfigId + query 要走请求体。
 * 所以用 fetch + ReadableStream 手动解析 SSE —— 顺带把"SSE 就是一段
 * text/event-stream 的纯文本"这件事显式地写出来，而不是藏在浏览器里。
 */

(() => {
  'use strict';

  const API = '/api/agent/chat/stream';
  const LOG_LIMIT = 300;

  const el = {
    messages: document.getElementById('messages'),
    empty: document.getElementById('empty'),
    composer: document.getElementById('composer'),
    query: document.getElementById('query'),
    configId: document.getElementById('config-id'),
    send: document.getElementById('send'),
    stop: document.getElementById('stop'),
    hint: document.getElementById('hint'),
    log: document.getElementById('log'),
    clearLog: document.getElementById('clear-log'),
    status: document.getElementById('status'),
    statusText: document.getElementById('status-text'),
  };

  let controller = null;   // 当前请求的 AbortController
  let streaming = false;

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
  });

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

  // ---------------------------------------------------------------- 主流程

  async function run(agentConfigId, query) {
    streaming = true;
    toggleSending(true);
    setStatus('busy', '生成中…');
    setHint('');

    const card = createAnswerCard();
    const state = { text: '', steps: 0, done: null };
    const startedAt = performance.now();
    controller = new AbortController();

    try {
      const resp = await fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentConfigId, query }),
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
      appendLog('done', `前端计时 ${Math.round(performance.now() - startedAt)} ms`);
      scrollToBottom();
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

    switch (event) {
      case 'step':
        state.steps = data.step;
        setStepBadge(card, `第 ${data.step} 步`);
        break;

      case 'delta':
        state.text += data.text;
        // textContent 而不是 innerHTML：模型输出是不可信内容
        card.body.textContent = state.text;
        scrollToBottom();
        break;

      case 'tool':
        card.meta.appendChild(badge(`🔧 ${data.name}`, 'tool'));
        setHint(`正在执行 ${data.name}…`);
        break;

      case 'citation':
        addCitation(card, data);
        break;

      case 'done':
        state.done = data;
        card.meta.appendChild(badge(`${data.steps} 步`, 'step'));
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
        break;

      case 'error':
        card.meta.appendChild(badge(`错误 ${data.code}：${data.message}`, 'err'));
        setStatus('err', '后端返回错误');
        setHint('');
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

    const citations = document.createElement('div');
    citations.className = 'citations';

    wrap.append(bubble, meta, citations);
    el.messages.appendChild(wrap);
    scrollToBottom();

    return { wrap, body, cursor, meta, citations, stepBadge: null };
  }

  function setStepBadge(card, text) {
    if (!card.stepBadge) {
      card.stepBadge = badge(text, 'step');
      card.meta.insertBefore(card.stepBadge, card.meta.firstChild);
    } else {
      card.stepBadge.textContent = text;
    }
  }

  function addCitation(card, data) {
    const a = document.createElement('a');
    a.className = 'cite';
    a.href = data.url || '#';
    a.target = '_blank';
    a.rel = 'noopener noreferrer';

    const id = document.createElement('span');
    id.className = 'id';
    id.textContent = `#${data.documentId}`;

    a.append(id, document.createTextNode(data.title || '(无标题)'));
    card.citations.appendChild(a);
    scrollToBottom();
  }

  function badge(text, kind) {
    const span = document.createElement('span');
    span.className = kind ? `badge ${kind}` : 'badge';
    span.textContent = text;
    return span;
  }

  function appendLog(event, data) {
    const line = document.createElement('div');
    line.className = `e-${event}`;
    const payload = typeof data === 'string' ? data : JSON.stringify(data);
    line.textContent = `[${event}] ${payload}`;
    el.log.appendChild(line);

    while (el.log.childElementCount > LOG_LIMIT) {
      el.log.removeChild(el.log.firstChild);
    }
    el.log.scrollTop = el.log.scrollHeight;
  }

  function scrollToBottom() {
    el.messages.scrollTop = el.messages.scrollHeight;
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
