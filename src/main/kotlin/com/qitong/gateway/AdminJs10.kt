package com.qitong.gateway

/**
 * ★ v1.124 系统监控 + 备份中心 前端
 */
object AdminJs10 {
    fun js(): String = """

// ===== 模型价格表（全部模型默认价 + 管理员可维护自定义价，元/百万token） =====
loaders.prices = function(){
 var box = $('view-prices');
 if(!box) return;
 box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--muted);padding:10px;font-size:13px">加载中…</div></div>';
 api('/api/prices').then(function(r){
  if(r.code !== 0){ box.innerHTML = '<div class="card"><h3>💰 模型默认价格表</h3><div style="color:var(--red);padding:10px">加载失败</div></div>'; return; }
  var d = r.data || {};
  var tbl = d.table || {};
  var isAdmin = !!d.isAdmin;
  var rows = Object.keys(tbl).map(function(mid){
   var p = tbl[mid] || {};
   var isCustom = p.custom;
   var ops = '';
   if(isAdmin){
    ops = '<td style="white-space:nowrap">' +
     (isCustom ? '<button class="btn-ghost btn-sm" onclick="editPrice(\'' + esc(mid) + '\',' + p.input + ',' + p.output + ')">编辑</button> <button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delPrice(\'' + esc(mid) + '\')">删</button>' : '<button class="btn-ghost btn-sm" onclick="editPrice(\'' + esc(mid) + '\',' + p.input + ',' + p.output + ')">设自定义</button>') +
     '</td>';
   }
   return '<tr>' +
    '<td style="font-family:ui-monospace,monospace">'+esc(mid)+'</td>' +
    '<td>¥'+p.input+'</td>' +
    '<td>¥'+p.output+'</td>' +
    '<td>'+(isCustom?'<span class="badge cyan">自定义</span>':(p.default?'<span class="badge gray">默认价</span>':'<span class="badge green">已收录</span>'))+'</td>' +
    ops +
   '</tr>';
  }).join('');
  var adminBar = isAdmin ?
   '<div class="action-bar" style="margin-bottom:12px">' +
    '<button class="btn" onclick="addPrice()">+ 添加自定义价格</button>' +
    '<button class="btn-ghost" style="color:var(--red)" onclick="clearAllPrices()">清空全部自定义</button>' +
    '<span style="color:var(--muted);font-size:12px">自定义价格优先于内置价格与模型单价；计费时按：自定义表 → 模型单价 → 内置表</span>' +
   '</div>' : '';
  var html = adminBar +
   '<div class="grid grid-3" style="margin-bottom:14px">' +
    '<div class="stat"><div class="num" style="font-size:20px">¥'+d.defaultPrice+'</div><div class="lbl">默认价（未收录模型，元/百万token 输入）</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+d.outputMultiplier+'x</div><div class="lbl">输出价 = 输入价 × 倍数</div></div>' +
    '<div class="stat"><div class="num" style="font-size:20px">'+Object.keys(tbl).length+' 款</div><div class="lbl">已收录模型</div></div>' +
   '</div>' +
   '<div class="card"><h3>全部模型价格（按输入/输出计费）</h3><div class="table-wrap"><table><thead><tr><th>模型</th><th>输入价（元/百万token）</th><th>输出价（元/百万token）</th><th>状态</th>'+(isAdmin?'<th>操作</th>':'')+'</tr></thead><tbody>' +
    (rows || '<tr><td colspan="'+(isAdmin?'5':'4')+'" style="text-align:center;color:var(--muted)">价格表为空</td></tr>') +
   '</tbody></table></div>' +
'<div style="font-size:11px;color:var(--muted);margin-top:8px">💰 价格单位：元 / 百万 Token（输入价）。模型可单独设置自定义单价（编辑模型时设置），自定义价格表优先于模型单价与默认价。未收录模型统一按默认价计费。</div></div>';
   box.innerHTML = html;
   // ★ v1.158 模型映射区（外部请求名 -> 内部模型有序列表，按序故障转移）
   if(isAdmin){
    api('/api/model-mappings').then(function(mr){
     var mapBox = document.createElement('div');
     mapBox.style.marginTop = '14px';
     var list = mr.data || [];
     var rows2 = list.map(function(mp){
      return '<tr>' +
       '<td style="font-family:ui-monospace,monospace">'+esc(mp.alias)+'</td>' +
       '<td style="font-family:ui-monospace,monospace;font-size:12px;word-break:break-all">'+esc(mp.targets)+'</td>' +
       '<td>'+(mp.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>' +
       '<td style="white-space:nowrap">' +
        '<button class="btn-ghost btn-sm" onclick="editMapping(\''+esc(mp.alias)+'\',\''+esc(mp.targets)+'\','+(mp.enabled?'true':'false')+')">编辑</button> ' +
        '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delMapping(\''+esc(mp.alias)+'\')">删</button></td></tr>';
     }).join('');
     mapBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>🧭 模型映射 <span style="font-size:12px;color:var(--muted)">外部请求名 → 内部模型列表（按序故障转移，如 alias=全部模型 → targets=模型Key1,模型Key2,...）</span></h3>' +
      '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addMapping()">+ 添加映射</button>' +
      '<button class="btn-ghost" style="color:var(--red)" onclick="clearMappings()">清空全部</button></div>' +
      '<div class="table-wrap"><table><thead><tr><th>外部名</th><th>目标模型列表（逗号分隔）</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
      (rows2 || '<tr><td colspan="4" style="text-align:center;color:var(--muted)">暂无映射，添加后外部客户端可用「映射名」当模型名请求，网关自动按序路由到目标模型并故障转移</td></tr>') +
      '</tbody></table></div>' +
      '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 例：映射名填 <b>fast-ai</b>，目标填 <b>deepseek-chat, kimi-k2.6, qwen-max</b> → 客户端请求 model=fast-ai 时，网关先试 deepseek-chat，失败自动切 kimi-k2.6，再失败切 qwen-max</div></div>';
     box.appendChild(mapBox);
     // ★ v1.158 计费规则区（条件：模型/角色/时段 → 覆盖价或倍率）
     api('/api/pricing-rules').then(function(pr){
      var ruleBox = document.createElement('div');
      ruleBox.style.marginTop = '14px';
      var plist = pr.data || [];
      var prows = plist.map(function(rl){
       return '<tr>' +
        '<td>'+esc(rl.name)+'</td>' +
        '<td style="font-size:12px">'+esc(rl.modelPattern||'-')+'</td>' +
        '<td style="font-size:12px">'+esc(rl.role||'-')+'</td>' +
        '<td style="font-size:12px">'+esc(rl.timeRange||'-')+'</td>' +
        '<td>'+(rl.price>0?'¥'+rl.price:(rl.multiplier!==1.0?'×'+rl.multiplier:'-'))+'</td>' +
        '<td>'+(rl.enabled?'<span class="badge green">启用</span>':'<span class="badge gray">停用</span>')+'</td>' +
        '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="editRule('+rl.id+',\''+esc(rl.name)+'\',\''+esc(rl.modelPattern)+'\',\''+esc(rl.role)+'\',\''+esc(rl.timeRange)+'\','+rl.price+','+rl.multiplier+','+(rl.enabled?'true':'false')+')">编辑</button> ' +
        '<button class="btn-ghost btn-sm" style="color:var(--red)" onclick="delRule('+rl.id+')">删</button></td></tr>';
      }).join('');
      ruleBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>⚖️ 计费规则（表达式计费） <span style="font-size:12px;color:var(--muted)">按 模型/角色/时段 差异化定价</span></h3>' +
       '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addRule()">+ 添加规则</button>' +
       '<button class="btn-ghost" style="color:var(--red)" onclick="clearRules()">清空全部</button></div>' +
       '<div class="table-wrap"><table><thead><tr><th>规则名</th><th>模型匹配</th><th>角色</th><th>时段(HH:mm-HH:mm)</th><th>覆盖价/倍率</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
       (prows || '<tr><td colspan="7" style="text-align:center;color:var(--muted)">暂无计费规则。例：晚高峰 20:00-23:00 模型 deepseek* ×1.5；或 agent 角色所有模型 ×0.8</td></tr>') +
       '</tbody></table></div>' +
       '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 命中规则时：覆盖价(price>0)优先，否则按倍率；条件空=不限。计费优先级：计费规则 → 自定义价格表 → 模型单价 → 内置表</div></div>';
      box.appendChild(ruleBox);
      // ★ v1.158 渠道分组区（用户选组不选具体模型）
      api('/api/channel-groups').then(function(cgr){
       var grpBox = document.createElement('div');
       grpBox.style.marginTop = '14px';
       var groups = (cgr.data && cgr.data.groups) || [];
       var gmodels = (cgr.data && cgr.data.models) || [];
       var grow = groups.map(function(g){
        var cnt = gmodels.filter(function(m){ return m.channelGroup === g.name; }).length;
        return '<tr>' +
         '<td style="font-family:ui-monospace,monospace"><b>'+esc(g.name)+'</b></td>' +
         '<td style="font-size:12px">'+esc(g.description||'-')+'</td>' +
         '<td style="font-size:12px">'+cnt+' 个模型</td>' +
         '<td style="white-space:nowrap"><button class="btn-ghost btn-sm" onclick="delGroup(\''+esc(g.name)+'\')">删</button></td></tr>';
       }).join('');
       // 模型归组下拉（每行一个选择器）
       var gmRow = gmodels.map(function(m){
        var opts = '<option value="">(不分组)</option>' + groups.map(function(g){ return '<option value="'+esc(g.name)+'"'+(m.channelGroup===g.name?' selected':'')+'>'+esc(g.name)+'</option>'; }).join('');
        return '<tr><td style="font-size:12px;font-family:ui-monospace,monospace">'+esc(m.modelId)+'</td><td><select class="input" style="padding:3px 8px;font-size:12px" onchange="setModelGroup('+m.id+',this.value)">'+opts+'</select></td></tr>';
       }).join('');
       grpBox.innerHTML = '<div class="card" style="margin-top:14px"><h3>🔀 渠道分组 <span style="font-size:12px;color:var(--muted)">用户请求 model=组名 时，只在该组内自动选模型</span></h3>' +
        '<div class="action-bar" style="margin-bottom:8px"><button class="btn" onclick="addGroup()">+ 添加分组</button></div>' +
        '<div class="table-wrap"><table><thead><tr><th>组名</th><th>描述</th><th>模型数</th><th>操作</th></tr></thead><tbody>' +
        (grow || '<tr><td colspan="4" style="text-align:center;color:var(--muted)">暂无分组。例：fast（低延迟模型）/ cheap（便宜模型）/ stable（稳定模型）</td></tr>') +
        '</tbody></table></div>' +
        '<h3 style="margin-top:10px">模型归组（选择后自动保存）</h3>' +
        '<div style="max-height:240px;overflow-y:auto"><table class="tb"><thead><tr><th>模型</th><th>所属分组</th></tr></thead><tbody>' +
        (gmRow || '<tr><td colspan="2" style="text-align:center;color:var(--muted)">暂无模型</td></tr>') +
        '</tbody></table></div>' +
        '<div style="font-size:11px;color:var(--muted);margin-top:6px">💡 客户端请求 <b>model=fast</b> → 网关只在 fast 组的模型里按权重/健康自动选择并故障转移；qtai-sj 配组名也能按组选</div></div>';
       box.appendChild(grpBox);
      });
     });
    });
   }
  });
 };
// ★ v1.158 价格表管理：添加/编辑/删除（仅管理员）
window.addPrice = function(){
 openModal('添加自定义价格', '<div class="form-row"><label>模型ID *</label><input id="prModel" class="input" placeholder="如 deepseek-v4-flash"></div>' +
  '<div class="form-row"><label>输入价（元/百万token）*</label><input id="prIn" class="input" type="number" step="0.01" min="0" value="2"></div>' +
  '<div class="form-row"><label>输出价（元/百万token，空=输入价×2）</label><input id="prOut" class="input" type="number" step="0.01" min="0" placeholder="留空自动×2"></div>', function(){
  var mid = ($('prModel').value||'').trim();
  var inP = parseFloat($('prIn').value)||0;
  var outP = $('prOut').value ? (parseFloat($('prOut').value)||0) : inP*2;
  if(!mid){ toast('请填写模型ID', false); return; }
  api('/api/prices', { method:'POST', body: { modelId: mid, inputPrice: inP, outputPrice: outP } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editPrice = function(mid, inP, outP){
 openModal('编辑价格：'+esc(mid), '<div class="form-row"><label>模型ID</label><input class="input" value="'+esc(mid)+'" disabled></div>' +
  '<div class="form-row"><label>输入价（元/百万token）</label><input id="prIn" class="input" type="number" step="0.01" min="0" value="'+inP+'"></div>' +
  '<div class="form-row"><label>输出价（元/百万token）</label><input id="prOut" class="input" type="number" step="0.01" min="0" value="'+outP+'"></div>', function(){
  var inP2 = parseFloat($('prIn').value)||0;
  var outP2 = parseFloat($('prOut').value)||0;
  api('/api/prices', { method:'POST', body: { modelId: mid, inputPrice: inP2, outputPrice: outP2 } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delPrice = function(mid){
 if(!confirm('确定删除「'+mid+'」的自定义价格？（删除后回退内置价格）')) return;
 api('/api/prices/' + encodeURIComponent(mid), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearAllPrices = function(){
  if(!confirm('确定清空全部自定义价格？（恢复为内置价格表）')) return;
  api('/api/prices/clear', { method:'POST' }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0) loaders.prices();
  });
};
// ★ v1.158 模型映射管理（外部请求名 -> 内部模型列表按序路由）
window.addMapping = function(){
 openModal('添加模型映射', '<div class="form-row"><label>外部模型名 *（客户端请求时用这个当 model）</label><input id="mpAlias" class="input" placeholder="如 fast-ai"></div>' +
  '<div class="form-row"><label>目标模型列表 *（逗号分隔，按顺序故障转移）</label><input id="mpTargets" class="input" placeholder="如 deepseek-chat, kimi-k2.6, qwen-max"></div>', function(){
  var alias = ($('mpAlias').value||'').trim();
  var tgts = ($('mpTargets').value||'').trim();
  if(!alias){ toast('请填写外部模型名', false); return; }
  if(!tgts){ toast('请填写目标模型列表', false); return; }
  api('/api/model-mappings', { method:'POST', body: { alias: alias, targets: tgts, enabled: true } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editMapping = function(alias, tgts, enabled){
 openModal('编辑映射：'+esc(alias), '<div class="form-row"><label>外部模型名</label><input class="input" value="'+esc(alias)+'" disabled></div>' +
  '<div class="form-row"><label>目标模型列表（逗号分隔）</label><input id="mpTargets" class="input" value="'+esc(tgts||'')+'"></div>' +
  '<div class="form-row"><label><input type="checkbox" id="mpEnabled"'+(enabled?' checked':'')+'>启用</label></div>', function(){
  var t2 = ($('mpTargets').value||'').trim();
  var en = $('mpEnabled') ? $('mpEnabled').checked : true;
  if(!t2){ toast('请填写目标模型列表', false); return; }
  api('/api/model-mappings', { method:'POST', body: { alias: alias, targets: t2, enabled: en } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delMapping = function(alias){
 if(!confirm('确定删除映射「'+esc(alias)+'」？')) return;
 api('/api/model-mappings/' + encodeURIComponent(alias), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearMappings = function(){
 if(!confirm('确定清空全部模型映射？')) return;
 api('/api/model-mappings/clear', { method:'POST' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
// ★ v1.158 计费规则管理
window.addRule = function(){
 openModal('添加计费规则', '<div class="form-row"><label>规则名 *</label><input id="rlName" class="input" placeholder="如 晚高峰加价"></div>' +
  '<div class="form-row"><label>模型匹配（空=全部，支持模糊如 deepseek）</label><input id="rlModel" class="input" placeholder="如 deepseek / gpt-4 / 留空全部"></div>' +
  '<div class="form-row"><label>角色（空=全部，admin/agent/user）</label><input id="rlRole" class="input" placeholder="如 agent / 留空全部"></div>' +
  '<div class="form-row"><label>时段（HH:mm-HH:mm，空=全天）</label><input id="rlTime" class="input" placeholder="如 20:00-23:00 / 留空全天"></div>' +
  '<div class="form-row"><label>覆盖价（元/百万token，0=不用覆盖价）</label><input id="rlPrice" class="input" type="number" step="0.1" min="0" value="0"></div>' +
  '<div class="form-row"><label>倍率（如 1.5=加价50%，0.8=8折）</label><input id="rlMul" class="input" type="number" step="0.1" value="1"></div>', function(){
  var nm = ($('rlName').value||'').trim();
  if(!nm){ toast('请填写规则名', false); return; }
  api('/api/pricing-rules', { method:'POST', body: {
   name: nm, modelPattern: ($('rlModel').value||'').trim(), role: ($('rlRole').value||'').trim(),
   timeRange: ($('rlTime').value||'').trim(), price: parseFloat($('rlPrice').value)||0,
   multiplier: parseFloat($('rlMul').value)||1, enabled: true
  }}).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.editRule = function(id, nm, mp, rl, tr, price, mul, en){
 openModal('编辑计费规则', '<div class="form-row"><label>规则名</label><input id="rlName" class="input" value="'+esc(nm)+'"></div>' +
  '<div class="form-row"><label>模型匹配</label><input id="rlModel" class="input" value="'+esc(mp||'')+'"></div>' +
  '<div class="form-row"><label>角色</label><input id="rlRole" class="input" value="'+esc(rl||'')+'"></div>' +
  '<div class="form-row"><label>时段</label><input id="rlTime" class="input" value="'+esc(tr||'')+'"></div>' +
  '<div class="form-row"><label>覆盖价</label><input id="rlPrice" class="input" type="number" step="0.1" min="0" value="'+(price>0?price:0)+'"></div>' +
  '<div class="form-row"><label>倍率</label><input id="rlMul" class="input" type="number" step="0.1" value="'+(mul||1)+'"></div>' +
  '<div class="form-row"><label><input type="checkbox" id="rlEnabled"'+(en?' checked':'')+'>启用</label></div>', function(){
  var nm2 = ($('rlName').value||'').trim();
  if(!nm2){ toast('请填写规则名', false); return; }
  api('/api/pricing-rules', { method:'POST', body: {
   id: id, name: nm2, modelPattern: ($('rlModel').value||'').trim(), role: ($('rlRole').value||'').trim(),
   timeRange: ($('rlTime').value||'').trim(), price: parseFloat($('rlPrice').value)||0,
   multiplier: parseFloat($('rlMul').value)||1, enabled: $('rlEnabled')?$('rlEnabled').checked:true
  }}).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delRule = function(id){
 if(!confirm('确定删除该计费规则？')) return;
 api('/api/pricing-rules/'+id, { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.clearRules = function(){
 if(!confirm('确定清空全部计费规则？')) return;
 api('/api/pricing-rules/clear', { method:'POST' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
// ★ v1.158 渠道分组管理
window.addGroup = function(){
 openModal('添加渠道分组', '<div class="form-row"><label>组名 *（客户端请求时用这个当 model）</label><input id="cgName" class="input" placeholder="如 fast / cheap / stable"></div>' +
  '<div class="form-row"><label>描述</label><input id="cgDesc" class="input" placeholder="如 低延迟优先"></div>', function(){
  var nm = ($('cgName').value||'').trim();
  if(!nm){ toast('请填写组名', false); return; }
  api('/api/channel-groups', { method:'POST', body: { name: nm, description: ($('cgDesc').value||'').trim() } }).then(function(r){
   toast(r.msg, r.code === 0);
   if(r.code === 0){ closeModal(); loaders.prices(); }
  });
 });
};
window.delGroup = function(name){
 if(!confirm('确定删除分组「'+esc(name)+'」？（组内模型自动取消分组）')) return;
 api('/api/channel-groups/' + encodeURIComponent(name), { method:'DELETE' }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code === 0) loaders.prices();
 });
};
window.setModelGroup = function(id, group){
 api('/api/models/'+id+'/group', { method:'POST', body: { group: group } }).then(function(r){
  toast(r.msg, r.code === 0);
  if(r.code !== 0) loaders.prices();
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