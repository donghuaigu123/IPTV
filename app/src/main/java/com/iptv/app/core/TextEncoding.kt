package com.iptv.app.core

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 文本编码智能识别与修复（v2.0.2）。
 *
 * 背景：大量直播订阅源来自第三方，编码五花八门；历史上采集/TVBox 展开时用
 * ISO-8859-1（Latin-1）读取头部样本，又把样本直接当正文解析，导致 UTF-8 的
 * 中文被按 Latin-1 解码成「GOé å」「ç½ ç»„」这类乱码。
 *
 * 本工具提供两层能力：
 * 1. [decode]：按字节智能解码 —— 识别 BOM、优先 UTF-8、UTF-8 解不通时回退 GBK，
 *    最后再做一次乱码修复。用于所有网络响应体读取。
 * 2. [fixMojibake]：修复已经错乱的字符串（UTF-8 字节被 Latin-1/CP1252 误读），
 *    采用高置信度检测，避免误伤正常的西欧文字（如 Café、François）。
 *    用于修复已经存入 SharedPreferences 的历史乱码频道数据。
 */
object TextEncoding {

    private val UTF8: Charset = StandardCharsets.UTF_8
    private val LATIN1: Charset = StandardCharsets.ISO_8859_1
    private val GBK: Charset = safeCharset("GBK")
    private val CP1252: Charset = safeCharset("windows-1252")

    private fun safeCharset(name: String): Charset =
        try { Charset.forName(name) } catch (_: Throwable) { UTF8 }

    /** 把原始字节智能解码为文本 */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1) BOM 优先
        val bom = bomKind(bytes)
        if (bom != null) {
            val off = bom.second
            if (bytes.size <= off) return ""
            return when (bom.first) {
                BOM.UTF8 -> String(bytes, off, bytes.size - off, UTF8)
                BOM.UTF16LE -> String(bytes, off, bytes.size - off, StandardCharsets.UTF_16LE)
                BOM.UTF16BE -> String(bytes, off, bytes.size - off, StandardCharsets.UTF_16BE)
            }
        }

        // 2) 先按 UTF-8 解码（绝大多数源）
        val utf8 = String(bytes, UTF8)
        val bad = replacementCount(utf8)
        // 3) UTF-8 解不通（大量替换字符）时，尝试 GBK/GB2312（国内源常见）
        if (bad > 0 && bad.toLong() * 24 >= bytes.size.toLong()) {
            val gbk = String(bytes, GBK)
            if (replacementCount(gbk) < bad) return gbk
        }
        // 4) UTF-8 能解但内容是错乱的西欧字符 → 尝试还原
        return fixMojibake(utf8)
    }

    private enum class BOM { UTF8, UTF16LE, UTF16BE }

    private fun bomKind(b: ByteArray): Pair<BOM, Int>? = when {
        b.size >= 3 && u8(b[0]) == 0xEF && u8(b[1]) == 0xBB && u8(b[2]) == 0xBF -> BOM.UTF8 to 3
        b.size >= 2 && u8(b[0]) == 0xFF && u8(b[1]) == 0xFE -> BOM.UTF16LE to 2
        b.size >= 2 && u8(b[0]) == 0xFE && u8(b[1]) == 0xFF -> BOM.UTF16BE to 2
        else -> null
    }

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    /**
     * 修复「UTF-8 字节被 Latin-1 / Windows-1252 误解码」的字符串。
     * 例：网络→ç½ ç»„、快→å¿«、节→èŠ‚。
     */
    fun fixMojibake(text: String?): String {
        if (text.isNullOrEmpty() || !looksMojibake(text)) return text ?: ""
        val originalBad = replacementCount(text)

        // Latin-1 优先（绝大多数 mojibake 由它产生）
        val latin = try { String(text.toByteArray(LATIN1), UTF8) } catch (_: Throwable) { text }
        if (isBetter(latin, originalBad)) return latin

        // Windows-1252：0x80-0x9F 被映射成可打印字符的情形
        val cp = try { String(text.toByteArray(CP1252), UTF8) } catch (_: Throwable) { text }
        if (isBetter(cp, originalBad)) return cp

        return text
    }

    /** 修复结果是否明显优于原文：出现 CJK 字符，或替换字符减少且文本仍可读 */
    private fun isBetter(candidate: String, originalBad: Int): Boolean {
        val bad = replacementCount(candidate)
        if (bad > originalBad) return false
        val cjk = candidate.count { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF }
        if (cjk >= 1) return true
        return bad < originalBad && candidate.any { it.isLetterOrDigit() }
    }

    /**
     * 高置信度判定是否 mojibake：
     * 正常中文不含 U+00C0..U+00FF；正常西欧文字（Café/François）虽含少量高位字符，
     * 但几乎不会同时出现 U+0080..U+00BF 的 C1 控制区字符。
     * mojibake 则二者往往同时大量出现。
     */
    private fun looksMojibake(text: String): Boolean {
        var c1 = 0      // U+0080..U+00BF
        var high = 0    // U+00C0..U+00FF
        val limit = minOf(text.length, 2048)
        var i = 0
        while (i < limit) {
            val code = text[i].code
            if (code in 0x80..0xBF) c1++
            else if (code in 0xC0..0xFF) high++
            i++
        }
        return c1 >= 1 && high >= 2
    }

    private fun replacementCount(s: String): Int = s.count { it == '\uFFFD' }
}
