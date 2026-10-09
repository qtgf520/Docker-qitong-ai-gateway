package com.qitong.gateway.yuanbao

/**
 * 元宝 Bot 协议 · 轻量 Protobuf wire-format 编解码（纯 Kotlin 零依赖）
 * 依据腾讯元宝 openclaw 插件 conn.json / biz.json 协议描述手写。
 * 仅覆盖本项目用到的最小消息集。
 */
object YuanbaoProto {

    // ---------- 基础读写 ----------

    class Writer {
        private val buf = java.io.ByteArrayOutputStream()
        fun varint(v: Long) {
            var value = v
            while (true) {
                if ((value and 0x7f.inv().toLong()) == 0L) { buf.write(value.toInt()); return }
                buf.write(((value and 0x7f) or 0x80).toInt()); value = value ushr 7
            }
        }
        fun tag(field: Int, wireType: Int) { varint(((field shl 3) or wireType).toLong()) }
        fun string(field: Int, s: String) { if (s.isEmpty()) return; tag(field, 2); val b = s.toByteArray(Charsets.UTF_8); varint(b.size.toLong()); buf.write(b) }
        fun bytes(field: Int, b: ByteArray) { if (b.isEmpty()) return; tag(field, 2); varint(b.size.toLong()); buf.write(b) }
        fun uint32(field: Int, v: Int) { if (v == 0) return; tag(field, 0); varint((v.toLong()) and 0xffffffffL) }
        fun int32(field: Int, v: Int) { if (v == 0) return; tag(field, 0); varint(v.toLong()) }
        fun bool(field: Int, v: Boolean) { if (!v) return; tag(field, 0); varint(1L) }
        fun message(field: Int, encode: () -> ByteArray) {
            val inner = encode()
            if (inner.isEmpty()) return
            tag(field, 2); varint(inner.size.toLong()); buf.write(inner)
        }
        fun toBytes(): ByteArray = buf.toByteArray()
    }

