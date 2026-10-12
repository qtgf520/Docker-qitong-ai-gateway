package com.qitong.gateway

/**
 * ★ v1.124 系统监控 + 备份中心 前端
 */
object AdminJs10 {
    fun js(): String = """
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