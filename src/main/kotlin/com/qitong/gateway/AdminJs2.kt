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
   var key = m.providerId + '::' + m.modelId;
   return '<tr id="mdRow-' + esc(key) + '" data-key="' + esc(key) + '">' +
    '<td>'+(m.isEnabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>'+
    '<td>'+esc(m.modelId)+'</td><td>'+esc(m.displayName)+(m.customAlias?' <span class="badge purple">'+esc(m.customAlias)+'</span>':'')+'</td>'+
    '<td><span class="badge blue">'+esc(pmap[m.providerId]||('P'+m.providerId))+'</span></td>'+
    '<td>'+pubTxt+'</td><td><span class="badge blue">'+esc(m.ownerName||'')+'</span></td>'+
    '<td>'+priceTxt+'</td><td>'+(m.isDefault?'<span class="badge green">默认</span>':'')+'</td><td style="font-size:12px">'+m.contextWindow+'</td>'+
    '<td class="md-health" style="min-width:90px"><span class="badge gray">未测</span></td>'+
    '<td>'+enBtn+'</td>'+
    '<td style="white-space:nowrap"><button class="btn-ghost btn-sm md-test-btn" onclick="testOneModel('+m.providerId+',\''+esc(m.modelId)+'\',\''+esc(key)+'\')">测速</button> '+
    '<button class="btn-ghost" onclick="editModel('+m.id+')">编辑</button> <button class="btn-ghost" style="color:var(--red)" onclick="delModel('+m.id+')">删除</button></td></tr>';
  }).join('');
  box.innerHTML = [
   '<div class="action-bar">',
    '<button class="btn" onclick="editModel(0)">手动添加模型</button>',
    '<button class="btn" id="batchSpeedBtn" onclick="runBatchSpeedTest()">自动全部测速</button>',
    '<label style="display:flex;align-items:center;gap:4px;font-size:13px;color:var(--muted)"><input type="checkbox" id="batchAutoClose" checked>自动关闭失败模型</label>',
    '<span style="color:var(--muted);font-size:12px">点每行「测速」单测该模型；批量测速通过自动启用 / 失败自动关闭</span>',
   '</div><div class="card" id="batchSpeedCard" style="display:none"></div>',
   '<div class="card"><div class="table-wrap"><table><thead><tr><th>状态</th><th>模型ID</th><th>显示名</th><th>服务商</th><th>归属</th><th>属主</th><th>价格</th><th>默认</th><th>上下文</th><th>健康</th><th>启停</th><th>操作</th></tr></thead><tbody>' +
   rows + '<tr><td colspan="12" style="text-align:center;color:var(--muted)">' + (state.models.length ? '' : '暂无模型') + '</td></tr>' +
   '</tbody></table></div></div>'
  ].join('');
// 接续后台批量测速任务：高亮正在被测的那一行
   api('/api/speedtest/progress').then(function(r){
    if(r.code===0 && r.data && r.data.running){ pollMdProgress(); }
   });
   // 加载已有健康缓存显示到每行（测过的不再显示"未测"）
   api('/api/speedtest/health').then(function(rr){
    if(rr.code!==0 || !rr.data) return;
    var hmap = {};
    (rr.data||[]).forEach(function(h){ hmap[h.providerId+'::'+h.modelId] = h; });
    state.models.forEach(function(m){
     var key = m.providerId+'::'+m.modelId;
     var h = hmap[key];
     var row = document.getElementById('mdRow-' + key);
     if(!row || !h) return;
     var health = row.querySelector('.md-health'); if(!health) return;
     if(h.isHealthy){
      var tps = h.tps>0 ? h.tps.toFixed(1)+'t/s ' : '';
      health.innerHTML = '<span class="badge green" title="TTFT '+(h.ttftMs||0)+'ms / 总 '+(h.totalMs||0)+'ms">正常 '+tps+(h.totalMs||0)+'ms</span>';
     } else {
      health.innerHTML = '<span class="badge red">失败</span>';
     }
    });
   });
  });
 };
