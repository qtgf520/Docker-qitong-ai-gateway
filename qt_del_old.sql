-- 删除旧 token 的 bot（元宝1，id=2，旧 appKey 已被顶替）
DELETE FROM yuanbao_bots WHERE app_key='edqPvHDkEQQTWCYk1DBlncsBG01Psg4Q' AND id=2;

-- 查询验证
SELECT id,name,app_key,enabled FROM yuanbao_bots;