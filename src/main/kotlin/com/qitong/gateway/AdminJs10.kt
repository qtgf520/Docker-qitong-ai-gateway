package com.qitong.gateway

/**
 * ★ v1.124 系统监控 + 备份中心 前端
 */
object AdminJs10 {
    fun js(): String = """

// ===== 模型价格表（全部模型默认价，元/百万token） =====
loaders.prices = function(){
 var box = $('view-prices');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中…</div></div>';
 api('/api/prices').then(function(r){
  if(r.code !== 0){ box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--red);padding:10px">加载失败</div></div>'; return; }
  var d = r.data || {};
  var tbl = d.table || {};
  var rows = Object.keys(tbl).map(function(mid){
   var p = tbl[mid] || {};
   return '<tr>' +
    '<td style="font-family:ui-monospace,monospace">'+esc(mid)+'</td>' +
    '<td>¥'+p.input+'</td>' +
    '<td>¥'+p.output+'</td>' +
    '<td>'+(p.default?'<span class="badge gray">默认价</span>':'<span class="badge green">已收录</span>')+'</td>' +
   '</tr>';
  }).join('');
  var html =
   '<div class="grid grid-3" style="margin-bottom:14px">' +
    '<div class="stat"><div class="num" style="font-size:20px">¥'+d.defaultPrice+'</div><div class="lbl">默认价（未收录模型，元/百万token 输入）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+d.outputMultiplier+'x</div><div class="lbl">输出价 = 输入价 × 倍数</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+Object.keys(tbl).length+' 款</div><div class="lbl">已收录模型</div></div>' +
   '</div>' +
   '<div class="card"><h3>全部模型价格（按输入/输出计费）</h3><div class="table-wrap"><table><thead><tr><th>模型</th><th>输入价（元/百万token）</th><th>输出价（元/百万token）</th><th>状态</th></tr></thead><tbody>' +
    (rows || '<tr><td colspan="4" style="text-align:center;color:var(--muted)">价格表为空</td></tr>') +
   '</tbody></table></div>' +
   '<div style="font-size:11px;color:var(--muted);margin-top:8px">💰 价格单位：元 / 百万 Token（输入价）。模型可单独设置自定义单价（编辑模型时设置），自定义价优先于默认价。未收录模型统一按默认价计费。</div></div>';
  box.innerHTML = html;
 });
};
// ===== 备份中心（一键备份/恢复，仅管理员） =====
loaders.backups = function(){
 var box = $('view-backups');
 if(!box) return;
 box.innerHTML =
  '<div class="action-bar">' +
   '<button class="btn" onclick="doBackup()">💾 立即备份</button>' +
   '<button class="btn-ghost" onclick="loadBackups()">🔄 刷新</button>' +
   '<span style="color:var(--muted);font-size:13px">SQLite 在线一致性备份（不中断服务），默认保留最近 10 份</span>' +
  '</div>' +
  '<div class="grid grid-3" style="margin-bottom:14px">' +
   '<div class="stat"><div class="num" style="font-size:20px" id="bkCount">—</div><div class="lbl">备份总数</div></div>' +
   '<div class="stat"><div class="num" style="font-size:20px" id="bkSize">—</div><div class="lbl">总大小</div></div>' +
   '<div class="stat"><div class="num" style="font-size:20px;color:var(--green)">10 份</div><div class="lbl">自动保留上限</div></div>' +
  '</div>' +
  '<div class="card"><h3>备份列表</h3><div id="bkList" style="color:var(--muted);padding:10px">加载中…</div></div>';
 loadBackups();
};
window.doBackup = function(){
 api('/api/backups', {method:'POST'}).then(function(r){
  toast(r.msg || '备份完成', r.code===0);
  if(r.code===0) loadBackups();
 });
};
function loadBackups(){
 var el = $('bkList'); if(!el) return;
 api('/api/backups').then(function(r){
  if(r.code !== 0){ el.innerHTML = '<div style="color:var(--red)">加载失败</div>'; return; }
  var list = (r.data && r.data.list) || [];
  window._bkStore = list;
  var cntEl = $('bkCount'); if(cntEl) cntEl.textContent = list.length;
  var sizeEl = $('bkSize');
  if(sizeEl){
   var tot = 0;
   list.forEach(function(b){ tot += parseFloat(b.sizeMB) || 0; });
   sizeEl.textContent = tot.toFixed(2) + ' MB';
  }
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted);padding:20px;text-align:center">暂无备份，点击「立即备份」创建第一份</div>'; return; }
  var rows = list.map(function(b, bi){
   var t = new Date(b.time);
   var ts = t.getFullYear()+'-'+String(t.getMonth()+1).padStart(2,'0')+'-'+String(t.getDate()).padStart(2,'0')+' '+String(t.getHours()).padStart(2,'0')+':'+String(t.getMinutes()).padStart(2,'0');
   var isLatest = bi === 0;
   return '<tr style="'+(isLatest?'background:rgba(var(--primary-rgb),.06)':'')+'">' +
    '<td style="font-family:ui-monospace,monospace">🗄 '+esc(b.name)+(isLatest?' <span class="badge green">最新</span>':'')+'</td>' +
    '<td>'+b.sizeMB+' MB</td><td>'+ts+'</td>' +
    '<td><button class="btn-ghost btn-sm" style="color:var(--red)" onclick="restoreBackup('+bi+')">↩ 恢复</button></td></tr>';
  }).join('');
  el.innerHTML = '<div class="table-wrap"><table><thead><tr><th>备份文件</th><th>大小</th><th>时间</th><th>操作</th></tr></thead><tbody>'+rows+'</tbody></table></div>' +
   '<div style="font-size:11px;color:var(--muted);margin-top:8px">⚠️ 恢复会覆盖当前数据库，需重启容器生效。操作前建议先执行一次新备份。</div>';
 });
}
window.restoreBackup = function(i){
 var b = (window._bkStore||[])[i];
 if(!b || !b.name) return;
 var name = b.name;
 if(!confirm('确认恢复备份 '+name+' ？\n此操作将覆盖当前数据库，重启后生效。')) return;
 api('/api/backups/restore', {method:'POST', body:{name:name}}).then(function(r){
  toast(r.msg || '已恢复', r.code===0);
  if(r.code===0) loadBackups();
 });
};
"""
}