// 单模型测速：点某行「测速」按钮直接测
window.testOneModel = function(providerId, modelId, key){
  var row = document.getElementById('mdRow-' + key);
  var health = row ? row.querySelector('.md-health') : null;
  var btn = row ? row.querySelector('.md-test-btn') : null;
  if(btn) btn.disabled = true;
  if(health) health.innerHTML = '<span class="badge amber">测速中…</span>';
  api('/api/speedtest/one', { method:'POST', body:{ providerId:providerId, modelId:modelId } }).then(function(r){
   if(btn) btn.disabled = false;
   if(r.code!==0){ if(health) health.innerHTML = '<span class="badge red">失败</span>'; toast(r.msg||'测速失败', false); return; }
   var h = r.data || {};
   if(health){
    if(h.isHealthy){
     var tps = h.tps>0 ? h.tps.toFixed(1)+'t/s ' : '';
     health.innerHTML = '<span class="badge green" title="TTFT '+(h.ttftMs||0)+'ms / 总 '+(h.totalMs||0)+'ms">正常 '+tps+(h.totalMs||0)+'ms</span>';
    } else {
     health.innerHTML = '<span class="badge red">失败</span>';
    }
   }
  });
};
// 模型页轮询后台批量测速进度，高亮当前被测行 + 完成行回填状态
var mdPollTimer = null;
function pollMdProgress(){
 if(mdPollTimer) clearInterval(mdPollTimer);
 mdPollTimer = setInterval(function(){
  if(!$('view-models')){ clearInterval(mdPollTimer); return; }
  api('/api/speedtest/progress').then(function(r){
   if(r.code!==0 || !r.data){ return; }
   var d = r.data;
   if(d.currentKey){
    var row = document.getElementById('mdRow-' + d.currentKey);
    if(row){
     var h = row.querySelector('.md-health');
     if(h) h.innerHTML = '<span class="badge amber">测速中… '+(d.progress||0)+'%</span>';
    }
    var tx = $('mdProgTxt'); if(tx) tx.textContent = '进度 '+(d.done||0)+'/'+(d.total||0)+' · 通过 '+(d.passed||0);
   }
   // 完成后：回填健康状态到每行 + 恢复按钮
   if(!d.running){
    clearInterval(mdPollTimer);
    if(mdPollTimer){ clearInterval(mdPollTimer); mdPollTimer=null; }
    var btn = $('batchSpeedBtn'); if(btn) btn.disabled = false;
    api('/api/speedtest/health').then(function(rr){
     if(rr.code!==0 || !rr.data) return;
     var hmap={}; (rr.data||[]).forEach(function(h){ hmap[h.providerId+'::'+h.modelId]=h; });
     state.models.forEach(function(m){
      var key=m.providerId+'::'+m.modelId; var h=hmap[key]; if(!h) return;
      var row=document.getElementById('mdRow-'+key); if(!row) return;
      var health=row.querySelector('.md-health'); if(!health) return;
      if(h.isHealthy){
       var tps=h.tps>0?h.tps.toFixed(1)+'t/s ':'';
       health.innerHTML='<span class="badge green" title="TTFT '+(h.ttftMs||0)+'ms / 总 '+(h.totalMs||0)+'ms">正常 '+tps+(h.totalMs||0)+'ms</span>';
      } else {
       health.innerHTML='<span class="badge red">失败</span>';
      }
     });
    });
    var card=$('batchSpeedCard');
    if(card) card.innerHTML='<div style="text-align:center;color:var(--green);padding:20px">✅ 批量测速完成：通过 '+d.passed+'/'+d.total+' 个</div>';
    toast('批量测速完成：'+d.passed+'/'+d.total+' 正常', true);
   }
  });
 },1200);
};
// 行内启停（对齐原APP toggleModel）
window.toggleModel = function(id){
 api('/api/models/toggle', { method:'POST', body: { id: id } }).then(function(r){
  toast(r.msg, r.code === 0);
  loaders.models();
 });
};
// 自动全部测速（对齐原APP batchTestAllModels）：后台任务逐个测 + 前端轮询逐步更新每行状态
window.runBatchSpeedTest = function(){
 var card = $('batchSpeedCard');
 var autoClose = $('batchAutoClose') ? $('batchAutoClose').checked : true;
 card.style.display = 'block';
 card.innerHTML = '<div style="text-align:center;color:var(--muted);padding:30px">🚀 正在启动后台批量测速...<br><span style="font-size:12px">逐个串行测速全部模型（TTFT/TPS/总耗时），下方模型行会逐步更新状态</span></div>';
 $('batchSpeedBtn').disabled = true;
 // 用后台任务接口（不阻塞请求，前端轮询 progress 逐步显示）
 api('/api/speedtest/start', { method:'POST', body: {} }).then(function(r){
  if(r.code !== 0){
   $('batchSpeedBtn').disabled = false;
   card.innerHTML = '<div style="color:var(--red);padding:20px;text-align:center">' + esc(r.msg||'启动失败') + '</div>';
   return;
  }
  card.innerHTML = '<div style="text-align:center;color:var(--muted);padding:30px">后台测速已启动...<span id="mdProgTxt" style="font-size:12px;display:block;margin-top:8px"></span></div>';
  pollMdProgress(); // 轮询逐步高亮当前行
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
  '<div class="form-row"><label><input type="checkbox" id="mdEnabled"'+(m.isEnabled!==false?' checked':'')+'>启用</label> <label style="margin-left:12px"><input type="checkbox" id="mdPublic"'+(m.isPublic?' checked':'')+'>公用（所有用户可见可用）</label> <label style="margin-left:12px"><input type="checkbox" id="mdDefault"'+(m.isDefault?' checked':'')+'>默认</label></div>'
 ].join('');
 openModal(id ? '编辑模型' : '添加模型', html, function(){
  var body = { id:id||0, providerId:parseInt($('mdProv').value)||0, modelId:$('mdId').value.trim(), displayName:$('mdName').value.trim()||$('mdId').value.trim(), customAlias:$('mdAlias').value.trim(), contextWindow:parseInt($('mdCtx').value)||4096, price:parseFloat($('mdPrice').value)||0, isEnabled:$('mdEnabled').checked, isPublic:$('mdPublic').checked, isDefault:$('mdDefault').checked };
  if(!body.modelId){ toast('请填写模型ID', false); return; }
  api('/api/models', { method:'POST', body: body }).then(function(r){
   if(r.code === 0){ toast('保存成功', true); closeModal(); loaders.models(); } else toast(r.msg, false);
  });
 });
};
window.delModel = function(id){
 if(!confirm('确定删除该模型？')) return;
 api('/api/models/' + id, { method:'DELETE' }).then(function(r){
  if(r.code === 0){ toast('已删除', true); loaders.models(); } else toast(r.msg, false);
 });
};
// ===== 测速（后台逐模型测速 + 进度条，离开页面也在跑） =====
var speedTestActive = false;
var speedPollTimer = null;
loaders.speedtest = function(){
  $('view-speedtest').innerHTML = '<div class="action-bar"><button class="btn" onclick="runSpeedTest()">开始批量测速</button><button class="btn-ghost" onclick="loadSpeedModels()">刷新列表</button><span style="color:var(--muted);font-size:13px">后台逐模型测速：离开页面也继续跑，回来看进度/结果</span></div><div class="card" id="stCard"><div style="text-align:center;color:var(--muted);padding:30px">加载模型列表...</div></div>';
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
      return '<tr id="stRow-'+esc(key)+'" data-key="'+esc(key)+'"><td>'+esc(m.displayName||m.modelId)+'</td><td>'+esc(m.modelId)+'</td><td><span class="badge blue">'+esc(m.providerName||('P'+m.providerId))+'</span></td><td>'+(m.enabled?'<span class="badge green">已启用</span>':'<span class="badge gray">停用</span>')+'</td><td class="st-state" style="color:var(--muted)">待测速</td><td class="st-ttft">—</td><td class="st-tps">—</td><td class="st-total">—</td></tr>';
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
      progBar.style.cssText = 'margin-bottom:12px;background:var(--inset);border-radius:8px;height:8px;overflow:hidden;position:relative';
      progBar.innerHTML = '<div id="stProgFill" style="width:0%;height:100%;background:var(--primary);transition:width .4s"></div><div id="stProgText" style="position:absolute;top:-18px;right:0;font-size:11px;color:var(--muted)"></div>';
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
            if(st){ st.textContent = '测速中...'; st.style.color = 'var(--cyan)'; }
          }
        }
        // 已完成的渲染结果
        renderSpeedResults(s);
        if(s.running){
          setTimeout(tick, 1200);
        } else {
          speedTestActive = false;
          var btn = $('view-speedtest').querySelector('.action-bar .btn');
          if(btn) btn.innerHTML = '开始批量测速';
          var wrap = $('stProgWrap');
          if(wrap && wrap.parentNode) wrap.parentNode.removeChild(wrap);
          if(s.error) toast(s.error, false);
          else toast('测速完成：' + (s.passed||0) + '/' + (s.total||0) + ' 正常', true);
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
    var st = cells[4]; if(st){ st.textContent = ok ? '完成' : '失败'; st.style.color = ok ? 'var(--green)' : 'var(--red)'; }
    if(cells[5]) cells[5].textContent = ok && d.ttftMs > 0 ? d.ttftMs + 'ms' : '—';
    if(cells[6]) cells[6].textContent = ok && d.tps > 0 ? d.tps.toFixed(2) + ' tok/s' : '—';
    if(cells[7]) cells[7].textContent = ok && d.totalMs > 0 ? d.totalMs + 'ms' : '—';
  });
}
// 启动后台批量测速（前端只发一次，其余交给后端 + 轮询）
window.runSpeedTest = function(){
  if(speedTestActive){ toast('测速进行中，请等待完成', false); return; }
  var btn = $('view-speedtest').querySelector('.action-bar .btn');
  if(btn) btn.innerHTML = '启动中...';
  api('/api/speedtest/start', { method:'POST', body: {} }).then(function(r){
    if(r.code === 0){
      speedTestActive = true;
      if(btn) btn.innerHTML = '测速中...';
      pollSpeedProgress();
    } else {
      if(btn) btn.innerHTML = '开始批量测速';
      toast(r.msg || '启动失败', false);
    }
  });
};
// ===== 聊天（现代双栏：左会话列表 + 右消息区，输入框固定底部，含 qtai-sj） =====
loaders.chat = function(){
 var box = $('view-chat');
 box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
 Promise.all([api('/api/conversations'), api('/api/models')]).then(function(res){
  state.conversations = res[0].data || []; state.models = res[1].data || [];
  var convList = (state.conversations||[]).map(function(c){
   var title = c.title||('会话'+c.id);
   var ava = title.charAt(0).toUpperCase();
   return '<div class="chat-conv-item'+(state.currentChatConv===c.id?' active':'')+'" onclick="openConv('+c.id+')">'+
    '<div class="chat-conv-ava">'+esc(ava)+'</div>'+
    '<div class="chat-conv-body">'+
     '<div class="chat-conv-title">'+esc(title)+'</div>'+
     '<div class="chat-conv-time">'+convTime(c.updatedAt||c.createdAt)+'</div>'+
    '</div>'+
    '<div class="chat-conv-ops">'+
     '<button class="btn-ghost btn-sm" title="重命名" onclick="event.stopPropagation();renameConv('+c.id+',\''+esc((c.title||'').replace(/'/g,''))+'\')">✏️</button>'+
     '<button class="btn-ghost btn-sm danger" title="删除" onclick="event.stopPropagation();deleteConv('+c.id+')">🗑</button>'+
    '</div></div>';
  }).join('') || '<div style="color:var(--muted);font-size:12px;padding:14px">暂无会话，点「+ 新」开始</div>';
  var modelOpts = state.models.map(function(m){ return '<option value="'+esc(m.modelId)+'"'+(state.chatModel===m.modelId?' selected':'')+'>'+esc(m.displayName)+'</option>'; }).join('');
  if(!modelOpts) modelOpts = '<option value="qtai-sj" selected>🔄 自动化切换</option>';
  box.innerHTML = [
   '<div class="chat-layout" id="chatLayout">',
    '<div class="chat-overlay" id="chatOverlay" onclick="closeChatSide()"></div>',
    '<div class="chat-side" id="chatSide">',
     '<div class="chat-side-head"><b>💬 会话</b><button class="btn-ghost btn-sm" onclick="newChat()" title="新聊天">+ 新</button></div>',
     '<div class="chat-side-list">'+convList+'</div>',
    '</div>',
'<div class="chat-main">'+
     '<div class="chat-toolbar">'+
      '<button class="chat-burger" onclick="toggleChatSide()" title="会话列表">☰</button>'+
      '<span class="chat-toolbar-title" id="chatCurTitle">💬 聊天</span>'+
      '<select id="chatModel" class="input" style="width:170px;border-radius:16px;padding:6px 10px">'+modelOpts+'</select>'+
      '<button class="chat-toggle" id="thinkBtn" onclick="toggleThink()" title="开启后模型进行深度推理（思考型模型生效）">🧠 深度思考</button>'+
      '<button class="btn-ghost btn-sm" onclick="deleteConv()" title="删除当前会话">🗑</button>'+
     '</div>'+
     '<div id="chatMsgs" class="chat-msgs"><div class="msg-row system"><div class="bubble">👋 点 ☰ 打开会话列表，输入框固定在底部，📎可发图片/文件</div></div></div>',
     '<div class="chat-input"><div style="display:flex;flex-direction:column;flex:1;min-width:0"><div class="chat-attach-preview" id="chatAttachPreview"></div><div style="display:flex;gap:8px;align-items:center"><button class="chat-att" onclick="pickChatFile()" title="上传图片/文件">📎</button><input id="chatInput" class="input" placeholder="输入消息... (Enter发送)" onkeydown="if(event.key===\'Enter\')sendChat()"></div></div><button class="btn" onclick="sendChat()">发送</button></div>',
    '</div>',
   '</div>'
  ].join('');
  if(state.conversations.length && !state.currentChatConv){
   state.currentChatConv = state.conversations[0].id;
   loadConvMsgs();
  }
 });
};
function convTime(ts){
 if(!ts) return '';
 var d = new Date(ts); var now = new Date();
 if(d.toDateString()===now.toDateString()) return d.getHours()+':'+String(d.getMinutes()).padStart(2,'0');
 return (d.getMonth()+1)+'/'+d.getDate();
}
function fmtTime2(ts){
 if(!ts) return '';
 var d = new Date(ts);
 return d.getHours()+':'+String(d.getMinutes()).padStart(2,'0');
}
window.toggleChatSide = function(){
 var s = $('chatSide'); var o = $('chatOverlay');
 if(!s) return;
 var open = s.classList.toggle('open');
 if(o) o.classList.toggle('show', open);
};
window.closeChatSide = function(){
 var s = $('chatSide'); var o = $('chatOverlay');
 if(s) s.classList.remove('open');
 if(o) o.classList.remove('show');
};
window.openConv = function(id){
 state.currentChatConv = id;
 document.querySelectorAll('.chat-conv-item').forEach(function(x){ x.classList.remove('active'); });
 var el2 = document.querySelector('.chat-conv-item[onclick="openConv('+id+')"]');
 if(el2) el2.classList.add('active');
 // 更新顶部标题
 var title = '💬 聊天';
 (state.conversations||[]).forEach(function(c){ if(c.id===id) title = c.title || ('会话'+c.id); });
 var tt = $('chatCurTitle'); if(tt) tt.textContent = title;
 closeChatSide();
 loadConvMsgs();
};
window.loadConvMsgs = function(){
 api('/api/conversations/'+(state.currentChatConv||0)).then(function(r){
  if(r.code === 0){
   var msgs = r.data.messages || [];
   $('chatMsgs').innerHTML = msgs.map(function(m){
    var t = fmtTime2(m.createdAt);
    if(m.role==='user') return '<div class="msg-row user"><div class="bubble">'+renderMd(m.content)+'<div class="msg-time">'+t+'</div></div></div>';
    return '<div class="msg-row assistant"><div class="chat-ava">🤖</div><div class="bubble">'+renderMd(m.content)+'<div class="msg-time">'+t+'</div></div></div>';
   }).join('') || '<div class="msg-row system"><div class="bubble">空对话</div></div>';
   var e3 = $('chatMsgs'); if(e3) e3.scrollTop = e3.scrollHeight;
  }
 });
};
// ===== 深度思考开关（开启后请求带 thinking，思考型模型生效） =====
window._thinkOn = function(){ try{ return localStorage.getItem('qt_think')==='1'; }catch(e){ return false; } };
window.toggleThink = function(){
 var on = !window._thinkOn();
 try{ localStorage.setItem('qt_think', on?'1':'0'); }catch(e){}
 var tb = $('thinkBtn'); if(tb) tb.classList.toggle('on', on);
 toast(on ? '🧠 深度思考已开启' : '深度思考已关闭', true);
};
// 轻量 Markdown 渲染：转义 HTML -> 代码壳(复制/下载) -> think剥离 -> 图片 -> 换行
window._codeStore = window._codeStore || [];
var CODE_EXT = {js:'.js',javascript:'.js',ts:'.ts',typescript:'.ts',python:'.py',py:'.py',bash:'.sh',sh:'.sh',shell:'.sh',html:'.html',css:'.css',json:'.json',java:'.java',kotlin:'.kt',kt:'.kt',sql:'.sql',go:'.go',rust:'.rs',rs:'.rs',c:'.c',cpp:'.cpp',yaml:'.yml',yml:'.yml',md:'.md',markdown:'.md',xml:'.xml',dockerfile:'.Dockerfile'};
function renderMd(t){
 var codes = [];
 var s = String(t||'');
 // 先抽离代码块（避免 esc 破坏）
 s = s.replace(/```([\w+#.-]*)[^\S\n]*\n?([\s\S]*?)```/g, function(m, lang, code){
  codes.push({lang:(lang||'text').toLowerCase(), code:code.replace(/\n+$/,'')});
  return '\u0000C' + (codes.length-1) + '\u0000';
 });
 s = esc(s);
 // 保险：剥离残余 think 标签
 s = s.replace(/&lt;think&gt;[\s\S]*?(&lt;\/think&gt;|$)/g, '');
 // 行内 `code`
 s = s.replace(/`([^`\n]+)`/g, '<code class="ic">$1</code>');
 // 图片 URL
 s = s.replace(/(https?:\/\/[^\s<>\)]+\.(png|jpe?g|gif|webp|svg))/gi, '<img src="$1" alt="img" style="max-width:100%;border-radius:6px">');
 // /uploads/ 本地图片
 s = s.replace(/(\/uploads\/[^\s<>\)]+)/g, '<img src="$1" alt="img" style="max-width:100%;border-radius:6px">');
 // 加粗
 s = s.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
 // 换行
 s = s.replace(/\n/g, '<br>');
 // 还原代码壳
 s = s.replace(/\u0000C(\d+)\u0000/g, function(m, i){
  var c = codes[+i]; if(!c) return '';
  window._codeStore.push({code:c.code, ext:CODE_EXT[c.lang]||'.txt'});
  var si = window._codeStore.length - 1;
  return '<div class="code-shell"><div class="code-head"><span class="code-lang">'+esc(c.lang)+'</span>'+
   '<span class="code-ops"><button class="code-btn" onclick="copyCodeBlock('+si+',this)">复制</button>'+
   '<button class="code-btn" onclick="downloadCodeBlock('+si+')">下载</button></span></div>'+
   '<pre><code>'+esc(c.code)+'</code></pre></div>';
 });
 return s;
}
window.copyCodeBlock = function(i, btn){
 var c = window._codeStore[i]; if(!c) return;
 copyText(c.code);
 if(btn){ btn.textContent = '已复制'; setTimeout(function(){ btn.textContent = '复制'; }, 1200); }
};
window.downloadCodeBlock = function(i){
 var c = window._codeStore[i]; if(!c) return;
 var blob = new Blob([c.code], {type:'text/plain;charset=utf-8'});
 var a = document.createElement('a');
 a.href = URL.createObjectURL(blob);
 a.download = 'snippet-' + Date.now() + c.ext;
 document.body.appendChild(a); a.click();
 setTimeout(function(){ URL.revokeObjectURL(a.href); a.remove(); }, 500);
};
// 待发送附件
state.chatAttachments = state.chatAttachments || [];
window.pickChatFile = function(){
 var inp = document.createElement('input');
 inp.type='file'; inp.accept='image/*,.pdf,.txt,.md,.json,.csv,.doc,.docx,.zip,.mp4';
 inp.onchange = function(){
  var f = inp.files[0]; if(!f) return;
  var reader = new FileReader();
  reader.onload = function(){
   api('/api/upload',{method:'POST',body:{filename:f.name,mime:f.type,data:reader.result}}).then(function(r){
    if(r.code===0){ state.chatAttachments.push({url:r.data.url,name:r.data.name,mime:r.data.mime}); toast('已附加 '+r.data.name, true); renderAttachPreview(); }
    else toast(r.msg||'上传失败', false);
   });
  };
  reader.readAsDataURL(f);
 };
 inp.click();
};
function renderAttachPreview(){
 var box = $('chatAttachPreview'); if(!box) return;
 box.innerHTML = (state.chatAttachments||[]).map(function(a,i){
  var isImg = (a.mime||'').indexOf('image')===0 || /\.(png|jpe?g|gif|webp)$/i.test(a.name);
  return '<span class="att-chip" onclick="removeAttach('+i+')" title="点击移除">'+(isImg?'🖼':'📎')+' '+esc(a.name)+' ✕</span>';
 }).join('');
}
window.removeAttach = function(i){ state.chatAttachments.splice(i,1); renderAttachPreview(); };
window.sendChat = function(){
 var input = $('chatInput'); if(!input) return;
 var content = input.value.trim();
 var atts = state.chatAttachments||[];
 if(!content && !atts.length) return;
 if(window._chatBusy) return;
 window._chatBusy = true;
 var convId = state.currentChatConv || 0;
 var model = $('chatModel').value || 'qtai-sj';
 state.chatModel = model;
 var msgs = $('chatMsgs');
 // 组装发送文本：附件作为引用附在末尾
 var sendText = content;
 if(atts.length){
  sendText += (content?'\n\n':'') + atts.map(function(a){ return '[附件:'+a.name+']('+a.url+')'; }).join('\n');
 }
 // 构建用户消息（局部 DOM，不用 innerHTML 拼接全局）
 var uRow = document.createElement('div'); uRow.className = 'msg-row user';
 uRow.innerHTML = '<div class="bubble">'+renderMd(content)+
   (atts.length?'<div class="chat-attach-preview">'+atts.map(function(a){return '<span class="att-chip">📎 '+esc(a.name)+'</span>';}).join('')+'</div>':'')+
   '<div class="msg-time">'+fmtTime2(Date.now())+'</div></div>';
 msgs.appendChild(uRow);
 // 构建助手占位（局部引用，不用全局 id，避免重复 id 导致覆盖上一条）
 var aRow = document.createElement('div'); aRow.className = 'msg-row assistant';
 aRow.innerHTML = '<div class="chat-ava">🤖</div><div class="assistant-col"></div>';
 msgs.appendChild(aRow);
 var col = aRow.querySelector('.assistant-col');
 msgs.scrollTop = msgs.scrollHeight;
 input.value = ''; state.chatAttachments=[]; renderAttachPreview();
 api('/api/chat', { method:'POST', body: { conversationId: convId, content: sendText, model: model, thinking: window._thinkOn() } }).then(function(r){
  if(!col) return;
  if(r.code === 0){
   var reply = r.data.reply || '(无响应)';
   var reasoning = r.data.reasoning || '';
   var html = '';
   if(reasoning){
    html += '<div class="chat-reason" onclick="this.classList.toggle(\'open\')">💭 思考过程（点击展开）<div class="cr-body">'+esc(reasoning)+'</div></div>';
   }
   html += '<div class="bubble chat-wait-bubble">思考中...</div><div class="msg-time">'+fmtTime2(Date.now())+'</div>';
   col.innerHTML = html;
   var wb = col.querySelector('.chat-wait-bubble');
   var full = reply; var i = 0;
   var tick = setInterval(function(){
    i += 2;
    if(wb){ wb.innerHTML = renderMd(full.slice(0,i)) + (i<full.length?'▍':''); msgs.scrollTop = msgs.scrollHeight; }
    if(i >= full.length){ clearInterval(tick); if(wb) wb.innerHTML = renderMd(full); msgs.scrollTop = msgs.scrollHeight; }
   }, 16);
   if(convId === 0){ state.currentChatConv = r.data.conversationId; setTimeout(function(){ loaders.chat(); }, full.length * 2 + 200); }
  } else {
   col.innerHTML = '<div class="bubble">⚠️ '+(r.msg||'失败')+'</div>';
  }
 }).finally(function(){ window._chatBusy = false; });
};
window.newChat = function(){
 state.currentChatConv = 0;
 closeChatSide();
 var msgs = $('chatMsgs'); if(msgs) msgs.innerHTML = '<div class="msg-row system"><div class="bubble">新对话，开始输入吧</div></div>';
 var tt = $('chatCurTitle'); if(tt) tt.textContent = '💬 新聊天';
 var inp = $('chatInput'); if(inp) inp.focus();
 toast('已开始新聊天', true);
};
window.renameConv = function(id, oldTitle){
 openModal('重命名会话', '<div class="form-row"><label>输入自定义标题（或让 AI 生成后自行修改）</label><input class="input" id="renameInput" value="'+esc(oldTitle||'')+'"></div>'+
  '<div class="form-row" style="font-size:12px;color:var(--muted)">💡 新对话已自动用首条消息生成标题，这里可自由修改</div>', function(){
  var t = $('renameInput').value.trim();
  if(!t){ toast('标题不能为空',false); return; }
  api('/api/conversations/'+id+'/rename',{method:'POST',body:{title:t}}).then(function(r){
   if(r.code===0){ toast('标题已更新',true); closeModal(); api('/api/conversations').then(function(rr){ state.conversations=(rr&&rr.data)||[]; loaders.chat(); }); } else toast(r.msg,false);
  });
 });
};
window.deleteConv = function(id){
 var cid = id || state.currentChatConv;
 if(!cid){ toast('请先选择会话', false); return; }
 if(!confirm('删除该会话？')) return;
 api('/api/conversations/' + cid, { method:'DELETE' }).then(function(r){
  if(r.code === 0){ toast('已删除', true); state.currentChatConv = 0; loaders.chat(); } else toast(r.msg, false);
 });
};
"""
}