package io.legado.app.model.ai.purify

object NovelPurificationPromptSmokeTest {

    const val SAMPLE_INPUT = """第十二章 夜雨
请牢记本站域名：www.example.com，手机用户请访问 m.example.com
“你真的
要走吗? ”林晚问道。。他点点头，，说道：“是的，明天我会在次回来。”


窗外的雨声越来越急。
窗外的雨声越来越急。
<script>showAd('请收藏本站');</script>"""

    const val REFERENCE_OUTPUT = """第十二章 夜雨
　　“你真的要走吗？”林晚问道。
　　他点点头，说道：“是的，明天我会再次回来。”
　　窗外的雨声越来越急。"""

    data class Result(val issues: List<String>) {
        val passed: Boolean
            get() = issues.isEmpty()
    }

    fun evaluate(output: String): Result {
        val issues = buildList {
            if (output.isBlank()) add("模型返回了空正文")
            if (listOf("www.example.com", "m.example.com", "请牢记本站域名")
                    .any(output::contains)
            ) {
                add("网站广告没有清理干净")
            }
            if (output.contains("<script>") || output.contains("请收藏本站")) {
                add("脚本或推广残留没有清理干净")
            }
            if (listOf("? ”", "。。", "，，").any(output::contains)) {
                add("错误标点没有修复干净")
            }
            if (output.contains("在次回来")) add("明确错别字没有修复")
            if (output.contains("真的\n要走")) add("句中机械换行没有修复")
            if (!output.contains("林晚问道。\n　　他点点头")) {
                add("粘连的对白段落没有拆开")
            }
            if (output.contains("\n\n")) add("正文仍有异常空行")
            val normalParagraphs = output.lineSequence()
                .drop(1)
                .filter(String::isNotBlank)
                .toList()
            if (normalParagraphs.any { !it.startsWith("　　") }) {
                add("普通正文段落没有按文学格式缩进")
            }
            if (occurrenceCount(output, "窗外的雨声越来越急。") != 1) {
                add("连续重复句没有正确去重")
            }
            if (listOf("第十二章 夜雨", "林晚", "明天").any { !output.contains(it) }) {
                add("原文中的标题、人物或事实信息被遗漏")
            }
            if (output.contains("```") || output.startsWith("净化后") ||
                output.startsWith("处理结果") || output.startsWith("以下是")
            ) {
                add("输出包含说明或 Markdown 包装")
            }
            if (output != output.trim()) add("正文前后存在额外空白")
        }
        return Result(issues)
    }

    private fun occurrenceCount(text: String, target: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val index = text.indexOf(target, startIndex)
            if (index < 0) return count
            count++
            startIndex = index + target.length
        }
    }
}
