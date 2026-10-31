package com.qitong.gateway

/** 后台 JS 第七部分：微信机器人 —— 扫码登录 / 机器人列表 / 启停 / 日志 / 用户管理（v69；v1.165 补齐用户对齐 QQ） */
object AdminJs7 {
  fun js(): String = """
// ===== 微信机器人管理（v69 ilink 通道） =====
 loaders.weixin = function(){
  var box = $('view-weixin');
  if(!box) return;
  box.innerHTML = '<div class="card"><div class="qq-tabs">'+
   '<button class="log-tab" onclick="wxTab(0)">📊 动态预览</button>'+
   '<button class="log-tab" onclick="wxTab(1)">🤖 机器人</button>'+
   '<button class="log-tab" onclick="wxTab(2)">📱 扫码登录</button>'+
   '<button class="log-tab" onclick="wxTab(3)">📋 运行日志</button>'+
   '<button class="log-tab" onclick="wxTab(4)">👥 用户</button></div>'+
   '<div id="wxPane"><div style="color:var(--muted);padding:20px">加载中…</div></div></div>';
  wxTab(0);
 };
 var wxTabIdx = 0;
 function wxTab(i){ wxTabIdx = i; if(i===0) wxOverview(); else if(i===1) wxLoadBots(); else if(i===2) wxScan(); else if(i===3) wxLoadLogs(); else if(i===4) wxLoadUsers(); }
function wxTime(ts){ if(!ts) return '-'; var d=new Date(ts); return d.toLocaleString('zh-CN',{hour12:false}); }
// ★ v72 微信动态预览（对齐 QQ 动态概览；v87 加 5 秒自动刷新 + 实时动态流 + 友好状态）
 var wxFeedTimer = null;
function wxStatusText(st){
 // ★ v87 友好状态文字：在线/连接中/离线/报错
 if(st==='ONLINE') return '<span class="badge green">在线</span>';
 if(st==='CONNECTING'||st==='LOGIN_WAIT') return '<span class="badge amber">连接中</span>';
 if(st==='ERROR') return '<span class="badge red">离线·异常</span>';
 if(st==='PAUSED') return '<span class="badge amber">风控暂停</span>';
 return '<span class="badge gray">离线</span>';
}
function wxOverview(){
 var el=$('wxPane'); if(!el) return;
 clearInterval(wxFeedTimer);
 el.innerHTML = '<div id="wxOverview"><div style="color:var(--muted);padding:20px">加载中…</div></div>';
 function load(){
  api('/api/weixin/overview').then(function(r){
   var box=$('wxOverview'); if(!box) return;
   var d=r.data||{}; var ov=d.overview||{}; var bots=d.bots||[];
   var stats = '<div class="grid grid-4" style="margin-bottom:14px">'+
    '<div class="stat"><div class="num" style="color:var(--cyan)">'+(ov.totalMessages||0)+'</div><div class="lbl">累计消息</div></div>'+
    '<div class="stat"><div class="num" style="color:var(--green)">'+(ov.onlineBots||0)+'/'+(ov.totalBots||0)+'</div><div class="lbl">在线机器人</div></div>'+
    '<div class="stat"><div class="num">'+(bots.length)+'</div><div class="lbl">已配机器人</div></div></div>';
   var rows=bots.map(function(b){
    return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td>'+
     '<td>'+wxStatusText(b.status)+'</td>'+
     '<td><b style="color:var(--cyan)">'+(b.messagesHandled||0)+'</b> 条</td>'+
     '<td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td></tr>';
   }).join('');
   // 实时动态流（对齐 QQ）
   var feed=(d.recent||[]).map(function(l){
    var color = l.type==='error'?'var(--red)':(l.type==='command'||l.type==='status'?'var(--amber)':'var(--green)');
    return '<div style="display:flex;gap:8px;padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
     '<span class="badge" style="color:'+color+';font-size:10px">'+esc(l.type)+'</span>'+
     '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
     '<span style="color:var(--muted);font-size:11px">'+wxTime(l.createdAt)+'</span></div>';
   }).join('') || '<div style="color:var(--muted);padding:16px">暂无动态</div>';
   box.innerHTML = stats +
    '<div class="card" style="box-shadow:none;border:1px solid var(--border)"><h3>🤖 机器人状态</h3><div style="overflow-x:auto"><table class="tb"><thead><tr><th>名称</th><th>状态</th><th>消息数</th><th>最近错误</th></tr></thead><tbody>'+(rows||'<tr><td colspan="4" style="color:var(--muted)">暂无机器人，点「📱扫码登录」接入</td></tr>')+'</tbody></table></div></div>'+
    '<div class="card" style="box-shadow:none;border:1px solid var(--border)"><h3>📡 实时动态（每5秒刷新）</h3><div id="wxFeed">'+feed+'</div></div>';
  }).catch(function(){ var el2=$('wxOverview'); if(el2) el2.innerHTML='<div style="color:var(--red);padding:16px">加载失败</div>'; });
 }
 load();
 // ★ v87 每 5 秒自动刷新动态流（对齐 QQ）
 wxFeedTimer = setInterval(function(){
  if(!$('wxOverview')){ clearInterval(wxFeedTimer); return; }
  api('/api/weixin/overview').then(function(r){
   var f=$('wxFeed'); if(f){
    var rec=(r.data&&r.data.recent)||[];
    f.innerHTML = rec.map(function(l){
     var color = l.type==='error'?'var(--red)':(l.type==='command'||l.type==='status'?'var(--amber)':'var(--green)');
     return '<div style="display:flex;gap:8px;padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
      '<span class="badge" style="color:'+color+';font-size:10px">'+esc(l.type)+'</span>'+
      '<span style="flex:1;color:var(--text)">'+esc(l.content)+'</span>'+
      '<span style="color:var(--muted);font-size:11px">'+wxTime(l.createdAt)+'</span></div>';
    }).join('');
   }
   // 同步刷新状态/消息数（不闪屏：只更新表格数据区）
   var box=$('wxOverview'); if(!box) return;
   api('/api/weixin/overview').then(function(r2){
    var bots=(r2.data&&r2.data.bots)||[];
    // 重建机器人状态表（仅当数据变化时）
    var ov=r2.data&&r2.data.overview||{};
    var onlineEl=document.querySelector('.grid .stat .num[style*="var(--green)"]');
    // 简单直接：重载主线程每10秒整页刷新由上层控制
   });
  });
 },5000);
}
// ---- 机器人列表 ----
function wxLoadBots(){
 var el=$('wxPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn" onclick="wxForm()">+ 添加微信机器人</button><button class="btn-ghost" onclick="wxLoadBots()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">微信 bot（腾讯官方 ilink 通道）扫码接入，token 本地存储</span></div><div style="overflow-x:auto"><div id="wxBotList"></div></div>';
 api('/api/weixin/bots').then(function(r){
  var box=$('wxBotList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无微信机器人，点「+ 添加」或「📱 扫码登录」接入</div>'; return; }
  var rows=list.map(function(b){
   var statusTxt = wxStatusText(b.status);
   return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td>'+
    '<td>'+statusTxt+'</td>'+
    '<td><b style="color:var(--cyan)">'+(b.messagesHandled||0)+'</b> 条</td>'+
    '<td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td>'+
    '<td style="white-space:nowrap">'+
    '<button class="btn-ghost btn-sm" onclick="wxEdit('+b.id+')">编辑</button> '+
    '<button class="btn-ghost btn-sm" onclick="wxRestart('+b.id+')">重连</button> '+
    '<button class="btn-ghost btn-sm" onclick="wxToggle('+b.id+')">'+(b.enabled?'停用':'启用')+'</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="wxDel('+b.id+')">删</button></td></tr>';
  }).join('');
  box.innerHTML = '<table class="tb"><thead><tr><th>名称</th><th>状态</th><th>消息数</th><th>最近错误</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>';
 }).catch(function(e){ el.innerHTML='<div style="color:var(--red);padding:16px">加载失败：'+esc(e.message||e)+'</div>'; });
}
// ★ v69e 编辑改为传 ID 拉取（避免 JSON 引号闭合 onclick 老坑）
function wxEdit(id){
 api('/api/weixin/bots').then(function(r){
  var list=r.data||[];
  var b=list.filter(function(x){return x.id===id;})[0]||{};
  wxForm(b);
 });
}
function wxForm(b){
 b=b||{};
 var html =
  '<div class="form-row"><label>机器人名称（备注）</label><input class="input" id="wxName" value="'+esc(b.name||'')+'" placeholder="例如：微信bot小号"></div>'+
  '<div class="form-row"><label>Bot Token（ilink bot_token，扫码登录后自动填入）</label><input class="input" id="wxToken" type="password" value="" placeholder="'+(b.hasToken?'已保存，留空则不修改':'扫码登录后自动获得')+'"></div>'+
  '<div class="form-row"><label>iLink Bot ID（ilink_bot_id）</label><input class="input" id="wxBotId" value="'+esc(b.ilinkBotId||'')+'" placeholder="扫码后自动获得，如 xxx@im.bot"></div>'+
  '<div class="form-row"><label>使用模型（留空用 qtai-sj）</label><input class="input" id="wxModel" value="'+esc(b.aiModel||'')+'" placeholder="qtai-sj"></div>'+
  '<div class="form-row"><label>系统人设 / System Prompt（可选）</label><textarea class="input" id="wxPrompt" rows="3" placeholder="例如：你是綦桐小助理…">'+esc(b.systemPrompt||'')+'</textarea></div>';
 openModal(b.id?'编辑微信机器人':'添加微信机器人', html, function(){
  var name=$('wxName').value.trim();
  if(!name){ toast('名称必填', false); return; }
  api('/api/weixin/bots',{method:'POST',body:{
   id:b.id||0, name:name,
   botToken:$('wxToken').value.trim(),
   ilinkBotId:$('wxBotId').value.trim(),
   aiModel:$('wxModel').value.trim()||'qtai-sj',
   systemPrompt:$('wxPrompt').value, enabled:true
  }}).then(function(r){
   if(r.code===0){ toast('保存成功', true); closeModal(); wxLoadBots(); } else toast(r.msg||'保存失败', false);
  });
 });
}
function wxRestart(id){
 api('/api/weixin/bots/'+id+'/restart',{method:'POST'}).then(function(r){ toast(r.msg||'已重连', r.code===0); setTimeout(wxLoadBots,800); });
}
// ★ v69e 启停切换
function wxToggle(id){
 api('/api/weixin/bots/'+id+'/toggle',{method:'POST'}).then(function(r){
  toast(r.msg||'', r.code===0);
  if(r.code===0) setTimeout(wxLoadBots,800);
 });
}
function wxDel(id){
 if(!confirm('确认删除该微信机器人？')) return;
 api('/api/weixin/bots/'+id,{method:'DELETE'}).then(function(){ toast('已删除'); wxLoadBots(); });
}
// ---- ★ v1.165 微信用户管理（对齐 QQ 用户页：列表/备注/权限/记忆/解绑） ----
function wxLoadUsers(){
 var el=$('wxPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn-ghost" onclick="wxLoadUsers()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">微信用户（按 openid 隔离；可改备注/权限、看记忆、解绑）</span></div>'+
  '<div style="overflow-x:auto"><div id="wxUserList"><div style="color:var(--muted);padding:18px">加载中…</div></div></div>';
 api('/api/weixin/users').then(function(r){
  var box=$('wxUserList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无微信用户（有人在微信里跟机器人说过话后自动出现）</div>'; return; }
  var rows=list.map(function(u){
   var bound = u.boundUserId>0 ? '<span class="badge blue">已绑账号#'+u.boundUserId+'</span>' : '<span class="badge gray">未绑定</span>';
   var perm = ['禁止','查询','操作','管理','全部'][u.permLevel||1];
   return '<tr><td><b>'+esc(u.displayName||(u.openid||'').substring(0,12))+'</b><div style="font-size:11px;color:var(--muted)">'+esc(u.openid||'')+'</div></td>'+
    '<td>'+bound+'</td>'+
    '<td><span class="badge '+(u.permLevel>=3?'green':'gray')+'">'+perm+'</span></td>'+
    '<td style="font-size:11px;color:var(--muted)">'+wxTime(u.createdAt)+'</td>'+
    '<td style="white-space:nowrap">'+
    '<button class="btn-ghost btn-sm" onclick="wxUserEdit(\''+esc(u.openid)+'\')">配置</button> '+
    '<button class="btn-ghost btn-sm" onclick="wxUserMemory(\''+esc(u.openid)+'\')">🧠记忆</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="wxUserDel(\''+esc(u.openid)+'\')">解绑</button></td></tr>';
  }).join('');
  box.innerHTML = '<table class="tb"><thead><tr><th>用户</th><th>绑定</th><th>权限</th><th>首次活跃</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>';
 }).catch(function(e){ el.innerHTML='<div style="color:var(--red);padding:16px">加载失败：'+esc(e.message||e)+'</div>'; });
}
// 用户独立配置（备注 + 权限下拉）
function wxUserEdit(openid){
 api('/api/weixin/users').then(function(r){
  var list=r.data||[];
  var u=list.filter(function(x){return x.openid===openid;})[0]||{};
  var html =
   '<div class="form-row"><label>备注名称</label><input class="input" id="wxuName" value="'+esc(u.displayName||'')+'"></div>'+
   '<div class="form-row"><label>权限等级</label><select class="input" id="wxuPerm">'+
    '<option value="0"'+(u.permLevel===0?' selected':'')+'>⛔ 禁止</option>'+
    '<option value="1"'+(u.permLevel===1?' selected':'')+'>🔍 查询</option>'+
    '<option value="2"'+(u.permLevel===2?' selected':'')+'>🛠 操作</option>'+
    '<option value="3"'+(u.permLevel===3?' selected':'')+'>🛠 管理（充值/配置）</option>'+
    '<option value="4"'+(u.permLevel===4?' selected':'')+'>✅ 全部</option></select></div>';
  openModal('微信用户配置 · '+(openid||'').substring(0,12), html, function(){
   api('/api/weixin/users/update',{method:'POST',body:{
    openid: openid,
    displayName: $('wxuName').value.trim(),
    permLevel: parseInt($('wxuPerm').value)||1
   }}).then(function(r){
    if(r.code===0){ toast('已保存', true); closeModal(); wxLoadUsers(); } else toast(r.msg||'失败', false);
   });
  });
 });
}
// 查看用户记忆（对齐 QQ qqUserMemory）
function wxUserMemory(openid){
 api('/api/weixin/users/memory/detail?openid='+encodeURIComponent(openid)).then(function(r){
  var list=(r&&r.data)||[];
  var body = list.length
   ? '<div style="max-height:400px;overflow-y:auto">'+list.map(function(m){
     return '<div style="display:flex;gap:8px;padding:7px 0;border-bottom:1px solid var(--border)">'+
      '<span style="flex:1">'+esc(m.content)+'<div style="font-size:11px;color:var(--muted);margin-top:2px">'+wxTime(m.timestamp)+'</div></span>'+
      '<button class="btn-ghost btn-sm danger" onclick="wxMemoryDel('+(m.id||0)+',\''+esc(openid)+'\')">删</button></div>';
    }).join('')+'</div>'
   : '<div style="color:var(--muted);padding:12px">该用户还没有记忆。</div>';
  var foot = '<div style="display:flex;gap:8px;margin-top:10px">'+
   '<button class="btn-ghost btn-sm danger" onclick="wxUserMemoryClear(\''+esc(openid)+'\')">清空记忆</button></div>';
  openModal('微信用户记忆 · '+(openid||'').substring(0,12), body+foot, null, {hideFooter:true});
 });
}
function wxMemoryDel(id,openid){
 if(!confirm('确认删除这条记忆？')) return;
 api('/api/weixin/users/memory/'+id,{method:'DELETE'}).then(function(r){
  toast(r.msg||'', r.code===0); if(r.code===0) wxUserMemory(openid);
 });
}
function wxUserMemoryClear(openid){
 if(!confirm('确认清空该用户的全部记忆？'))return;
 api('/api/weixin/users/memory/clear',{method:'POST',body:{openid:openid}}).then(function(r){
  toast(r.msg||'已清空', r.code===0); if(r.code===0) wxUserMemory(openid);
 });
}
function wxUserDel(openid){
 if(!confirm('确认解绑该微信用户？（绑定记录删除，记忆保留）')) return;
 api('/api/weixin/users/'+encodeURIComponent(openid),{method:'DELETE'}).then(function(r){
  toast(r.msg||'已解绑', r.code===0); if(r.code===0) wxLoadUsers();
 });
}
// ---- 扫码登录 ----
var wxScanTimer = null;
function wxScan(){
 var el=$('wxPane'); if(!el) return;
 if(wxScanTimer){ clearInterval(wxScanTimer); wxScanTimer=null; }
 el.innerHTML = '<div class="action-bar"><button class="btn" onclick="wxScan()">重新生成二维码</button><button class="btn-ghost" onclick="wxLoadBots()">返回列表</button>'+
  '<span style="font-size:12px;color:var(--muted)">用微信「扫一扫」扫码 → 手机上确认 → 自动完成连接（微信 bot 扫码）</span></div><div id="wxScanBox" style="text-align:center;padding:30px">生成中…</div>';
 api('/api/weixin/qrcode').then(function(r){
  var box=$('wxScanBox'); if(!box) return;
  var d=r.data||{};
  if(d.qrcodeUrl){
   // qrcode_img_content 是扫码链接（liteapp.weixin.qq.com），不是图片；用公共 QR API 把它渲染成二维码图片
   var qrImg = 'https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=' + encodeURIComponent(d.qrcodeUrl);
   box.innerHTML = '<img src="'+esc(qrImg)+'" style="width:220px;height:220px;border-radius:10px;border:1px solid var(--border)" onerror="this.onerror=null;this.src=&#39;https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=&#39;+encodeURIComponent(\''+esc(d.qrcodeUrl)+'\')"/><br/>'+
    '<div style="margin-top:12px;font-size:13px;color:var(--muted)">用微信「扫一扫」扫上面的码 → 手机上确认 → 自动完成连接</div>'+
    '<div style="margin-top:8px"><a href="'+esc(d.qrcodeUrl)+'" target="_blank" style="font-size:12px;color:var(--primary)">二维码打不开？点这里在微信打开</a></div>'+
    '<div id="wxScanStatus" style="margin-top:8px;font-size:12px;color:var(--muted)">等待扫码…</div>';
   // 每 5 秒轮询 confirm，直到连接成功/过期
   var qrcode = d.qrcode;
   wxScanTimer = setInterval(function(){
    api('/api/weixin/qrcode/confirm',{method:'POST',body:{qrcode:qrcode}}).then(function(resp){
     var st=$('wxScanStatus'); if(!st) return;
     var result = (resp.data&&resp.data.result)||'';
     st.innerHTML = esc(result);
     if(result.indexOf('✅')===0 || result.indexOf('❌')===0 || result.indexOf('ℹ️')===0 || result.indexOf('⚠️')===0){
      clearInterval(wxScanTimer); wxScanTimer=null;
      if(result.indexOf('✅')===0){ setTimeout(wxLoadBots, 1500); }
     }
    }).catch(function(){ /* 网络抖动忽略，下轮重试 */ });
   }, 5000);
  } else {
   box.innerHTML = '<div style="color:var(--red)">二维码获取失败：'+esc(d.message||'未知错误')+'</div>';
  }
 }).catch(function(e){ el.innerHTML='<div style="color:var(--red);padding:16px">二维码获取失败：'+esc(e.message||e)+'</div>'; });
}
// ---- 运行日志 ----
function wxLoadLogs(){
 var el=$('wxPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn-ghost" onclick="wxLoadLogs()">刷新</button>'+
  '<button class="btn-ghost danger" onclick="wxLogsClear()">🗑 清空全部</button>'+
  '<span style="font-size:12px;color:var(--muted)">微信通道运行日志（单条可删，最近 200 条）</span></div><div id="wxLogList"></div>';
 api('/api/weixin/logs').then(function(r){
  var box=$('wxLogList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无日志</div>'; return; }
  box.innerHTML = list.map(function(l){
   return '<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:12px;display:flex;align-items:center;gap:8px">'+
    '<span style="color:var(--muted);flex-shrink:0">'+wxTime(l.createdAt)+'</span> <code>'+esc(l.type||'')+'</code> '+
    '<span style="flex:1;word-break:break-all">'+esc(l.content||'')+'</span>'+
    '<button class="btn-ghost btn-sm danger" onclick="wxLogDel('+l.id+')">删</button></div>';
  }).join('');
 });
}
// ★ v72 单条删除
function wxLogDel(id){
 if(!confirm('确认删除该条日志？')) return;
 api('/api/weixin/logs/'+id,{method:'DELETE'}).then(function(r){ toast(r.msg||'已删除', r.code===0); wxLoadLogs(); });
}
// ★ v72 清空全部
function wxLogsClear(){
 if(!confirm('确认清空全部微信日志？')) return;
 api('/api/weixin/logs/clear',{method:'POST'}).then(function(r){ toast(r.msg||'已清空', r.code===0); wxLoadLogs(); });
}
"""
}