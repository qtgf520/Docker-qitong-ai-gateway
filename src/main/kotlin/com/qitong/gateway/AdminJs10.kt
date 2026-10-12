package com.qitong.gateway

/**
 * ★ v1.124 系统监控 + 备份中心 前端
 */
object AdminJs10 {
    fun js(): String = """
// ===== 系统监控（实时 CPU/内存/磁盘/网络/负载） =====
var _monTimer = null;
loaders.monitor = function(){
 var box = $('view-monitor');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>📊 系统监控（每 3 秒自动刷新）</h3><div id="monGrid" style="color:var(--muted);padding:10px">加载中…</div></div>';
 function renderMon(d){
  if(!d) return;
  var memPct = d.memTotalMB > 0 ? Math.round(d.memUsedMB / d.memTotalMB * 100) : 0;
  var diskPct = d.diskTotalMB > 0 ? Math.round(d.diskUsedMB / d.diskTotalMB * 100) : 0;
  var cpuColor = d.cpu > 80 ? 'var(--red)' : (d.cpu > 60 ? 'var(--amber)' : 'var(--green)');
  var memColor = memPct > 80 ? 'var(--red)' : (memPct > 60 ? 'var(--amber)' : 'var(--green)');
  var diskColor = diskPct > 85 ? 'var(--red)' : (diskPct > 70 ? 'var(--amber)' : 'var(--green)');
  var el = $('monGrid'); if(!el) return;
  el.innerHTML =
   '<div class="grid grid-4" style="grid-template-columns:repeat(4,1fr);margin:0">' +
    '<div class="stat"><div class="num" style="font-size:20px;color:'+cpuColor+'">'+d.cpu+'%</div><div class="lbl">CPU 使用率'+'（负载 '+d.load1+'/'+d.load5+'/'+d.load15+'）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px;color:'+memColor+'">'+d.memUsedMB+' MB</div><div class="lbl">JVM 内存（'+memPct+'% / 最大 '+d.memTotalMB+' MB）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px;color:'+diskColor+'">'+d.diskUsedMB+' MB</div><div class="lbl">磁盘（'+diskPct+'% / 共 '+d.diskTotalMB+' MB）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">↓'+d.netInKB+' KB/s</div><div class="lbl">↑'+d.netOutKB+' KB/s · '+d.threads+' 线程</div></div>' +
   '</div>';
 }
 api('/api/monitor').then(function(r){ if(r.code===0 && r.data) renderMon(r.data); });
 if(_monTimer) clearInterval(_monTimer);
 _monTimer = setInterval(function(){
  if(!$('view-monitor') || !$('view-monitor').classList.contains('active')){ clearInterval(_monTimer); _monTimer=null; return; }
  api('/api/monitor').then(function(r){ if(r.code===0 && r.data) renderMon(r.data); });
 }, 3000);
};

// ===== 备份中心（一键备份/恢复，仅管理员） =====
loaders.backups = function(){
 var box = $('view-backups');
 if(!box) return;
 box.innerHTML =
  '<div class="action-bar">' +
   '<button class="btn" onclick="doBackup()">💾 立即备份</button>' +
   '<span style="color:var(--muted);font-size:13px">SQLite 在线一致性备份（不中断服务），默认保留最近 10 份</span>' +
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
  if(!list.length){ el.innerHTML = '<div style="color:var(--muted)">暂无备份，点击「立即备份」创建第一份</div>'; return; }
   var rows = list.map(function(b, bi){
   var t = new Date(b.time);
   var ts = t.getFullYear()+'-'+String(t.getMonth()+1).padStart(2,'0')+'-'+String(t.getDate()).padStart(2,'0')+' '+String(t.getHours()).padStart(2,'0')+':'+String(t.getMinutes()).padStart(2,'0');
   return '<tr><td style="font-family:ui-monospace,monospace">'+esc(b.name)+'</td><td>'+b.sizeMB+' MB</td><td>'+ts+'</td>' +
    '<td><button class="btn-ghost btn-sm" style="color:var(--red)" onclick="restoreBackup('+bi+')">恢复</button></td></tr>';
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