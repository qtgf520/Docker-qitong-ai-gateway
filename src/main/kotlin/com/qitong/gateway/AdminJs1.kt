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
// ===== 🌐 i18n 多语言引擎（15语言全界面翻译） =====
var I18N = {
  zh: { home:'首页', providers:'服务商', models:'模型', speedtest:'测速排行', chat:'内置聊天', keys:'API密钥', rules:'路由规则', usage:'用量统计', tickets:'工单中心', profile:'个人中心', settings:'网关设置', logs:'操作日志', announcements:'公告管理', users:'用户管理', about:'关于我们', search:'搜索', online:'在线', offline:'离线', save:'保存', cancel:'取消', delete:'删除', edit:'编辑', add:'添加', confirm:'确定', loading:'加载中...', none:'暂无数据' },
  'zh-tw': { home:'首頁', providers:'服務商', models:'模型', speedtest:'測速排行', chat:'內置聊天', keys:'API密鑰', rules:'路由規則', usage:'用量統計', tickets:'工單中心', profile:'個人中心', settings:'網關設置', logs:'操作日誌', announcements:'公告管理', users:'用戶管理', about:'關於我們', search:'搜索', online:'在線', offline:'離線', save:'保存', cancel:'取消', delete:'刪除', edit:'編輯', add:'添加', confirm:'確定', loading:'加載中...', none:'暫無數據' },
  en: { home:'Home', providers:'Providers', models:'Models', speedtest:'Speed Test', chat:'Chat', keys:'API Keys', rules:'Routing', usage:'Usage', tickets:'Tickets', profile:'Profile', settings:'Settings', logs:'Logs', announcements:'Announcements', users:'Users', about:'About', search:'Search', online:'Online', offline:'Offline', save:'Save', cancel:'Cancel', delete:'Delete', edit:'Edit', add:'Add', confirm:'OK', loading:'Loading...', none:'No data' },
  ja: { home:'ホーム', providers:'プロバイダー', models:'モデル', speedtest:'速度テスト', chat:'チャット', keys:'APIキー', rules:'ルーティング', usage:'使用量', tickets:'チケット', profile:'プロフィール', settings:'設定', logs:'ログ', announcements:'お知らせ', users:'ユーザー', about:'情報', search:'検索', online:'オンライン', offline:'オフライン', save:'保存', cancel:'キャンセル', delete:'削除', edit:'編集', add:'追加', confirm:'OK', loading:'読み込み中...', none:'データなし' },
  ko: { home:'홈', providers:'제공자', models:'모델', speedtest:'속도 테스트', chat:'채팅', keys:'API 키', rules:'라우팅', usage:'사용량', tickets:'티켓', profile:'프로필', settings:'설정', logs:'로그', announcements:'공지', users:'사용자', about:'정보', search:'검색', online:'온라인', offline:'오프라인', save:'저장', cancel:'취소', delete:'삭제', edit:'편집', add:'추가', confirm:'확인', loading:'로딩 중...', none:'데이터 없음' },
  es: { home:'Inicio', providers:'Proveedores', models:'Modelos', speedtest:'Test Velocidad', chat:'Chat', keys:'Claves API', rules:'Rutas', usage:'Uso', tickets:'Tickets', profile:'Perfil', settings:'Ajustes', logs:'Registros', announcements:'Anuncios', users:'Usuarios', about:'Acerca', search:'Buscar', online:'En línea', offline:'Fuera', save:'Guardar', cancel:'Cancelar', delete:'Eliminar', edit:'Editar', add:'Añadir', confirm:'OK', loading:'Cargando...', none:'Sin datos' },
  fr: { home:'Accueil', providers:'Fournisseurs', models:'Modèles', speedtest:'Test Vitesse', chat:'Chat', keys:'Clés API', rules:'Routage', usage:'Usage', tickets:'Tickets', profile:'Profil', settings:'Paramètres', logs:'Journaux', announcements:'Annonces', users:'Utilisateurs', about:'À propos', search:'Rechercher', online:'En ligne', offline:'Hors ligne', save:'Enregistrer', cancel:'Annuler', delete:'Supprimer', edit:'Modifier', add:'Ajouter', confirm:'OK', loading:'Chargement...', none:'Aucune donnée' },
  de: { home:'Start', providers:'Anbieter', models:'Modelle', speedtest:'Geschwindigkeit', chat:'Chat', keys:'API-Schlüssel', rules:'Routing', usage:'Nutzung', tickets:'Tickets', profile:'Profil', settings:'Einstellungen', logs:'Protokolle', announcements:'Ankündigungen', users:'Benutzer', about:'Über', search:'Suchen', online:'Online', offline:'Offline', save:'Speichern', cancel:'Abbrechen', delete:'Löschen', edit:'Bearbeiten', add:'Hinzufügen', confirm:'OK', loading:'Laden...', none:'Keine Daten' },
  ru: { home:'Главная', providers:'Провайдеры', models:'Модели', speedtest:'Тест скорости', chat:'Чат', keys:'API-ключи', rules:'Маршруты', usage:'Использование', tickets:'Тикеты', profile:'Профиль', settings:'Настройки', logs:'Журналы', announcements:'Объявления', users:'Пользователи', about:'О нас', search:'Поиск', online:'В сети', offline:'Офлайн', save:'Сохранить', cancel:'Отмена', delete:'Удалить', edit:'Изменить', add:'Добавить', confirm:'ОК', loading:'Загрузка...', none:'Нет данных' },
  pt: { home:'Início', providers:'Provedores', models:'Modelos', speedtest:'Teste Velocidade', chat:'Chat', keys:'Chaves API', rules:'Rotas', usage:'Uso', tickets:'Tickets', profile:'Perfil', settings:'Configurações', logs:'Registros', announcements:'Anúncios', users:'Usuários', about:'Sobre', search:'Buscar', online:'Online', offline:'Offline', save:'Salvar', cancel:'Cancelar', delete:'Excluir', edit:'Editar', add:'Adicionar', confirm:'OK', loading:'Carregando...', none:'Sem dados' },
  vi: { home:'Trang chủ', providers:'Nhà cung cấp', models:'Mô hình', speedtest:'Kiểm tra tốc độ', chat:'Trò chuyện', keys:'Khóa API', rules:'Định tuyến', usage:'Sử dụng', tickets:'Ticket', profile:'Hồ sơ', settings:'Cài đặt', logs:'Nhật ký', announcements:'Thông báo', users:'Người dùng', about:'Giới thiệu', search:'Tìm kiếm', online:'Trực tuyến', offline:'Ngoại tuyến', save:'Lưu', cancel:'Hủy', delete:'Xóa', edit:'Sửa', add:'Thêm', confirm:'OK', loading:'Đang tải...', none:'Không có dữ liệu' },
  th: { home:'หน้าแรก', providers:'ผู้ให้บริการ', models:'โมเดล', speedtest:'ทดสอบความเร็ว', chat:'แชท', keys:'คีย์ API', rules:'เส้นทาง', usage:'การใช้งาน', tickets:'ตั๋ว', profile:'โปรไฟล์', settings:'ตั้งค่า', logs:'บันทึก', announcements:'ประกาศ', users:'ผู้ใช้', about:'เกี่ยวกับ', search:'ค้นหา', online:'ออนไลน์', offline:'ออฟไลน์', save:'บันทึก', cancel:'ยกเลิก', delete:'ลบ', edit:'แก้ไข', add:'เพิ่ม', confirm:'ตกลง', loading:'กำลังโหลด...', none:'ไม่มีข้อมูล' },
  ar: { home:'الرئيسية', providers:'الموفرون', models:'النماذج', speedtest:'اختبار السرعة', chat:'الدردشة', keys:'مفاتيح API', rules:'التوجيه', usage:'الاستخدام', tickets:'التذاكر', profile:'الملف', settings:'الإعدادات', logs:'السجلات', announcements:'الإعلانات', users:'المستخدمون', about:'حول', search:'بحث', online:'متصل', offline:'غير متصل', save:'حفظ', cancel:'إلغاء', delete:'حذف', edit:'تعديل', add:'إضافة', confirm:'موافق', loading:'جار التحميل...', none:'لا توجد بيانات' },
  hi: { home:'होम', providers:'प्रदाता', models:'मॉडल', speedtest:'स्पीड टेस्ट', chat:'चैट', keys:'API कुंजी', rules:'रूटिंग', usage:'उपयोग', tickets:'टिकट', profile:'प्रोफ़ाइल', settings:'सेटिंग्स', logs:'लॉग', announcements:'घोषणाएँ', users:'उपयोगकर्ता', about:'परिचय', search:'खोज', online:'ऑनलाइन', offline:'ऑफ़लाइन', save:'सहेजें', cancel:'रद्द करें', delete:'हटाएँ', edit:'संपादित करें', add:'जोड़ें', confirm:'ठीक', loading:'लोड हो रहा...', none:'कोई डेटा नहीं' },
  id: { home:'Beranda', providers:'Penyedia', models:'Model', speedtest:'Tes Kecepatan', chat:'Chat', keys:'Kunci API', rules:'Routing', usage:'Penggunaan', tickets:'Tiket', profile:'Profil', settings:'Pengaturan', logs:'Log', announcements:'Pengumuman', users:'Pengguna', about:'Tentang', search:'Cari', online:'Daring', offline:'Luring', save:'Simpan', cancel:'Batal', delete:'Hapus', edit:'Ubah', add:'Tambah', confirm:'OK', loading:'Memuat...', none:'Tidak ada data' }
};
var curLang = localStorage.getItem('qt_lang') || 'zh';
function t(key){ var d = I18N[curLang] || I18N.zh; return d[key] !== undefined ? d[key] : (I18N.zh[key] !== undefined ? I18N.zh[key] : key); }
// 应用语言：翻译菜单/底部导航/顶栏（页面数据由 loaders 重渲染）
function applyLang(){
  var map = { dashboard:'home', providers:'providers', models:'models', speedtest:'speedtest', chat:'chat', keys:'keys', rules:'rules', usage:'usage', tickets:'tickets', profile:'profile', settings:'settings', logs:'logs', announcements:'announcements', users:'users', about:'about' };
  document.querySelectorAll('.sidebar nav a[data-page], .bottom-nav a[data-page]').forEach(function(a){
    var key = map[a.getAttribute('data-page')];
    if(key) a.querySelector('b').textContent = t(key);
  });
  $('userInfo').textContent = localStorage.getItem('qt_user') || '';
}
function saveLangLocal(lang){ curLang = lang; localStorage.setItem('qt_lang', lang); applyLang(); }
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
// 全局搜索：模糊匹配页面并跳转
function globalSearch(){
  var q = ($('globalSearch') ? $('globalSearch').value : '').trim().toLowerCase();
  if(!q) return;
  var map = [
    ['首页','dashboard'],['服务商','providers'],['模型','models'],['测速','speedtest'],
    ['聊天','chat'],['密钥','keys'],['路由','rules'],['用量','usage'],
    ['工单','tickets'],['个人','profile'],['设置','settings'],['日志','logs'],
    ['公告','announcements'],['用户','users'],['关于','about']
  ];
  // 加上英文/拼音匹配
  map.push(['home','dashboard'],['provider','providers'],['model','models'],['speed','speedtest'],
    ['chat','chat'],['key','keys'],['rule','rules'],['usage','usage'],
    ['ticket','tickets'],['profile','profile'],['setting','settings'],['log','logs'],
    ['announce','announcements'],['user','users'],['about','about']);
  var hit = map.filter(function(m){ return m[0].toLowerCase().indexOf(q) >= 0 || q.indexOf(m[0].toLowerCase()) >= 0; });
  if(hit.length){ switchView(hit[0][1]); if($('globalSearch')) $('globalSearch').value=''; }
  else toast('未找到页面: ' + q, false);
}
// ===== 首页（完整功能：启停/地址/自动测速/强制选择模型/三指标排行榜/池灯） =====
loaders.dashboard = function(){
  var box = $('view-dashboard');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/status').then(function(r){
    var st = r.data;
    if(!st) return;
    // 用户余额（顶部）
    api('/api/me/balance').then(function(br){
      var bal = br.data || {};
      var balEl = $('view-dashboard') ? document.querySelector('#view-dashboard .bal-card') : null;
      if(balEl){
        balEl.innerHTML = '<div class="stat"><div class="num" style="color:var(--green)">¥'+(bal.balance||0).toFixed(2)+'</div><div class="lbl">我的余额</div></div>' +
          (bal.quotaLimit>0 ? '<div class="stat"><div class="num">'+fmtNum(bal.quotaUsed||0)+' / '+fmtNum(bal.quotaLimit)+'</div><div class="lbl">额度(token)</div></div>' : '') +
          '<div class="stat"><div class="num">'+(bal.totalRecharge||0).toFixed(2)+'</div><div class="lbl">累计充值</div></div>';
      }
    });
    // 模型排行榜（三指标 + 全部已启用模型 + 点灯=强制切换）
    var rankRows = '';
    var pool = st.forcedPool || [];
    var active = st.forcedActive || st.activeModel || '';  // 当前活跃（含强制池首位）
    (st.pipelineSorted || []).forEach(function(key, i){
      var parts = key.split('::');
      var mid = parts.length > 1 ? parts[1] : key;
      var pid = parts.length > 1 ? parts[0] : '';
      var h = (st.healthCache || []).find(function(x){ return x.key === key; });
      var ok = h ? h.isHealthy : false;
      var inPool = pool.indexOf(key) >= 0;
      var isActive = (mid === active) || (inPool && pool[0] === key);  // 亮灯=当前强制活跃
      var lat = (h && h.isHealthy && h.totalMs > 0) ? h.totalMs + 'ms' : '—';
      var ttft = (h && h.isHealthy && h.ttftMs > 0) ? h.ttftMs + 'ms' : '—';
      var tps = (h && h.isHealthy && h.tps > 0) ? h.tps.toFixed(2) + ' tok/s' : '—';
      // 池灯：点击=强制切换到此模型（点灯）
      var poolDot = '<span class="pool-dot ' + (isActive ? 'on' : (inPool ? 'pooled' : '')) + '" title="' + (isActive ? '当前强制活跃，点击移出' : '点击强制切换到此模型') + '" onclick="forceSwitchModel(\'' + key.replace(/'/g, '') + '\')" style="cursor:pointer"></span>';
      var poolBtn = '<button class="btn-ghost ' + (inPool ? 'danger' : '') + '" style="padding:2px 8px;font-size:12px" onclick="toggleForcedModel(\'' + key.replace(/'/g, '') + '\')">' + (inPool ? '移出池' : '加入池') + '</button>';
      rankRows += '<tr><td>' + poolDot + '</td><td>' + (i+1) + '</td><td>' + esc(mid) + (isActive ? ' <span class="badge cyan">▶ 当前</span>' : '') + '</td><td>P' + esc(pid) + '</td><td>' + ttft + '</td><td>' + tps + '</td><td>' + lat + '</td><td><span class="badge ' + (ok ? 'green' : 'gray') + '">' + (ok ? '正常' : '待测速') + '</span></td><td>' + poolBtn + '</td></tr>';
    });
    // 强制故障池（池灯展示，首位=当前活跃）
    var poolHtml = '';
    if(pool.length){
      poolHtml = '<div class="card"><h3>🎯 强制故障池 (' + pool.length + ')</h3><div style="display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px">' +
        pool.map(function(k, idx){
          var mid = k.split('::').length > 1 ? k.split('::')[1] : k;
          var isTop = idx === 0;
          return '<span class="badge ' + (isTop ? 'green' : 'purple') + '" style="cursor:pointer" onclick="forceSwitchModel(\'' + k.replace(/'/g, '') + '\')" title="点击强制切换">' + (isTop ? '▶ ' : '') + esc(mid) + ' <span class="pool-dot ' + (isTop ? 'on' : '') + '"></span></span>';
        }).join('') + '</div><span style="font-size:12px;color:var(--muted)">点灯/点击=强制切换到此模型；首位=当前活跃，qtai-sj 优先走它</span><div style="margin-top:8px"><button class="btn-ghost" onclick="clearForcedPool()">↩️ 清空</button></div></div>';
    }
    // 地址行
    var gwPort = st.gatewayPort || 18889;
    var ip = st.serverIp || '127.0.0.1';
    var addrHtml = '<div class="card"><h3>📋 网关地址</h3>' +
      '<div class="form-row"><label>本地地址</label><div class="addr-line" onclick="copyAddr(this)">http://localhost:' + gwPort + '/v1 <span class="copy-tag">📋 复制</span></div></div>' +
      '<div class="form-row"><label>服务器地址（对外）</label><div class="addr-line" onclick="copyAddr(this)">http://' + esc(ip) + ':' + gwPort + '/v1 <span class="copy-tag">📋 复制</span></div></div></div>';
    box.innerHTML = [
      '<div class="card bal-card"><div class="grid grid-3" style="margin:0"><div class="stat"><div class="num" style="color:var(--green)">¥0.00</div><div class="lbl">我的余额</div></div></div></div>',
      '<div class="card" id="announceCard"><h3>📢 公告</h3><div style="color:var(--muted);padding:8px;font-size:13px">加载中...</div></div>',
      '<div class="grid grid-4" style="grid-template-columns:repeat(4,1fr)">' +
        '<div class="stat"><div class="num" style="font-size:20px">' + fmtUptime(st.uptime || 0) + '</div><div class="lbl">运行时长</div></div>' +
        '<div class="stat"><div class="num" style="font-size:20px">' + (st.pipelineSorted||[]).length + '</div><div class="lbl">可用模型</div></div>' +
        '<div class="stat"><div class="num" style="font-size:20px;color:' + ((st.healthCache||[]).filter(function(x){return x.isHealthy;}).length / Math.max((st.healthCache||[]).length,1) * 100 > 50 ? 'var(--green)' : 'var(--amber)') + '">' + Math.round((st.healthCache||[]).filter(function(x){return x.isHealthy;}).length / Math.max((st.healthCache||[]).length,1) * 100) + '%</div><div class="lbl">健康率</div></div>' +
        '<div class="stat"><div class="num" style="font-size:20px">' + (st.autoFailover ? '🟢' : '⚪') + '</div><div class="lbl">故障转移</div></div>' +
      '</div>',
      '<div class="grid grid-2">',
        '<div class="card" id="gwCtrlCard"><h3>⚡ 网关控制</h3><div style="display:flex;gap:10px;align-items:center">' +
          '<span id="gwDot" class="dot ' + (st.running ? '' : 'off') + '"></span>' +
          '<span id="gwState" style="font-weight:700;color:' + (st.running ? 'var(--green)' : 'var(--red)') + '">' + (st.running ? '运行中' : '已停止') + '</span>' +
          '<button class="btn" id="gwToggleBtn" onclick="toggleGateway()">' + (st.running ? '⏸ 暂停' : '▶️ 启动') + '</button>' +
        '</div><div style="margin-top:8px;font-size:12px;color:var(--muted)">活跃模型：' + esc(st.activeModel || 'qtai-sj') + '</div></div>',
        '<div class="card" id="autoSpeedCard"><h3>⏱ 自动测速</h3><div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">' +
          '<button class="btn" id="autoSpeedBtn" onclick="toggleAutoSpeed()">' + (st.autoSpeedTest ? '⏹ 停止自动测速' : '▶️ 启动自动测速') + '</button>' +
          '<select id="speedInterval" class="input" style="width:130px" onchange="saveSpeedInterval()">' +
            '<option value="5"' + (st.speedIntervalMin==5?' selected':'') + '>5分钟</option>' +
            '<option value="15"' + (st.speedIntervalMin==15?' selected':'') + '>15分钟</option>' +
            '<option value="30"' + (st.speedIntervalMin==30?' selected':'') + '>30分钟</option>' +
            '<option value="60"' + (st.speedIntervalMin==60?' selected':'') + '>1小时</option>' +
            '<option value="120"' + (st.speedIntervalMin==120?' selected':'') + '>2小时</option>' +
            '<option value="240"' + (st.speedIntervalMin==240?' selected':'') + '>4小时</option>' +
          '</select><span style="font-size:11px;color:var(--muted)" id="speedScopeTag"></span></div><div style="margin-top:6px;font-size:12px;color:var(--cyan)" id="dashCountdown"></div></div>',
      '</div>',
      addrHtml,
      poolHtml,
      '<div class="card"><h3>📊 模型排行榜 <span style="font-size:12px;color:var(--muted)">（点击 ⚪ 灯加入/移出强制故障池，多选支持自动故障转移）</span></h3><div class="table-wrap"><table><thead><tr><th>池</th><th>#</th><th>模型ID</th><th>服务商</th><th>TTFT</th><th>TPS</th><th>总耗时</th><th>状态</th><th>强制池</th></tr></thead><tbody>' +
        (rankRows || '<tr><td colspan="9" style="text-align:center;color:var(--muted)">暂无启用模型，请先在服务商页添加并同步</td></tr>') +
      '</tbody></table></div></div>'
    ].join('');
    // 加载公告填充公告卡片
    api('/api/announcements').then(function(ar){
      if(ar && ar.code === 0){
        var list = ar.data || [];
        var ac = $('announceCard');
        if(ac){
          if(list.length){
            var items = list.map(function(a){
              return '<div style="padding:8px 0;border-bottom:1px solid var(--border)">' +
                (a.isPinned?'<span class="badge red">📌</span> ':'') + '<b style="color:var(--text)">'+esc(a.title)+'</b>' +
                '<div style="color:var(--muted);font-size:13px;margin-top:4px">'+esc(a.content)+'</div>' +
                '<small style="color:var(--muted);font-size:11px">'+new Date(a.createdAt).toLocaleString('zh-CN',{hour12:false})+'</small>' +
                '</div>';
            }).join('');
            ac.innerHTML = '<h3>📢 公告</h3><div style="max-height:180px;overflow-y:auto">' + items + '</div>';
          } else {
            ac.innerHTML = '<h3>📢 公告</h3><div style="color:var(--muted);padding:8px;font-size:13px">暂无公告</div>';
          }
        }
      }
    });
    // 根据角色更新控制卡标题与范围提示
    api('/api/auth/me').then(function(me){
      if(me && me.code === 0 && me.data){
        var isAdmin = me.data.role === 'admin';
        if(!isAdmin){
          var gtitle = $('gwCtrlCard').querySelector('h3');
          if(gtitle) gtitle.textContent = '⚡ 我的API控制';
          var spTag = $('speedScopeTag');
          if(spTag) spTag.textContent = '（仅我的模型）';
        } else {
          var spTag2 = $('speedScopeTag');
          if(spTag2) spTag2.textContent = '（全局）';
        }
      }
    });
    // 若自动测速开启，显示倒计时
    if(st.autoSpeedTest){
      var interval = parseInt(st.speedIntervalMin) || 60;
      var dc = $('dashCountdown');
      if(dc){ dc.textContent = '⏳ 下次自动测速：' + interval + '分00秒'; startDashCountdown(interval * 60); }
    }
  });
};
// ===== 首页操作 =====
window.toggleGateway = function(){
  api('/api/gateway/toggle', { method:'POST', body: { action: 'toggle' } }).then(function(r){
    toast(r.msg, r.code === 0);
    loaders.dashboard();
  });
};
// 首页自动测速倒计时
var dashCountdownTimer = null;
function startDashCountdown(seconds){
  if(dashCountdownTimer) clearInterval(dashCountdownTimer);
  var left = seconds;
  var el = $('dashCountdown');
  if(!el) return;
  dashCountdownTimer = setInterval(function(){
    left--;
    if(left <= 0){ clearInterval(dashCountdownTimer); el.textContent = '⏳ 即将自动测速...'; return; }
    var m = Math.floor(left/60), s = left%60;
    el.textContent = '⏳ 下次自动测速：' + m + '分' + (s<10?'0':'') + s + '秒';
  }, 1000);
}
window.toggleAutoSpeed = function(){
  var st = $('autoSpeedBtn');
  var turningOn = st ? st.textContent.indexOf('启动') >= 0 : true;
  var interval = parseInt($('speedInterval') ? $('speedInterval').value : 60) || 60;
  api('/api/gateway/auto-speedtest', { method:'POST', body: { enabled: turningOn, intervalMin: interval } }).then(function(r){
    toast(r.msg, r.code === 0);
    if(turningOn){
      var el = $('dashCountdown');
      if(el){ el.textContent = '⏳ 下次自动测速：' + interval + '分00秒'; startDashCountdown(interval * 60); }
    } else {
      if(dashCountdownTimer) clearInterval(dashCountdownTimer);
      var el2 = $('dashCountdown');
      if(el2) el2.textContent = '';
    }
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
window.toggleForcedModel = function(key){
  var action = 'add';
  // 判断当前是否在池中：从配置再拉一次最新状态
  api('/api/status').then(function(r){
    var pool = (r.data && r.data.forcedPool) || [];
    var inPool = pool.indexOf(key) >= 0;
    var body = { action: inPool ? 'remove' : 'add', modelKey: key };
    api('/api/gateway/forced-pool', { method:'POST', body: body }).then(function(r2){
      toast(r2.msg, r2.code === 0);
      loaders.dashboard();
    });
  });
};
// 点灯=强制切换到此模型（对齐原APP：点池灯=强制切到该模型，qtai-sj 优先走它）
window.forceSwitchModel = function(key){
  api('/api/gateway/forced-pool', { method:'POST', body: { action: 'add', modelKey: key } }).then(function(r){
    if(r.code === 0){
      toast('⚡ 已强制切换到 ' + ((r.data && r.data.activeModel) || key), true);
      loaders.dashboard();
    } else toast(r.msg, false);
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
window.copyText = function(txt){
  if(navigator.clipboard){ navigator.clipboard.writeText(txt).then(function(){ toast('✅ 已复制', true); }); }
  else { toast('已复制: ' + txt, true); }
};
// ===== 服务商 =====
loaders.providers = function(){
  var box = $('view-providers');
  box.innerHTML = '<div style="text-align:center;color:var(--muted);padding:40px">加载中...</div>';
  api('/api/providers').then(function(r){
    state.providers = r.data || [];
    var rows = state.providers.map(function(p){
      var keyTxt = p.apiKey ? (p.apiKey.indexOf('****')>=0 ? p.apiKey : esc(p.apiKey)) : '<span style="color:var(--muted)">无</span>';
      var pubTxt = p.isPublic ? '<span class="badge green">公用</span>' : (p.ownerId>0 ? '<span class="badge blue">私有</span>' : '<span class="badge gray">系统</span>');
      return '<tr><td><b>'+esc(p.name)+'</b> <span class="badge purple">P'+(p.customId||p.id)+'</span></td><td>'+esc(p.type)+'</td><td style="font-size:12px;color:var(--muted)">'+esc(p.baseUrl)+(p.port?':'+esc(p.port):'')+'</td><td style="font-size:12px;font-family:monospace">'+keyTxt+'</td><td>'+pubTxt+'</td><td><span class="badge blue">'+esc(p.ownerName||'')+'</span></td><td><span class="badge '+(p.isEnabled?'green':'gray')+'">'+(p.isEnabled?'已启用':'已停用')+'</span></td><td><button class="btn-ghost" onclick="editProvider('+p.id+')">编辑</button> <button class="btn-ghost" onclick="syncProvider('+p.id+')">同步</button> <button class="btn-ghost" style="color:var(--red)" onclick="delProvider('+p.id+')">删除</button></td></tr>';
    }).join('');
    box.innerHTML = [
      '<div class="action-bar"><button class="btn" onclick="editProvider(0)">＋ 添加服务商</button><span style="color:var(--muted);font-size:12px">OpenAI Compatible / Ollama / Custom · 公用=所有人可见</span></div>',
      '<div class="card"><div class="table-wrap"><table><thead><tr><th>名称</th><th>类型</th><th>地址</th><th>API Key</th><th>归属</th><th>属主</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
      rows + '<tr><td colspan="8" style="text-align:center;color:var(--muted)">' + (state.providers.length ? '' : '暂无服务商') + '</td></tr>' +
      '</tbody></table></div></div>'
    ].join('');
  });
};
window.editProvider = function(id){
  var p = state.providers.find(function(x){ return x.id===id; }) || {};
  var canEditKey = !p.apiKey || p.apiKey.indexOf('****') < 0;
  var keyVal = canEditKey ? esc(p.apiKey||'') : '';
  var html = [
    '<div class="form-row"><label>名称 *</label><input id="pvName" class="input" value="'+esc(p.name||'')+'"></div>',
    '<div class="form-row"><label>类型</label><select id="pvType" class="input"><option value="OpenAI Compatible"'+(p.type==='OpenAI Compatible'?' selected':'')+'>OpenAI Compatible</option><option value="Ollama"'+(p.type==='Ollama'?' selected':'')+'>Ollama</option><option value="Custom"'+(p.type==='Custom'?' selected':'')+'>Custom</option></select></div>',
    '<div class="form-row"><label>Base URL *</label><input id="pvUrl" class="input" value="'+esc(p.baseUrl||'')+'" placeholder="https://api.openai.com"></div>',
    '<div class="form-row"><label>端口（可选）</label><input id="pvPort" class="input" value="'+esc(p.port||'')+'"></div>',
    '<div class="form-row"><label>API Key'+(canEditKey?'':'（无权限查看，留空保持不变）')+'</label><input id="pvKey" class="input" value="'+keyVal+'"'+(canEditKey?'':' placeholder="**** 已隐藏 ****"')+'></div>',
    '<div class="form-row"><label>聊天路径</label><input id="pvPath" class="input" value="'+esc(p.chatPath||'')+'" placeholder="/v1/chat/completions"></div>',
    '<div class="form-row"><label>自定义服务商ID（pID）</label><input id="pvCid" class="input" value="'+esc(p.customId||'')+'"></div>',
    '<div class="form-row"><label><input type="checkbox" id="pvEnabled"'+(p.isEnabled!==false?' checked':'')+'> 启用</label> <label style="margin-left:14px"><input type="checkbox" id="pvPublic"'+(p.isPublic?' checked':'')+'> 公用（所有人可见可用）</label></div>'
  ].join('');
  openModal(id ? '编辑服务商' : '添加服务商', html, function(){
    var body = { id: id||0, name:$('pvName').value.trim(), type:$('pvType').value, baseUrl:$('pvUrl').value.trim(), port:$('pvPort').value.trim(), apiKey:$('pvKey').value.trim(), chatPath:$('pvPath').value.trim(), customId:$('pvCid').value.trim(), isEnabled:$('pvEnabled').checked, isPublic:$('pvPublic').checked };
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