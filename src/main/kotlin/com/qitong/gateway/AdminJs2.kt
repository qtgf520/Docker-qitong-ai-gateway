package com.qitong.gateway

/** 后台 JS 第二部分：模型 + 测速 + 聊天 */
object AdminJs2 {
    fun js(): String = """
// ===== 模型 =====
loaders.models = function(){
  var box = $('view-models');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  Promise.all([api('/api/models'), api('/api/providers')]).then(function(res){
    state.models = res[0].data || []; state.providers = res[1].data || [];
    var pmap = {}; state.providers.forEach(function(p){ pmap[p.id] = p.name; });
    var rows = state.models.map(function(m){
      var enBtn = '<button class="btn-ghost ' + (m.isEnabled ? '' : 'danger') + '" style="padding:2px 8px;font-size:12px" onclick="toggleModel(' + m.id + ')">' + (m.isEnabled ? '停用' : '启用') + '</button>';
      var pubTxt = m.isPublic ? '<span class="badge green">公用</span>' : (m.ownerId>0 ? '<span class="badge blue">私有</span>' : '<span class="badge gray">系统</span>');
      var priceTxt = m.price > 0 ? ('¥'+m.price+'/M') : '<span style="color:var(--muted)">默认价</span>';
      return '<tr><td>'+(m.isEnabled?'<span class="badge green">✓</span>':'<span class="badge gray">✗</span>')+'</td><td>'+esc(m.modelId)+'</td><td>'+esc(m.displayName)+(m.customAlias?' <span class="badge purple">'+esc(m.customAlias)+'</span>':'')+'</td><td><span class="badge blue">'+esc(pmap[m.providerId]||('P'+m.providerId))+'</span></td><td>'+pubTxt+'</td><td><span class="badge blue">'+esc(m.ownerName||'')+'</span></td><td>'+priceTxt+'</td><td>'+(m.isDefault?'<span class="badge green">默认</span>':'')+'</td><td style="font-size:12px">'+m.contextWindow+'</td><td>'+enBtn+'</td><td><button class="btn-ghost" onclick="editModel('+m.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delModel('+m.id+')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="action-bar">',
        '<button class="btn" onclick="editModel(0)">＋ 手动添加模型</button>',
        '<button class="btn" id="batchSpeedBtn" onclick="runBatchSpeedTest()">⚡ 自动全部测速</button>',
        '<label style="display:flex;align-items:center;gap:4px;font-size:13px;color:var(--muted)"><input type="checkbox" id="batchAutoClose" checked> 自动关闭失败模型</label>',
        '<span style="color:var(--muted);font-size:12px">测速通过自动启用 / 失败自动关闭（可手动启用）</span>',
      '</div><div class="card" id="batchSpeedCard" style="display:none"></div>',
      '<div class="card"><div class="table-wrap"><table><thead><tr><th></th><th>模型ID</th><th>显示名</th><th>服务商</th><th>归属</th><th>属主</th><th>价格</th><th>默认</th><th>上下文</th><th>启停</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="11" style="text-align:center;color:var(--muted)">' + (state.models.length ? '' : '暂无模型') + '</td></tr>' +
      '</tbody></table></div></div>'
    ].join('');
  });
};
// 行内启停（对齐原APP toggleModel）
window.toggleModel = function(id){
  api('/api/models/toggle', { method:'POST', body: { id: id } }).then(function(r){
    toast(r.msg, r.code === 0);
    loaders.models();
  });
};
// 自动全部测速（对齐原APP batchTestAllModels）：串行测试全部模型，可选自动关闭失败模型
window.runBatchSpeedTest = function(){
  var card = $('batchSpeedCard');
  var autoClose = $('batchAutoClose') ? $('batchAutoClose').checked : true;
  card.style.display = 'block';
  card.innerHTML = '<div style="text-align:center;color:var(--muted);padding:30px">⚡ 正在逐个串行测速全部模型（三指标：TTFT/TPS/总耗时）...<br><span style="font-size:12px">测速通过将自动启用，失败' + (autoClose ? '将自动关闭' : '保留原状态') + '</span></div>';
  $('batchSpeedBtn').disabled = true;
  api('/api/speedtest/batch', { method:'POST', body: { autoClose: autoClose } }).then(function(r){
    $('batchSpeedBtn').disabled = false;
    if(r.code === 0){
      var list = r.data || [];
      var rows = list.map(function(h,i){
        var stOk = h.isHealthy;
        var ttft = stOk && h.ttftMs > 0 ? h.ttftMs + 'ms' : '—';
        var tps = stOk && h.tps > 0 ? h.tps.toFixed(2) + ' tok/s' : '—';
        var total = stOk && h.totalMs > 0 ? h.totalMs + 'ms' : '—';
        return '<tr><td>'+(i+1)+'</td><td>'+esc(h.modelId)+'</td><td>P'+h.providerId+'</td><td>'+ttft+'</td><td>'+tps+'</td><td>'+total+'</td><td>'+esc(h.displayName||'')+'</td><td><span class="badge '+(stOk?'green':'red')+'">'+(stOk?'正常':'失败')+'</span></td><td><span class="badge '+(h.enabled?'green':'gray')+'">'+(h.enabled?'已启用':'已停用')+'</span></td></tr>';
      }).join('');
      card.innerHTML = '<div class="table-wrap"><table><thead><tr><th>#</th><th>模型ID</th><th>服务商</th><th>TTFT</th><th>TPS</th><th>总耗时</th><th>显示名</th><th>状态</th><th>启用</th></tr></thead><tbody>' +
        rows + '<tr><td colspan="9" style="text-align:center;color:var(--muted)">' + (list.length ? '' : '没有可测速的模型') + '</td></tr>' +
      '</tbody></table></div>';
      toast('✅ 批量测速完成：' + list.filter(function(x){ return x.isHealthy; }).length + '/' + list.length + ' 正常', true);
      loaders.models(); // 刷新启停状态
    } else {
      card.innerHTML = '<div style="color:var(--red);padding:20px;text-align:center">' + esc(r.msg || '测速失败') + '</div>';
    }
  });
};
window.editModel = function(id){
  var m = state.models.find(function(x){ return x.id===id; }) || {};
  var opts = state.providers.map(function(p){ return '<option value="'+p.id+'"'+(p.id===m.providerId?' selected':'')+'>'+esc(p.name)+'</option>'; }).join('');
  var html = [
    '<div class="form-row"><label>服务商 *</label><select id="mdProv" class="input">'+(opts||'<option value="0">请先添加服务商</option>')+'</select></div>',
    '<div class="form-row"><label>模型ID *</label><input id="mdId" class="input" value="'+esc(m.modelId||'')+'" placeholder="如 gpt-4o"></div>',
    '<div class="form-row"><label>显示名</label><input id="mdName" class="input" value="'+esc(m.displayName||'')+'"></div>',
    '<div class="form-row"><label>自定义别名</label><input id="mdAlias" class="input" value="'+esc(m.customAlias||'')+'"></div>',
    '<div class="form-row"><label>上下文窗口</label><input id="mdCtx" class="input" type="number" value="'+(m.contextWindow||4096)+'"></div>',
    '<div class="form-row"><label>单价（元/百万Token，0=自动默认价）</label><input id="mdPrice" class="input" type="number" step="0.1" value="'+(m.price||0)+'"><small style="color:var(--muted)">如 gpt-4o 默认 ¥15/M，留0自动按内置价格表</small></div>',
    '<div class="form-row"><label><input type="checkbox" id="mdEnabled"'+(m.isEnabled!==false?' checked':'')+'> 启用</label> <label style="margin-left:12px"><input type="checkbox" id="mdPublic"'+(m.isPublic?' checked':'')+'> 公用（所有用户可见可用）</label> <label style="margin-left:12px"><input type="checkbox" id="mdDefault"'+(m.isDefault?' checked':'')+'> 默认</label></div>'
  ].join('');
  openModal(id ? '编辑模型' : '添加模型', html, function(){
    var body = { id:id||0, providerId:parseInt($('mdProv').value)||0, modelId:$('mdId').value.trim(), displayName:$('mdName').value.trim()||$('mdId').value.trim(), customAlias:$('mdAlias').value.trim(), contextWindow:parseInt($('mdCtx').value)||4096, price:parseFloat($('mdPrice').value)||0, isEnabled:$('mdEnabled').checked, isPublic:$('mdPublic').checked, isDefault:$('mdDefault').checked };
    if(!body.modelId){ toast('请填写模型ID', false); return; }
    api('/api/models', { method:'POST', body: body }).then(function(r){
      if(r.code === 0){ toast('✅ 保存成功', true); closeModal(); loaders.models(); } else toast(r.msg, false);
    });
  });
};
window.delModel = function(id){
  if(!confirm('确定删除该模型？')) return;
  api('/api/models/' + id, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); loaders.models(); } else toast(r.msg, false);
  });
// ===== 测速（对齐原APP：默认显示全部待测模型 + 后台逐个测速 + 进度条，离开页面也在跑） =====
var speedTestActive = false;
var speedPollTimer = null;
loaders.speedtest = function(){
  $('view-speedtest').innerHTML = '<div class="action-bar"><button class="btn" onclick="runSpeedTest()">⚡ 开始批量测速</button><button class="btn-ghost" onclick="loadSpeedModels()">🔄 刷新列表</button><span style="color:var(--muted);font-size:13px">后台逐模型测速：离开页面也继续跑，回来看进度/结果</span></div><div class="card" id="stCard"><div style="text-align:center;color:var(--muted);padding:30px">加载模型列表...</div></div><div style="text-align:center;color:var(--muted);font-size:12px" id="stCountdown"></div>';
  // 先渲染待测列表，再检查是否有后台任务在跑 → 有则继续轮询
  loadSpeedModels();
  checkSpeedTask();
};
// 检查后台测速任务（离开页面回来也能接续）
function checkSpeedTask(){
  api('/api/speedtest/progress').then(function(r){
    if(r.code === 0 && r.data){
      var s = r.data;
      if(s.running){ speedTestActive = true; pollSpeedProgress(); }
      else if((s.results||[]).length){ renderSpeedResults(s); }
    }
  });
}
// 加载待测模型列表（默认全部显示为"待测速"）
function loadSpeedModels(){
  api('/api/speedtest/models').then(function(r){
    if(r.code !== 0){ toast(r.msg, false); return; }
    var models = r.data || [];
    var rows = models.map(function(m){
      var key = m.providerId + '::' + m.modelId;
      return '<tr id="stRow-'+esc(key)+'" data-key="'+esc(key)+'"><td>'+esc(m.displayName||m.modelId)+'</td><td>'+esc(m.modelId)+'</td><td><span class="badge blue">'+esc(m.providerName||('P'+m.providerId))+'</span></td><td>'+(m.enabled?'<span class="badge green">已启用</span>':'<span class="badge gray">停用</span>')+'</td><td class="st-state" style="color:var(--muted)">⏳ 待测速</td><td class="st-ttft">—</td><td class="st-tps">—</td><td class="st-total">—</td></tr>';
    }).join('');
    $('stCard').innerHTML = '<div class="table-wrap"><table><thead><tr><th>显示名</th><th>模型ID</th><th>服务商</th><th>状态</th><th>测试状态</th><th>TTFT</th><th>TPS</th><th>总耗时</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="8" style="text-align:center;color:var(--muted)">' + (models.length ? '' : '暂无已启用模型，请先在模型页启用或添加') + '</td></tr>' +
      '</tbody></table></div>';
  });
}
// 渲染进度条 + 轮询后台任务结果
function pollSpeedProgress(){
  if(speedPollTimer) clearInterval(speedPollTimer);
  // 确保有进度条容器
  if(!$('stProgWrap')){
    var stCard = $('stCard');
    if(stCard){
      var progBar = document.createElement('div');
      progBar.id = 'stProgWrap';
      progBar.style.cssText = 'margin-bottom:12px;background:rgba(15,23,42,.5);border-radius:8px;height:8px;overflow:hidden;position:relative';
      progBar.innerHTML = '<div id="stProgFill" style="width:0%;height:100%;background:linear-gradient(90deg,#4F46E5,#06B6D4);transition:width .4s"></div><div id="stProgText" style="position:absolute;top:-18px;right:0;font-size:11px;color:var(--muted)"></div>';
      stCard.parentNode.insertBefore(progBar, stCard);
    }
  }
  function tick(){
    api('/api/speedtest/progress').then(function(r){
      if(r.code === 0 && r.data){
        var s = r.data;
        var pct = s.progress || 0;
        var fill = $('stProgFill'); var txt = $('stProgText');
        if(fill) fill.style.width = pct + '%';
        if(txt) txt.textContent = pct + '% (' + (s.done||0) + '/' + (s.total||0) + ')';
        // 当前在测的模型高亮
        if(s.currentKey){
          var row = document.getElementById('stRow-' + esc(s.currentKey));
          if(row){
            var cells = row.querySelectorAll('td');
            var st = cells[4];
            if(st){ st.textContent = '⏳ 测速中...'; st.style.color = 'var(--cyan)'; }
          }
        }
        // 已完成的渲染结果
        renderSpeedResults(s);
        if(s.running){
          setTimeout(tick, 1200);
        } else {
          speedTestActive = false;
          var btn = $('view-speedtest').querySelector('.action-bar .btn');
          if(btn) btn.innerHTML = '⚡ 开始批量测速';
          var wrap = $('stProgWrap');
          if(wrap && wrap.parentNode) wrap.parentNode.removeChild(wrap);
          if(s.error) toast('⚠️ ' + s.error, false);
          else toast('✅ 测速完成：' + (s.passed||0) + '/' + (s.total||0) + ' 正常', true);
        }
      }
    });
  }
  tick();
}
// 渲染测速结果到表格行（按 data-key 匹配）
function renderSpeedResults(s){
  var results = s.results || [];
  results.forEach(function(d){
    var key = d.providerId + '::' + d.modelId;
    var row = document.getElementById('stRow-' + esc(key));
    if(!row) return;
    var cells = row.querySelectorAll('td');
    var ok = d.isHealthy;
    var st = cells[4]; if(st){ st.textContent = ok ? '✅ 完成' : '❌ 失败'; st.style.color = ok ? 'var(--green)' : 'var(--red)'; }
    if(cells[5]) cells[5].textContent = ok && d.ttftMs > 0 ? d.ttftMs + 'ms' : '—';
    if(cells[6]) cells[6].textContent = ok && d.tps > 0 ? d.tps.toFixed(2) + ' tok/s' : '—';
    if(cells[7]) cells[7].textContent = ok && d.totalMs > 0 ? d.totalMs + 'ms' : '—';
  });
}
// 启动后台批量测速（前端只发一次，其余交给后端 + 轮询）
window.runSpeedTest = function(){
  if(speedTestActive){ toast('测速进行中，请等待完成', false); return; }
  var btn = $('view-speedtest').querySelector('.action-bar .btn');
  if(btn) btn.innerHTML = '⏳ 启动中...';
  api('/api/speedtest/start', { method:'POST', body: {} }).then(function(r){
    if(r.code === 0){
      speedTestActive = true;
      if(btn) btn.innerHTML = '⏳ 测速中...';
      pollSpeedProgress();
    } else {
      if(btn) btn.innerHTML = '⚡ 开始批量测速';
      toast(r.msg || '启动失败', false);
    }
  });
};
};
// ===== 聊天 =====
loaders.chat = function(){
  var box = $('view-chat');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  Promise.all([api('/api/conversations'), api('/api/models')]).then(function(res){
    state.conversations = res[0].data || []; state.models = res[1].data || [];
    var convOpts = '<option value="0">＋ 新对话</option>' + state.conversations.map(function(c){ return '<option value="'+c.id+'">'+esc(c.title)+'</option>'; }).join('');
    var modelOpts = state.models.map(function(m){ return '<option value="'+esc(m.modelId)+'">'+esc(m.displayName)+'</option>'; }).join('') || '<option value="qtai-sj">🔄 自动化切换</option>';
    box.innerHTML = [
      '<div class="card" style="padding:12px 16px"><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">',
        '<select id="chatConv" class="input" style="width:160px" onchange="loadConvMsgs()">'+convOpts+'</select>',
        '<select id="chatModel" class="input" style="width:180px">'+modelOpts+'</select>',
        '<button class="btn-ghost" onclick="deleteConv()">删除会话</button>',
      '</div></div>',
      '<div class="card chat-box"><div id="chatMsgs" class="chat-msgs"><div class="msg-row system"><div class="bubble">选择或新建会话开始聊天</div></div></div>',
        '<div class="chat-input"><input id="chatInput" class="input" placeholder="输入消息... (Enter发送)" onkeydown="if(event.key===EnterKey)sendChat()"><button class="btn" onclick="sendChat()">发送</button></div>',
      '</div>'
    ].join('');
  });
};
var EnterKey = 'Enter';
window.loadConvMsgs = function(){
  var sel = $('chatConv'); if(!sel) return;
  var id = sel.value;
  state.currentChatConv = parseInt(id) || 0;
  if(!id || id === '0'){ $('chatMsgs').innerHTML = '<div class="msg-row system"><div class="bubble">新对话</div></div>'; return; }
  api('/api/conversations/' + id).then(function(r){
    if(r.code === 0){
      var msgs = r.data.messages || [];
      $('chatMsgs').innerHTML = msgs.map(function(m){
        return '<div class="msg-row '+m.role+'"><div class="bubble">'+esc(m.content)+'</div></div>';
      }).join('') || '<div class="msg-row system"><div class="bubble">空对话</div></div>';
      var el = $('chatMsgs'); el.scrollTop = el.scrollHeight;
    }
  });
};
window.sendChat = function(){
  var input = $('chatInput'); if(!input) return;
  var content = input.value.trim(); if(!content) return;
  var convId = state.currentChatConv || 0;
  var model = $('chatModel').value || 'qtai-sj';
  var msgs = $('chatMsgs');
  msgs.innerHTML += '<div class="msg-row user"><div class="bubble">'+esc(content)+'</div></div><div class="msg-row assistant"><div class="bubble" id="waitBubble">思考中...</div></div>';
  msgs.scrollTop = msgs.scrollHeight;
  input.value = '';
  api('/api/chat', { method:'POST', body: { conversationId: convId, content: content, model: model } }).then(function(r){
    var wb = $('waitBubble');
    if(r.code === 0){
      var reply = r.data.reply || '(无响应)';
      // 打字机逐字显示效果
      var full = reply;
      var i = 0;
      var tick = setInterval(function(){
        i += 2;  // 每次2字，流畅
        if(wb){
          wb.textContent = full.slice(0, i) + (i < full.length ? '▍' : '');
          msgs.scrollTop = msgs.scrollHeight;
        }
        if(i >= full.length){ clearInterval(tick); if(wb) wb.textContent = full; msgs.scrollTop = msgs.scrollHeight; }
      }, 16);
      if(convId === 0){ state.currentChatConv = r.data.conversationId; setTimeout(function(){ loaders.chat(); }, full.length * 2 + 200); }
    } else {
      if(wb) wb.textContent = '❌ ' + (r.msg || '失败');
    }
  });
};
window.deleteConv = function(){
  var id = state.currentChatConv;
  if(!id){ toast('请先选择会话', false); return; }
  if(!confirm('删除该会话？')) return;
  api('/api/conversations/' + id, { method:'DELETE' }).then(function(r){
    if(r.code === 0){ toast('✅ 已删除', true); state.currentChatConv = 0; loaders.chat(); } else toast(r.msg, false);
  });
};
"""
}