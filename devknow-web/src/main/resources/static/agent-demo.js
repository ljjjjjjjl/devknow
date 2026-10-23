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

  /**
   * 示例问题池。
   *
   * <b>不是随手写的。</b>每一条都用 {@code POST /api/admin/search} 打过，只保留 top-1
   * 精排分 ≥ 0.6 的（Agent 的拒答阈值是 0.4，要留足余量），并且人工确认过 top-5 里
   * 确实有对得上主题的文档 —— 光看分数不够：「@WebMvcTest 应该怎么用」的 top-1 是
   * 一条 AOP auto-configuration issue，真正的权威文档（Testing Spring Boot
   * Applications）排在它后面一位。
   *
   * 被淘汰的三条（做示例会直接触发拒答，很难看）：
   * 「Logback 升级要注意什么」0.367、「虚拟线程怎么开启，有什么坑」0.591、
   * 「怎么给接口加限流」0.027。
   *
   * 元组第二个值是测量时的 top-1 精排分，悬停能看到。换语料或换模型后要重跑。
   */
  const SAMPLE_POOL = [
    ['怎么配置多个数据源', 0.99],
    ['Spring Boot 里怎么配置 JTA 分布式事务', 1.00],
    ['@WebMvcTest 应该怎么用', 0.93],
    ['怎么开启优雅停机', 0.88],
    ['如何自定义健康检查端点', 0.95],
    ['Spring Boot 3 升级要注意什么', 0.89],
    ['怎么配置 CORS', 0.92],
    ['怎么把指标接入 Prometheus', 0.88],
    ['怎么用 Testcontainers 写集成测试', 0.99],
    ['怎么配置 Redis 连接池', 0.95],
    ['Spring Boot 的配置文件加载顺序是什么', 0.92],
    ['怎么用 @ConfigurationProperties 绑定配置', 0.98],
    ['怎么自定义 Banner', 0.98],
  ];

  /** 每次显示几个示例问题 */
  const SAMPLE_COUNT = 3;

  /** 示例按钮的配色档位数，与 CSS 里的 .sample.c1 ~ .c5 对应 */
  const SAMPLE_COLORS = 5;

  const el = {
    messages: document.getElementById('messages'),
    empty: document.getElementById('empty'),
    samples: document.getElementById('samples'),
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
    renderSamples();          // 顺手换一批示例，不然"新会话"看到的还是刚才那三个
    el.cites.textContent = '';
    el.citeCount.textContent = '0';
    el.query.value = '';

    setStatus('idle', '就绪');
    setHint('已开新会话，下一句会新建一个');
    updateJump();
  }

  /**
   * 随机填一批示例问题。
   *
   * 洗牌用 Fisher-Yates：{@code sort(() => Math.random() - .5)} 那种写法分布不均，
   * 池子不大但也没必要将就。色档同样随机起头，免得每批都是"蓝绿紫"同一个顺序。
   */
  function renderSamples() {
    const pool = SAMPLE_POOL.slice();
    for (let i = pool.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [pool[i], pool[j]] = [pool[j], pool[i]];
    }
    const base = Math.floor(Math.random() * SAMPLE_COLORS);

    el.samples.textContent = '';
    pool.slice(0, SAMPLE_COUNT).forEach(([question, score], idx) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `sample c${((base + idx) % SAMPLE_COLORS) + 1}`;
      btn.textContent = question;
      btn.title = `top-1 精排分 ${score.toFixed(2)}`;
      el.samples.appendChild(btn);
    });
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

    // startedAt 必须早于 createAnswerCard：思考计时器的起点是"请求发出的那一刻"，
    // 而卡片一建出来就开始走秒
    const startedAt = performance.now();
    const card = createAnswerCard(startedAt);
    const state = { text: '', steps: 0, done: null, startedAt, thinkingMs: null };
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
      // 中断 / 报错时首字可能永远没来：计时器必须停，否则每失败一次就泄漏一个 interval，
      // 而且那块"正在思考"会一直转下去
      card.stopThink();
      if (card.thinkMs === null) {
        card.think.root.classList.add('gone');
      }
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
        card.setStage('会话已就绪，正在规划检索…');
        break;

      case 'step':
        state.steps = data.step;
        setStepBadge(card, `第 ${data.step} 步`);
        card.setStage(data.step <= 1
          ? '第 1 步 · 正在决定检索策略…'
          : `第 ${data.step} 步 · 正在整理答案…`);
        break;

      case 'delta':
        // 首个 delta 就是"用户看到第一个字"的那一刻 —— 思考时间在这里定格。
        // 之后的所有 delta 只更新正文，不再动计时
        if (state.thinkingMs === null) {
          state.thinkingMs = performance.now() - state.startedAt;
          finishThink(card, state.thinkingMs);
          card.cursor.classList.remove('hidden');
          // 底部提示原本停在"正在执行 search_knowledge…"，首字都到了它就成了过期信息
          setHint('');
        }
        state.text += data.text;
        // textContent 而不是 innerHTML：模型输出是不可信内容。
        // 但它会连光标一起清掉，所以文本写完后要把光标重新挂到末尾，
        // 这样生成过程中末尾一直有个闪烁的插入符（流式的"活着"的感觉全靠它）
        card.body.textContent = state.text;
        card.body.appendChild(card.cursor);
        follow(stick);
        break;

      case 'tool':
        card.meta.appendChild(badge(`🔧 ${data.name}`, 'tool'));
        card.setStage(`正在执行 ${data.name}…`);
        setHint(`正在执行 ${data.name}…`);
        break;

      case 'citation':
        // 引用渲染在右栏，不影响消息区高度，所以不用跟着滚
        addCitation(data);
        card.setStage(`已命中 ${el.cites.childElementCount} 篇依据，正在组织回答…`);
        break;

      case 'done':
        state.done = data;
        // 步数徽章 setStepBadge 已经放了一个（"第 N 步"），这里不再重复加"2 步"
        // 生成总时长不面向用户：节点照样进 DOM，但 .t-total 把它渲染成完全透明
        card.meta.appendChild(badge(`${data.elapsedMs} ms`)).classList.add('t-total');
        card.meta.appendChild(badge(`${(data.citations || []).length} 条引用`));
        card.meta.appendChild(badge(data.model));
        // 流式拼出来的文本与最终答案对不上时以后端为准，并在日志里留痕
        if (data.answer && data.answer !== state.text) {
          appendLog('error', '流式文本与最终答案不一致，已以后端返回覆盖');
          state.text = data.answer;
          card.body.textContent = data.answer;
        }
        finishThink(card, state.thinkingMs === null ? performance.now() - state.startedAt : state.thinkingMs);
        setStatus('ok', '完成', ` · ${data.elapsedMs} ms`);
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

  // ---------------------------------------------------------------- 思考态

  /** 秒数统一一位小数：否则 4 / 4.05 / 5.12 三种宽度会把后面的正文推来推去 */
  function fmtSec(ms) {
    return (ms / 1000).toFixed(1);
  }

  /**
   * 造一块"首字之前"的占位。
   *
   * <p>存在的理由很实际：请求发出后到第一个字抵达之间要等 4~5 秒，
   * 这段时间用户盯着的是一块空白，无法判断"发出去了"还是"卡住了"。
   * 主流 agent（Claude / ChatGPT / DeepSeek）都在这一层给反馈，
   * 差别只在文案走不到真实阶段 —— 这里用 SSE 的 start / step / tool / citation
   * 驱动文案，所以显示的是<b>真实</b>进度，不是假的轮播。
   */
  function createThink(startedAt) {
    const root = document.createElement('div');
    root.className = 'think';

    const head = document.createElement('div');
    head.className = 'think-head';

    const orb = document.createElement('span');
    orb.className = 'think-orb';

    const text = document.createElement('span');
    text.className = 'think-text';
    text.setAttribute('aria-live', 'polite');
    text.textContent = '正在连接模型…';

    const timer = document.createElement('span');
    timer.className = 'think-timer';
    timer.textContent = '0.0s';

    head.append(orb, text, timer);

    const bar = document.createElement('div');
    bar.className = 'think-bar';
    bar.appendChild(document.createElement('i'));

    const skel = document.createElement('div');
    skel.className = 'think-skeleton';
    for (const w of ['88%', '96%', '62%']) {
      const line = document.createElement('i');
      line.style.width = w;
      skel.appendChild(line);
    }

    root.append(head, bar, skel);

    // 100ms 一跳：比 1s 更"在动"，又不会让主线程每帧重排
    const tick = setInterval(() => {
      timer.textContent = `${fmtSec(performance.now() - startedAt)}s`;
    }, 100);

    return {
      root,
      setStage(msg) {
        if (msg) text.textContent = msg;
      },
      stop() {
        clearInterval(tick);
      },
    };
  }

  /**
   * 首个字到达：停表、折叠思考块、挂一个"已思考 x.x s"药丸。
   *
   * <p>折叠而不是删除 —— 药丸点一下能把它展开，复盘时想知道
   * "这 4.6 秒里到底卡在哪一步"就有地方看。重复调用是安全的（幂等）。
   */
  function finishThink(card, ms) {
    if (card.thinkMs !== null) {
      return;
    }
    card.thinkMs = ms;
    card.think.stop();
    card.think.root.classList.add('gone');

    const pill = document.createElement('button');
    pill.type = 'button';
    pill.className = 'badge think-pill';
    pill.textContent = `已思考 ${fmtSec(ms)}s`;
    pill.title = '从发出请求到第一个字抵达前端 · 点一下展开过程';
    pill.addEventListener('click', () => {
      card.think.root.classList.toggle('gone');
      pill.classList.toggle('open');
      updateJump();
    });
    card.meta.appendChild(pill);
    card.thinkPill = pill;
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

  function createAnswerCard(startedAt) {
    const wrap = document.createElement('div');
    wrap.className = 'msg';

    const bubble = document.createElement('div');
    bubble.className = 'bubble answer';

    // 思考块在前、正文在后：首字之前的视觉高度由它提供，
    // 首字到达后它塌缩成 0 高，正文自然顶上来，不需要手动调布局
    const think = createThink(startedAt);

    const body = document.createElement('div');
    body.className = 'answer-body';

    // 光标一开始是隐藏的：思考阶段有旋转环和扫光条，再来个闪烁光标太吵
    const cursor = document.createElement('span');
    cursor.className = 'cursor hidden';

    body.appendChild(cursor);
    bubble.append(think.root, body);

    const meta = document.createElement('div');
    meta.className = 'meta';

    wrap.append(bubble, meta);
    el.messages.appendChild(wrap);
    scrollToBottom();

    return {
      wrap, body, cursor, meta, stepBadge: null,
      think, thinkPill: null, thinkMs: null,
      setStage: (msg) => think.setStage(msg),
      stopThink: () => think.stop(),
    };
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

  /**
   * 顶栏状态。
   *
   * @param masked 附在文字后面的"看不见的部分"（生成总时长）。
   *   仍然写进 DOM 是为了可调试、可复制，但由 CSS 渲染成全透明 ——
   *   产品上不想让用户盯着总耗时，那份数据留在右栏原始事件流里就够。
   */
  function setStatus(kind, text, masked) {
    el.status.className = kind === 'busy' ? 'status busy'
      : kind === 'ok' ? 'status ok'
      : kind === 'err' ? 'status err'
      : 'status';
    el.statusText.textContent = text;
    if (masked) {
      const span = document.createElement('span');
      span.className = 't-total';
      span.textContent = masked;
      el.statusText.appendChild(span);
    }
  }

  // ---------------------------------------------------------------- 启动

  // 首屏就填一批示例问题（每次刷新都不一样）
  renderSamples();
  updateConv();
})();
