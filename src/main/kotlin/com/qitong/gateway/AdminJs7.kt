package com.qitong.gateway

/** 后台 JS 第七部分：微信机器人 —— 扫码登录 / 机器人列表 / 启停 / 日志（v69） */
object AdminJs7 {
  fun js(): String = """
// ===== 微信机器人管理（v69 ilink 通道） =====
 loaders.weixin = function(){
  var box = $('view-weixin');
  if(!box) return;
  box.innerHTML = '<div class="card"><div class="qq-tabs">'+
   '<button class="log-tab" onclick="wxLoadBots()">🤖 机器人</button>'+
   '<button class="log-tab" onclick="wxScan()">📱 扫码登录</button>'+
   '<button class="log-tab" onclick="wxLoadLogs()">📋 运行日志</button></div>'+
   '<div id="wxPane"><div style="color:var(--muted);padding:20px">加载中…</div></div></div>';
  wxLoadBots();
 };
function wxTime(ts){ if(!ts) return '-'; var d=new Date(ts); return d.toLocaleString('zh-CN',{hour12:false}); }
// ---- 机器人列表 ----
function wxLoadBots(){
 var el=$('wxPane'); if(!el) return;
 el.innerHTML = '<div class="action-bar"><button class="btn" onclick="wxForm()">+ 添加微信机器人</button><button class="btn-ghost" onclick="wxLoadBots()">刷新</button>'+
  '<span style="font-size:12px;color:var(--muted)">微信个人号通过腾讯官方 ilink 通道接入（扫码登录，token 本地存储）</span></div><div id="wxBotList"></div>';
 api('/api/weixin/bots').then(function(r){
  var box=$('wxBotList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无微信机器人，点「+ 添加」或「📱 扫码登录」接入</div>'; return; }
  var rows=list.map(function(b){
   return '<tr><td><b>'+esc(b.name||'未命名')+'</b></td>'+
    '<td>'+(b.online?'<span class="badge green">在线</span>':'<span class="badge gray">'+(b.status||'离线')+'</span>')+'</td>'+
    '<td>'+(b.messagesHandled||0)+'</td>'+
    '<td style="font-size:11px;color:var(--red)">'+esc(b.lastError||'')+'</td>'+
    '<td style="white-space:nowrap">'+
    '<button class="btn-ghost btn-sm" onclick="wxForm('+JSON.stringify(b).replace(/\"/g,'"')+')">编辑</button> '+
    '<button class="btn-ghost btn-sm" onclick="wxRestart('+b.id+')">重连</button> '+
    '<button class="btn-ghost btn-sm danger" onclick="wxDel('+b.id+')">删</button></td></tr>';
  }).join('');
  box.innerHTML = '<table class="tb"><thead><tr><th>名称</th><th>状态</th><th>消息数</th><th>最近错误</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table>';
 }).catch(function(e){ el.innerHTML='<div style="color:var(--red);padding:16px">加载失败：'+esc(e.message||e)+'</div>'; });
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
 api('/api/weixin/bots/'+id+'/restart',{method:'POST'}).then(function(){ toast('已重连'); setTimeout(wxLoadBots,800); });
}
function wxDel(id){
 if(!confirm('确认删除该微信机器人？')) return;
 api('/api/weixin/bots/'+id,{method:'DELETE'}).then(function(){ toast('已删除'); wxLoadBots(); });
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
  '<span style="font-size:12px;color:var(--muted)">微信通道运行日志（最近 200 条）</span></div><div id="wxLogList"></div>';
 api('/api/weixin/logs').then(function(r){
  var box=$('wxLogList'); if(!box) return;
  var list=r.data||[];
  if(!list.length){ box.innerHTML='<div style="color:var(--muted);padding:18px">暂无日志</div>'; return; }
  box.innerHTML = list.map(function(l){
   return '<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:12px">'+
    '<span style="color:var(--muted)">'+wxTime(l.createdAt)+'</span> <code>'+esc(l.type||'')+'</code> '+
    '<span>'+esc(l.content||'')+'</span></div>';
  }).join('');
 });
}
"""
}