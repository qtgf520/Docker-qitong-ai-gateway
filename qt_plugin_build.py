#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""綦桐AI网关 QQ插件包生成器：把旧版QQ机器人小游戏脚本编译为网关独有插件格式
- GBK -> UTF-8
- 清理旧平台标记：[imgurl]...[/imgurl]、【换行】、faceXX、╔╗═框线、%条件% 转说明
- 保留 $变量$ 模板（网关 matchPlugin 已支持替换）
- 生成 plugin.json（名称/描述/版本/菜单/作者）
"""
import os, re, shutil, zipfile, json, sys

SRC = '/storage/emulated/0/Download/BaiduNetdisk/百度云解压/QQ机器人_20260930120544/更多小游戏文件可放这里'
BUILD = '/tmp/qt_plugin_build/小游戏包'
ZIP_OUT = '/storage/emulated/0/Download/綦桐AI网关-QQ小游戏插件-v1.0.zip'

GBK = 'gb18030'  # gb18030 兼容 GBK 且含生僻字

def read_any(path):
    """优先 UTF-8，失败回退 GBK/gb18030"""
    for enc in ('utf-8', 'gb18030', 'gbk'):
        try:
            with open(path, 'rb') as f:
                return f.read().decode(enc)
        except (UnicodeDecodeError, Exception):
            continue
    # 实在不行用 errors=replace
    with open(path, 'rb') as f:
        return f.read().decode('utf-8', errors='replace')

def clean_line(line, cmd_name=None):
    """清理旧平台标记，保留游戏文字"""
    s = line
    # 去掉 [imgurl]...[/imgurl] 图片占位
    s = re.sub(r'\[imgurl\][^\]]*(\.[a-zA-Z]+)?\|?\[?/?(imgurl)?\]?', '', s)
    s = re.sub(r'\[/imgurl\]', '', s)
    s = re.sub(r'\[imgurl\][^\]]*\]', '', s)
    # 去掉 face 表情码（face120 / face0xBD260000）
    s = re.sub(r'face[0-9a-fA-FxX]+', '', s)
    # 【换行】 -> 换行分隔（在行尾不处理，中间转成两个空格分隔）
    s = s.replace('【换行】', '  ')
    # 框线字符清理
    s = re.sub(r'[╔╗╚╝║═━─]+', '', s)
    # %条件% 清理：%xxx% 整段移除（旧平台限制条件，网关不强制执行）
    s = re.sub(r'%[^%]*%', '', s)
    # 去掉行首命令前缀（如「打怪 xxx」「抽奖 xxx」——命令名 + 空白）
    if cmd_name:
        pat = r'^\s*' + re.escape(cmd_name) + r'\s*[:：]?\s*'
        s = re.sub(pat, '', s)
    # 去掉 tab 开头（命令分隔）
    s = s.strip('\t ')
    # 清理多余空白
    s = re.sub(r'[ \t]+', ' ', s).strip()
    return s

def convert_file(src, dst, cmd_name=None):
    text = read_any(src)
    out_lines = []
    for line in text.split('\n'):
        line = line.rstrip('\r')
        if not line.strip():
            continue
        # 跳过明显的平台配置行（优先回复区）
        if '复制到优先回复' in line or line.strip().startswith('XXX') and len(line.strip()) < 5:
            continue
        cleaned = clean_line(line, cmd_name)
        # 丢弃纯变量行：整行去掉 $ 和标点后无实质文字（如 "$扣除3000$，" 只剩 "扣除3000" 不算；"$禁言10分钟$，$掉装备$，$奖励3万$" 去掉$后是名词堆砌）
        # 判定：行内含中文句子成分（出现"了/的/获得/得到/你/我"等动词性词）才保留，纯名词堆砌行丢弃
        stripped = cleaned.replace('$', '').replace('，', '').replace('。', '').replace('、', '').replace('：', '').replace(':', '').replace(' ', '')
        if not stripped or stripped == 'XXX':
            continue
        # 纯功能配置行（无句子动词，全名词堆砌）
        if cleaned.count('$') >= 2 and not re.search(r'[了你我他她被得在是到去大小好快真很啊吧呢]', cleaned):
            continue
        if not cleaned:
            continue
        out_lines.append(cleaned)
    with open(dst, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out_lines))
    return len(out_lines)

def main():
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(os.path.join(BUILD, '游戏'), exist_ok=True)
    os.makedirs(os.path.join(BUILD, '菜单'), exist_ok=True)

    stats = {'游戏': 0, '菜单': 0, '跳过': []}
    # 游戏目录
    game_src = os.path.join(SRC, '游戏')
    if os.path.isdir(game_src):
        for fn in sorted(os.listdir(game_src)):
            if not fn.lower().endswith('.txt'):
                continue
            cmd = fn[:-4]
            n = convert_file(os.path.join(game_src, fn), os.path.join(BUILD, '游戏', fn), cmd_name=cmd)
            if n > 0:
                stats['游戏'] += 1
            else:
                stats['跳过'].append(fn)
    # 菜单目录
    menu_src = os.path.join(SRC, '菜单')
    if os.path.isdir(menu_src):
        for fn in sorted(os.listdir(menu_src)):
            if not fn.lower().endswith('.txt'):
                continue
            n = convert_file(os.path.join(menu_src, fn), os.path.join(BUILD, '菜单', fn))
            if n > 0:
                stats['菜单'] += 1
            else:
                stats['跳过'].append(fn)

    # 游戏命令列表（文件名前缀）
    game_names = sorted(fn[:-4] for fn in os.listdir(os.path.join(BUILD, '游戏')) if fn.endswith('.txt'))
    cmd_list = '、'.join(game_names[:40])
    cmd_more = f'等 {len(game_names)} 款' if len(game_names) > 40 else ''

    # plugin.json 元数据
    plugin = {
        'name': '小游戏包',
        'description': '经典QQ机器人小游戏合集（已编译为綦桐AI网关插件格式），含抽奖/打怪/三国大富翁/猜数字等84款娱乐游戏',
        'version': '1.0.0',
        'author': '綦桐AI网关',
        'menu': f'🎮 小游戏包 v1.0（{len(game_names)}款）\n发游戏名即玩：{cmd_list}{cmd_more}\n发「菜单」查看全部插件',
        'games': len(game_names),
        'menus': stats['菜单'],
        'commands': game_names
    }
    with open(os.path.join(BUILD, 'plugin.json'), 'w', encoding='utf-8') as f:
        json.dump(plugin, f, ensure_ascii=False, indent=2)

    # 打包 zip
    if os.path.exists(ZIP_OUT):
        os.remove(ZIP_OUT)
    with zipfile.ZipFile(ZIP_OUT, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(BUILD):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, BUILD)
                zf.write(full, rel)
    size = os.path.getsize(ZIP_OUT)
    print(json.dumps({'stats': stats, 'games': len(game_names), 'zip': ZIP_OUT, 'size_bytes': size}, ensure_ascii=False))

if __name__ == '__main__':
    main()