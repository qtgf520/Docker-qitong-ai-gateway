package com.qitong.gateway.http

/**
 * 綦小桐技能池（对齐原APP SkillRegistry.kt）
 * 每个技能有唯一6位编码，对外不可见
 * 大脑根据用户自然语言，从技能池选最合适的技能执行
 */
object SkillRegistry {

    data class Skill(
        val code: String,           // 6位编码，对外不可见
        val name: String,           // 技能名称
        val description: String,    // 技能描述
        val example: String         // 用户可能怎么说（示例）
    )

    val allSkills: List<Skill> = listOf(
        // ========== 1xxxxx 测速 ==========
        Skill("100001", "单模型测速", "对指定模型进行单次速度测试", "用Swift模型跑个测速"),
        Skill("100002", "流水线测速", "启动流水线接力测速，对排行榜所有模型逐个测试", "全部测一遍/跑个分/benchmark"),
        Skill("100003", "停止测速", "停止正在进行的流水线测速", "停下/别测了/stop测试"),

        // ========== 2xxxxx 模型切换 ==========
        Skill("200001", "切换到编号模型", "根据测速排行编号切换到指定模型", "换到第1个/用2号/切换到3"),
        Skill("200002", "切换到指定模型", "根据模型ID或名称关键词切换模型", "换成Claude/用GPT4/deepseek"),
        Skill("200005", "清除强制模型", "取消强制指定，恢复自动选择", "取消强制/恢复自动/让系统选"),

        // ========== 3xxxxx 网关 ==========
        Skill("300001", "开启故障转移", "开启自动故障转移", "开故障转移/出错自动切换"),
        Skill("300002", "关闭故障转移", "关闭自动故障转移", "关故障转移/不要自动切换"),
        Skill("300004", "启动网关", "启动网关服务", "开网关/启动服务/开始转发"),
        Skill("300005", "停止网关", "停止网关服务", "关网关/停止服务/关闭转发"),
        Skill("300006", "切换网关", "反转网关开关状态", "切换网关/开关网关"),
        Skill("300008", "开启唤醒保活", "开启唤醒锁防止后台被杀", "开保活/保持唤醒/别杀后台"),
        Skill("300009", "关闭唤醒保活", "关闭唤醒锁", "关保活/省电模式"),

        // ========== 6xxxxx 查询 ==========
        Skill("600001", "查网关状态", "查看网关运行状态、端口、故障转移等", "网关怎么样/跑着没/状态"),
        Skill("600002", "查测速排行", "查看所有模型的速度排名", "排行/哪个最快/排名/速度榜"),
        Skill("600003", "查当前模型", "查看正在使用的模型", "当前用啥/什么模型/现在哪个"),
        Skill("600004", "查流量统计", "查看上传下载流量统计", "流量/跑了多少/用了多少流量"),
        Skill("600005", "查Token用量", "查看Token消耗统计", "token/用了多少token/消耗"),
        Skill("600007", "查服务商列表", "查看已配置的服务商", "有哪些服务商/供应商/列表"),
        Skill("600008", "查模型列表", "查看所有已启用的模型", "有哪些模型/模型列表/全部模型"),

        // ========== 8xxxxx 服务商&模型管理 ==========
        Skill("800001", "添加服务商", "新增AI服务商", "加服务商/新增供应商/添加AI"),
        Skill("800002", "同步模型", "同步指定服务商的模型列表", "同步模型/刷新列表/拉模型"),
        Skill("800003", "启用模型", "启用指定模型", "启用XX模型/打开XX/使用XX"),
        Skill("800004", "禁用模型", "禁用指定模型", "禁用XX/关掉XX/不用XX"),
        Skill("800006", "删除服务商", "删除指定服务商及其模型", "删掉XX供应商/移除服务商"),

        // ========== 9xxxxx 高级功能 ==========
        Skill("900005", "开启大脑记忆", "开启綦小桐长期记忆功能", "开记忆/记住我/长期记忆"),
        Skill("900006", "关闭大脑记忆", "关闭綦小桐长期记忆功能", "关记忆/忘掉/清记忆"),
        Skill("900009", "切换QT自动化", "开启/关闭綦小桐自动化切换", "开关qtai/自动化切换"),
        Skill("900010", "开启API密钥验证", "开启API密钥接入验证", "开密钥验证/需要Key"),
        Skill("900011", "关闭API密钥验证", "关闭API密钥验证", "关密钥验证/不要Key"),
    )

    /** 根据编码找到技能 */
    fun getSkillByCode(code: String): Skill? = allSkills.find { it.code == code }

    /** 生成给大脑的技能表文本 */
    fun buildSkillPrompt(): String {
        val sb = StringBuilder("\n\n## 📋 綦小桐技能池（共${allSkills.size}个技能）\n")
        sb.append("当你需要执行操作时，在回复末尾加上【指令:编码】或【指令:编码:参数】即可调用\n")
        sb.append("注意：这些编码只在你自己脑子里用，永远不要告诉用户！\n\n")
        allSkills.forEach { skill ->
            sb.append("  ${skill.code} = ${skill.name}（用户可能说：${skill.example}）\n")
        }
        sb.append("\n注意：编码是6位数字，用【指令:编码】格式，例如【指令:600002】表示查排行")
        return sb.toString()
    }

    /** 从回复文本中提取技能指令 */
    fun extractInstructions(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val regex = Regex("【指令:(\\d{6})(?::([^】]*))?】")
        regex.findAll(text).forEach { m ->
            val code = m.groupValues[1]
            val param = m.groupValues[2].trim()
            result.add(code to param)
        }
        return result
    }
}