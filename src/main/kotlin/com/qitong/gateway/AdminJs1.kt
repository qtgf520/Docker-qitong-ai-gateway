package com.qitong.gateway

/** 后台 JS 第一部分：核心框架 + 首页 + 服务商 */
object AdminJs1 {
    fun js(): String = """
var token = localStorage.getItem('qt_token') || '';
if(!token){ location.href='/login'; }
var state = { providers: [], models: [], keys: [], rules: [], conversations: [], currentChatConv: 0 };
function $(id){ return document.getElementById(id); }
function esc(s){ if(s==null) return ''; return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'&#39;'); }
function toast(msg, ok){ var t=$('toast'); t.textContent=msg; t.className='toast show '+(ok?'ok':'err'); setTimeout(function(){ t.className='toast'; }, 2600); }
function fmtBytes(b){ b=b||0; if(b<1024)return b+'B'; if(b<1048576)return(b/1024).toFixed(1)+'KB'; if(b<1073741824)return(b/1048576).toFixed(1)+'MB'; return(b/1073741824).toFixed(2)+'GB'; }
function fmtNum(n){ n=n||0; return n.toLocaleString?n.toLocaleString():String(n); }
function fmtUptime(s){ s=s||0; if(s<60)return s+'秒'; if(s<3600)return Math.floor(s/60)+'分钟'; if(s<86400)return Math.floor(s/3600)+'小时'; return Math.floor(s/86400)+'天'; }
function openModal(title, html, onSave){
  $('modalTitle').textContent = title;
  $('modalBody').innerHTML = html + '<div style="margin-top:14px;display:flex;gap:10px"><button class="btn" onclick="modalSave()">保存</button><button class="btn-ghost" onclick="closeModal()">取消</button></div>';
  window.modalSave = onSave;
  $('modal').classList.add('show');
}
function closeModal(){ $('modal').classList.remove('show'); }
function api(url, opts){
  opts = opts || {};
  opts.method = opts.method || 'GET';
  var headers = { 'Authorization': 'Bearer ' + token };
  var body = opts.body;
  if(body && typeof body === 'object'){ headers['Content-Type'] = 'application/json'; body = JSON.stringify(body); }
  return fetch(url, { method: opts.method, headers: headers, body: body }).then(function(res){
    return res.text().then(function(txt){
      var data = null;
      try { data = JSON.parse(txt); } catch(e){ data = { code:-1, msg:'响应异常' }; }
      if(res.status === 401){ localStorage.removeItem('qt_token'); location.href='/login'; }
      return data;
    });
  }).catch(function(err){ return { code:-1, msg:'网络错误: '+err.message }; });
}
// ===== 导航 =====
var loaders = {};
function switchView(pg){
  document.querySelectorAll('.view').forEach(function(v){ v.classList.remove('active'); });
  var el = $('view-' + pg); if(el) el.classList.add('active');
  document.querySelectorAll('.sidebar nav a, .bottom-nav a').forEach(function(a){
    a.classList.toggle('active', a.getAttribute('data-page') === pg);
  });
  closeSidebar();
  var fn = loaders[pg]; if(fn) fn();
}
function toggleSidebar(){
  var s=$('sidebar'), m=$('mask');
  if(s.classList.contains('open')){ s.classList.remove('open'); m.classList.remove('show'); }
  else { s.classList.add('open'); m.classList.add('show'); }
}
function closeSidebar(){ $('sidebar').classList.remove('open'); $('mask').classList.remove('show'); }
document.addEventListener('click', function(e){
  var a = e.target.closest ? e.target.closest('a[data-page]') : null;
  if(a){ e.preventDefault(); switchView(a.getAttribute('data-page')); }
});
function doLogout(){ localStorage.removeItem('qt_token'); location.href='/login'; }
// ===== 首页（完整功能：启停/地址/自动测速/强制池/排行榜） =====
loaders.dashboard = function(){
  var box = $('view-dashboard');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/status').then(function(r){
    var st = r.data;
    if(!st) return;
    // 模型排行榜（含测速状态）
    var rankRows = '';
    (st.pipelineSorted || []).forEach(function(key, i){
      var parts = key.split('::');
      var mid = parts.length > 1 ? parts[1] : key;
      var pid = parts.length > 1 ? parts[0] : '';
      var h = (st.healthCache || []).find(function(x){ return x.key === key; });
      var ok = h ? h.isHealthy : false;
      var lat = h && h.isHealthy ? h.latencyMs + 'ms' : '—';
      rankRows += '<tr><td>' + (i+1) + '</td><td>' + esc(mid) + '</td><td>P' + esc(pid) + '</td><td>' + lat + '</td><td><span class="badge ' + (ok ? 'green' : 'gray') + '">' + (ok ? '正常' : '待测速') + '</span></td></tr>';
    });
    // 强制故障池
    var poolHtml = '';
    var pool = st.forcedPool || [];
    if(pool.length){
      poolHtml = '<div class="card"><h3>🎯 强制故障池 (' + pool.length + ')</h3><div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px">' +
        pool.map(function(k){
          var mid = k.split('::').length > 1 ? k.split('::')[1] : k;
          return '<span class="badge purple">' + esc(mid) + '</span>';
        }).join('') + '</div><button class="btn-ghost" onclick="clearForcedPool()">↩️ 清空</button></div>';
    }
    // 地址行
    var gwPort = st.gatewayPort || 18889;
    var ip = st.serverIp || '127.0.0.1';
    var addrHtml = '<div class="card"><h3>📋 网关地址</h3>' +
      '<div class="form-row"><label>本地地址</label><div class="addr-line" onclick="copyAddr(this)">http://localhost:' + gwPort + '/v1 <span class="copy-tag">📋 复制</span></div></div>' +
      '<div class="form-row"><label>服务器地址（对外）</label><div class="addr-line" onclick="copyAddr(this)">http://' + esc(ip) + ':' + gwPort + '/v1 <span class="copy-tag">📋 复制</span></div></div></div>';
    box.innerHTML = [
      '<div class="grid grid-2">',
        '<div class="card"><h3>⚡ 网关控制</h3><div style="display:flex;gap:10px;align-items:center">' +
          '<span id="gwDot" class="dot ' + (st.running ? '' : 'off') + '"></span>' +
          '<span id="gwState" style="font-weight:700;color:' + (st.running ? 'var(--green)' : 'var(--red)') + '">' + (st.running ? '运行中' : '已停止') + '</span>' +
          '<button class="btn" id="gwToggleBtn" onclick="toggleGateway()">' + (st.running ? '⏸ 暂停' : '▶️ 启动') + '</button>' +
        '</div><div style="margin-top:8px;font-size:12px;color:var(--muted)">活跃模型：' + esc(st.activeModel || 'qtai-sj') + '</div></div>',
        '<div class="card"><h3>⏱ 自动测速</h3><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">' +
          '<button class="btn" id="autoSpeedBtn" onclick="toggleAutoSpeed()">' + (st.autoSpeedTest ? '⏹ 停止自动测速' : '▶️ 启动自动测速') + '</button>' +
          '<select id="speedInterval" class="input" style="width:130px" onchange="saveSpeedInterval()">' +
            '<option value="5"' + (st.speedIntervalMin==5?' selected':'') + '>5分钟</option>' +
            '<option value="15"' + (st.speedIntervalMin==15?' selected':'') + '>15分钟</option>' +
            '<option value="30"' + (st.speedIntervalMin==30?' selected':'') + '>30分钟</option>' +
            '<option value="60"' + (st.speedIntervalMin==60?' selected':'') + '>1小时</option>' +
            '<option value="120"' + (st.speedIntervalMin==120?' selected':'') + '>2小时</option>' +
            '<option value="240"' + (st.speedIntervalMin==240?' selected':'') + '>4小时</option>' +
          '</select></div></div>',
      '</div>',
      addrHtml,
      poolHtml,
      '<div class="card"><h3>📊 模型排行榜</h3><div class="table-wrap"><table><thead><tr><th>#</th><th>模型ID</th><th>服务商</th><th>延迟</th><th>状态</th></tr></thead><tbody>' +
        (rankRows || '<tr><td colspan="5" style="text-align:center;color:var(--muted)">暂无模型，请先在服务商页添加并同步</td></tr>') +
      '</tbody></table></div></div>'
    ].join('');
  });
};
// ===== 首页操作 =====
window.toggleGateway = function(){
  api('/api/gateway/toggle', { method:'POST', body: { action: 'toggle' } }).then(function(r){
    toast(r.msg, r.code === 0);
    loaders.dashboard();
  });
};
window.toggleAutoSpeed = function(){
  var st = $('autoSpeedBtn');
  var turningOn = st ? st.textContent.indexOf('启动') >= 0 : true;
  var interval = parseInt($('speedInterval') ? $('speedInterval').value : 60) || 60;
  api('/api/gateway/auto-speedtest', { method:'POST', body: { enabled: turningOn, intervalMin: interval } }).then(function(r){
    toast(r.msg, r.code === 0);
    loaders.dashboard();
  });
};
window.saveSpeedInterval = function(){
  var interval = parseInt($('speedInterval').value) || 60;
  var on = ($('autoSpeedBtn') ? $('autoSpeedBtn').textContent.indexOf('停止') >= 0 : false);
  api('/api/gateway/auto-speedtest', { method:'POST', body: { enabled: on, intervalMin: interval } }).then(function(r){
    toast(r.msg, r.code === 0);
  });
};
window.clearForcedPool = function(){
  api('/api/gateway/forced-pool', { method:'POST', body: { action: 'clear' } }).then(function(r){
    toast(r.msg, r.code === 0);
    loaders.dashboard();
  });
};
window.copyAddr = function(el){
  var txt = el.childNodes[0].textContent.trim();
  if(navigator.clipboard){ navigator.clipboard.writeText(txt).then(function(){ toast('✅ 已复制: ' + txt, true); }); }
  else { toast('已复制: ' + txt, true); }
};
// ===== 服务商 =====
loaders.providers = function(){
  var box = $('view-providers');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/providers').then(function(r){
    state.providers = r.data || [];
    var rows = state.providers.map(function(p){
      return '<tr><td><b>'+esc(p.name)+'</b> <span class="badge purple">P'+(p.customId||p.id)+'</span></td><td>'+esc(p.type)+'</td><td style="font-size:12px;color:var(--muted)">'+esc(p.baseUrl)+(p.port?':'+esc(p.port):'')+'</td><td><span class="badge '+(p.isEnabled?'green':'gray')+'">'+(p.isEnabled?'已启用':'已停用')+'</span></td><td><button class="btn-ghost" onclick="editProvider('+p.id+')">编辑</button> <button class="btn-ghost" onclick="syncProvider('+p.id+')">同步</button> <button class="btn-ghost" style="color:var(--red)" onclick="delProvider('+p.id+')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="action-bar"><button class="btn" onclick="editProvider(0)">＋ 添加服务商</button><span style="color:var(--muted);font-size:12px">OpenAI Compatible / Ollama / Custom</span></div>',
      '<div class="card"><div class="table-wrap"><table><thead><tr><th>名称</th><th>类型</th><th>地址</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="5" style="text-align:center;color:var(--muted)">' + (state.providers.length ? '' : '暂无服务商') + '</td></tr>' +
      '</tbody></table></div></div>'
    ].join('');
  });
};
window.editProvider = function(id){
  var p = state.providers.find(function(x){ return x.id===id; }) || {};
  var html = [
    '<div class="form-row"><label>名称 *</label><input id="pvName" class="input" value="'+esc(p.name||'')+'"></div>',
    '<div class="form-row"><label>类型</label><select id="pvType" class="input"><option value="OpenAI Compatible"'+(p.type==='OpenAI Compatible'?' selected':'')+'>OpenAI Compatible</option><option value="Ollama"'+(p.type==='Ollama'?' selected':'')+'>Ollama</option><option value="Custom"'+(p.type==='Custom'?' selected':'')+'>Custom</option></select></div>',
    '<div class="form-row"><label>Base URL *</label><input id="pvUrl" class="input" value="'+esc(p.baseUrl||'')+'" placeholder="https://api.openai.com"></div>',
    '<div class="form-row"><label>端口（可选）</label><input id="pvPort" class="input" value="'+esc(p.port||'')+'"></div>',
    '<div class="form-row"><label>API Key</label><input id="pvKey" class="input" value="'+esc(p.apiKey||'')+'"></div>',
    '<div class="form-row"><label>聊天路径</label><input id="pvPath" class="input" value="'+esc(p.chatPath||'')+'" placeholder="/v1/chat/completions"></div>',
    '<div class="form-row"><label>自定义服务商ID（pID）</label><input id="pvCid" class="input" value="'+esc(p.customId||'')+'"></div>',
    '<div class="form-row"><label><input type="checkbox" id="pvEnabled"'+(p.isEnabled!==false?' checked':'')+'> 启用</label></div>'
  ].join('');
  openModal(id ? '编辑服务商' : '添加服务商', html, function(){
    var body = { id: id||0, name:$('pvName').value.trim(), type:$('pvType').value, baseUrl:$('pvUrl').value.trim(), port:$('pvPort').value.trim(), apiKey:$('pvKey').value.trim(), chatPath:$('pvPath').value.trim(), customId:$('pvCid').value.trim(), isEnabled:$('pvEnabled').checked };
    if(!body.name || !body.baseUrl){ toast('请填写名称和Base URL', false); return; }
    api('/api/providers', { method:'POST', body: body }).then(function(r){
      if(r.code === 0){ toast('✅ 保存成功', true); closeModal(); loaders.providers(); } else toast(r.msg, false);
    });
  });
};
window.delProvider = function(id){
  if(!confirm('确定删除该服务商？其下模型也会删除')) return;
  api('/api/providers/' + id, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.providers(); } else toast(r.msg, false);
  });
};
window.syncProvider = function(id){
  toast('⏳ 同步模型列表...', true);
  api('/api/providers/' + id + '/sync', { method:'POST' }).then(function(r){
    if(r.code === 0){ toast('✅ ' + r.msg, true); loaders.models(); } else toast(r.msg, false);
  });
};
"""
}