    class Reader(private val data: ByteArray) {
        private var pos = 0
        fun eof(): Boolean = pos >= data.size
        fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        /** 读取下一个字段的 (field, wireType)，返回 null 表示结束 */
        fun nextField(): Pair<Int, Int>? {
            if (eof()) return null
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            return field to wireType
        }
        fun readBytes(size: Int): ByteArray { val r = data.copyOfRange(pos, pos + size); pos += size; return r }
        fun readString(): String { val len = readVarint().toInt(); return String(readBytes(len), Charsets.UTF_8) }
        fun readUint32(): Long = readVarint()
        fun readInt32(): Int = readVarint().toInt()
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> { pos += 8 }
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> { pos += 4 }
                else -> { pos = data.size }
            }
        }
    }

    /** 通用小对象读取：给定字段处理器，遍历直到 end */
    inline fun parse(data: ByteArray, handler: (field: Int, wt: Int, r: Reader) -> Boolean) {
        val r = Reader(data)
        while (!r.eof()) {
            val (field, wt) = r.nextField() ?: break
            if (!handler(field, wt, r)) r.skip(wt)
        }
    }

    // ---------- Head ----------

    data class Head(
        var cmdType: Int = 0, var cmd: String = "", var seqNo: Int = 0,
        var msgId: String = "", var module: String = "", var status: Int = 0
    )

    fun encodeHead(h: Head): ByteArray {
        val w = Writer()
        w.uint32(1, h.cmdType); w.string(2, h.cmd); w.uint32(3, h.seqNo)
        w.string(4, h.msgId); w.string(5, h.module); w.int32(10, h.status)
        return w.toBytes()
    }

    fun decodeHead(data: ByteArray): Head {
        val h = Head()
        parse(data) { f, wt, r ->
            when (f) {
                1 -> h.cmdType = r.readUint32().toInt()
                2 -> h.cmd = r.readString()
                3 -> h.seqNo = r.readUint32().toInt()
                4 -> h.msgId = r.readString()
                5 -> h.module = r.readString()
                10 -> h.status = r.readInt32()
                else -> return@parse false
            }
            true
        }
        return h
    }

    // ---------- ConnMsg ----------

    fun encodeConnMsg(head: Head, data: ByteArray): ByteArray {
        val w = Writer()
        w.message(1) { encodeHead(head) }
        w.bytes(2, data)
        return w.toBytes()
    }

    /** 解码 ConnMsg，返回 (head, data) */
    fun decodeConnMsg(raw: ByteArray): Pair<Head, ByteArray> {
        var headBytes = ByteArray(0); var data = ByteArray(0)
        parse(raw) { f, wt, r ->
            when (f) {
                1 -> { val len = r.readVarint().toInt(); headBytes = r.readBytes(len) }
                2 -> { val len = r.readVarint().toInt(); data = r.readBytes(len) }
                else -> return@parse false
            }
            true
        }
        return decodeHead(headBytes) to data
    }

    // ---------- AuthBind / Ping ----------

    fun encodeAuthBind(uid: String, source: String, token: String): ByteArray {
        val w = Writer()
        w.string(1, "ybBot")
        w.message(2) { // AuthInfo
            val ai = Writer()
            ai.string(1, uid); ai.string(2, source); ai.string(3, token)
            ai.toBytes()
        }
        w.message(3) { // DeviceInfo（只填关键字段）
            val di = Writer()
            di.string(1, "2.18.3")   // appVersion
            di.string(2, "Linux")    // appOperationSystem
            di.string(24, "2026.9.9") // botVersion
            di.string(10, "16")      // instanceId
            di.toBytes()
        }
        return w.toBytes()
    }

    fun encodePing(): ByteArray = ByteArray(0)

    fun decodeAuthBindRsp(data: ByteArray): Pair<Int, String> {
        var code = 0; var message = ""
        parse(data) { f, wt, r ->
            when (f) {
                1 -> code = r.readInt32()
                2 -> message = r.readString()
                else -> return@parse false
            }
            true
        }
        return code to message
    }

    // ---------- PushMsg ----------

    data class PushMsg(val cmd: String = "", val module: String = "", val msgId: String = "", val data: ByteArray = ByteArray(0))

    fun decodePushMsg(data: ByteArray): PushMsg {
        var cmd = ""; var module = ""; var msgId = ""; var payload = ByteArray(0)
        parse(data) { f, wt, r ->
            when (f) {
                1 -> cmd = r.readString()
                2 -> module = r.readString()
                3 -> msgId = r.readString()
                4 -> { val len = r.readVarint().toInt(); payload = r.readBytes(len) }
                else -> return@parse false
            }
            true
        }
        return PushMsg(cmd, module, msgId, payload)
    }

    // ---------- InboundMessagePush（收消息） ----------

    data class InboundMessage(
        val callbackCommand: String = "",
        val fromAccount: String = "",
        val toAccount: String = "",
        val senderNickname: String = "",
        val groupId: String = "",
        val groupCode: String = "",
        val groupName: String = "",
        val msgId: String = "",
        val clawMsgType: Int = 0,   // 1=群 2=私聊
        val text: String = "",
        val imageDesc: String = ""
    )

    fun decodeInboundMessagePush(data: ByteArray): InboundMessage? {
        var msg = InboundMessage()
        var text = ""; var imageDesc = ""
        parse(data) { f, wt, r ->
            when (f) {
                1 -> msg = msg.copy(callbackCommand = r.readString())
                2 -> msg = msg.copy(fromAccount = r.readString())
                3 -> msg = msg.copy(toAccount = r.readString())
                4 -> msg = msg.copy(senderNickname = r.readString())
                5 -> msg = msg.copy(groupId = r.readString())
                6 -> msg = msg.copy(groupCode = r.readString())
                7 -> msg = msg.copy(groupName = r.readString())
                12 -> msg = msg.copy(msgId = r.readString())
                18 -> msg = msg.copy(clawMsgType = r.readUint32().toInt())
                13 -> { // repeated MsgBodyElement
                    val len = r.readVarint().toInt()
                    val el = r.readBytes(len)
                    parse(el) { f2, wt2, r2 ->
                        if (f2 == 2) { // msgContent
                            val clen = r2.readVarint().toInt()
                            val cdata = r2.readBytes(clen)
                            parse(cdata) { f3, wt3, r3 ->
                                when (f3) {
                                    1 -> text = if (text.isEmpty()) r3.readString() else text
                                    5 -> imageDesc = if (imageDesc.isEmpty()) r3.readString() else imageDesc
                                    else -> return@parse false
                                }
                                true
                            }
                        }
                        true
                    }
                }
                else -> return@parse false
            }
            true
        }
        if (msg.callbackCommand.isBlank() && msg.msgId.isBlank() && msg.fromAccount.isBlank()) return null
        return msg.copy(text = text, imageDesc = imageDesc)
    }

    // ---------- DirectedPush（v1.119c 关键补充：消息可能以 JSON content 字符串推送） ----------

    /** 解码 DirectedPush：type (uint32) + content (string JSON) */
    fun decodeDirectedPush(data: ByteArray): Pair<Int, String> {
        var type = 0; var content = ""
        parse(data) { f, wt, r ->
            when (f) {
                1 -> type = r.readUint32().toInt()
                2 -> content = r.readString()
                else -> return@parse false
            }
            true
        }
        return type to content
    }

    /** 从 JSON 字符串解析用户消息文本（msg_body[].msg_content.text 或顶层 text） */
    fun extractTextFromJson(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            // 顶层 text
            if (obj.has("text") && obj.optString("text").isNotBlank()) return obj.optString("text")
            // msg_body 数组
            val body = obj.optJSONArray("msg_body")
            if (body != null && body.length() > 0) {
                val first = body.optJSONObject(0) ?: return ""
                val mc = first.optJSONObject("msg_content")
                if (mc != null && mc.optString("text").isNotBlank()) return mc.optString("text")
                if (first.optString("text").isNotBlank()) return first.optString("text")
            }
            ""
        } catch (e: Exception) { "" }
    }

    fun encodeSendC2C(msgId: String, toAccount: String, fromAccount: String, text: String): ByteArray {
        val w = Writer()
        w.string(1, msgId); w.string(2, toAccount); w.string(3, fromAccount)
        w.uint32(4, (Math.random() * 0x7fffffff).toInt())
        w.message(5) { // msgBody element (text)
            val el = Writer()
            el.string(1, "text")
            el.message(2) {
                val mc = Writer()
                mc.string(1, text)
                mc.toBytes()
            }
            el.toBytes()
        }
        return w.toBytes()
    }

    fun encodeSendGroupC2C(msgId: String, toAccount: String, fromAccount: String, text: String, groupCode: String): ByteArray {
        val w = Writer()
        w.string(1, msgId); w.string(2, toAccount); w.string(3, fromAccount)
        w.uint32(4, (Math.random() * 0x7fffffff).toInt())
        w.message(5) {
            val el = Writer()
            el.string(1, "text")
            el.message(2) {
                val mc = Writer()
                mc.string(1, text)
                mc.toBytes()
            }
            el.toBytes()
        }
        w.string(6, groupCode)
        return w.toBytes()
    }

    fun encodeSendGroupMsg(msgId: String, groupCode: String, fromAccount: String, toAccount: String, text: String): ByteArray {
        val w = Writer()
        w.string(1, msgId); w.string(2, groupCode); w.string(3, fromAccount); w.string(4, toAccount)
        w.string(5, java.util.UUID.randomUUID().toString().replace("-", "").take(16))
        w.message(6) {
            val el = Writer()
            el.string(1, "text")
            el.message(2) {
                val mc = Writer()
                mc.string(1, text)
                mc.toBytes()
            }
            el.toBytes()
        }
        return w.toBytes()
    }

    // ---------- ★ v1.119 命令同步（关键！元宝后台靠这个激活 bot 消息回调） ----------

    /** SyncInformationReq：syncType=1 COMMANDS + commandData（bot_commands 列表） */
    fun encodeSyncInformation(botVersion: String = "2026.9.9", pluginVersion: String = "2.18.3"): ByteArray {
        val w = Writer()
        w.uint32(1, 1)  // syncType = SYNC_INFORMATION_TYPE_COMMANDS
        w.string(2, botVersion)
        w.string(3, pluginVersion)
        // commandData (field 11) — 传一组命令让元宝后台注册 bot 能力
        w.message(11) {
            val cd = Writer()
            // botCommands (field 1): 内置命令列表
            listOf(
                "help" to "查看帮助",
                "status" to "查看网关状态",
                "balance" to "查询余额",
                "bind" to "绑定网关账号",
                "persona" to "切换人格",
                "remind" to "设置提醒",
                "todo" to "待办管理",
                "health" to "全功能体检"
            ).forEachIndexed { i, (name, desc) ->
                cd.message(1) {
                    val c = Writer()
                    c.string(1, "/$name")
                    c.string(2, desc)
                    c.toBytes()
                }
            }
            cd.toBytes()
        }
        return w.toBytes()
    }

    /** QueryBotInfoReq：查询机器人信息（验证 bot 身份） */
    fun encodeQueryBotInfo(botId: String): ByteArray {
        val w = Writer()
        w.string(1, botId)
        return w.toBytes()
    }

    /** 解析 SyncInformationRsp（code/message） */
    fun decodeSyncInformationRsp(data: ByteArray): Pair<Int, String> {
        var code = 0; var msg = ""
        parse(data) { f, wt, r ->
            when (f) {
                1 -> code = r.readInt32()
                2 -> msg = r.readString()
                else -> return@parse false
            }
            true
        }
        return code to msg
    }

    /** 解析 QueryBotInfoRsp（code/ownerId） */
    fun decodeQueryBotInfoRsp(data: ByteArray): Pair<Int, String> {
        var code = 0; var ownerId = ""
        parse(data) { f, wt, r ->
            when (f) {
                1 -> code = r.readInt32()
                2 -> ownerId = r.readString()
                else -> return@parse false
            }
            true
        }
        return code to ownerId
    }
}