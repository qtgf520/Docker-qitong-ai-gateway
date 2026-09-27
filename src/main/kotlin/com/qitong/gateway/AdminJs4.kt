package com.qitong.gateway

/** 后台 JS 第四部分：UX 增强包 — 主题 / 命令面板 / 表格筛选排序 / 接入示例 / 自动刷新 / CSV 导出 */
object AdminJs4 {
    fun js(): String = """
// ================= UX 增强包 =================
(function(){
'use strict';

// ===== 主题切换（深色默认 / 浅色可选，localStorage 记忆） =====
function applyTheme(th){
  document.documentElement.setAttribute('data-theme', th);
  localStorage.setItem('qt_theme', th);
  var b = document.getElementById('themeBtn');
  if(b) b.textContent = (th === 'light') ? '深色' : '浅色';
  // 自定义强调色按明暗主题重算（浅色主题下自动压暗保证对比度）
  applyAccent(localStorage.getItem('qt_accent') || '');
}
window.toggleTheme = function(){
  applyTheme((localStorage.getItem('qt_theme') === 'light') ? 'dark' : 'light');
};
var savedTheme = localStorage.getItem('qt_theme');
if(savedTheme) applyTheme(savedTheme);

// ===== 全局强调色（自定义主题色，写入 CSS 变量全站生效） =====
var ACCENT_PRESETS = [
  ['默认蓝','#3E7BFA'],['青碧','#0E9488'],['苔绿','#3FB27F'],['琥珀','#D99A3D'],
  ['绯红','#E5636A'],['黛紫','#8B6DEB'],['石墨','#64748B']
];
function accentRgb(hex){
  hex = String(hex || '').replace('#', '');
  if(/^[0-9a-fA-F]{3}$/.test(hex)) hex = hex[0]+hex[0]+hex[1]+hex[1]+hex[2]+hex[2];
  if(!/^[0-9a-fA-F]{6}$/.test(hex)) return null;
  var n = parseInt(hex, 16);
  return [(n>>16)&255, (n>>8)&255, n&255];
}
function applyAccent(hex){
  var st = document.documentElement.style;
  if(!hex){
    st.removeProperty('--primary'); st.removeProperty('--primary2');
    st.removeProperty('--cyan'); st.removeProperty('--primary-rgb');
    localStorage.removeItem('qt_accent');
    return;
  }
  var c = accentRgb(hex); if(!c) return;
  var light = document.documentElement.getAttribute('data-theme') === 'light';
  var use = c;
  if(light){
    var lum = (0.2126*c[0] + 0.7152*c[1] + 0.0722*c[2]) / 255;
    if(lum > 0.42) use = [Math.round(c[0]*0.72), Math.round(c[1]*0.72), Math.round(c[2]*0.72)];
  }
  st.setProperty('--primary', 'rgb(' + use.join(',') + ')');
  st.setProperty('--primary2', 'rgb(' + use.join(',') + ')');
  st.setProperty('--cyan', 'rgb(' + use.join(',') + ')');
  st.setProperty('--primary-rgb', c.join(','));
  localStorage.setItem('qt_accent', hex);
}
window.applyAccent = applyAccent;

// ===== 液态玻璃开关 =====
function glassOn(){ return (localStorage.getItem('qt_glass') || 'on') !== 'off'; }
function applyGlass(on){
  document.documentElement.setAttribute('data-glass', on ? 'on' : 'off');
  localStorage.setItem('qt_glass', on ? 'on' : 'off');
  var b = document.getElementById('glassBtn');
  if(b) b.textContent = on ? '玻璃 开' : '玻璃 关';
}
window.quickToggleGlass = function(){
  var on = glassOn();
  applyGlass(!on);
  toast(!on ? '液态玻璃已开启' : '液态玻璃已关闭', true);
};

// ===== 外观设置弹窗 =====
window.openThemeSettings = function(){
  var cur = localStorage.getItem('qt_accent') || '';
  var sw = ACCENT_PRESETS.map(function(p){
    return '<button class="swatch' + (cur === p[1] ? ' sel' : '') + '" title="' + p[0] + '" style="background:' + p[1] + '" onclick="pickAccent(\'' + p[1] + '\')"></button>';
  }).join('');
  var html = '<div class="form-row"><label>强调色（按钮 / 徽章 / 聊天气泡 / 链接全站跟随）</label>' +
    '<div class="swatch-row">' + sw + '</div>' +
    '<div style="display:flex;gap:8px;margin-top:12px;align-items:center;flex-wrap:wrap">' +
    '<input type="color" id="accentPick" value="' + (cur || '#3E7BFA') + '" style="width:46px;height:32px;background:var(--inset);border:1px solid var(--border);border-radius:6px">' +
    '<button class="btn-ghost" onclick="pickAccent(document.getElementById(\'accentPick\').value)">应用自定义色</button>' +
    '<button class="btn-ghost" onclick="pickAccent(\'\')">恢复默认</button></div></div>' +
    '<div class="form-row"><label>液态玻璃</label>' +
    '<button class="btn-ghost" id="glassToggle" onclick="toggleGlassSetting()">' + (glassOn() ? '已开启 · 点击关闭' : '已关闭 · 点击开启') + '</button>' +
    '<div style="font-size:11px;color:var(--muted);margin-top:6px">作用于顶栏 / 侧栏 / 弹窗 / 命令面板 / Toast；低性能设备可关闭；系统开启“减少透明度”时自动退回实心</div></div>';
  openModal('主题外观', html, function(){}, { hideFooter: true });
};
window.pickAccent = function(hex){
  applyAccent(hex);
  toast(hex ? '主题色已应用' : '已恢复默认主题色', true);
  openThemeSettings();
};
window.toggleGlassSetting = function(){
  applyGlass(!glassOn());
  var b = document.getElementById('glassToggle');
  if(b) b.textContent = glassOn() ? '已开启 · 点击关闭' : '已关闭 · 点击开启';
};
// 初始化：玻璃默认开；已存的自定义强调色立即生效
applyGlass(glassOn());
(function(){ var sa = localStorage.getItem('qt_accent'); if(sa) applyAccent(sa); })();

// ===== 当前页刷新 / 自动刷新 =====
window.manualRefresh = function(){
  var cur = document.querySelector('.view.active');
  if(!cur) return;
  var pg = cur.id.replace('view-','');
  if(loaders[pg]){ loaders[pg](); toast('已刷新', true); }
};
var autoTimer = null;
window.toggleAutoRefresh = function(){
  if(autoTimer){ clearInterval(autoTimer); autoTimer = null; toast('自动刷新已关闭', true); return; }
  autoTimer = setInterval(function(){
    var cur = document.querySelector('.view.active');
    if(!cur) return;
    var pg = cur.id.replace('view-','');
    if(pg === 'chat') return; // 聊天页不自动刷新，避免打断
    if(loaders[pg]) loaders[pg]();
  }, 30000);
  toast('自动刷新已开启（每30秒）', true);
};

// ===== 页面切换跟踪（用于刷新与面板） =====
var curPage = 'dashboard';
var _origSwitchView = window.switchView;
if(typeof _origSwitchView === 'function'){
  window.switchView = function(pg){ _origSwitchView(pg); curPage = pg; };
}

// ===== 表格增强：筛选 + 点击表头排序 =====
function sortTable(t, idx, th){
  var tbody = t.querySelector('tbody'); if(!tbody) return;
  var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
  if(rows.length < 2) return;
  var asc = th.getAttribute('data-sort') !== 'asc';
  t.querySelectorAll('th').forEach(function(x){ x.removeAttribute('data-sort'); var si = x.querySelector('.sort-ind'); if(si) si.remove(); });
  th.setAttribute('data-sort', asc ? 'asc' : 'desc');
  var ind = document.createElement('span'); ind.className = 'sort-ind'; ind.textContent = asc ? '▲' : '▼'; th.appendChild(ind);
  rows.sort(function(a, b){
    var ca = a.children[idx], cb = b.children[idx];
    if(!ca || !cb) return 0;
    var ta = ca.textContent.trim().replace('¥','').replace('%',''), tb = cb.textContent.trim().replace('¥','').replace('%','');
    var na = parseFloat(ta), nb = parseFloat(tb);
    if(!isNaN(na) && !isNaN(nb) && /^[-+]?[\d.,]/.test(ta) && /^[-+]?[\d.,]/.test(tb)) return asc ? na - nb : nb - na;
    return asc ? ta.localeCompare(tb, 'zh') : tb.localeCompare(ta, 'zh');
  });
  rows.forEach(function(r){ tbody.appendChild(r); });
}
function setupTable(t){
  if(t.getAttribute('data-enh')) return;
  var bodyRows = t.querySelectorAll('tbody tr');
  var real = 0;
  bodyRows.forEach(function(r){ if(r.children.length > 1) real++; });
  if(real < 3) return; // 行太少不增强
  t.setAttribute('data-enh', '1');
  var wrap = t.parentNode; // .table-wrap
  if(wrap && wrap.parentNode && !wrap.parentNode.querySelector(':scope > .tbl-tools')){
    var tools = document.createElement('div');
    tools.className = 'tbl-tools';
    var inp = document.createElement('input');
    inp.className = 'input tbl-filter';
    inp.placeholder = '输入关键字筛选本表…';
    inp.addEventListener('input', function(){
      var q = this.value.toLowerCase();
      bodyRows = t.querySelectorAll('tbody tr');
      bodyRows.forEach(function(r){
        if(r.children.length <= 1){ return; } // 跳过“暂无数据”占位行
        r.style.display = (r.textContent.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
      });
    });
    tools.appendChild(inp);
    wrap.parentNode.insertBefore(tools, wrap);
  }
  var head = t.querySelector('thead');
  if(head && !t.getAttribute('data-sortable')){
    t.setAttribute('data-sortable', '1');
    Array.prototype.forEach.call(head.querySelectorAll('th'), function(th, idx){
      th.style.cursor = 'pointer';
      th.title = '点击排序';
      th.addEventListener('click', function(e){
        if(e.target.tagName === 'BUTTON' || e.target.tagName === 'INPUT') return;
        sortTable(t, idx, th);
      });
    });
  }
}

// ===== 首页接入示例卡（OpenAI / Claude / Gemini 三格式 + curl） =====
function escAttr(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function injectSnippets(active){
  if(document.getElementById('snipCard')) return;
  var addrEl = active.querySelector('.addr-line');
  if(!addrEl) return;
  var m = (addrEl.textContent || '').match(/https?:\/\/[^\s]+/);
  if(!m) return;
  var base = m[0].replace(/\/+$/, '');
  if(base.slice(-3) === '/v1') base = base.slice(0, -3);
  var card = document.createElement('div');
  card.className = 'card'; card.id = 'snipCard';
  card.innerHTML =
    '<h3>接入示例（Base URL 一键复制）</h3>' +
    '<div class="snip-row"><span class="snip-name">OpenAI 兼容</span><div class="addr-line" onclick="copyText(\'' + base + '/v1\')">' + escAttr(base) + '/v1 <span class="copy-tag">复制</span></div></div>' +
    '<div class="snip-row"><span class="snip-name">Claude 格式</span><div class="addr-line" onclick="copyText(\'' + base + '/v1\')">' + escAttr(base) + '/v1 <span class="copy-tag">复制</span></div></div>' +
    '<div class="snip-row"><span class="snip-name">Gemini 格式</span><div class="addr-line" onclick="copyText(\'' + base + '/v1beta\')">' + escAttr(base) + '/v1beta <span class="copy-tag">复制</span></div></div>' +
    '<div style="font-size:12px;color:var(--muted);margin:10px 0 6px">curl 示例（模型名可换成任意已启用模型）：</div>' +
    '<div class="code-block" id="snipCurl">curl ' + escAttr(base) + '/v1/chat/completions \\\n' +
    '  -H "Content-Type: application/json" \\\n' +
    '  -H "Authorization: Bearer sk-你的密钥" \\\n' +
    '  -d \'{"model":"模型ID","messages":[{"role":"user","content":"你好"}]}\'</div>' +
    '<div class="action-bar" style="margin:10px 0 0"><button class="btn-ghost" onclick="copyText(document.getElementById(\'snipCurl\').textContent)">复制 curl</button></div>' +
    '<small style="color:var(--muted);display:block;margin-top:8px">常见客户端（Cherry Studio / LobeChat / NextChat / 沉浸式翻译等）选择 OpenAI 兼容，填入上方 Base URL 与 API 密钥即可。</small>';
  var anchor = active.querySelector('.card');
  if(anchor && anchor.contains(addrEl)){ anchor.parentNode.insertBefore(card, anchor.nextSibling); }
  else { active.insertBefore(card, active.firstChild); }
  // 拉取一个可用模型名回填 curl
  api('/api/status').then(function(r){
    if(r.code !== 0) return;
    var hs = r.data.healthCache || [];
    var ok = hs.filter(function(x){ return x.isHealthy; });
    var pick = (ok[0] || hs[0] || {});
    var mid = pick.modelKey || pick.modelId || pick.mid || '';
    if(mid){
      var pre = document.getElementById('snipCurl');
      if(pre) pre.textContent = pre.textContent.replace('"模型ID"', '"' + mid + '"');
    }
  }).catch(function(){});
}

// ===== 用量页 CSV 导出 =====
window.exportUsageCsv = function(){
  var active = document.getElementById('view-usage');
  if(!active) return;
  var lines = [];
  active.querySelectorAll('.card').forEach(function(card, ci){
    var h = card.querySelector('h3');
    var t = card.querySelector('table');
    if(!t) return;
    lines.push('# ' + ((h && h.textContent) || ('表' + (ci+1))).trim());
    t.querySelectorAll('tr').forEach(function(tr){
      var cells = Array.prototype.map.call(tr.children, function(td){ return '"' + td.textContent.trim().replace(/"/g, '""') + '"'; });
      if(cells.length) lines.push(cells.join(','));
    });
    lines.push('');
  });
  if(lines.length === 0){ toast('没有可导出的数据', false); return; }
  var blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = 'usage-' + new Date().toISOString().slice(0, 10) + '.csv';
  a.click();
  URL.revokeObjectURL(url);
  toast('CSV 已导出', true);
};
function injectCsv(active){
  if(active.querySelector('.csv-bar')) return;
  var bar = document.createElement('div');
  bar.className = 'action-bar csv-bar';
  bar.innerHTML = '<button class="btn-ghost" onclick="exportUsageCsv()">导出 CSV</button><span style="font-size:12px;color:var(--muted)">导出本页全部用量表格，Excel 可直接打开</span>';
  active.insertBefore(bar, active.firstChild);
}

// ===== DOM 观察器：页面渲染后自动应用增强 =====
var enhanceTimer = null;
function enhancePage(){
  var active = document.querySelector('.view.active');
  if(!active) return;
  active.querySelectorAll('.table-wrap table').forEach(setupTable);
  if(active.id === 'view-dashboard') injectSnippets(active);
  if(active.id === 'view-usage') injectCsv(active);
}
function scheduleEnhance(){
  if(enhanceTimer) clearTimeout(enhanceTimer);
  enhanceTimer = setTimeout(enhancePage, 200);
}
var contentEl = document.getElementById('content');
if(contentEl && window.MutationObserver){
  new MutationObserver(scheduleEnhance).observe(contentEl, { childList: true, subtree: true });
}
scheduleEnhance();

// ===== Ctrl+K 命令面板 =====
var PAGES = [
  ['dashboard', '首页', '概览'], ['usage', '用量统计', '概览'], ['providers', '服务商', '接入'],
  ['models', '模型', '接入'], ['speedtest', '测速排行', '接入'], ['rules', '路由规则', '接入'],
  ['keys', 'API密钥', '接入'], ['chat', '内置聊天', '工具'], ['tickets', '工单中心', '工具'],
  ['settings', '网关设置', '系统'], ['profile', '个人中心', '系统'], ['logs', '全部操作日志', '系统'],
  ['announcements', '公告管理', '系统'], ['users', '用户管理', '系统'], ['about', '关于我们', '系统']
];
var CMDS = [
  { label: '切换 浅色/深色 主题', hint: '命令', run: function(){ toggleTheme(); } },
  { label: '刷新当前页面', hint: '命令', run: function(){ manualRefresh(); } },
  { label: '开启 / 关闭 自动刷新（30秒）', hint: '命令', run: function(){ toggleAutoRefresh(); } },
  { label: '导出用量 CSV', hint: '命令', run: function(){ switchView('usage'); setTimeout(exportUsageCsv, 900); } },
  { label: '批量测速全部模型', hint: '跳转', run: function(){ switchView('speedtest'); } },
  { label: '打开命令面板', hint: 'Ctrl+K', run: function(){ openPalette(); } }
];
var lastItems = [];
function buildItems(q){
  q = (q || '').toLowerCase();
  var items = [];
  PAGES.forEach(function(p){
    if(!q || p[1].toLowerCase().indexOf(q) >= 0 || p[0].indexOf(q) >= 0) items.push({ label: p[1], hint: p[2], run: function(){ switchView(p[0]); } });
  });
  CMDS.forEach(function(c){
    if(!q || c.label.toLowerCase().indexOf(q) >= 0) items.push({ label: c.label, hint: c.hint, run: c.run });
  });
  return items;
}
window.renderCmdk = function(q){
  var list = document.getElementById('cmdkList');
  lastItems = buildItems(q).slice(0, 12);
  if(!lastItems.length){ list.innerHTML = '<div class="cmdk-item" style="color:var(--muted)">无匹配结果</div>'; return; }
  list.innerHTML = lastItems.map(function(it, i){
    return '<div class="cmdk-item' + (i === 0 ? ' sel' : '') + '" data-i="' + i + '"><span>' + esc(it.label) + '</span><span class="cmdk-hint">' + esc(it.hint) + '</span></div>';
  }).join('');
  Array.prototype.forEach.call(list.querySelectorAll('.cmdk-item'), function(el){
    el.addEventListener('click', function(){ closePalette(); lastItems[parseInt(el.getAttribute('data-i'))].run(); });
  });
};
window.runFirstCmdk = function(){
  if(!lastItems.length) return;
  closePalette();
  lastItems[0].run();
};
window.openPalette = function(){
  var k = document.getElementById('cmdk');
  k.classList.add('show');
  var inp = document.getElementById('cmdkInput');
  inp.value = '';
  renderCmdk('');
  setTimeout(function(){ inp.focus(); }, 30);
};
window.closePalette = function(){
  var k = document.getElementById('cmdk');
  if(k) k.classList.remove('show');
};
document.addEventListener('keydown', function(e){
  if((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')){
    e.preventDefault();
    var k = document.getElementById('cmdk');
    if(k.classList.contains('show')) closePalette(); else openPalette();
  } else if(e.key === 'Escape'){
    closePalette();
  }
});
})();
"""
}
